package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Tests for [CashFlowCalculator].
 *
 * ## Test gaps (not yet covered):
 * - Weekly rule multiple bills: verify that when multiple recurring bills fall due
 *   in the same week, the calculator correctly accumulates each one rather than
 *   de-duplicating or skipping patterns.
 * - PAID exclusion: test that expenses/bills already marked as paid are excluded
 *   from upcoming-obligation and cash-flow projections.
 * - Sorted by due date: confirm that the output list of daily cash-flow entries
 *   is sorted chronologically by due date, especially when expenses span multiple
 *   days with tie-breaking rules.
 * - Generation failure: test that when [RecurringExpenseEngine.getPatterns] throws,
 *   the calculator degrades gracefully (e.g. falls back to known expenses only).
 */
class CashFlowCalculatorTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var calculator: CashFlowCalculator

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        val recurringPatternsProvider = mockk<MergedRecurringPatternsProvider>(relaxed = true)
        val recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        val recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)

        calculator = CashFlowCalculator(
            expenseRepository = expenseRepository,
            recurringPatternsProvider = recurringPatternsProvider,
            timeProvider = timeProvider,
            recurringLifecycleCoordinator = recurringLifecycleCoordinator,
            recurringOccurrenceDao = recurringOccurrenceDao,
            analyticsCurrencyNormalizer = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            currencyConverter = mockk(relaxed = true)
        )
    }

    @Test
    fun `daily cashflow computes starting income expenses recurring and ending balances`() = runTest {
        val d1 = ms("2026-04-01")
        val d2 = ms("2026-04-02")
        val d3 = ms("2026-04-03")

        val tx = listOf(
            expense(d1, 200.0, TransactionType.DEPOSIT),
            expense(d1, 40.0, TransactionType.PURCHASE),
            expense(d2, 30.0, TransactionType.PURCHASE)
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns listOf(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 10.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = d2,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-04")), startingBalance = 100.0)

        assertEquals(3, result.size)

        // Day 1: 100 + 200 - 40 - 0 = 260
        assertApproxEquals(100.0, result[0].startingBalance)
        assertApproxEquals(260.0, result[0].endingBalance)
        assertEquals(CashFlowRiskLevel.LOW, result[0].riskLevel)

        // Day 2: 260 + 0 - 30 - 10 = 220
        assertApproxEquals(260.0, result[1].startingBalance)
        assertApproxEquals(220.0, result[1].endingBalance)
        assertEquals(1, result[1].predictedRecurring.size)
        assertEquals(CashFlowRiskLevel.LOW, result[1].riskLevel)
    }

    @Test
    fun `no income path handles expense only days and negative balances`() = runTest {
        val d1 = ms("2026-04-01")
        val tx = listOf(expense(d1, 80.0, TransactionType.PURCHASE))
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-02")), startingBalance = 50.0)

        assertEquals(1, result.size)
        assertApproxEquals(-30.0, result.first().endingBalance)
        assertEquals(CashFlowRiskLevel.HIGH, result.first().riskLevel)
    }

    @Test
    fun `no expenses path preserves balance and emits no recurring`() = runTest {
        val d1 = ms("2026-04-01")
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(Date(d1), Date(ms("2026-04-03")), startingBalance = 600.0)

        assertEquals(2, result.size)
        assertTrue(result.all { it.endingBalance == 600.0 })
        assertTrue(result.all { it.riskLevel == CashFlowRiskLevel.NONE })
    }

    @Test
    fun `upcoming bills returns patterns within next N days`() = runTest {
        val now = ms("2026-04-01")
        every { timeProvider.now() } returns now

        val all = listOf(expense(now, 1.0, TransactionType.PURCHASE))
        every { expenseRepository.getAllExpenses() } returns flowOf(all)

        val inRange = RecurringPattern(
            merchantName = "Gym",
            averageAmount = 20.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = ms("2026-04-05"),
            confidence = 0.9f,
            previousDates = emptyList()
        )
        val outOfRange = inRange.copy(merchantName = "Rent", nextExpectedDate = ms("2026-05-10"))

        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns listOf(inRange, outOfRange)

        val upcoming = calculator.getUpcomingBills(daysAhead = 10)
        assertEquals(1, upcoming.size)
        assertEquals("Gym", upcoming.first().merchantName)
    }

    /**
     * A.9 regression: CashFlowCalculator must process all rows even when
     * the expense count exceeds the old LIMIT 2000 default.
     *
     * The calculator calls [ExpenseRepository.getExpensesBetween] which,
     * after the Batch 1 repository-level fix, delegates to the uncapped
     * DAO query.  This test feeds 2500 expenses spanning two days and
     * verifies that the cumulative balance includes every row.
     */
    @Test
    fun `A9 regression - cashflow includes all rows beyond old 2000 limit`() = runTest {
        val d1 = ms("2026-04-01")
        val d2 = ms("2026-04-02")

        // 1500 purchases on day 1 (€1 each) + 1000 purchases on day 2 (€1 each) = 2500 total
        val expenses = mutableListOf<Expense>()
        for (i in 1..1500) {
            expenses.add(expense(d1, 1.0, TransactionType.PURCHASE))
        }
        for (i in 1..1000) {
            expenses.add(expense(d2, 1.0, TransactionType.PURCHASE))
        }

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns expenses
        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-03")), startingBalance = 3000.0
        )

        assertEquals(2, result.size)

        // Day 1: 3000 - 1500 = 1500
        assertApproxEquals(3000.0, result[0].startingBalance)
        assertApproxEquals(1500.0, result[0].endingBalance)

        // Day 2: 1500 - 1000 = 500
        assertApproxEquals(1500.0, result[1].startingBalance)
        assertApproxEquals(500.0, result[1].endingBalance)

        // Total expenses across both days must equal 2500
        val totalExpenseCount = result[0].expenses.size + result[1].expenses.size
        assertEquals(2500, totalExpenseCount)
    }

    // ============================================================================
    // A.10 Batch 5 – movement-aware classification tests
    // ============================================================================

    /**
     * A.10 Batch 5: TRANSFER rows with transferDirection == INCOMING must be
     * treated as inflow.  TRANSFER rows with transferDirection == OUTGOING must
     * be treated as outflow.  UNKNOWN rows and transfers without a direction
     * must NOT affect the cash-flow balance.
     */
    @Test
    fun `A10 Batch5 - incoming transfer is inflow and outgoing transfer is outflow`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 100.0, TransactionType.DEPOSIT),
            expense(d1, 25.0, TransactionType.PURCHASE),
            expense(d1, 500.0, TransactionType.TRANSFER, transferDir = TransferDirection.INCOMING),  // inflow
            expense(d1, 200.0, TransactionType.TRANSFER, transferDir = TransferDirection.OUTGOING),  // outflow
            expense(d1, 300.0, TransactionType.UNKNOWN)     // should be ignored
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = 0.0
        )

        assertEquals(1, result.size)
        // Balance should be 0 + 100 (deposit) + 500 (incoming transfer)
        //                      - 25 (purchase) - 200 (outgoing transfer) = 375
        // Unknown (300) must NOT be subtracted
        assertApproxEquals(375.0, result[0].endingBalance)
        // income list has the deposit + incoming transfer
        assertEquals(2, result[0].income.size)
        // expense list has purchase + outgoing transfer
        assertEquals(2, result[0].expenses.size)
    }

    /**
     * A.10 Batch 5: TRANSFER rows without a transferDirection (null) must
     * be excluded from both inflow and outflow, just like UNKNOWN.
     */
    @Test
    fun `A10 Batch5 - transfer without direction and unknown rows do not affect cash-flow balance`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 100.0, TransactionType.DEPOSIT),
            expense(d1, 25.0, TransactionType.PURCHASE),
            expense(d1, 500.0, TransactionType.TRANSFER),  // no direction -> ignored
            expense(d1, 300.0, TransactionType.UNKNOWN)     // should be ignored
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = 0.0
        )

        assertEquals(1, result.size)
        // Balance should be 0 + 100 (deposit) - 25 (purchase) = 75
        // Transfer (500, no direction) and Unknown (300) must NOT be counted
        assertApproxEquals(75.0, result[0].endingBalance)
        // income list has only the deposit
        assertEquals(1, result[0].income.size)
        // expense list has only the purchase
        assertEquals(1, result[0].expenses.size)
    }

    /**
     * A.10 Batch 5: WITHDRAWAL must still count as an outflow for cash-flow
     * balance purposes (it moves money out of the account), even though it
     * is not canonical "spending" for ratio purposes.
     */
    @Test
    fun `A10 Batch5 - withdrawal counted as outflow for balance`() = runTest {
        val d1 = ms("2026-04-01")

        val tx = listOf(
            expense(d1, 200.0, TransactionType.DEPOSIT),
            expense(d1, 50.0, TransactionType.WITHDRAWAL)
        )

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = 100.0
        )

        assertEquals(1, result.size)
        // 100 + 200 - 50 = 250
        assertApproxEquals(250.0, result[0].endingBalance)
    }

    /**
     * A.10 Batch 5: classification must be purely type-based, never sign-based.
     * Previously a negative amount would cause the row to be mis-classified as
     * income.  After the fix, a PURCHASE with a negative amount stays on the
     * expense side.
     */
    @Test
    fun `A10 Batch5 - negative amount purchase stays on expense side`() = runTest {
        val d1 = ms("2026-04-01")

        // A refund recorded as a negative-amount PURCHASE
        val tx = listOf(expense(d1, -10.0, TransactionType.PURCHASE))

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns tx
        every { expenseRepository.getAllExpenses() } returns flowOf(tx)
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()

        val result = calculator.calculateDailyCashFlow(
            Date(d1), Date(ms("2026-04-02")), startingBalance = 100.0
        )

        assertEquals(1, result.size)
        // income list must be empty (no sign-based leakage)
        assertEquals(0, result[0].income.size)
        // expense list has the purchase
        assertEquals(1, result[0].expenses.size)
        // Balance: 100 + 0 - (-10) = 110
        assertApproxEquals(110.0, result[0].endingBalance)
    }

    private fun expense(
        date: Long,
        amount: Double,
        type: TransactionType,
        transferDir: TransferDirection? = null
    ) = Expense(
        amount = amount,
        merchant = "T",
        transactionType = type,
        date = date,
        transferDirection = transferDir
    )

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}