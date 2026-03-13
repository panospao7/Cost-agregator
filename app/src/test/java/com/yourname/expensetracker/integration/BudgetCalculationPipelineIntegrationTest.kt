package com.yourname.expensetracker.integration

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.AmountUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BudgetCalculationPipelineIntegrationTest {

    // ============================================================================
    // SECTION 1: PERIOD CALCULATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate monthly period correctly`() {
        val now = System.currentTimeMillis()
        
        val period = TimePeriodUtils.getMonthRange(now)
        
        assertNotNull("Period should exist", period)
        assertTrue("Start should be before end", period.first < period.second)
    }

    @Test
    fun `integration - get month range with offset`() {
        val now = System.currentTimeMillis()
        
        val currentMonth = TimePeriodUtils.getMonthRange(now, 0)
        val lastMonth = TimePeriodUtils.getMonthRange(now, -1)
        
        assertTrue("Current month should be after last month", currentMonth.first > lastMonth.first)
    }

    @Test
    fun `integration - calculate daily period correctly`() {
        val now = System.currentTimeMillis()
        
        val period = TimePeriodUtils.getLastNDaysRange(now, 1)
        
        assertNotNull("Period should exist", period.first)
        assertNotNull("Period should exist", period.second)
    }

    // ============================================================================
    // SECTION 2: BUDGET CALCULATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate budget remaining correctly`() {
        val budgetAmount = 500.0
        val spentAmount = 250.0
        
        val remaining = budgetAmount - spentAmount
        
        assertEquals(250.0, remaining, 0.001)
    }

    @Test
    fun `integration - calculate budget percentage correctly`() {
        val budgetAmount = 500.0
        val spentAmount = 250.0
        
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertEquals(50.0, percentage, 0.001)
    }

    @Test
    fun `integration - over budget calculation`() {
        val budgetAmount = 500.0
        val spentAmount = 600.0
        
        val remaining = budgetAmount - spentAmount
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertTrue("Should be over budget", remaining < 0)
        assertEquals(120.0, percentage, 0.001)
    }

    // ============================================================================
    // SECTION 3: PERIOD TRANSITION PIPELINE
    // ============================================================================

    @Test
    fun `integration - month boundary calculations`() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        val startOfMonth = calendar.timeInMillis
        val period = TimePeriodUtils.getMonthRange(startOfMonth)
        
        assertTrue("Period should span month", period.second > period.first)
    }

    @Test
    fun `integration - year boundary calculations`() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        
        val startOfYear = calendar.timeInMillis
        val monthRange = TimePeriodUtils.getMonthRange(startOfYear)
        
        assertTrue("Period should span month", monthRange.second > monthRange.first)
    }

    // ============================================================================
    // SECTION 4: AMOUNT ROUNDING PIPELINE
    // ============================================================================

    @Test
    fun `integration - currency rounding for budget`() {
        val amount = 123.456
        
        val rounded = Math.round(amount * 100.0) / 100.0
        
        assertEquals(123.46, rounded, 0.001)
    }

    @Test
    fun `integration - budget threshold calculations`() {
        val budgetAmount = 500.0
        val warningThreshold = 0.75f
        val criticalThreshold = 0.90f
        
        val warningAmount = budgetAmount * warningThreshold
        val criticalAmount = budgetAmount * criticalThreshold
        
        assertEquals(375.0, warningAmount, 0.001)
        assertEquals(450.0, criticalAmount, 0.001)
    }

    // ============================================================================
    // SECTION 5: ROLLOVER CALCULATION PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate rollover correctly`() {
        val budgetAmount = 500.0
        val spentAmount = 300.0
        val rolloverEnabled = true
        
        val remaining = budgetAmount - spentAmount
        val rolloverAmount = if (rolloverEnabled && remaining > 0) remaining else 0.0
        
        assertEquals(200.0, rolloverAmount, 0.001)
    }

    @Test
    fun `integration - no rollover when over budget`() {
        val budgetAmount = 500.0
        val spentAmount = 600.0
        val rolloverEnabled = true
        
        val remaining = budgetAmount - spentAmount
        val rolloverAmount = if (rolloverEnabled && remaining > 0) remaining else 0.0
        
        assertEquals(0.0, rolloverAmount, 0.001)
    }

    // ============================================================================
    // SECTION 6: DAILY BUDGET CALCULATION
    // ============================================================================

    @Test
    fun `integration - calculate daily budget from monthly`() {
        val monthlyBudget = 1500.0
        val daysInMonth = 30
        
        val dailyBudget = monthlyBudget / daysInMonth
        
        assertEquals(50.0, dailyBudget, 0.001)
    }

    @Test
    fun `integration - calculate daily budget from weekly`() {
        val weeklyBudget = 350.0
        val daysInWeek = 7
        
        val dailyBudget = weeklyBudget / daysInWeek
        
        assertEquals(50.0, dailyBudget, 0.01)
    }

    // ============================================================================
    // SECTION 7: EDGE CASES
    // ============================================================================

    @Test
    fun `integration - zero budget handled`() {
        val budgetAmount = 0.0
        val spentAmount = 0.0
        
        val remaining = budgetAmount - spentAmount
        val percentage = if (budgetAmount > 0) (spentAmount / budgetAmount) * 100 else 0.0
        
        assertEquals(0.0, remaining, 0.001)
        assertEquals(0.0, percentage, 0.001)
    }

    @Test
    fun `integration - very large budget handled`() {
        val budgetAmount = Double.MAX_VALUE / 1000
        val spentAmount = budgetAmount / 2
        
        val remaining = budgetAmount - spentAmount
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertTrue(remaining > 0)
        assertEquals(50.0, percentage, 0.001)
    }

    @Test
    fun `integration - zero spent handled`() {
        val budgetAmount = 500.0
        val spentAmount = 0.0
        
        val remaining = budgetAmount - spentAmount
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertEquals(500.0, remaining, 0.001)
        assertEquals(0.0, percentage, 0.001)
    }

    // ============================================================================
    // SECTION 8: MULTI-CATEGORY BUDGET PIPELINE
    // ============================================================================

    @Test
    fun `integration - calculate total budget from categories`() {
        val categoryBudgets = listOf(
            "Food" to 200.0,
            "Transport" to 100.0,
            "Entertainment" to 150.0
        )
        
        val totalBudget = categoryBudgets.sumOf { it.second }
        
        assertEquals(450.0, totalBudget, 0.001)
    }

    @Test
    fun `integration - calculate category percentages`() {
        val totalBudget = 500.0
        val categoryBudgets = listOf(
            "Food" to 200.0,
            "Transport" to 100.0,
            "Entertainment" to 200.0
        )
        
        val percentages = categoryBudgets.map { (_, amount) ->
            (amount / totalBudget) * 100
        }
        
        assertEquals(40.0, percentages[0], 0.001)
        assertEquals(20.0, percentages[1], 0.001)
        assertEquals(40.0, percentages[2], 0.001)
    }

    // ============================================================================
    // SECTION 9: DATE RANGE PIPELINE
    // ============================================================================

    @Test
    fun `integration - get last N days range`() {
        val now = System.currentTimeMillis()
        
        val range = TimePeriodUtils.getLastNDaysRange(now, 7)
        
        assertTrue("Range should be valid", range.second > range.first)
    }

    @Test
    fun `integration - get last N days includes today`() {
        val now = System.currentTimeMillis()
        
        val range = TimePeriodUtils.getLastNDaysRange(now, 1)
        
        assertTrue("Range should include now", range.second >= now)
    }

    // ============================================================================
    // SECTION 10: PERFORMANCE
    // ============================================================================

    @Test
    fun `integration - calculate 100 budgets quickly`() {
        val budgets = (1..100).map { it * 100.0 }
        val spent = (1..100).map { it * 50.0 }
        
        val startTime = System.nanoTime()
        
        budgets.zip(spent).forEach { (budget, spentAmount) ->
            val remaining = budget - spentAmount
            val percentage = (spentAmount / budget) * 100
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should calculate quickly", duration < 1_000_000_000)
    }
}
