package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * RP-04 slice A2: logical occurrence reconciliation tests for
 * [RecurringRuleLifecycleCoordinator] (plan section P4-003/004).
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric, MaterializerTest pattern),
 * REAL [DatabaseWriteBarrier] over a mocked [RestoreMaintenanceMode], REAL expander /
 * resolver / materializer / projection service, REAL coordinator. Only the outer
 * lifecycle writer seam is a fake (in-memory) implementation.
 *
 * Each test asserts the plan's invariant checklist post-mutation: no duplicate
 * occurrenceKey, no duplicate open logical slot (due date), no orphan open planned row,
 * no open delivery for a missing/non-PLANNED occurrence, and the asserted critical event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringRuleLifecycleCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var ruleDao: ManualRecurringExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var deliveryDao: RecurringReminderDeliveryDao
    private lateinit var plannedDao: PlannedExpenseDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var maintenanceMode: RestoreMaintenanceMode
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var eventWriter: RecordingEventWriter
    private lateinit var coordinator: RecurringRuleLifecycleCoordinator

    private val timeProvider = object : TimeProvider {
        override fun now() = NOW
    }

    companion object {
        /** Fixed reconciliation reference instant: 2026-09-19T12:00Z. */
        private const val NOW = 1_788_830_400_000L

        private val REFERENCE_DAY: Long =
            LocalDate.of(2026, 9, 19).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        private fun dayOf(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
        maintenanceMode = mockMaintenance(normal = true)
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        eventWriter = RecordingEventWriter()

        val lifecycleCoordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = maintenanceMode,
            writeBarrier = writeBarrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = NoopDomainTransactionRunner,
            eventWriter = eventWriter
        )

        val projectionService = com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService(
            plannedExpenseDao = plannedDao,
            occurrenceDao = occurrenceDao,
            writeBarrier = writeBarrier
        )

        coordinator = RecurringRuleLifecycleCoordinator(
            database = database,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            plannedExpenseDao = plannedDao,
            lifecycleEventDao = eventDao,
            lifecycleCoordinator = daggerLazyOf(lifecycleCoordinator),
            expander = RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            expenseDao = expenseDao,
            eventWriter = eventWriter,
            planProjectionService = daggerLazyOf(projectionService)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun mockMaintenance(normal: Boolean): RestoreMaintenanceMode =
        io.mockk.mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            io.mockk.every { currentMode() } returns
                (
                    if (normal) RestoreMaintenanceMode.Mode.NORMAL
                    else RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
                    )
            io.mockk.every { isWritesAllowed() } returns normal
        }

    private fun <T> daggerLazyOf(value: T): dagger.Lazy<T> = dagger.Lazy { value }

    /**
     * A1-harness-equivalent transaction runner: executes the block synchronously
     * with a fixed context (real atomicity is provided by the real Room
     * `withTransaction` that wraps it).
     */
    private object NoopDomainTransactionRunner :
        com.yourname.expensetracker.domain.transaction.DomainTransactionRunner {
        override suspend fun <T> runInTransaction(
            correlationId: String,
            causationId: String?,
            operationId: String,
            source: String,
            metadata: Map<String, String>,
            block: suspend (com.yourname.expensetracker.domain.transaction.TransactionContext) -> T
        ): T = block(
            com.yourname.expensetracker.domain.transaction.TransactionContext(
                correlationId = correlationId,
                causationId = causationId,
                operationId = operationId,
                source = source,
                occurredAt = NOW
            )
        )
    }

    private fun rule(
        id: Long,
        merchant: String = "Netflix",
        amount: Double = 15.99,
        currency: String = "EUR",
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        nextDate: Long = dayOf(2026, 10, 1),
        isActive: Boolean = true
    ): ManualRecurringExpense = ManualRecurringExpense(
        id = id,
        merchant = merchant,
        amount = amount,
        currency = currency,
        frequency = frequency,
        nextDate = nextDate,
        createdAt = NOW - 90L * 24 * 60 * 60 * 1000,
        isActive = isActive
    )

    private fun occurrence(
        id: Long,
        ruleId: Long,
        dueDay: LocalDate,
        status: String = "PLANNED",
        amount: Double = 15.99,
        currency: String = "EUR",
        key: String? = null,
        linkedExpenseId: Long? = null,
        paidAmount: Double? = null,
        paidCurrency: String? = null
    ): RecurringOccurrence {
        val due = dayOf(dueDay.year, dueDay.monthValue, dueDay.dayOfMonth)
        return RecurringOccurrence(
            id = id,
            sourceType = "RECURRING_RULE",
            sourceId = ruleId,
            occurrenceKey = key ?: "RECURRING_RULE|$ruleId|$due|MONTHLY",
            dueDate = due,
            status = status,
            expectedAmount = amount,
            expectedCurrency = currency,
            frequency = "MONTHLY",
            merchant = "Netflix",
            linkedExpenseId = linkedExpenseId,
            paidAmount = paidAmount,
            paidCurrency = paidCurrency,
            createdAt = NOW - 30L * 24 * 60 * 60 * 1000,
            updatedAt = NOW - 30L * 24 * 60 * 60 * 1000
        )
    }

    private suspend fun seed(vararg occurrences: RecurringOccurrence) {
        for (occ in occurrences) {
            val withId = occ.copy(id = 0)
            val inserted = occurrenceDao.insert(withId)
            // occurrenceDao.insert uses OnConflictStrategy.IGNORE; force the requested id
            // by deleting and re-inserting with the explicit key when needed.
            if (inserted <= 0) {
                // Key collision in seed: fail loudly — tests must use unique keys.
                throw IllegalStateException("Seed insert ignored (duplicate key?): ${occ.occurrenceKey}")
            }
            // Room autogenerate may not equal the requested id; remap ids are NOT
            // required by tests (they address rows by key/date, not id).
        }
    }

    private suspend fun seed(occurrences: List<RecurringOccurrence>) = seed(*occurrences.toTypedArray())

    private suspend fun seedRule(rule: ManualRecurringExpense) {
        ruleDao.insert(rule)
    }

    private suspend fun seedDelivery(occurrenceKey: String, window: String, scheduledAt: Long, status: String = "SCHEDULED") {
        val occ = occurrenceDao.getByKey(occurrenceKey) ?: error("missing occurrence $occurrenceKey")
        deliveryDao.insert(
            RecurringReminderDelivery(
                occurrenceId = occ.id,
                reminderWindow = window,
                scheduledAt = scheduledAt,
                status = status,
                createdAt = NOW,
                updatedAt = NOW
            )
        )
    }

    private suspend fun seedPlannedRow(occurrenceKey: String, amount: Double, currency: String, date: Long) {
        val occ = occurrenceDao.getByKey(occurrenceKey) ?: error("missing occurrence $occurrenceKey")
        plannedDao.insertPlannedExpense(
            com.yourname.expensetracker.data.database.entity.PlannedExpense(
                description = "Netflix",
                amount = amount,
                currency = currency,
                date = date,
                isRecurring = true,
                priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST,
                createdAt = NOW,
                sourceOccurrenceKey = occurrenceKey,
                openSourceOccurrenceKey = occurrenceKey,
                sourceRecurringRuleId = occ.sourceId,
                merchantKey = com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("Netflix"),
                updatedAt = NOW
            )
        )
    }

    private suspend fun seedLinkedExpense(dueDay: LocalDate, amount: Double, currency: String = "EUR"): Long {
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = "Netflix",
            transactionType = TransactionType.PURCHASE,
            date = dayOf(dueDay.year, dueDay.monthValue, dueDay.dayOfMonth),
            createdAt = NOW,
            source = "test"
        )
        return expenseDao.insert(expense)
    }

    /** Plan invariant checklist, asserted after every mutation. */
    private suspend fun assertInvariants(ruleId: Long, expectRuleDeleted: Boolean = false) {
        val occurrences = if (expectRuleDeleted) emptyList() else occurrenceDao.getBySource("RECURRING_RULE", ruleId)

        // no duplicate occurrenceKey
        assertEquals(
            "duplicate occurrenceKey",
            occurrences.size,
            occurrences.map { it.occurrenceKey }.distinct().size
        )

        // no duplicate open logical slot (due date)
        val open = occurrences.filter { it.status == "PLANNED" }
        assertEquals(
            "duplicate open logical slot",
            open.size,
            open.map { it.dueDate }.distinct().size
        )

        // no orphan open planned row: every open PLANNED occurrence has at most its own row,
        // and every planned row keyed to this rule is either fulfilled or backed by an occurrence
        val keys = occurrences.map { it.occurrenceKey }.toSet()
        val rowsByRule = plannedRowsFor(ruleId)
        for (row in rowsByRule) {
            val rowKey = row.sourceOccurrenceKey
            if (row.status == "PLANNED") {
                assertTrue(
                    "orphan open planned row: $rowKey",
                    rowKey != null && keys.contains(rowKey) &&
                        occurrences.first { it.occurrenceKey == rowKey }.status == "PLANNED"
                )
            }
        }

        // no delivery for a missing/non-PLANNED occurrence (open deliveries only)
        if (occurrences.isNotEmpty()) {
            val ids = occurrences.map { it.id }
            val deliveries = deliveryDao.getByOccurrenceIds(ids)
            val openStatuses = setOf("SCHEDULED", "SNOOZED", "CLAIMED", "FAILED_TRANSIENT")
            for (d in deliveries) {
                if (d.status in openStatuses) {
                    val occ = occurrences.firstOrNull { it.id == d.occurrenceId }
                    assertNotNull("open delivery ${d.id} references missing occurrence", occ)
                    assertEquals(
                        "open delivery ${d.id} on non-PLANNED occurrence",
                        "PLANNED",
                        occ!!.status
                    )
                }
            }
        }
    }

    private suspend fun plannedRowsFor(ruleId: Long): List<com.yourname.expensetracker.data.database.entity.PlannedExpense> {
        // Read-side helper via real DAO: rows carry sourceRecurringRuleId.
        return plannedDao.getByRecurringRuleId(ruleId)
    }

    /**
     * Event reads go through the REAL event DAO because deactivate/activate/delete
     * use the tolerated transitional direct-DAO event pattern (A1-verified file
     * style); updateRule uses the injected event writer. Both land in the same
     * table, so one read path asserts all critical events.
     */
    private suspend fun eventsOfType(type: String) =
        eventDao.getEventsByType(type)

    // ── updateRule: logical reconciliation ──────────────────────────────────

    @Test
    fun `updateAmountPreservesOverdueOpenOccurrenceAndUpdatesFutureOpenOccurrences`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        // Overdue open occurrence (before reference day) + future open occurrence
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PLANNED"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )
        seedPlannedRow("RECURRING_RULE|1|${dayOf(2026, 9, 1)}|MONTHLY", 15.99, "EUR", dayOf(2026, 9, 1))
        seedPlannedRow("RECURRING_RULE|1|${dayOf(2026, 10, 1)}|MONTHLY", 15.99, "EUR", dayOf(2026, 10, 1))

        coordinator.updateRule(rule(id = 1L, amount = 25.0))

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        val overdue = after.first { it.dueDate == dayOf(2026, 9, 1) }
        val future = after.first { it.dueDate == dayOf(2026, 10, 1) }
        // Overdue obligation preserved with OLD snapshot
        assertEquals("PLANNED", overdue.status)
        assertEquals(15.99, overdue.expectedAmount, 0.0001)
        // Future open occurrence adopted the new snapshot
        assertEquals("PLANNED", future.status)
        assertEquals(25.0, future.expectedAmount, 0.0001)
        // Derived planned row for the future slot updated atomically
        val futureRow = plannedDao.getBySourceOccurrenceKey(future.occurrenceKey)!!
        assertEquals(25.0, futureRow.amount, 0.0001)
        assertEquals("RULE_UPDATED_RECONCILED", eventsOfType("RULE_UPDATED_RECONCILED").single().eventType)
        assertInvariants(1L)
    }

    @Test
    fun `updateCurrencyPreservesPaidSnapshotAndAtomicallyUpdatesFutureRows`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PAID",
                amount = 15.99, currency = "EUR", paidAmount = 15.99, paidCurrency = "EUR"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )

        coordinator.updateRule(rule(id = 1L, amount = 15.99, currency = "USD"))

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        val paid = after.first { it.status == "PAID" }
        val future = after.first { it.status == "PLANNED" }
        // Paid snapshot never rewritten from the new rule
        assertEquals("EUR", paid.expectedCurrency)
        assertEquals("EUR", paid.paidCurrency)
        // Future open row adopted the new currency
        assertEquals("USD", future.expectedCurrency)
        assertInvariants(1L)
    }

    @Test
    fun `updateDateMapsMovedOpenSlotWithoutDuplicatePlannedObligation`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L, nextDate = dayOf(2026, 10, 1)))
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"))
        seedPlannedRow("RECURRING_RULE|1|${dayOf(2026, 10, 1)}|MONTHLY", 15.99, "EUR", dayOf(2026, 10, 1))

        // Move the due date by a week; MONTHLY slots regenerate from the new anchor.
        coordinator.updateRule(rule(id = 1L, nextDate = dayOf(2026, 10, 8)))

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        val open = after.filter { it.status == "PLANNED" }
        // Exactly one open obligation for the (single) new slot date — no duplicates,
        // and the old slot row was retired, not duplicated alongside the new one.
        val newSlotDates = open.map { it.dueDate }.toSet()
        assertTrue("new slot date must exist", newSlotDates.contains(dayOf(2026, 10, 8)))
        assertFalse("old slot must not remain open", newSlotDates.contains(dayOf(2026, 10, 1)))
        // One open row per logical slot across the whole regenerated window.
        assertEquals(open.size, newSlotDates.size)
        assertInvariants(1L)
    }

    @Test
    fun `updateFrequencyMapsSlotsWithoutDuplicateOrConflictingOpenRows`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L, nextDate = dayOf(2026, 10, 1)))
        // Weekly rule with a future open slot
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED", key = "RECURRING_RULE|1|${dayOf(2026, 10, 1)}|WEEKLY"))

        // Switch to BIWEEKLY: the 10-01 slot is unchanged as a date; it is re-keyed in place.
        coordinator.updateRule(
            rule(id = 1L, frequency = RecurrenceFrequency.BIWEEKLY, nextDate = dayOf(2026, 10, 1))
        )

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        val open = after.filter { it.status == "PLANNED" }
        assertTrue(open.isNotEmpty())
        // One row per date; the 10-01 row is re-keyed to BIWEEKLY, in place.
        val first = open.first { it.dueDate == dayOf(2026, 10, 1) }
        assertEquals("RECURRING_RULE|1|${dayOf(2026, 10, 1)}|BIWEEKLY", first.occurrenceKey)
        assertInvariants(1L)
    }

    @Test
    fun `updatePreservesPaidSkippedMissedCancelledAndIgnoredOccurrences`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        val statuses = listOf("PAID", "SKIPPED", "MISSED", "CANCELLED", "IGNORED")
        val dueDates = listOf(
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15),
            LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 9, 1)
        )
        seed(statuses.indices.map { i ->
            occurrence(id = 0, ruleId = 1, dueDay = dueDates[i], status = statuses[i])
        })

        coordinator.updateRule(rule(id = 1L, amount = 99.0))

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        for ((i, status) in statuses.withIndex()) {
            val row = after.first { it.dueDate == dayOf(dueDates[i].year, dueDates[i].monthValue, dueDates[i].dayOfMonth) }
            assertEquals("terminal row must be preserved", status, row.status)
            assertEquals("terminal row must not adopt new snapshot", 15.99, row.expectedAmount, 0.0001)
        }
        assertInvariants(1L)
    }

    @Test
    fun `updatePreservesLinkedExpenseAndDoesNotRewritePaidSnapshot`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        val expenseId = seedLinkedExpense(LocalDate.of(2026, 9, 1), 16.50)
        seed(
            occurrence(
                id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PAID",
                linkedExpenseId = expenseId, paidAmount = 16.50, paidCurrency = "EUR"
            )
        )

        coordinator.updateRule(rule(id = 1L, amount = 30.0, currency = "USD"))

        val paid = occurrenceDao.getBySource("RECURRING_RULE", 1L).first { it.status == "PAID" }
        assertEquals(expenseId, paid.linkedExpenseId)
        assertEquals(16.50, paid.paidAmount!!, 0.0001)
        assertEquals("EUR", paid.paidCurrency)
        // Historical snapshot fields untouched
        assertEquals(15.99, paid.expectedAmount, 0.0001)
        assertInvariants(1L)
    }

    @Test
    fun `updateReconcilesPlannedRowsAndReminderWindowsAtomically`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        val oct1 = dayOf(2026, 10, 1)
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"))
        seedPlannedRow("RECURRING_RULE|1|$oct1|MONTHLY", 15.99, "EUR", oct1)
        seedDelivery("RECURRING_RULE|1|$oct1|MONTHLY", "DUE_DAY", oct1)

        coordinator.updateRule(rule(id = 1L, amount = 20.0))

        val future = occurrenceDao.getBySource("RECURRING_RULE", 1L).first { it.status == "PLANNED" }
        // Planned row refreshed to the new snapshot, same row identity (by key)
        val row = plannedDao.getBySourceOccurrenceKey(future.occurrenceKey)!!
        assertEquals(20.0, row.amount, 0.0001)
        // Delivery still exists for the occurrence and window (retargeted schedule, not duplicated)
        val deliveries = deliveryDao.getByOccurrenceIds(listOf(future.id))
        assertEquals(1, deliveries.size)
        assertEquals("DUE_DAY", deliveries.single().reminderWindow)
        assertInvariants(1L)
    }

    @Test
    fun `overdueOccurrenceRemainsClaimableAndReminderPolicyIsExplicit`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PLANNED"))

        coordinator.updateRule(rule(id = 1L, amount = 15.99, currency = "USD"))

        val overdue = occurrenceDao.getBySource("RECURRING_RULE", 1L).first { it.status == "PLANNED" }
        // Overdue open row survived the update and remains claimable (status PLANNED,
        // unlinked) with its OLD currency snapshot.
        assertEquals(dayOf(2026, 9, 1), overdue.dueDate)
        assertEquals("EUR", overdue.expectedCurrency)
        assertEquals(null, overdue.linkedExpenseId)
        // Reminder policy is explicit: no new past-due deliveries are created for it
        // (allowPastDueReminderDeliveries=false in the reconciler's materialization).
        val deliveries = deliveryDao.getByOccurrenceIds(listOf(overdue.id))
        assertTrue(deliveries.none { it.status == "SCHEDULED" && it.scheduledAt < NOW })
        assertInvariants(1L)
    }

    @Test
    fun `logicalSlotCollisionFailsClosedAndRollsBack`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        // Two open rows on the same due date = unresolvable pre-condition
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"),
            occurrence(
                id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED",
                key = "RECURRING_RULE|1|${dayOf(2026, 10, 1)}|WEEKLY"
            )
        )

        var thrown: Throwable? = null
        try {
            coordinator.updateRule(rule(id = 1L, amount = 22.0))
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected fail-closed conflict", thrown is IllegalStateException)

        // Rolled back: rule row unchanged, both rows still open, no event written.
        assertEquals(15.99, ruleDao.getById(1L)!!.amount, 0.0001)
        assertEquals(2, occurrenceDao.getBySource("RECURRING_RULE", 1L).size)
        assertTrue(eventsOfType("RULE_UPDATED_RECONCILED").isEmpty())
    }

    @Test
    fun `repeatedIdenticalUpdateIsIdempotent`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PLANNED"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )

        val updated = rule(id = 1L, amount = 19.0)
        coordinator.updateRule(updated)
        val snapshot1 = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        coordinator.updateRule(updated)
        val snapshot2 = occurrenceDao.getBySource("RECURRING_RULE", 1L)

        assertEquals(
            snapshot1.map { it.occurrenceKey to it.status }.sortedBy { it.first },
            snapshot2.map { it.occurrenceKey to it.status }.sortedBy { it.first }
        )
        // The first update materializes the full 12-month window (preserved
        // overdue slot + matched slot + new in-window candidates), so the
        // idempotence contract is size STABILITY across identical updates,
        // not the seeded row count.
        assertEquals(snapshot1.size, snapshot2.size)
        assertInvariants(1L)
    }

    @Test
    fun `ruleMutationRollsBackRuleDerivedRowsAndCriticalEventOnFailure`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PLANNED"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )
        seedPlannedRow("RECURRING_RULE|1|${dayOf(2026, 10, 1)}|MONTHLY", 15.99, "EUR", dayOf(2026, 10, 1))

        // Restore mode flips between barrier-check and transaction commit → blocked.
        io.mockk.every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
        io.mockk.every { maintenanceMode.isWritesAllowed() } returns false

        var thrown: Throwable? = null
        try {
            coordinator.updateRule(rule(id = 1L, amount = 27.0))
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected DatabaseAccessBlockedException", thrown is DatabaseAccessBlockedException)

        // Everything rolled back: rule row, occurrences, derived planned row, no event.
        assertEquals(15.99, ruleDao.getById(1L)!!.amount, 0.0001)
        assertEquals(15.99, occurrenceDao.getBySource("RECURRING_RULE", 1L).first { it.dueDate == dayOf(2026, 10, 1) }.expectedAmount, 0.0001)
        assertEquals(15.99, plannedDao.getBySourceOccurrenceKey("RECURRING_RULE|1|${dayOf(2026, 10, 1)}|MONTHLY")!!.amount, 0.0001)
        assertTrue(eventsOfType("RULE_UPDATED_RECONCILED").isEmpty())

        // Restore normal mode for invariants helper
        io.mockk.every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        io.mockk.every { maintenanceMode.isWritesAllowed() } returns true
        assertInvariants(1L)
    }

    @Test
    fun `deactivateRemovesOpenDerivedStateButPreservesTerminalOccurrences`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PAID",
                paidAmount = 15.99, paidCurrency = "EUR"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )
        val oct1 = dayOf(2026, 10, 1)
        seedPlannedRow("RECURRING_RULE|1|$oct1|MONTHLY", 15.99, "EUR", oct1)
        seedDelivery("RECURRING_RULE|1|$oct1|MONTHLY", "DUE_DAY", oct1)

        coordinator.deactivateRule(1L)

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        assertEquals(1, after.size)
        assertEquals("PAID", after.single().status)
        // Open derived state gone
        assertEquals(0, plannedRowsFor(1L).count { it.status == "PLANNED" })
        assertEquals(0, deliveryDao.getByOccurrenceIds(listOf(after.single().id)).size)
        assertEquals("RULE_DEACTIVATED", eventsOfType("RULE_DEACTIVATED").single().eventType)
        assertEquals(false, ruleDao.getById(1L)!!.isActive)
        assertInvariants(1L)
    }

    @Test
    fun `activateRegeneratesMissingOpenStateWithoutDuplicates`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L, isActive = false))
        // Terminal row preserved through deactivate/reactivate cycle
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PAID"))

        coordinator.activateRule(1L)

        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        // Terminal row NOT recreated
        assertEquals(1, after.count { it.status == "PAID" })
        // New open state generated, one per slot, no duplicates
        val open = after.filter { it.status == "PLANNED" }
        assertTrue(open.isNotEmpty())
        assertEquals(open.size, open.map { it.dueDate }.distinct().size)
        assertEquals("RULE_ACTIVATED_REGENERATED", eventsOfType("RULE_ACTIVATED_REGENERATED").single().eventType)
        assertInvariants(1L)
    }

    @Test
    fun `deleteRuleRemovesAllStatusesDeliveriesAndPlannedRowsAndWritesRuleDeleted`() = runTest(timeout = 60.seconds) {
        seedRule(rule(id = 1L))
        val oct1 = dayOf(2026, 10, 1)
        seed(
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 9, 1), status = "PAID"),
            occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED")
        )
        seedPlannedRow("RECURRING_RULE|1|$oct1|MONTHLY", 15.99, "EUR", oct1)
        seedDelivery("RECURRING_RULE|1|$oct1|MONTHLY", "DUE_DAY", oct1)

        // Capture the seeded rows' ids BEFORE deletion (rows are addressed by key).
        val idsBefore = occurrenceDao.getBySource("RECURRING_RULE", 1L).map { it.id }
        assertTrue(idsBefore.isNotEmpty())

        coordinator.deleteRule(1L)

        assertEquals(0, occurrenceDao.getBySource("RECURRING_RULE", 1L).size)
        assertEquals(null, ruleDao.getById(1L))
        assertEquals(0, plannedRowsFor(1L).size)
        // No deliveries for the deleted occurrences remain.
        assertTrue(deliveryDao.getByOccurrenceIds(idsBefore).isEmpty())
        assertEquals("RULE_DELETED", eventsOfType("RULE_DELETED").single().eventType)
    }

    @Test
    fun `updateRuleUnknownIdFailsClosed`() = runTest(timeout = 60.seconds) {
        // No rule with id 999 exists: updateRule must throw BEFORE any transaction,
        // write no critical event, and leave derived state untouched.
        seedRule(rule(id = 1L)) // unrelated rule present, must be unaffected
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"))

        var thrown: Throwable? = null
        try {
            coordinator.updateRule(rule(id = 999L, amount = 42.0))
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected IllegalArgumentException for unknown rule id", thrown is IllegalArgumentException)

        // No event, no derived-state change for the unknown id...
        assertTrue(eventsOfType("RULE_UPDATED_RECONCILED").isEmpty())
        assertTrue(occurrenceDao.getBySource("RECURRING_RULE", 999L).isEmpty())
        assertEquals(0, plannedRowsFor(999L).size)
        // ...and the unrelated rule's state is untouched.
        assertEquals(1, occurrenceDao.getBySource("RECURRING_RULE", 1L).size)
        assertInvariants(1L)
    }

    @Test
    fun `activateRuleMissingIdIsIdempotentNoOp`() = runTest(timeout = 60.seconds) {
        // Documented contract: activateRule on a missing rule silently returns
        // (no throw, no event, no derived-state change). Pinned explicitly so the
        // silent-return cannot regress into an exception or a phantom write.
        seedRule(rule(id = 1L))
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"))
        val before = occurrenceDao.getBySource("RECURRING_RULE", 1L)

        coordinator.activateRule(999L) // must not throw

        assertTrue(eventsOfType("RULE_ACTIVATED_REGENERATED").isEmpty())
        assertTrue(occurrenceDao.getBySource("RECURRING_RULE", 999L).isEmpty())
        assertEquals(0, plannedRowsFor(999L).size)
        // Unrelated rule untouched (same rows, same statuses).
        val after = occurrenceDao.getBySource("RECURRING_RULE", 1L)
        assertEquals(
            before.map { it.occurrenceKey to it.status }.sortedBy { it.first },
            after.map { it.occurrenceKey to it.status }.sortedBy { it.first }
        )
        assertInvariants(1L)
    }

    @Test
    fun `preExistingOrphanOpenPlannedRowFailsClosedOnRuleUpdate`() = runTest(timeout = 60.seconds) {
        // RP-04 P4-004 general invariant (e): an open planned row with NO backing
        // PLANNED occurrence — seeded directly via DAO to simulate pre-existing
        // corruption — must fail the rule-update reconciliation closed (throw +
        // rollback), never survive the gate or be silently adopted.
        seedRule(rule(id = 1L))
        seed(occurrence(id = 0, ruleId = 1, dueDay = LocalDate.of(2026, 10, 1), status = "PLANNED"))

        // Orphan: open planned row keyed to a slot the rule has no occurrence for.
        val orphanKey = "RECURRING_RULE|1|${dayOf(2026, 11, 1)}|MONTHLY"
        plannedDao.insertPlannedExpense(
            com.yourname.expensetracker.data.database.entity.PlannedExpense(
                description = "Orphan row",
                amount = 5.0,
                currency = "EUR",
                date = dayOf(2026, 11, 1),
                isRecurring = true,
                priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST,
                createdAt = NOW,
                sourceOccurrenceKey = orphanKey,
                openSourceOccurrenceKey = orphanKey,
                sourceRecurringRuleId = 1L,
                merchantKey = com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("Orphan row"),
                updatedAt = NOW
            )
        )
        assertEquals(1, plannedDao.getOpenPlannedByRecurringRuleId(1L).count { it.sourceOccurrenceKey == orphanKey })

        var thrown: Throwable? = null
        try {
            coordinator.updateRule(rule(id = 1L, amount = 27.0))
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue(
            "expected IllegalStateException (INVARIANT_VIOLATION ORPHAN_PLANNED_ROW), got: $thrown",
            thrown is IllegalStateException
        )

        // Fail closed: everything rolled back — rule row unchanged, no critical
        // event, the orphan row itself is untouched (rollback, not deletion).
        assertEquals(15.99, ruleDao.getById(1L)!!.amount, 0.0001)
        assertTrue(eventsOfType("RULE_UPDATED_RECONCILED").isEmpty())
        assertEquals(
            1,
            plannedDao.getOpenPlannedByRecurringRuleId(1L).count { it.sourceOccurrenceKey == orphanKey }
        )
        // The legitimate open occurrence is also untouched.
        val occ = occurrenceDao.getBySource("RECURRING_RULE", 1L).single()
        assertEquals(15.99, occ.expectedAmount, 0.0001)
    }

    // ── test seams ───────────────────────────────────────────────────────────

    /**
     * Recording event writer that ALSO persists to the REAL event DAO, so the
     * RULE_UPDATED_RECONCILED critical event (written via the writer inside the
     * update transaction) lands in the same table the transitional direct-DAO
     * events land in — one read path (eventDao) asserts all critical events.
     */
    private inner class RecordingEventWriter : RecurringLifecycleEventWriter {
        override suspend fun writeCritical(
            occurrenceId: Long?,
            eventType: String,
            oldStatus: String?,
            newStatus: String?,
            metadata: String?,
            occurredAt: Long
        ): Long {
            return eventDao.insert(
                com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) NOW else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }
    }
}
