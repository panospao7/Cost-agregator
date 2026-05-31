package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Batch 6 — Transaction-type filtering tests for [FinancialHealthCalculator].
 *
 * Verifies that spend-control metrics (totals, volatility, and the resulting
 * spendingControl score) only count canonical spending rows (PURCHASE) and
 * ignore DEPOSIT, TRANSFER, WITHDRAWAL, and UNKNOWN transaction types.
 *
 * Key properties under test:
 * - Non-spend rows do not inflate spend totals in any period (day/week/month)
 * - Non-spend rows do not affect volatility calculations
 * - Adding non-spend rows does not change the spendingControl score component
 * - Score weights, thresholds, streak math, and time-boundary behavior are preserved
 */
class FinancialHealthCalculatorTransactionTypeTest {

    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()

    @Before
    fun setUpCurrencyMocks() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun expense(
        id: Long,
        date: Long,
        amount: Double,
        type: TransactionType = TransactionType.PURCHASE
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "TestMerchant",
        transactionType = type,
        date = date
    )

    private fun onTrackBudget(amount: Double = 1000.0): BudgetStatusSnapshot = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = amount * 0.5,
        remainingAmount = amount * 0.5,
        percentUsed = 50.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = Long.MIN_VALUE,
        periodEnd = Long.MAX_VALUE
    )

    /** Compute health scores with standard defaults so tests stay concise. */
    private fun compute(
        calculator: FinancialHealthCalculator,
        expenses: List<Expense>,
        budgetAmount: Double = 1000.0
    ): HealthScoreResult = calculator.calculateHealthScores(
        expenses = expenses,
        budgetStatuses = listOf(onTrackBudget(budgetAmount)),
        pendingReviews = 0,
        todayStreak = 0,
        weekStreak = 0,
        monthStreak = 0,
        noSpendStreak = 0
    )

    // ========================================================================
    // Today — non-spend rows must not affect daily spending control
    // ========================================================================

    @Test
    fun `DEPOSIT today does not affect today spending control score`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchaseOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 10.0, TransactionType.PURCHASE)
        )
        val withDeposit = purchaseOnly + expense(
            2L, toEpochMs(2026, 4, 15, 10, 0), 5000.0, TransactionType.DEPOSIT
        )

        val scorePurchaseOnly = compute(calculator, purchaseOnly)
        val scoreWithDeposit = compute(calculator, withDeposit)

        assertEquals(
            "DEPOSIT must not change today spendingControl",
            scorePurchaseOnly.today.breakdown.spendingControl,
            scoreWithDeposit.today.breakdown.spendingControl
        )
    }

    @Test
    fun `TRANSFER today does not affect today spending control score`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchaseOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 10.0, TransactionType.PURCHASE)
        )
        val withTransfer = purchaseOnly + expense(
            2L, toEpochMs(2026, 4, 15, 10, 0), 3000.0, TransactionType.TRANSFER
        )

        val scorePurchaseOnly = compute(calculator, purchaseOnly)
        val scoreWithTransfer = compute(calculator, withTransfer)

        assertEquals(
            "TRANSFER must not change today spendingControl",
            scorePurchaseOnly.today.breakdown.spendingControl,
            scoreWithTransfer.today.breakdown.spendingControl
        )
    }

    @Test
    fun `WITHDRAWAL today does not affect today spending control score`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchaseOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 10.0, TransactionType.PURCHASE)
        )
        val withWithdrawal = purchaseOnly + expense(
            2L, toEpochMs(2026, 4, 15, 10, 0), 500.0, TransactionType.WITHDRAWAL
        )

        val scorePurchaseOnly = compute(calculator, purchaseOnly)
        val scoreWithWithdrawal = compute(calculator, withWithdrawal)

        assertEquals(
            "WITHDRAWAL must not change today spendingControl",
            scorePurchaseOnly.today.breakdown.spendingControl,
            scoreWithWithdrawal.today.breakdown.spendingControl
        )
    }

    // ========================================================================
    // Week — non-spend rows must not affect weekly spending control or volatility
    // ========================================================================

    @Test
    fun `non-spend rows this week do not affect weekly spending control`() {
        // Wednesday April 15, 2026
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchases = listOf(
            expense(1L, toEpochMs(2026, 4, 13, 10, 0), 50.0, TransactionType.PURCHASE),
            expense(2L, toEpochMs(2026, 4, 14, 11, 0), 30.0, TransactionType.PURCHASE)
        )
        val mixed = purchases + listOf(
            expense(3L, toEpochMs(2026, 4, 13, 12, 0), 2000.0, TransactionType.DEPOSIT),
            expense(4L, toEpochMs(2026, 4, 14, 13, 0), 1500.0, TransactionType.TRANSFER),
            expense(5L, toEpochMs(2026, 4, 15, 8, 0), 300.0, TransactionType.WITHDRAWAL)
        )

        val scorePurchases = compute(calculator, purchases)
        val scoreMixed = compute(calculator, mixed)

        assertEquals(
            "Week spendingControl must be identical with or without non-spend rows",
            scorePurchases.week.breakdown.spendingControl,
            scoreMixed.week.breakdown.spendingControl
        )
    }

    // ========================================================================
    // Month — non-spend rows must not affect monthly spending control or volatility
    // ========================================================================

    @Test
    fun `non-spend rows this month do not affect monthly spending control`() {
        val now = toEpochMs(2026, 4, 20, 14, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchases = listOf(
            expense(1L, toEpochMs(2026, 4, 5, 10, 0), 100.0, TransactionType.PURCHASE),
            expense(2L, toEpochMs(2026, 4, 10, 11, 0), 150.0, TransactionType.PURCHASE),
            expense(3L, toEpochMs(2026, 4, 15, 12, 0), 200.0, TransactionType.PURCHASE)
        )
        val mixed = purchases + listOf(
            expense(4L, toEpochMs(2026, 4, 1, 9, 0), 5000.0, TransactionType.DEPOSIT),
            expense(5L, toEpochMs(2026, 4, 3, 14, 0), 3000.0, TransactionType.TRANSFER),
            expense(6L, toEpochMs(2026, 4, 7, 16, 0), 800.0, TransactionType.WITHDRAWAL)
        )

        val scorePurchases = compute(calculator, purchases)
        val scoreMixed = compute(calculator, mixed)

        assertEquals(
            "Month spendingControl must be identical with or without non-spend rows",
            scorePurchases.month.breakdown.spendingControl,
            scoreMixed.month.breakdown.spendingControl
        )
    }

    // ========================================================================
    // All-period composite check
    // ========================================================================

    @Test
    fun `mixed non-spend types across all periods do not change composite score`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchases = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 20.0, TransactionType.PURCHASE),  // today
            expense(2L, toEpochMs(2026, 4, 13, 10, 0), 40.0, TransactionType.PURCHASE),  // this week
            expense(3L, toEpochMs(2026, 4, 5, 12, 0), 80.0, TransactionType.PURCHASE)    // this month
        )
        val mixed = purchases + listOf(
            expense(4L, toEpochMs(2026, 4, 15, 10, 0), 9999.0, TransactionType.DEPOSIT),
            expense(5L, toEpochMs(2026, 4, 13, 11, 0), 7777.0, TransactionType.TRANSFER),
            expense(6L, toEpochMs(2026, 4, 5, 13, 0), 5555.0, TransactionType.WITHDRAWAL)
        )

        val scorePurchases = compute(calculator, purchases)
        val scoreMixed = compute(calculator, mixed)

        assertEquals(
            "Composite score must be identical with or without non-spend rows",
            scorePurchases.composite,
            scoreMixed.composite
        )
        assertEquals(
            "Today score must be identical",
            scorePurchases.today.score,
            scoreMixed.today.score
        )
        assertEquals(
            "Week score must be identical",
            scorePurchases.week.score,
            scoreMixed.week.score
        )
        assertEquals(
            "Month score must be identical",
            scorePurchases.month.score,
            scoreMixed.month.score
        )
    }

    // ========================================================================
    // Only-non-spend rows => zero-spend semantics
    // ========================================================================

    @Test
    fun `only non-spend rows produces same result as empty expense list`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val nonSpendOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 1000.0, TransactionType.DEPOSIT),
            expense(2L, toEpochMs(2026, 4, 13, 10, 0), 500.0, TransactionType.TRANSFER),
            expense(3L, toEpochMs(2026, 4, 5, 12, 0), 300.0, TransactionType.WITHDRAWAL)
        )

        val scoreNonSpend = compute(calculator, nonSpendOnly)
        val scoreEmpty = compute(calculator, emptyList())

        assertEquals(
            "Non-spend-only should produce same today spendingControl as empty",
            scoreEmpty.today.breakdown.spendingControl,
            scoreNonSpend.today.breakdown.spendingControl
        )
        assertEquals(
            "Non-spend-only should produce same week spendingControl as empty",
            scoreEmpty.week.breakdown.spendingControl,
            scoreNonSpend.week.breakdown.spendingControl
        )
        assertEquals(
            "Non-spend-only should produce same month spendingControl as empty",
            scoreEmpty.month.breakdown.spendingControl,
            scoreNonSpend.month.breakdown.spendingControl
        )
    }

    // ========================================================================
    // UNKNOWN type is also not spending
    // ========================================================================

    @Test
    fun `UNKNOWN transaction type does not affect spending control`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        val purchaseOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 10.0, TransactionType.PURCHASE)
        )
        val withUnknown = purchaseOnly + expense(
            2L, toEpochMs(2026, 4, 15, 10, 0), 999.0, TransactionType.UNKNOWN
        )

        val scorePurchaseOnly = compute(calculator, purchaseOnly)
        val scoreWithUnknown = compute(calculator, withUnknown)

        assertEquals(
            "UNKNOWN must not change today spendingControl",
            scorePurchaseOnly.today.breakdown.spendingControl,
            scoreWithUnknown.today.breakdown.spendingControl
        )
    }

    // ========================================================================
    // Preservation: score weights, thresholds, and streak math are unchanged
    // ========================================================================

    @Test
    fun `score weights thresholds and streaks remain unchanged after filtering`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now), mockk(), currencySettingsRepository)

        // Use only PURCHASE rows so filtering doesn't change anything —
        // this verifies the core math is untouched by the refactoring.
        val expenses = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 25.0, TransactionType.PURCHASE),
            expense(2L, toEpochMs(2026, 4, 13, 12, 0), 50.0, TransactionType.PURCHASE),
            expense(3L, toEpochMs(2026, 4, 5, 15, 0), 75.0, TransactionType.PURCHASE)
        )

        val result = calculator.calculateHealthScores(
            expenses = expenses,
            budgetStatuses = listOf(onTrackBudget()),
            pendingReviews = 2,
            todayStreak = 5,
            weekStreak = 8,
            monthStreak = 10,
            noSpendStreak = 0
        )

        // Verify all scores are in valid range
        assertTrue("Today score in [0,100]", result.today.score in 0..100)
        assertTrue("Week score in [0,100]", result.week.score in 0..100)
        assertTrue("Month score in [0,100]", result.month.score in 0..100)
        assertTrue("Composite score in [0,100]", result.composite in 0..100)

        // Verify breakdown components are non-negative
        assertTrue("Budget health >= 0", result.today.breakdown.budgetHealth >= 0)
        assertTrue("Spending control >= 0", result.today.breakdown.spendingControl >= 0)
        assertTrue("Cleanliness >= 0", result.today.breakdown.cleanliness >= 0)
        assertTrue("Bonus points >= 0", result.today.breakdown.bonusPoints >= 0)

        // weekStreak = 8, which triggers streak bonus of 3
        // allBudgetsOnTrack = true, which adds 2
        // noSpendStreak = 0, no bonus
        // Total bonus = 5
        assertEquals(
            "Week bonus should reflect streak=8 + onTrack",
            5,
            result.week.breakdown.bonusPoints
        )
    }
}
