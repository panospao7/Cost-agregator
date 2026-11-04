package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FL-06 (weather): the forecast pool for a positive overall budget is the shared
 * BudgetPeriodRemaining (effective limit minus effective spend in the budget period, whatever
 * its length) minus obligations. An overall budget that is Unavailable (no effective spend)
 * takes the no-budget path. Category budgets only matter with no positive overall limit, and
 * then only when month-scale.
 *
 * "Now" is fixed to 2024-01-15 12:00 UTC (31-day January). Gross spend is 1000, no recurring
 * patterns, planned items or goals.
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

    private fun overallBudget(
        amount: Double,
        periodMs: Long,
        effectiveSpend: Double? = 1000.0,
        effectiveHealth: BudgetHealthStatus? = BudgetHealthStatus.ON_TRACK
    ) = BudgetStatusSnapshot(
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
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = effectiveSpend,
        effectiveHealthStatus = effectiveHealth
    )

    private fun categoryBudget(amount: Double, periodMs: Long) = BudgetStatusSnapshot(
        budgetCategoryId = 5L,
        budgetAmount = amount,
        categoryName = "Food",
        spentAmount = 1000.0,
        remainingAmount = amount - 1000.0,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = periodStart,
        periodEnd = periodStart + periodMs,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = 1000.0,
        effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
    )

    private suspend fun synthesize(budgets: List<BudgetStatusSnapshot>, displayCurrency: String = "EUR") =
        engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = budgets,
            spendingPace = pace,
            displayCurrency = displayCurrency
        )

    @Test
    fun `FL-06 yearly 12000 limit with effective 9300 gives 2700 and LOW`() = runTest {
        val noBudget = synthesize(emptyList())
        val yearly = synthesize(
            listOf(overallBudget(12_000.0, 366 * dayMs, 9_300.0, BudgetHealthStatus.WARNING))
        )

        assertEquals("12000 - 9300 effective", 2_700.0, yearly.components.discretionaryBudget, 0.001)
        assertEquals(RiskLevel.LOW, yearly.components.riskLevel)
        assertTrue(
            "a usable yearly budget does not take the no-budget confidence penalty",
            yearly.confidence > noBudget.confidence
        )
    }

    @Test
    fun `FL-06 weekly 100 limit with effective 150 gives 0 and CRITICAL`() = runTest {
        val weekly = synthesize(
            listOf(overallBudget(100.0, 7 * dayMs, 150.0, BudgetHealthStatus.EXCEEDED))
        )

        assertEquals(0.0, weekly.components.discretionaryBudget, 0.0)
        assertEquals(RiskLevel.CRITICAL, weekly.components.riskLevel)
    }

    @Test
    fun `FL-06 rolling monthly 1000 limit with effective 950 gives 50 and CRITICAL`() = runTest {
        val rolling = synthesize(
            listOf(overallBudget(1_000.0, 31 * dayMs, 950.0, BudgetHealthStatus.CRITICAL))
        )

        assertEquals(50.0, rolling.components.discretionaryBudget, 0.001)
        assertEquals("95 percent is over the critical line", RiskLevel.CRITICAL, rolling.components.riskLevel)
    }

    @Test
    fun `FL-06 control month-length overall budget 3000 with effective 1000 gives 2000`() = runTest {
        val noBudget = synthesize(emptyList())
        val monthly = synthesize(listOf(overallBudget(3_000.0, 31 * dayMs)))

        assertEquals("3000 limit - 1000 effective spend", 2_000.0, monthly.components.discretionaryBudget, 0.001)
        assertTrue(
            "a usable budget does not take the no-budget confidence penalty",
            monthly.confidence > noBudget.confidence
        )
    }

    @Test
    fun `FL-06 effective spend null takes the no-budget path with 0 and the confidence penalty`() = runTest {
        val noBudget = synthesize(emptyList())
        val unavailable = synthesize(
            listOf(overallBudget(3_000.0, 31 * dayMs, effectiveSpend = null, effectiveHealth = null))
        )

        assertEquals(
            "gross spend must not stand in for the missing effective spend",
            0.0, unavailable.components.discretionaryBudget, 0.0
        )
        assertEquals(noBudget.confidence, unavailable.confidence, 1e-9)
        assertEquals(noBudget.components.riskLevel, unavailable.components.riskLevel)
    }

    @Test
    fun `FL-06 blank display currency is unavailable and takes the no-budget path`() = runTest {
        val noBudget = synthesize(emptyList(), displayCurrency = "")
        val blank = synthesize(listOf(overallBudget(3_000.0, 31 * dayMs)), displayCurrency = "")

        assertEquals(0.0, blank.components.discretionaryBudget, 0.0)
        assertEquals(noBudget.confidence, blank.confidence, 1e-9)
    }

    @Test
    fun `FL-06 degenerate period with end equal to start still reads the shared remaining`() = runTest {
        val zeroLength = synthesize(listOf(overallBudget(3_000.0, 0L)))

        assertEquals(2_000.0, zeroLength.components.discretionaryBudget, 0.001)
    }

    @Test
    fun `FL-06 degenerate period with end before start still reads the shared remaining`() = runTest {
        val inverted = synthesize(listOf(overallBudget(3_000.0, -5 * dayMs)))

        assertEquals(2_000.0, inverted.components.discretionaryBudget, 0.001)
    }

    @Test
    fun `FL-06 category fallback gives 0 for a yearly category budget`() = runTest {
        val noBudget = synthesize(emptyList())
        val yearlyCategory = synthesize(listOf(categoryBudget(12_000.0, 366 * dayMs)))

        assertEquals(0.0, yearlyCategory.components.discretionaryBudget, 0.0)
        assertEquals(noBudget.confidence, yearlyCategory.confidence, 1e-9)
    }

    @Test
    fun `FL-06 category fallback control month-scale category budget 3000 gives 2000`() = runTest {
        val monthlyCategory = synthesize(listOf(categoryBudget(3_000.0, 31 * dayMs)))

        assertEquals("3000 category limit - 1000 spent", 2_000.0, monthlyCategory.components.discretionaryBudget, 0.001)
    }
}
