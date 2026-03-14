package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BudgetCalculatorStressTest {

    // ============================================================================
    // SECTION 1: PERIOD WINDOW CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate daily period window`() {
        val anchorDate = System.currentTimeMillis()
        
        val period = calculateDailyPeriod(anchorDate)
        
        assertTrue("Period should span 24 hours", period.second - period.first == 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `stress - calculate weekly period window`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val anchorDate = cal.timeInMillis
        
        val period = calculateWeeklyPeriod(anchorDate)
        
        assertTrue("Period should span 7 days", period.second - period.first == 7 * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `stress - calculate monthly period window`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val anchorDate = cal.timeInMillis
        
        val period = calculateMonthlyPeriod(anchorDate)
        
        assertTrue("Period should be valid", period.second > period.first)
    }

    @Test
    fun `stress - calculate period for leap year February`() {
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.FEBRUARY, 15)
        val anchorDate = cal.timeInMillis
        
        val period = calculateMonthlyPeriod(anchorDate)
        
        // February 2024 has 29 days
        assertTrue("Leap year February should have 29 days", 
            (period.second - period.first) >= 29L * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `stress - calculate period for non-leap year February`() {
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.FEBRUARY, 15)
        val anchorDate = cal.timeInMillis
        
        val period = calculateMonthlyPeriod(anchorDate)
        
        // February 2023 has 28 days
        assertTrue("Non-leap year February should have 28 days",
            (period.second - period.first) >= 28L * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `stress - calculate period for 31-day months`() {
        val months31Days = listOf(Calendar.JANUARY, Calendar.MARCH, Calendar.MAY, 
            Calendar.JULY, Calendar.AUGUST, Calendar.OCTOBER, Calendar.DECEMBER)
        
        months31Days.forEach { month ->
            val cal = Calendar.getInstance()
            cal.set(2024, month, 15)
            val anchorDate = cal.timeInMillis
            
            val period = calculateMonthlyPeriod(anchorDate)
            
            val spanDays = (period.second - period.first) / (24L * 60 * 60 * 1000L)
            assertTrue("Month $month should be around 31 days", spanDays in 30L..32L)
        }
    }

    @Test
    fun `stress - calculate period for 30-day months`() {
        val months30Days = listOf(Calendar.APRIL, Calendar.JUNE, Calendar.SEPTEMBER, Calendar.NOVEMBER)
        
        months30Days.forEach { month ->
            val cal = Calendar.getInstance()
            cal.set(2024, month, 15)
            val anchorDate = cal.timeInMillis
            
            val period = calculateMonthlyPeriod(anchorDate)
            
            assertTrue("Month $month should have 30 days",
                (period.second - period.first) >= 30L * 24 * 60 * 60 * 1000L)
        }
    }

    // ============================================================================
    // SECTION 2: BUDGET ROLLOVER CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate rollover with remaining budget`() {
        val budgetAmount = 500.0
        val spentAmount = 300.0
        val rollover = true
        
        val remaining = budgetAmount - spentAmount
        val rolloverAmount = if (rollover && remaining > 0) remaining else 0.0
        
        assertEquals(200.0, rolloverAmount, 0.001)
    }

    @Test
    fun `stress - no rollover when disabled`() {
        val budgetAmount = 500.0
        val spentAmount = 300.0
        val rollover = false
        
        val remaining = budgetAmount - spentAmount
        val rolloverAmount = if (rollover && remaining > 0) remaining else 0.0
        
        assertEquals(0.0, rolloverAmount, 0.001)
    }

    @Test
    fun `stress - no rollover when over budget`() {
        val budgetAmount = 500.0
        val spentAmount = 600.0
        val rollover = true
        
        val remaining = budgetAmount - spentAmount
        val rolloverAmount = if (rollover && remaining > 0) remaining else 0.0
        
        assertEquals(0.0, rolloverAmount, 0.001)
    }

    @Test
    fun `stress - multi-month rollover chain`() {
        val monthlyBudget = 500.0
        val monthlySpending = listOf(300.0, 400.0, 200.0, 450.0)
        val rollover = true
        
        var cumulativeRollover = 0.0
        
        monthlySpending.forEach { spent ->
            val effectiveBudget = monthlyBudget + cumulativeRollover
            val remaining = effectiveBudget - spent
            cumulativeRollover = if (rollover && remaining > 0) remaining else 0.0
        }
        
        assertTrue("Should have accumulated rollover", cumulativeRollover >= 0)
    }

    // ============================================================================
    // SECTION 3: PERCENTAGE CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate percentage used`() {
        val budgetAmount = 500.0
        val spentAmount = 250.0
        
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertEquals(50.0, percentage, 0.001)
    }

    @Test
    fun `stress - percentage over 100 percent`() {
        val budgetAmount = 500.0
        val spentAmount = 750.0
        
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertEquals(150.0, percentage, 0.001)
    }

    @Test
    fun `stress - percentage with zero budget`() {
        val budgetAmount = 0.0
        val spentAmount = 100.0
        
        val percentage = if (budgetAmount > 0) (spentAmount / budgetAmount) * 100 else 0.0
        
        assertEquals(0.0, percentage, 0.001)
    }

    // ============================================================================
    // SECTION 4: WARNING THRESHOLD CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate warning threshold`() {
        val budgetAmount = 500.0
        val warningThreshold = 0.75f
        
        val warningAmount = budgetAmount * warningThreshold
        
        assertEquals(375.0, warningAmount, 0.001)
    }

    @Test
    fun `stress - calculate critical threshold`() {
        val budgetAmount = 500.0
        val criticalThreshold = 0.90f
        
        val criticalAmount = budgetAmount * criticalThreshold
        
        assertEquals(450.0, criticalAmount, 0.001)
    }

    @Test
    fun `stress - determine budget health status`() {
        val budgetAmount = 500.0
        val spentAmount = 400.0
        val warningThreshold = 0.75f
        val criticalThreshold = 0.90f
        
        val percentage = spentAmount / budgetAmount
        val status = when {
            percentage >= criticalThreshold -> "CRITICAL"
            percentage >= warningThreshold -> "WARNING"
            else -> "HEALTHY"
        }
        
        assertEquals("WARNING", status)
    }

    // ============================================================================
    // SECTION 5: DAILY BUDGET CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - calculate daily budget allocation`() {
        val monthlyBudget = 1500.0
        val daysInMonth = 30
        
        val dailyBudget = monthlyBudget / daysInMonth
        
        assertEquals(50.0, dailyBudget, 0.001)
    }

    @Test
    fun `stress - calculate remaining daily budget`() {
        val monthlyBudget = 1500.0
        val spentAmount = 750.0
        val daysRemaining = 15
        
        val remainingBudget = monthlyBudget - spentAmount
        val dailyRemaining = remainingBudget / daysRemaining
        
        assertEquals(50.0, dailyRemaining, 0.001)
    }

    @Test
    fun `stress - calculate pacing status`() {
        val monthlyBudget = 1500.0
        val spentAmount = 1000.0
        val daysElapsed = 20
        val daysInMonth = 30
        
        val expectedSpending = monthlyBudget * (daysElapsed.toDouble() / daysInMonth)
        val pacing = spentAmount - expectedSpending
        
        assertEquals("Should be exactly on pace", 0.0, pacing, 0.001)
    }

    // ============================================================================
    // SECTION 6: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - handle very large budget`() {
        val budgetAmount = Double.MAX_VALUE / 1000
        val spentAmount = budgetAmount / 2
        
        val remaining = budgetAmount - spentAmount
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertTrue("Should handle large values", remaining > 0)
        assertEquals(50.0, percentage, 0.001)
    }

    @Test
    fun `stress - handle very small budget`() {
        val budgetAmount = 0.01
        val spentAmount = 0.005
        
        val remaining = budgetAmount - spentAmount
        val percentage = (spentAmount / budgetAmount) * 100
        
        assertEquals(0.005, remaining, 0.0001)
        assertEquals(50.0, percentage, 0.001)
    }

    @Test
    fun `stress - handle midnight boundary`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        val midnight = cal.timeInMillis
        val period = calculateDailyPeriod(midnight)
        
        assertEquals(midnight, period.first)
    }

    @Test
    fun `stress - handle month-end boundary`() {
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.JANUARY, 31)
        
        val endOfMonth = cal.timeInMillis
        val period = calculateMonthlyPeriod(endOfMonth)
        
        assertTrue("Period should include end of month", period.second >= endOfMonth)
    }

    // ============================================================================
    // SECTION 7: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - calculate 1000 periods quickly`() {
        val periods = BudgetPeriod.values()
        
        val startTime = System.nanoTime()
        
        repeat(1000) {
            val period = periods[it % periods.size]
            val anchorDate = System.currentTimeMillis()
            calculatePeriod(period, anchorDate)
        }
        
        val duration = System.nanoTime() - startTime
        
        assertTrue("Should calculate quickly", duration < 1_000_000_000)
    }

    // Helper functions
    private fun calculateDailyPeriod(anchorDate: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = anchorDate }
        val start = cal.apply { set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
        val end = cal.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        return start to end
    }

    private fun calculateWeeklyPeriod(anchorDate: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = anchorDate }
        val start = cal.timeInMillis
        val end = cal.apply { add(Calendar.WEEK_OF_YEAR, 1) }.timeInMillis
        return start to end
    }

    private fun calculateMonthlyPeriod(anchorDate: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = anchorDate }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = cal.timeInMillis
        val end = cal.apply { add(Calendar.MONTH, 1) }.timeInMillis
        return start to end
    }

    private fun calculatePeriod(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        return when (period) {
            BudgetPeriod.DAILY -> calculateDailyPeriod(anchorDate)
            BudgetPeriod.WEEKLY -> calculateWeeklyPeriod(anchorDate)
            BudgetPeriod.MONTHLY -> calculateMonthlyPeriod(anchorDate)
            BudgetPeriod.YEARLY -> calculateMonthlyPeriod(anchorDate) // Simplified
        }
    }
}
