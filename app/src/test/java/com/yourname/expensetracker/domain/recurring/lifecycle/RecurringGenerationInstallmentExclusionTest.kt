package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * S6c-2b: the recurring GENERATION path (create / activate / top-up / update through
 * [OccurrenceConflictResolver], plus the in-memory projection) consults the installment link policy
 * (INV-5, domain spec row (d)). An expense that pays an installment entry, or is a plan purchase
 * record, is never the payment of a generated occurrence: the occurrence stays PLANNED and
 * unlinked, no PAID-side event is written, and its reminders are still scheduled. A normal expense
 * of the same merchant, amount and day still pays the occurrence.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric), REAL barrier over a mocked
 * [RestoreMaintenanceMode], REAL [InstallmentEntryLinkReaderImpl], REAL
 * [RecurringInstallmentEligibility], resolver, materializer, projection service and coordinators.
 * Only the lifecycle event writer seam is an in-memory-DAO fake. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringGenerationInstallmentExclusionTest {

    private lateinit var database: AppDatabase
    private lateinit var ruleDao: ManualRecurringExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var deliveryDao: RecurringReminderDeliveryDao
    private lateinit var plannedDao: PlannedExpenseDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var planDao: InstallmentPlanDao
    private lateinit var lifecycle: RecurringLifecycleCoordinator
    private lateinit var ruleCoordinator: RecurringRuleLifecycleCoordinator

    private val clockNow = dayOf(2026, 9, 19) + HALF_DAY
    private val timeProvider = object : TimeProvider {
        override fun now() = clockNow
    }

    /** The expense shapes the installment policy must keep out of generated occurrences. */
    private enum class Shape(val merchant: String) {
        /** Linked by an installment entry (any status; a PAID entry here). */
        INSTALLMENT_LINKED("Alpha"),

        /** Plan purchase record: plan row AND the INSTALLMENT_PURCHASE flag. */
        PLAN_ROW_WITH_FLAG("Bravo"),

        /** Plan purchase record: plan row only, no flag. */
        PLAN_ROW_ONLY("Charlie"),

        /** Orphaned INSTALLMENT_PURCHASE flag, no plan row at all. */
        FLAG_ONLY("Delta")
    }

    companion object {
        private const val HALF_DAY = 12L * 60 * 60 * 1000

        private fun dayOf(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        private val DUE = dayOf(2026, 10, 1)
        private const val CONTROL_MERCHANT = "Echo"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleDao = database.manualRecurringExpenseDao()
        occurrenceDao = database.recurringOccurrenceDao()
        deliveryDao = database.recurringReminderDeliveryDao()
        plannedDao = database.plannedExpenseDao()
        eventDao = database.recurringLifecycleEventDao()
        expenseDao = database.expenseDao()
        planDao = database.installmentPlanDao()

        val maintenance = io.mockk.mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            io.mockk.every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            io.mockk.every { isWritesAllowed() } returns true
        }
        val barrier = DatabaseWriteBarrier(maintenance)
        val eventWriter = object : RecurringLifecycleEventWriter {
            override suspend fun writeCritical(
                occurrenceId: Long?,
                eventType: String,
                oldStatus: String?,
                newStatus: String?,
                metadata: String?,
                occurredAt: Long
            ): Long = eventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) clockNow else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }
        fun materializer() = RecurringOccurrenceMaterializer(
            database = database,
            writeBarrier = barrier,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            timeProvider = timeProvider,
            eventWriter = eventWriter,
            plannedExpenseDao = plannedDao
        )
        // Real reader over the real DB: the generation path reads the real installment link sets.
        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { InstallmentEntryLinkReaderImpl(planDao) }
        )

        lifecycle = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = materializer(),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = maintenance,
            writeBarrier = barrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = io.mockk.mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility
        )
        val projection = RecurringPlanProjectionService(
            plannedExpenseDao = plannedDao,
            occurrenceDao = occurrenceDao,
            writeBarrier = barrier
        )
        ruleCoordinator = RecurringRuleLifecycleCoordinator(
            database = database,
            writeBarrier = barrier,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            plannedExpenseDao = plannedDao,
            lifecycleEventDao = eventDao,
            lifecycleCoordinator = dagger.Lazy { lifecycle },
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = materializer(),
            expenseDao = expenseDao,
            eventWriter = eventWriter,
            planProjectionService = dagger.Lazy { projection }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun rule(id: Long, merchant: String, isActive: Boolean = true) = ManualRecurringExpense(
        id = id,
        merchant = merchant,
        amount = 15.99,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = DUE,
        createdAt = DUE,
        isActive = isActive
    )

    private fun plan(uuid: String, purchaseExpenseId: Long? = null) = InstallmentPlan(
        planUuid = uuid,
        kind = if (purchaseExpenseId == null) "STANDALONE" else "PURCHASE_ATTACHED",
        purchaseExpenseId = purchaseExpenseId,
        displayName = "Synthetic plan",
        currency = "EUR",
        splitMethod = "EQUAL_SPLIT",
        declaredTotal = 300.0,
        installmentCount = 3,
        frequency = "MONTHLY",
        firstDueDate = 20_000L,
        anchorDayOfMonth = 15,
        residualPlacement = "LAST",
        fulfilmentMode = "CONFIRM",
        declaredOnDate = 19_999L,
        status = "ACTIVE",
        createdAt = clockNow,
        updatedAt = clockNow
    )

    /** Same merchant, amount, currency and calendar day as the occurrence of [merchant]'s rule. */
    private suspend fun seedExpense(merchant: String, shape: Shape?): Long {
        val reason = when (shape) {
            Shape.PLAN_ROW_WITH_FLAG, Shape.FLAG_ONLY -> "INSTALLMENT_PURCHASE"
            else -> null
        }
        val expenseId = expenseDao.insert(
            Expense(
                amount = 15.99,
                currency = "EUR",
                merchant = merchant,
                transactionType = TransactionType.PURCHASE,
                date = DUE + HALF_DAY / 2,
                createdAt = DUE,
                source = "test",
                spendingExclusionReason = reason
            )
        )
        when (shape) {
            Shape.INSTALLMENT_LINKED -> {
                val planId = planDao.insertPlan(plan("plan-$merchant"))
                planDao.insertEntries(
                    listOf(
                        InstallmentEntry(
                            planId = planId,
                            seq = 1,
                            kind = "REGULAR",
                            dueDate = 20_000L,
                            scheduledAmount = 15.99,
                            status = "PAID",
                            linkedExpenseId = expenseId,
                            linkOrigin = "USER_LINKED",
                            statusChangedAt = clockNow
                        )
                    )
                )
            }
            Shape.PLAN_ROW_WITH_FLAG, Shape.PLAN_ROW_ONLY ->
                planDao.insertPlan(plan("plan-$merchant", purchaseExpenseId = expenseId))
            Shape.FLAG_ONLY, null -> Unit
        }
        return expenseId
    }

    private suspend fun dueOccurrence(ruleId: Long) =
        occurrenceDao.getBySource("RECURRING_RULE", ruleId).first { it.dueDate == DUE }

    /** The occurrence stays PLANNED and unlinked, and its reminders are still scheduled. */
    private suspend fun assertStaysPlannedWithReminders(ruleId: Long, label: String) {
        val occurrence = dueOccurrence(ruleId)
        assertEquals("$label: occurrence must stay PLANNED", "PLANNED", occurrence.status)
        assertNull("$label: occurrence must not be linked", occurrence.linkedExpenseId)
        assertNull("$label: no paid snapshot", occurrence.paidAmount)
        val deliveries = deliveryDao.getByOccurrenceIds(listOf(occurrence.id))
        assertTrue("$label: reminders must still be scheduled", deliveries.isNotEmpty())
        assertTrue(
            "$label: every reminder stays SCHEDULED: ${deliveries.map { it.status }}",
            deliveries.all { it.status == "SCHEDULED" }
        )
    }

    private suspend fun assertNoPaidSideEvents() {
        val fulfilled = eventDao.getEventsByType("PLANNED_FULFILLED")
        val fulfillmentSkipped = eventDao.getEventsByType("PLANNED_FULFILLMENT_SKIPPED")
        val paid = eventDao.getEventsByType("OCCURRENCE_PAID")
        assertTrue("no PLANNED_FULFILLED event", fulfilled.isEmpty())
        assertTrue("no PLANNED_FULFILLMENT_SKIPPED event", fulfillmentSkipped.isEmpty())
        assertTrue("no OCCURRENCE_PAID event", paid.isEmpty())
    }

    private suspend fun assertPaidByTheExpense(ruleId: Long, expenseId: Long) {
        val occurrence = dueOccurrence(ruleId)
        assertEquals("PAID", occurrence.status)
        assertEquals(expenseId, occurrence.linkedExpenseId)
    }

    // ── createRule ──────────────────────────────────────────────────────────

    @Test
    fun createRuleLeavesTheOccurrencePlannedForEveryInstallmentExpenseShape() = runTest(timeout = 60.seconds) {
        for (shape in Shape.values()) {
            seedExpense(shape.merchant, shape)
            val ruleId = ruleCoordinator.createRule(rule(id = 0L, merchant = shape.merchant))

            assertStaysPlannedWithReminders(ruleId, shape.name)
        }
        assertNoPaidSideEvents()
    }

    @Test
    fun createRuleStillPaysTheOccurrenceWithANormalExpense() = runTest(timeout = 60.seconds) {
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        val ruleId = ruleCoordinator.createRule(rule(id = 0L, merchant = CONTROL_MERCHANT))

        assertPaidByTheExpense(ruleId, expenseId)
    }

    // ── activateRule ────────────────────────────────────────────────────────

    @Test
    fun activateRuleLeavesTheOccurrencePlannedForEveryInstallmentExpenseShape() = runTest(timeout = 60.seconds) {
        for ((index, shape) in Shape.values().withIndex()) {
            val ruleId = index + 1L
            ruleDao.insert(rule(id = ruleId, merchant = shape.merchant, isActive = false))
            seedExpense(shape.merchant, shape)

            ruleCoordinator.activateRule(ruleId)

            assertStaysPlannedWithReminders(ruleId, shape.name)
        }
        assertNoPaidSideEvents()
    }

    @Test
    fun activateRuleStillPaysTheOccurrenceWithANormalExpense() = runTest(timeout = 60.seconds) {
        ruleDao.insert(rule(id = 1L, merchant = CONTROL_MERCHANT, isActive = false))
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        ruleCoordinator.activateRule(1L)

        assertPaidByTheExpense(1L, expenseId)
    }

    // ── topUpHorizon ────────────────────────────────────────────────────────

    @Test
    fun topUpHorizonLeavesTheOccurrencePlannedForEveryInstallmentExpenseShape() = runTest(timeout = 60.seconds) {
        for ((index, shape) in Shape.values().withIndex()) {
            val ruleId = index + 1L
            ruleDao.insert(rule(id = ruleId, merchant = shape.merchant))
            seedExpense(shape.merchant, shape)

            val inserted = ruleCoordinator.topUpHorizon(ruleId, clockNow)

            assertTrue("${shape.name}: the top-up must create slots", inserted > 0)
            assertStaysPlannedWithReminders(ruleId, shape.name)
        }
        assertNoPaidSideEvents()
    }

    @Test
    fun topUpHorizonStillPaysTheOccurrenceWithANormalExpense() = runTest(timeout = 60.seconds) {
        ruleDao.insert(rule(id = 1L, merchant = CONTROL_MERCHANT))
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        ruleCoordinator.topUpHorizon(1L, clockNow)

        assertPaidByTheExpense(1L, expenseId)
    }

    // ── updateRule ──────────────────────────────────────────────────────────

    @Test
    fun updateRuleLeavesTheNewSlotPlannedForEveryInstallmentExpenseShape() = runTest(timeout = 60.seconds) {
        for ((index, shape) in Shape.values().withIndex()) {
            val ruleId = index + 1L
            ruleDao.insert(rule(id = ruleId, merchant = shape.merchant))
            seedExpense(shape.merchant, shape)

            ruleCoordinator.updateRule(rule(id = ruleId, merchant = shape.merchant).copy(amount = 16.0))

            assertStaysPlannedWithReminders(ruleId, shape.name)
        }
        assertNoPaidSideEvents()
    }

    @Test
    fun updateRuleStillPaysTheNewSlotWithANormalExpense() = runTest(timeout = 60.seconds) {
        ruleDao.insert(rule(id = 1L, merchant = CONTROL_MERCHANT))
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        ruleCoordinator.updateRule(rule(id = 1L, merchant = CONTROL_MERCHANT).copy(amount = 16.0))

        assertPaidByTheExpense(1L, expenseId)
    }

    // ── generateOccurrences ─────────────────────────────────────────────────

    @Test
    fun generateOccurrencesLeavesTheOccurrencePlannedForEveryInstallmentExpenseShape() =
        runTest(timeout = 60.seconds) {
            for ((index, shape) in Shape.values().withIndex()) {
                val ruleId = index + 1L
                ruleDao.insert(rule(id = ruleId, merchant = shape.merchant))
                seedExpense(shape.merchant, shape)

                lifecycle.generateOccurrences(
                    ruleId = ruleId,
                    startDate = DUE,
                    endDate = dayOf(2026, 11, 1),
                    options = OccurrenceGenerationOptions(
                        createReminderDeliveries = true,
                        reminderWindows = listOf("DUE_DAY"),
                        generationSource = OccurrenceGenerationSource.TEST
                    )
                )

                assertStaysPlannedWithReminders(ruleId, shape.name)
            }
            assertNoPaidSideEvents()
        }

    @Test
    fun generateOccurrencesStillPaysTheOccurrenceWithANormalExpense() = runTest(timeout = 60.seconds) {
        ruleDao.insert(rule(id = 1L, merchant = CONTROL_MERCHANT))
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        lifecycle.generateOccurrences(
            ruleId = 1L,
            startDate = DUE,
            endDate = dayOf(2026, 11, 1),
            options = OccurrenceGenerationOptions(
                createReminderDeliveries = true,
                reminderWindows = listOf("DUE_DAY"),
                generationSource = OccurrenceGenerationSource.TEST
            )
        )

        assertPaidByTheExpense(1L, expenseId)
    }

    // ── projectOccurrences (in-memory, never persisted) ─────────────────────

    @Test
    fun projectOccurrencesDoesNotReportPaidForAnInstallmentExpense() = runTest(timeout = 60.seconds) {
        for ((index, shape) in Shape.values().withIndex()) {
            val ruleId = index + 1L
            ruleDao.insert(rule(id = ruleId, merchant = shape.merchant))
            seedExpense(shape.merchant, shape)

            val projected = lifecycle.projectOccurrences(ruleId, DUE, dayOf(2026, 11, 1))

            val occurrence = projected.single { it.dueDate == DUE }
            assertEquals("${shape.name}: projection must stay PLANNED", "PLANNED", occurrence.status)
            assertNull("${shape.name}: projection must not link", occurrence.linkedExpenseId)
            assertNull("${shape.name}: projection must not carry a paid snapshot", occurrence.paidAmount)
        }
        val persisted = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        assertTrue("the projection never persists", persisted.isEmpty())
    }

    @Test
    fun projectOccurrencesStillReportsPaidForANormalExpense() = runTest(timeout = 60.seconds) {
        ruleDao.insert(rule(id = 1L, merchant = CONTROL_MERCHANT))
        val expenseId = seedExpense(CONTROL_MERCHANT, shape = null)

        val projected = lifecycle.projectOccurrences(1L, DUE, dayOf(2026, 11, 1))

        val occurrence = projected.single { it.dueDate == DUE }
        assertEquals("PAID", occurrence.status)
        assertEquals(expenseId, occurrence.linkedExpenseId)
    }
}
