package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FinancialHealthCalculatorBudgetNormalizationTest {

    @Test
    fun `daily spending target normalizes monthly budget by actual overlap`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now))
        val (todayStart, todayEnd) = TimePeriodUtils.getDayRange(now)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val monthEnd = TimePeriodUtils.getEndOfMonth(now)

        val result = calculator.calculateHealthScores(
            expenses = listOf(expense(1L, now, 40.0)),
            budgetStatuses = listOf(
                budgetSnapshot(
                    budgetAmount = 300.0,
                    periodStart = monthStart,
                    periodEnd = monthEnd
                )
            ),
            pendingReviews = 0,
            todayStreak = 0,
            weekStreak = 0,
            monthStreak = 0,
            noSpendStreak = 0
        )

        val expectedDailyBudget = 300.0 * (todayEnd - todayStart).toDouble() / (monthEnd - monthStart).toDouble()
        val expectedRatio = 40.0 / expectedDailyBudget

        assertEquals(scoreForRatio(expectedRatio), result.today.breakdown.spendingControl)
    }

    @Test
    fun `weekly spending target does not double count overall and category budgets`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now))
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val monthEnd = TimePeriodUtils.getEndOfMonth(now)

        val result = calculator.calculateHealthScores(
            expenses = listOf(
                expense(1L, toEpochMs(2026, 4, 14, 9, 0), 100.0),
                expense(2L, toEpochMs(2026, 4, 15, 9, 0), 80.0)
            ),
            budgetStatuses = listOf(
                budgetSnapshot(
                    budgetAmount = 400.0,
                    budgetCategoryId = null,
                    periodStart = monthStart,
                    periodEnd = monthEnd
                ),
                budgetSnapshot(
                    budgetAmount = 200.0,
                    budgetCategoryId = 99L,
                    periodStart = monthStart,
                    periodEnd = monthEnd
                )
            ),
            pendingReviews = 0,
            todayStreak = 0,
            weekStreak = 0,
            monthStreak = 0,
            noSpendStreak = 0
        )

        val expectedWeeklyBudget = 400.0 * (weekEnd - weekStart).toDouble() / (monthEnd - monthStart).toDouble()
        val expectedRatio = 180.0 / expectedWeeklyBudget

        assertEquals(scoreForRatio(expectedRatio, volatilityPenalty = 0), result.week.breakdown.spendingControl)
    }

    @Test
    fun `monthly spending target sums overlapping mixed budget windows`() {
        val now = toEpochMs(2026, 4, 15, 12, 0)
        val calculator = FinancialHealthCalculator(FakeTimeProvider(now))
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)

        val result = calculator.calculateHealthScores(
            expenses = listOf(
                expense(1L, toEpochMs(2026, 4, 1, 10, 0), 350.0),
                expense(2L, toEpochMs(2026, 4, 8, 10, 0), 350.0),
                expense(3L, toEpochMs(2026, 4, 15, 10, 0), 350.0)
            ),
            budgetStatuses = listOf(
                budgetSnapshot(
                    budgetAmount = 300.0,
                    periodStart = TimePeriodUtils.getDayRange(now).first,
                    periodEnd = TimePeriodUtils.getDayRange(now).second
                ),
                budgetSnapshot(
                    budgetAmount = 700.0,
                    periodStart = weekStart,
                    periodEnd = weekEnd
                ),
                budgetSnapshot(
                    budgetAmount = 1_200.0,
                    periodStart = monthStart,
                    periodEnd = monthEnd
                )
            ),
            pendingReviews = 0,
            todayStreak = 0,
            weekStreak = 0,
            monthStreak = 0,
            noSpendStreak = 0
        )

        val expectedMonthlyBudget = 300.0 + 700.0 + 1_200.0
        val expectedRatio = 1_050.0 / expectedMonthlyBudget

        assertEquals(monthScoreForRatio(expectedRatio, volatilityPenalty = 0), result.month.breakdown.spendingControl)
    }

    private fun budgetSnapshot(
        budgetAmount: Double,
        budgetCategoryId: Long? = null,
        periodStart: Long,
        periodEnd: Long
    ) = BudgetStatusSnapshot(
        budgetCategoryId = budgetCategoryId,
        budgetAmount = budgetAmount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = budgetAmount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = periodStart,
        periodEnd = periodEnd
    )

    private fun expense(id: Long, date: Long, amount: Double): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Merchant$id",
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    private fun scoreForRatio(ratio: Double, volatilityPenalty: Int = 0): Int {
        val base = when {
            ratio <= 0.8 -> 25
            ratio <= 1.0 -> 20
            ratio <= 1.2 -> 15
            ratio <= 1.5 -> 10
            else -> 5
        }
        return (base - volatilityPenalty).coerceAtLeast(5)
    }

    private fun monthScoreForRatio(ratio: Double, volatilityPenalty: Int = 0): Int {
        val base = when {
            ratio <= 0.7 -> 25
            ratio <= 0.9 -> 25
            ratio <= 1.0 -> 20
            ratio <= 1.1 -> 15
            ratio <= 1.3 -> 10
            else -> 5
        }
        return (base - volatilityPenalty).coerceAtLeast(5)
    }

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
