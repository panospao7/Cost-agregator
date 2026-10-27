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
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * S6c-2: the recurring coordinator's link-path eligibility gate consults the schedule link policy
 * (INV-5) for the auto-link, the manual link and the post-update reconcile. An expense that pays
 * an installment entry, or is a plan purchase record, is never linked to a recurring occurrence.
 * A refusal reuses the existing ineligible outcome; an existing link to the same occurrence still
 * passes.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric), REAL barrier over a mocked
 * [RestoreMaintenanceMode], REAL [InstallmentEntryLinkReaderImpl] and REAL coordinator. Only the
 * lifecycle event writer seam is an in-memory-DAO fake. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringLifecycleCoordinatorInstallmentLinkPolicyTest {

    private lateinit var database: AppDatabase
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var ruleDao: ManualRecurringExpenseDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var planDao: InstallmentPlanDao
    private lateinit var lifecycle: RecurringLifecycleCoordinator

    /** One entry per reader call: whether the Room transaction was open at that moment (S6c-2 8a). */
    private val readerCallsInTransaction = mutableListOf<Boolean>()

    /** Delegates to the real reader and records [AppDatabase.inTransaction] on every call. */
    private inner class RecordingLinkReader(
        private val delegate: InstallmentEntryLinkReader
    ) : InstallmentEntryLinkReader {
        override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
            readerCallsInTransaction.add(database.inTransaction())
            return delegate.linkedEntryIdFor(expenseId)
        }

        override suspend fun isPlanPurchase(expenseId: Long): Boolean {
            readerCallsInTransaction.add(database.inTransaction())
            return delegate.isPlanPurchase(expenseId)
        }

        override suspend fun linkedExpenseIds(): Set<Long> {
            readerCallsInTransaction.add(database.inTransaction())
            return delegate.linkedExpenseIds()
        }

        override suspend fun planPurchaseExpenseIds(): Set<Long> {
            readerCallsInTransaction.add(database.inTransaction())
            return delegate.planPurchaseExpenseIds()
        }
    }

    private val clockNow = dayOf(2026, 9, 19) + HALF_DAY
    private val timeProvider = object : TimeProvider {
        override fun now() = clockNow
    }

    companion object {
        private const val HALF_DAY = 12L * 60 * 60 * 1000

        private fun dayOf(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceDao = database.recurringOccurrenceDao()
        ruleDao = database.manualRecurringExpenseDao()
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
        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { RecordingLinkReader(InstallmentEntryLinkReaderImpl(planDao)) }
        )
        lifecycle = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = barrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = database.recurringReminderDeliveryDao(),
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = database.plannedExpenseDao()
            ),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = database.recurringReminderDeliveryDao(),
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = maintenance,
            writeBarrier = barrier,
            plannedExpenseDao = database.plannedExpenseDao(),
            transactionRunner = io.mockk.mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun seedRule(nextDate: Long) {
        ruleDao.insert(
            ManualRecurringExpense(
                id = 1L,
                merchant = "Netflix",
                amount = 15.99,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = nextDate,
                createdAt = nextDate,
                isActive = true
            )
        )
    }

    private suspend fun seedOccurrence(due: Long): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = 1L,
            occurrenceKey = "RECURRING_RULE|1|$due|MONTHLY",
            dueDate = due,
            status = "PLANNED",
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = "Netflix",
            createdAt = due,
            updatedAt = due
        )
    )

    private suspend fun seedExpense(
        date: Long,
        reason: String? = null,
        type: TransactionType = TransactionType.PURCHASE
    ): Long =
        expenseDao.insert(
            Expense(
                amount = 15.99,
                currency = "EUR",
                merchant = "Netflix",
                transactionType = type,
                date = date,
                createdAt = date,
                source = "test",
                spendingExclusionReason = reason
            )
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

    /** Makes [expenseId] the payment of a PAID entry of a fresh plan (an installment-linked expense). */
    private suspend fun linkToInstallmentEntry(expenseId: Long, uuid: String = "plan-linked") {
        val planId = planDao.insertPlan(plan(uuid))
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

    /** S6c-2 8a: the gate read at least once, and every read ran inside the claim's Room transaction. */
    private fun assertGateReadsRanInsideTheTransaction() {
        assertTrue("the gate must read the installment reader", readerCallsInTransaction.isNotEmpty())
        assertTrue(
            "every installment reader call must run inside the Room transaction: $readerCallsInTransaction",
            readerCallsInTransaction.all { it }
        )
    }

    private suspend fun openOccurrenceAndMatchingExpense(
        reason: String? = null,
        type: TransactionType = TransactionType.PURCHASE
    ): Pair<Long, Long> {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val occurrenceId = seedOccurrence(due)
        val expenseId = seedExpense(due + HALF_DAY / 2, reason, type)
        return occurrenceId to expenseId
    }

    /** Changes the type of an already linked expense (an edit after the link exists). */
    private suspend fun retypeExpense(expenseId: Long, type: TransactionType) {
        expenseDao.update(expenseDao.getById(expenseId)!!.copy(transactionType = type))
    }

    // ── AE-13: auto-link ────────────────────────────────────────────────────

    @Test
    fun autoLinkSkipsAnInstallmentLinkedExpenseAndTheOccurrenceStaysPlanned() =
        runTest(timeout = 60.seconds) {
            val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
            linkToInstallmentEntry(expenseId)

            assertEquals(false, lifecycle.linkExpenseToOccurrence(expenseId))

            val occurrence = occurrenceDao.getById(occurrenceId)!!
            assertEquals("PLANNED", occurrence.status)
            assertNull(occurrence.linkedExpenseId)
            assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
            assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
            // 8a: the auto-link gate reads inside the claim transaction.
            assertGateReadsRanInsideTheTransaction()
        }

    // C2: a plan row WITHOUT the INSTALLMENT_PURCHASE flag still refuses the auto-link.
    @Test
    fun autoLinkSkipsAPlanPurchaseRowWithoutTheFlag() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(reason = null)
        planDao.insertPlan(plan("plan-no-flag-auto", purchaseExpenseId = expenseId))

        assertEquals(false, lifecycle.linkExpenseToOccurrence(expenseId))

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
    }

    @Test
    fun detailedAutoLinkReportsTheExistingIneligibleOutcomeForAnInstallmentLinkedExpense() =
        runTest(timeout = 60.seconds) {
            val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
            linkToInstallmentEntry(expenseId)

            assertEquals(
                RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible"),
                lifecycle.linkExpenseToOccurrenceDetailed(expenseId)
            )
            assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
        }

    @Test
    fun autoLinkStillLinksANormalExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()

        assertTrue(lifecycle.linkExpenseToOccurrence(expenseId))

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PAID", occurrence.status)
        assertEquals(expenseId, occurrence.linkedExpenseId)
        assertEquals(1, eventDao.getEventsByType("OCCURRENCE_PAID").size)
    }

    // ── manual link ─────────────────────────────────────────────────────────

    @Test
    fun manualLinkOfAnInstallmentLinkedExpenseIsRefused() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
        linkToInstallmentEntry(expenseId)

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
        // 8a: the manual-link gate reads inside the claim transaction.
        assertGateReadsRanInsideTheTransaction()
    }

    @Test
    fun manualLinkOfAPlanPurchaseRecordIsRefused() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(reason = "INSTALLMENT_PURCHASE")
        planDao.insertPlan(plan("plan-purchase", purchaseExpenseId = expenseId))

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
    }

    // C2: the plan row alone (no INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag) refuses the link.
    @Test
    fun manualLinkOfAPlanPurchaseRowWithoutTheFlagIsRefused() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(reason = null)
        planDao.insertPlan(plan("plan-no-flag-manual", purchaseExpenseId = expenseId))

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
    }

    // 8c: an orphan INSTALLMENT_PURCHASE flag with no plan row at all is refused.
    @Test
    fun manualLinkOfAnOrphanedPurchaseFlagWithNoPlanRowIsRefused() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(reason = "INSTALLMENT_PURCHASE")

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
    }

    @Test
    fun manualLinkOfAnOrphanedReversedFlagIsRefused() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(reason = "INSTALLMENT_REVERSED")

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
    }

    @Test
    fun manualLinkStillLinksANormalExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()

        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        assertEquals(expenseId, occurrenceDao.getById(occurrenceId)!!.linkedExpenseId)
    }

    // ── existing link to the same occurrence still passes ───────────────────

    @Test
    fun anExistingLinkToTheSameOccurrenceStillPassesTheGate() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        assertEquals(
            SpecificLinkResult.AlreadyLinkedToThis,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )
        assertEquals(
            RecurringExpenseReconcileResult.UpdatedLinkedSnapshot(expenseId, occurrenceId),
            lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PAID", occurrence.status)
        assertEquals(expenseId, occurrence.linkedExpenseId)
    }

    // ── reconcile after update ──────────────────────────────────────────────

    @Test
    fun reconcileDoesNotLinkAnInstallmentLinkedExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
        linkToInstallmentEntry(expenseId)

        assertEquals(
            RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible"),
            lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)
        )

        assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
        assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
    }

    @Test
    fun reconcileUnlinksAnExpenseThatBecameInstallmentLinkedAndNeverRelinksIt() =
        runTest(timeout = 60.seconds) {
            val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
            val occurrenceKey = occurrenceDao.getById(occurrenceId)!!.occurrenceKey
            // The planned row of the occurrence, so the link fulfils it and the unlink must reset it.
            database.plannedExpenseDao().insertPlannedExpense(
                PlannedExpense(
                    description = "Netflix",
                    amount = 15.99,
                    currency = "EUR",
                    date = dayOf(2026, 9, 19),
                    createdAt = clockNow,
                    sourceOccurrenceKey = occurrenceKey,
                    sourceRecurringRuleId = 1L,
                    status = "PLANNED",
                    updatedAt = clockNow,
                    openSourceOccurrenceKey = occurrenceKey
                )
            )
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
            )
            val plannedLinked = database.plannedExpenseDao().getBySourceOccurrenceKey(occurrenceKey)!!
            assertEquals("FULFILLED", plannedLinked.status)
            val paidEventsBefore = eventDao.getEventsByType("OCCURRENCE_PAID")
            linkToInstallmentEntry(expenseId)

            val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

            assertTrue(result is RecurringExpenseReconcileResult.Unlinked)
            val occurrence = occurrenceDao.getById(occurrenceId)!!
            assertEquals("PLANNED", occurrence.status)
            assertNull(occurrence.linkedExpenseId)
            assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
            // 8b: exactly one unlink event, no new PAID event, and the planned row is not FULFILLED.
            val unlinkedEvents = eventDao.getEventsByType("OCCURRENCE_UNLINKED")
            assertEquals(1, unlinkedEvents.size)
            val paidEventsAfter = eventDao.getEventsByType("OCCURRENCE_PAID")
            assertEquals(paidEventsBefore.size, paidEventsAfter.size)
            val plannedAfter = database.plannedExpenseDao().getBySourceOccurrenceKey(occurrenceKey)!!
            assertNotEquals("FULFILLED", plannedAfter.status)
        }

    @Test
    fun reconcileStillLinksANormalUnlinkedExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()

        val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

        assertTrue(result is RecurringExpenseReconcileResult.Linked)
        assertEquals(expenseId, occurrenceDao.getById(occurrenceId)!!.linkedExpenseId)
    }

    // ── owner item 37(b): layer-1 UNKNOWN alignment (F-AE-6 / F-AE-8) ────────

    // F-AE-6: a NEW link of an UNKNOWN expense is refused on every new-link path.
    @Test
    fun autoLinkRefusesAnUnknownExpenseAndTheOccurrenceStaysPlanned() = runTest(timeout = 60.seconds) {
        // owner item 37(b): UNKNOWN is never auto-linked.
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(type = TransactionType.UNKNOWN)

        assertEquals(false, lifecycle.linkExpenseToOccurrence(expenseId))

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
    }

    @Test
    fun detailedAutoLinkReportsTheExistingIneligibleOutcomeForAnUnknownExpense() =
        runTest(timeout = 60.seconds) {
            val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(type = TransactionType.UNKNOWN)

            assertEquals(
                RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible"),
                lifecycle.linkExpenseToOccurrenceDetailed(expenseId)
            )
            assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
        }

    @Test
    fun manualLinkRefusesAnUnknownExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(type = TransactionType.UNKNOWN)

        assertEquals(
            SpecificLinkResult.ExpenseNotEligible,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PLANNED", occurrence.status)
        assertNull(occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").isEmpty())
    }

    @Test
    fun reconcileDoesNotCreateANewLinkForAnUnlinkedUnknownExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(type = TransactionType.UNKNOWN)

        assertEquals(
            RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible"),
            lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)
        )
        assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
        assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
    }

    // F-AE-6 controls: PURCHASE (above) and WITHDRAWAL still link.
    @Test
    fun autoLinkAndManualLinkStillLinkAWithdrawalExpense() = runTest(timeout = 60.seconds) {
        val (autoOccurrenceId, autoExpenseId) =
            openOccurrenceAndMatchingExpense(type = TransactionType.WITHDRAWAL)

        assertTrue(lifecycle.linkExpenseToOccurrence(autoExpenseId))
        assertEquals("PAID", occurrenceDao.getById(autoOccurrenceId)!!.status)
        assertEquals(autoExpenseId, occurrenceDao.getById(autoOccurrenceId)!!.linkedExpenseId)
    }

    @Test
    fun manualLinkStillLinksAWithdrawalExpense() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense(type = TransactionType.WITHDRAWAL)

        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )
        assertEquals(expenseId, occurrenceDao.getById(occurrenceId)!!.linkedExpenseId)
    }

    // F-AE-8 (decision 46(a), R2): an EXISTING link on an UNKNOWN expense is kept and stays PAID.
    @Test
    fun reconcileKeepsAnExistingLinkWhenTheExpenseBecomesUnknown() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )
        retypeExpense(expenseId, TransactionType.UNKNOWN)

        assertEquals(
            RecurringExpenseReconcileResult.UpdatedLinkedSnapshot(expenseId, occurrenceId),
            lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)
        )

        val occurrence = occurrenceDao.getById(occurrenceId)!!
        assertEquals("PAID", occurrence.status)
        assertEquals(expenseId, occurrence.linkedExpenseId)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_UNLINKED").isEmpty())
    }

    @Test
    fun bulkReconcileKeepsAnExistingLinkOnAnUnknownExpenseAndNeverRevertsPaid() =
        runTest(timeout = 60.seconds) {
            val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
            )
            retypeExpense(expenseId, TransactionType.UNKNOWN)

            val result = lifecycle.reconcileAllLinkedExpensesAfterBulkUpdate("test_bulk")

            assertEquals(1, result.total)
            assertEquals(1, result.updatedSnapshots)
            assertEquals(0, result.unlinked)
            assertEquals(0, result.relinked)
            assertEquals(0, result.failed)
            val occurrence = occurrenceDao.getById(occurrenceId)!!
            assertEquals("PAID", occurrence.status)
            assertEquals(expenseId, occurrence.linkedExpenseId)
            assertTrue(eventDao.getEventsByType("OCCURRENCE_UNLINKED").isEmpty())
        }

    // Keep-variant control: TRANSFER is still refused for an existing link (only UNKNOWN is kept).
    @Test
    fun reconcileStillUnlinksAnExistingLinkWhenTheExpenseBecomesATransfer() = runTest(timeout = 60.seconds) {
        val (occurrenceId, expenseId) = openOccurrenceAndMatchingExpense()
        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, "TEST_SPECIFIC")
        )
        retypeExpense(expenseId, TransactionType.TRANSFER)

        val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

        assertTrue(result is RecurringExpenseReconcileResult.Unlinked)
        assertEquals("PLANNED", occurrenceDao.getById(occurrenceId)!!.status)
        assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
    }
}
