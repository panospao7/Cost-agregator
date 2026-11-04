package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 (weather): the forecast reads an overall budget limit only when the budget period is
 * month-scale. A yearly (or weekly) limit takes the existing no-budget path, a month-length
 * limit is unchanged, and a degenerate period (end <= start) keeps the legacy behaviour.
 *
 * "Now" is fixed to 2024-01-15 12:00 UTC (31-day January). Spend is 1000, no recurring
 * patterns, planned items or goals, so the discretionary pool is exactly limit - 1000.
 */
class SynthesisEngineBudgetPeriodTest {

    private val nowMs = 1_705_320_000_000L
    private val dayMs = 86_400_000L
    private val periodStart = 1_704_067_200_000L // 2024-01-01 00:00 UTC

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = nowMs
    }
    private val engine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))

    private val pace = SpendingPace(
        currentMonthSpent = 1000.0,
        daysElapsed = 15,
        daysInMonth = 31,
        projectedTotal = 2000.0,
        previousMonthTotal = null,
        averageMonthlyTotal = null,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR"
    )

    private fun overallBudget(amount: Double, periodMs: Long) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 1000.0,
        remainingAmount = amount - 1000.0,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = periodStart,
        periodEnd = periodStart + periodMs,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED"
    )

    private suspend fun synthesize(budgets: List<BudgetStatusSnapshot>) = engine.synthesize(
        pastSumDaily = emptyList(),
        recurringPatterns = emptyList(),
        plannedExpenses = emptyList(),
        savingsGoals = emptyList(),
        budgetStatuses = budgets,
        spendingPace = pace,
        displayCurrency = "EUR"
    )

    @Test
    fun `F1 yearly 12000 overall budget takes the no-budget path`() = runTest {
        val noBudget = synthesize(emptyList())
        val yearly = synthesize(listOf(overallBudget(12_000.0, 366 * dayMs)))

        assertEquals("no pool is derived from a yearly limit", 0.0, yearly.components.discretionaryBudget, 0.0)
        assertEquals(
            "yearly limit gets the same confidence as no budget at all",
            noBudget.confidence, yearly.confidence, 1e-9
        )
        assertEquals(noBudget.components.riskLevel, yearly.components.riskLevel)
    }

    @Test
    fun `F1 weekly overall budget takes the no-budget path`() = runTest {
        val noBudget = synthesize(emptyList())
        val weekly = synthesize(listOf(overallBudget(100.0, 7 * dayMs)))

        assertEquals(0.0, weekly.components.discretionaryBudget, 0.0)
        assertEquals(noBudget.confidence, weekly.confidence, 1e-9)
    }

    @Test
    fun `F1 control month-length overall budget is unchanged`() = runTest {
        val noBudget = synthesize(emptyList())
        val monthly = synthesize(listOf(overallBudget(3_000.0, 31 * dayMs)))

        assertEquals("3000 limit - 1000 spent", 2_000.0, monthly.components.discretionaryBudget, 0.001)
        assertTrue(
            "a usable budget does not take the no-budget confidence penalty",
            monthly.confidence > noBudget.confidence
        )
    }

    @Test
    fun `F1 degenerate period with end equal to start keeps the legacy behaviour`() = runTest {
        val zeroLength = synthesize(listOf(overallBudget(3_000.0, 0L)))

        assertEquals(2_000.0, zeroLength.components.discretionaryBudget, 0.001)
    }

    @Test
    fun `F1 degenerate period with end before start keeps the legacy behaviour`() = runTest {
        val inverted = synthesize(listOf(overallBudget(3_000.0, -5 * dayMs)))

        assertEquals(2_000.0, inverted.components.discretionaryBudget, 0.001)
    }
}
