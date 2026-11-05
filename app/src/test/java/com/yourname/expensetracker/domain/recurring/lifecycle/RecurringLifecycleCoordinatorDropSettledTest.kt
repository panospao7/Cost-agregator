package com.yourname.expensetracker.domain.recurring.lifecycle

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
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * FC-PAID-UNLINKED (fix-1.0.4 plan, S1): [RecurringLifecycleCoordinator.dropSettled] is read-only.
 * It drops open occurrences settled by an unlinked purchase, keeps everything else, fails towards
 * counting the bill when the read fails, rethrows cancellation, and never calls a write DAO.
 * The resolver is the real [OccurrenceConflictResolver]; only the data layer is mocked.
 */
class RecurringLifecycleCoordinatorDropSettledTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var manualRecurringExpenseDao: ManualRecurringExpenseDao
    private lateinit var plannedExpenseDao: PlannedExpenseDao
    private lateinit var lifecycleEventDao: RecurringLifecycleEventDao
    private lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    private lateinit var coordinator: RecurringLifecycleCoordinator

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun noonOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        occurrenceDao = mockk(relaxed = true)
        manualRecurringExpenseDao = mockk(relaxed = true)
        plannedExpenseDao = mockk(relaxed = true)
        lifecycleEventDao = mockk(relaxed = true)
        reminderDeliveryDao = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns noonOf(2026, 6, 20)
        val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        val reader = object : InstallmentEntryLinkReader {
            override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
            override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
            override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
            override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
        }
        val eligibility = RecurringInstallmentEligibility(dagger.Lazy { reader })
        sharedTimeProvider = timeProvider
        sharedRestoreMode = restoreMaintenanceMode
        sharedEligibility = eligibility
        coordinator = buildCoordinator(mockk<RecurringOccurrenceExpander>(relaxed = true))
    }

    private lateinit var sharedTimeProvider: TimeProvider
    private lateinit var sharedRestoreMode: RestoreMaintenanceMode
    private lateinit var sharedEligibility: RecurringInstallmentEligibility

    private fun buildCoordinator(expander: RecurringOccurrenceExpander): RecurringLifecycleCoordinator {
        val timeProvider = sharedTimeProvider
        val restoreMaintenanceMode = sharedRestoreMode
        val eligibility = sharedEligibility
        return RecurringLifecycleCoordinator(
            database = mockk<AppDatabase>(relaxed = true),
            expander = expander,
            resolver = OccurrenceConflictResolver(eligibility),
            materializer = mockk<RecurringOccurrenceMaterializer>(relaxed = true),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = manualRecurringExpenseDao,
            reminderDeliveryDao = reminderDeliveryDao,
            lifecycleEventDao = lifecycleEventDao,
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            plannedExpenseDao = plannedExpenseDao,
            transactionRunner = mockk<DomainTransactionRunner>(relaxed = true),
            eventWriter = mockk(relaxed = true),
            installmentEligibility = eligibility,
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
    }

    private fun occurrence(
        key: String,
        due: Long,
        status: String = "PLANNED",
        linked: Long? = null,
        amount: Double = 400.0,
        merchant: String = "Landlord"
    ) = RecurringOccurrence(
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = key,
        dueDate = due,
        status = status,
        linkedExpenseId = linked,
        expectedAmount = amount,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = merchant
    )

    private fun purchase(id: Long, date: Long, amount: Double = 400.0, merchant: String = "Landlord") = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        createdAt = date,
        source = "test",
    )

    private val due27 = startOfDay(2026, 6, 27)

    @Test
    fun dropSettledDropsTheSettledOccurrenceAndKeepsTheRest() = runTest(timeout = 60.seconds) {
        val rent = occurrence("rent", due27)
        val gym = occurrence("gym", startOfDay(2026, 6, 28), amount = 30.0, merchant = "Gym Club")
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()

        val result = coordinator.dropSettled(listOf(rent, gym))

        assertEquals(listOf("gym"), result.map { it.occurrenceKey })
    }

    @Test
    fun aPurchaseAlreadyLinkedToAnotherOccurrenceDoesNotSettle() = runTest(timeout = 60.seconds) {
        val rent = occurrence("rent", due27)
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns listOf(1L)

        val result = coordinator.dropSettled(listOf(rent))

        assertEquals(listOf("rent"), result.map { it.occurrenceKey })
    }

    @Test
    fun aReadFailureReturnsTheInputUnchangedSoBillsStayCounted() = runTest(timeout = 60.seconds) {
        val merged = listOf(occurrence("rent", due27))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } throws IllegalStateException("synthetic read failure")

        val result = coordinator.dropSettled(merged)

        assertSame(merged, result)
    }

    @Test
    fun aLinkedIdReadFailureAlsoKeepsBillsCounted() = runTest(timeout = 60.seconds) {
        val merged = listOf(occurrence("rent", due27))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } throws IllegalStateException("synthetic read failure")

        assertSame(merged, coordinator.dropSettled(merged))
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getExpensesBetween(any(), any()) } throws CancellationException("synthetic cancel")

        try {
            coordinator.dropSettled(listOf(occurrence("rent", due27)))
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("synthetic cancel", expected.message)
        }
    }

    @Test
    fun occurrencesThatAreNotOpenAreKeptAndNeverReadAgainstExpenses() = runTest(timeout = 60.seconds) {
        val paid = occurrence("paid", due27, status = "PAID", linked = 99L)
        val skipped = occurrence("skipped", due27, status = "SKIPPED")
        val missed = occurrence("missed", due27, status = "MISSED")
        val merged = listOf(paid, skipped, missed)
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))

        val result = coordinator.dropSettled(merged)

        assertSame(merged, result)
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any()) }
    }

    @Test
    fun anEmptyOpenSetReadsNothing() = runTest(timeout = 60.seconds) {
        assertTrue(coordinator.dropSettled(emptyList()).isEmpty())
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any()) }
    }

    @Test
    fun dropSettledCallsNoWriteDao() = runTest(timeout = 60.seconds) {
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()

        coordinator.dropSettled(listOf(occurrence("rent", due27)))

        coVerify(exactly = 1) { expenseDao.getExpensesBetween(any(), any()) }
        coVerify(exactly = 1) { occurrenceDao.getLinkedExpenseIds(any()) }
        // PIN CHANGED: dropSettled now also reads the active rules and the materialized rows of the
        // month, so those two reads are verified here; confirmVerified still proves no other call
        // (in particular no write) happened.
        coVerify(exactly = 1) { manualRecurringExpenseDao.getAllActive() }
        coVerify(exactly = 1) { occurrenceDao.getByDateRange(any(), any()) }
        confirmVerified(
            expenseDao,
            occurrenceDao,
            manualRecurringExpenseDao,
            plannedExpenseDao,
            lifecycleEventDao,
            reminderDeliveryDao
        )
    }

    // ---- Real projection path (the expander is real; rules come from getAllActive) ----

    private fun rule(id: Long, nextDate: Long) = ManualRecurringExpense(
        id = id,
        merchant = "Landlord",
        amount = 400.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = nextDate,
        createdAt = nextDate
    )

    /** Coordinator whose projection runs the real expander over the given active rules. */
    private fun realProjection(vararg rules: ManualRecurringExpense): RecurringLifecycleCoordinator {
        coEvery { manualRecurringExpenseDao.getAllActive() } returns rules.toList()
        for (r in rules) coEvery { manualRecurringExpenseDao.getById(r.id) } returns r
        return buildCoordinator(RecurringOccurrenceExpander())
    }

    /** The caller's open occurrence for rule [ruleId], keyed exactly as the real expander keys it. */
    private fun open(ruleId: Long, due: Long) = occurrence(
        key = "RECURRING_RULE|$ruleId|$due|MONTHLY",
        due = due
    ).copy(sourceId = ruleId)

    @Test
    fun anEarlierRuleTakesThePaymentSoTheLaterSameMerchantBillStaysCounted() = runTest(timeout = 60.seconds) {
        val due20 = startOfDay(2026, 6, 20)
        val due25 = startOfDay(2026, 6, 25)
        val real = realProjection(rule(1L, due20), rule(2L, due25))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 18)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        val callerList = listOf(open(2L, due25))

        val result = real.dropSettled(callerList)

        assertEquals(listOf(open(2L, due25).occurrenceKey), result.map { it.occurrenceKey })
    }

    @Test
    fun aProjectedPaidLinkCountsAsLinkedSoTheLaterBillStaysCounted() = runTest(timeout = 60.seconds) {
        val due25 = startOfDay(2026, 6, 25)
        val due27 = startOfDay(2026, 6, 27)
        val real = realProjection(rule(1L, due25), rule(2L, due27))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()

        val projectedA = real.projectOccurrences(1L, startOfDay(2026, 6, 1), startOfDay(2026, 7, 1))
        val result = real.dropSettled(listOf(open(2L, due27)))

        assertEquals(listOf("PAID"), projectedA.map { it.status })
        assertEquals(listOf(1L), projectedA.map { it.linkedExpenseId })
        assertEquals(listOf(open(2L, due27).occurrenceKey), result.map { it.occurrenceKey })
    }

    @Test
    fun twoPaymentsSettleTwoBillsAcrossTheWholeMonthSoTheCallersBillIsDropped() = runTest(timeout = 60.seconds) {
        val due20 = startOfDay(2026, 6, 20)
        val due25 = startOfDay(2026, 6, 25)
        val real = realProjection(rule(1L, due20), rule(2L, due25))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, noonOf(2026, 6, 18)),
            purchase(2L, noonOf(2026, 6, 24))
        )
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()

        val result = real.dropSettled(listOf(open(2L, due25)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun aSingleRuleBillPaidTwoDaysEarlyIsDroppedOnTheRealPath() = runTest(timeout = 60.seconds) {
        val real = realProjection(rule(1L, due27))
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()

        val result = real.dropSettled(listOf(open(1L, due27)))

        assertTrue(result.isEmpty())
    }

    @Test
    fun anActiveRulesReadFailureReturnsTheInputUnchanged() = runTest(timeout = 60.seconds) {
        coEvery { manualRecurringExpenseDao.getAllActive() } throws IllegalStateException("synthetic read failure")
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 25)))
        val callerList = listOf(open(1L, due27))

        val result = buildCoordinator(RecurringOccurrenceExpander()).dropSettled(callerList)

        assertSame(callerList, result)
    }

    @Test
    fun cancellationFromTheActiveRulesReadIsRethrown() = runTest(timeout = 60.seconds) {
        coEvery { manualRecurringExpenseDao.getAllActive() } throws CancellationException("synthetic cancel")

        try {
            buildCoordinator(RecurringOccurrenceExpander()).dropSettled(listOf(open(1L, due27)))
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("synthetic cancel", expected.message)
        }
    }

    // ---- SKIPPED ("already recorded", no link) rows compete for a payment but are never dropped ----

    @Test
    fun anUnlinkedSkippedEarlierBillTakesThePaymentSoTheLaterPlannedBillStays() = runTest(timeout = 60.seconds) {
        val due20 = startOfDay(2026, 6, 20)
        val due25 = startOfDay(2026, 6, 25)
        val real = realProjection(rule(1L, due20), rule(2L, due25))
        val skippedA = open(1L, due20).copy(status = "SKIPPED", linkedExpenseId = null)
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(skippedA)
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 18)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        val callerList = listOf(open(2L, due25))

        val result = real.dropSettled(callerList)

        assertEquals(listOf(open(2L, due25).occurrenceKey), result.map { it.occurrenceKey })
    }

    @Test
    fun aSkippedRowInTheCallersListIsNeverDroppedEvenWhenAPurchaseWouldMatchIt() = runTest(timeout = 60.seconds) {
        val due20 = startOfDay(2026, 6, 20)
        val due25 = startOfDay(2026, 6, 25)
        val real = realProjection(rule(1L, due20), rule(2L, due25))
        val skippedA = open(1L, due20).copy(status = "SKIPPED", linkedExpenseId = null)
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns listOf(skippedA)
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 18)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        val callerList = listOf(skippedA, open(2L, due25))

        val result = real.dropSettled(callerList)

        assertEquals(
            listOf(skippedA.occurrenceKey, open(2L, due25).occurrenceKey),
            result.map { it.occurrenceKey }
        )
    }

    @Test
    fun controlAPlannedEarlierBillTakesTheSamePaymentAndTheLaterBillStays() = runTest(timeout = 60.seconds) {
        val due20 = startOfDay(2026, 6, 20)
        val due25 = startOfDay(2026, 6, 25)
        val real = realProjection(rule(1L, due20), rule(2L, due25))
        coEvery { occurrenceDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(purchase(1L, noonOf(2026, 6, 18)))
        coEvery { occurrenceDao.getLinkedExpenseIds(any()) } returns emptyList()
        val callerList = listOf(open(1L, due20), open(2L, due25))

        val result = real.dropSettled(callerList)

        assertEquals(listOf(open(2L, due25).occurrenceKey), result.map { it.occurrenceKey })
    }
}
