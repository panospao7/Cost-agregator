package com.yourname.expensetracker.ui.screens.analytics

import org.junit.Assert.*
import org.junit.Test

class AnalyticsStateStressTest {

    @Test
    fun `stress - default state has correct defaults`() {
        val state = AnalyticsState()
        
        assertEquals(0.0, state.currentTotal, 0.0)
        assertEquals(0, state.transactionCount)
        assertTrue(state.isLoading)
    }

    @Test
    fun `stress - state with data is valid`() {
        val state = AnalyticsState(
            currentTotal = 1500.50,
            transactionCount = 25,
            isLoading = false
        )
        
        assertEquals(1500.50, state.currentTotal, 0.01)
        assertEquals(25, state.transactionCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `stress - change percent with positive growth`() {
        val state = AnalyticsState(
            currentTotal = 1500.0,
            previousTotal = 1000.0,
            changePercent = 50f
        )
        
        assertTrue(state.changePercent!! > 0)
    }

    @Test
    fun `stress - change percent with negative decline`() {
        val state = AnalyticsState(
            currentTotal = 800.0,
            previousTotal = 1000.0,
            changePercent = -20f
        )
        
        assertTrue(state.changePercent!! < 0)
    }

    @Test
    fun `stress - change percent when previous is zero`() {
        val state = AnalyticsState(
            currentTotal = 500.0,
            previousTotal = 0.0,
            changePercent = null
        )
        
        assertNull(state.changePercent)
    }

    @Test
    fun `stress - empty breakdown lists`() {
        val state = AnalyticsState()
        
        assertTrue(state.categoryBreakdown.isEmpty())
        assertTrue(state.merchantBreakdown.isEmpty())
        assertTrue(state.dailyTotals.isEmpty())
        assertTrue(state.insights.isEmpty())
    }

    @Test
    fun `stress - daily totals map has correct size`() {
        val dailyTotals = mapOf(
            "Monday" to 50.0,
            "Tuesday" to 75.0,
            "Wednesday" to 100.0
        )
        
        val state = AnalyticsState(dailyTotals = dailyTotals)
        
        assertEquals(3, state.dailyTotals.size)
    }

    @Test
    fun `stress - very large total amounts`() {
        val state = AnalyticsState(currentTotal = Double.MAX_VALUE)
        assertEquals(Double.MAX_VALUE, state.currentTotal, 0.0)
    }

    @Test
    fun `stress - zero amounts`() {
        val state = AnalyticsState(currentTotal = 0.0, transactionCount = 0)
        assertEquals(0.0, state.currentTotal, 0.0)
    }

    @Test
    fun `stress - negative amounts`() {
        val state = AnalyticsState(currentTotal = -100.0)
        assertEquals(-100.0, state.currentTotal, 0.0)
    }

    @Test
    fun `stress - location insights empty by default`() {
        val state = AnalyticsState()
        assertTrue(state.locationInsights.isEmpty())
    }

    @Test
    fun `stress - travel insight nullable`() {
        val state = AnalyticsState()
        assertNull(state.travelInsight)
    }

    @Test
    fun `stress - statistical insights nullable`() {
        val state = AnalyticsState()
        assertNull(state.statisticalInsights)
    }

    @Test
    fun `stress - spending patterns nullable`() {
        val state = AnalyticsState()
        assertNull(state.spendingPatterns)
    }

    @Test
    fun `stress - year over year nullable`() {
        val state = AnalyticsState()
        assertNull(state.yearOverYear)
    }

    @Test
    fun `stress - hour of day pattern has 24 hours`() {
        val hourlyPattern = (0..23).map { hour -> hour to (hour * 10.0) }
        val state = AnalyticsState(hourOfDayPattern = hourlyPattern)
        assertEquals(24, state.hourOfDayPattern.size)
    }

    @Test
    fun `stress - date range nullable`() {
        val state = AnalyticsState()
        assertNull(state.currentDateRange)
    }

    @Test
    fun `stress - date range set correctly`() {
        val startDate = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val endDate = System.currentTimeMillis()
        
        val state = AnalyticsState(currentDateRange = Pair(startDate, endDate))
        
        assertNotNull(state.currentDateRange)
    }

    @Test
    fun `stress - budget vs actual item creation`() {
        val item = BudgetVsActualItem(
            categoryName = "Food",
            categoryIcon = "restaurant",
            categoryColor = "#FF0000",
            budgetAmount = 500.0,
            actualSpent = 450.0,
            percentUsed = 0.9f
        )
        
        assertEquals("Food", item.categoryName)
        assertEquals(500.0, item.budgetAmount, 0.0)
    }

    @Test
    fun `stress - state copy preserves values`() {
        val original = AnalyticsState(currentTotal = 1000.0, transactionCount = 10, isLoading = false)
        val copied = original.copy(isLoading = true)
        
        assertEquals(1000.0, copied.currentTotal, 0.0)
        assertEquals(10, copied.transactionCount)
        assertTrue(copied.isLoading)
    }
}
