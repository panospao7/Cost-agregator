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
 *
 * The relative (baseline vs. with-non-spend) comparisons run against two budget
 * configurations: an effectively unbounded ON_TRACK budget (the original scenario)
 * and no budget at all (default targets 50 / 350 / 1500), where an inflated spend
 * total would visibly drop spendingControl from 25 to 5.
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

    private fun calculatorAt(now: Long): FinancialHealthCalculator =
        FinancialHealthCalculator(FakeTimeProvider(now), AnalyticsCurrencyNormalizer(mockk()), currencySettingsRepository)

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

    /** Label to budget list pairs every relative comparison is evaluated against. */
    private fun budgetConfigs(): List<Pair<String, List<BudgetStatusSnapshot>>> = listOf(
        "on-track budget" to listOf(onTrackBudget()),
        "no budget" to emptyList()
    )

    /** Compute health scores with standard defaults so tests stay concise. */
    private fun compute(
        calculator: FinancialHealthCalculator,
        expenses: List<Expense>,
        budgets: List<BudgetStatusSnapshot> = listOf(onTrackBudget())
    ): HealthScoreResult = calculator.calculateHealthScores(
        expenses = expenses,
        budgetStatuses = budgets,
        pendingReviews = 0,
        todayStreak = 0,
        weekStreak = 0,
        monthStreak = 0,
        noSpendStreak = 0
    )

    // ========================================================================
    // Today — each non-spend type must not affect daily spending control
    // ========================================================================

    private data class NonSpendRow(
        val label: String,
        val type: TransactionType,
        val amount: Double
    )

    @Test
    fun `single non-spend type today does not affect today spending control score`() {
        val rows = listOf(
            NonSpendRow("DEPOSIT", TransactionType.DEPOSIT, 5000.0),
            NonSpendRow("TRANSFER", TransactionType.TRANSFER, 3000.0),
            NonSpendRow("WITHDRAWAL", TransactionType.WITHDRAWAL, 500.0),
            NonSpendRow("UNKNOWN", TransactionType.UNKNOWN, 999.0)
        )
        assertEquals(4, rows.size)

        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = calculatorAt(now)
        val purchaseOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 10.0, TransactionType.PURCHASE)
        )

        for (row in rows) {
            val withNonSpend = purchaseOnly + expense(
                2L, toEpochMs(2026, 4, 15, 10, 0), row.amount, row.type
            )

            for ((budgetLabel, budgets) in budgetConfigs()) {
                val scorePurchaseOnly = compute(calculator, purchaseOnly, budgets)
                val scoreWithNonSpend = compute(calculator, withNonSpend, budgets)

                // Absolute pin: 10.0 spent today is far below any daily target, so control is the maximum 25.
                assertEquals(
                    "[${row.label} / $budgetLabel] purchase-only baseline today spendingControl",
                    25,
                    scorePurchaseOnly.today.breakdown.spendingControl
                )
                assertEquals(
                    "[${row.label} / $budgetLabel] ${row.label} must not change today spendingControl",
                    scorePurchaseOnly.today.breakdown.spendingControl,
                    scoreWithNonSpend.today.breakdown.spendingControl
                )
            }
        }
    }

    // ========================================================================
    // Week — non-spend rows must not affect weekly spending control or volatility
    // ========================================================================

    @Test
    fun `non-spend rows this week do not affect weekly spending control`() {
        // Wednesday April 15, 2026
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = calculatorAt(now)

        val purchases = listOf(
            expense(1L, toEpochMs(2026, 4, 13, 10, 0), 50.0, TransactionType.PURCHASE),
            expense(2L, toEpochMs(2026, 4, 14, 11, 0), 30.0, TransactionType.PURCHASE)
        )
        val mixed = purchases + listOf(
            expense(3L, toEpochMs(2026, 4, 13, 12, 0), 2000.0, TransactionType.DEPOSIT),
            expense(4L, toEpochMs(2026, 4, 14, 13, 0), 1500.0, TransactionType.TRANSFER),
            expense(5L, toEpochMs(2026, 4, 15, 8, 0), 300.0, TransactionType.WITHDRAWAL)
        )

        for ((budgetLabel, budgets) in budgetConfigs()) {
            val scorePurchases = compute(calculator, purchases, budgets)
            val scoreMixed = compute(calculator, mixed, budgets)

            // 80.0 spent with daily totals 50/30 (volatility 25 percent, below the penalty threshold) gives 25.
            assertEquals(
                "[$budgetLabel] purchase-only baseline week spendingControl",
                25,
                scorePurchases.week.breakdown.spendingControl
            )
            assertEquals(
                "[$budgetLabel] Week spendingControl must be identical with or without non-spend rows",
                scorePurchases.week.breakdown.spendingControl,
                scoreMixed.week.breakdown.spendingControl
            )
        }
    }

    // ========================================================================
    // Month — non-spend rows must not affect monthly spending control or volatility
    // ========================================================================

    @Test
    fun `non-spend rows this month do not affect monthly spending control`() {
        val now = toEpochMs(2026, 4, 20, 14, 0)
        val calculator = calculatorAt(now)

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

        for ((budgetLabel, budgets) in budgetConfigs()) {
            val scorePurchases = compute(calculator, purchases, budgets)
            val scoreMixed = compute(calculator, mixed, budgets)

            // 450.0 spent with daily totals 100/150/200 (volatility about 27 percent) gives 25.
            assertEquals(
                "[$budgetLabel] purchase-only baseline month spendingControl",
                25,
                scorePurchases.month.breakdown.spendingControl
            )
            assertEquals(
                "[$budgetLabel] Month spendingControl must be identical with or without non-spend rows",
                scorePurchases.month.breakdown.spendingControl,
                scoreMixed.month.breakdown.spendingControl
            )
        }
    }

    // ========================================================================
    // All-period composite check
    // ========================================================================

    @Test
    fun `mixed non-spend types across all periods do not change composite score`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = calculatorAt(now)

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

        for ((budgetLabel, budgets) in budgetConfigs()) {
            val scorePurchases = compute(calculator, purchases, budgets)
            val scoreMixed = compute(calculator, mixed, budgets)

            assertEquals(
                "[$budgetLabel] Composite score must be identical with or without non-spend rows",
                scorePurchases.composite,
                scoreMixed.composite
            )
            assertEquals(
                "[$budgetLabel] Today score must be identical",
                scorePurchases.today.score,
                scoreMixed.today.score
            )
            assertEquals(
                "[$budgetLabel] Week score must be identical",
                scorePurchases.week.score,
                scoreMixed.week.score
            )
            assertEquals(
                "[$budgetLabel] Month score must be identical",
                scorePurchases.month.score,
                scoreMixed.month.score
            )
        }
    }

    // ========================================================================
    // Only-non-spend rows => zero-spend semantics
    // ========================================================================

    @Test
    fun `only non-spend rows produces same result as empty expense list`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = calculatorAt(now)

        val nonSpendOnly = listOf(
            expense(1L, toEpochMs(2026, 4, 15, 9, 0), 1000.0, TransactionType.DEPOSIT),
            expense(2L, toEpochMs(2026, 4, 13, 10, 0), 500.0, TransactionType.TRANSFER),
            expense(3L, toEpochMs(2026, 4, 5, 12, 0), 300.0, TransactionType.WITHDRAWAL)
        )

        for ((budgetLabel, budgets) in budgetConfigs()) {
            val scoreNonSpend = compute(calculator, nonSpendOnly, budgets)
            val scoreEmpty = compute(calculator, emptyList(), budgets)

            // Zero spend is the best case in every period: spendingControl 25.
            assertEquals("[$budgetLabel] empty today spendingControl", 25, scoreEmpty.today.breakdown.spendingControl)
            assertEquals("[$budgetLabel] empty week spendingControl", 25, scoreEmpty.week.breakdown.spendingControl)
            assertEquals("[$budgetLabel] empty month spendingControl", 25, scoreEmpty.month.breakdown.spendingControl)

            assertEquals(
                "[$budgetLabel] Non-spend-only should produce same today spendingControl as empty",
                scoreEmpty.today.breakdown.spendingControl,
                scoreNonSpend.today.breakdown.spendingControl
            )
            assertEquals(
                "[$budgetLabel] Non-spend-only should produce same week spendingControl as empty",
                scoreEmpty.week.breakdown.spendingControl,
                scoreNonSpend.week.breakdown.spendingControl
            )
            assertEquals(
                "[$budgetLabel] Non-spend-only should produce same month spendingControl as empty",
                scoreEmpty.month.breakdown.spendingControl,
                scoreNonSpend.month.breakdown.spendingControl
            )
        }
    }

    // ========================================================================
    // Preservation: score weights, thresholds, and streak math are unchanged
    // ========================================================================

    @Test
    fun `score weights thresholds and streaks remain unchanged after filtering`() {
        val now = toEpochMs(2026, 4, 15, 14, 0)
        val calculator = calculatorAt(now)

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

        // Today: budgetHealth 25 (all on track), spendingControl 25, cleanliness 7 (2 pending reviews),
        // bonus 2 (on-track only; todayStreak 5 < 7). Raw 59 of 70 -> 84.
        assertEquals("Today budgetHealth", 25, result.today.breakdown.budgetHealth)
        assertEquals("Today spendingControl", 25, result.today.breakdown.spendingControl)
        assertEquals("Today cleanliness", 7, result.today.breakdown.cleanliness)
        assertEquals("Today bonusPoints", 2, result.today.breakdown.bonusPoints)
        assertEquals("Today score", 84, result.today.score)

        // weekStreak = 8, which triggers streak bonus of 3
        // allBudgetsOnTrack = true, which adds 2
        // noSpendStreak = 0, no bonus
        // Total bonus = 5
        assertEquals(
            "Week bonus should reflect streak=8 + onTrack",
            5,
            result.week.breakdown.bonusPoints
        )
        // Week: 25 + 25 + 7 + 5 = 62 of 70 -> 89
        assertEquals("Week spendingControl", 25, result.week.breakdown.spendingControl)
        assertEquals("Week score", 89, result.week.score)

        // Month: monthStreak 10 gives the same bonus of 5; 62 of 70 -> 89
        assertEquals("Month bonusPoints", 5, result.month.breakdown.bonusPoints)
        assertEquals("Month spendingControl", 25, result.month.breakdown.spendingControl)
        assertEquals("Month score", 89, result.month.score)

        // Composite is 0.2 * 84 + 0.3 * 89 + 0.5 * 89 = 88.0; in IEEE doubles the partial sums
        // (16.8 + 26.7 = 43.5, then + 44.5) are exact, so the truncated weighted sum is exactly 88.
        assertEquals("Composite", 88, result.composite)
    }
}
