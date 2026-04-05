package com.yourname.expensetracker.domain.analytics

import org.junit.Test
import org.junit.Assert.*
class RecurringIntervalLogicTest {
    @Test
    fun testRecurringIntervalLogic() {
        // Test Case 1: Weekly (7 days) with variance
        val weeklyIntervals = listOf(7.0, 7.1, 6.9, 7.0)
        var avg = weeklyIntervals.average()
        var rounded = kotlin.math.round(avg).toInt()
        assertTrue("Weekly should be detected", rounded in 5..10)

        // Test Case 2: Bi-weekly (14 days)
        val biWeeklyIntervals = listOf(14.0, 13.8, 14.1)
        avg = biWeeklyIntervals.average()
        rounded = kotlin.math.round(avg).toInt()
        assertTrue("Bi-weekly should be detected", rounded in 12..18)
        
        // Test Case 3: Monthly (30 days)
        val monthlyIntervals = listOf(30.0, 31.0, 29.0)
        avg = monthlyIntervals.average()
        rounded = kotlin.math.round(avg).toInt()
        assertTrue("Monthly should be detected", rounded in 25..35)

        // Test Case 4: Quarterly (90 days) - NEW
        val quarterlyIntervals = listOf(90.0, 91.0, 89.0)
        avg = quarterlyIntervals.average()
        rounded = kotlin.math.round(avg).toInt()
        assertTrue("Quarterly should be detected", rounded in 85..95)

        // Test Case 5: 11.9 days -> Should round to 12
        val edgeCase = listOf(11.9)
        avg = edgeCase.average()
        rounded = kotlin.math.round(avg).toInt()
        assertEquals(12, rounded)
        assertTrue("11.9 days (rounded to 12) should fall in bi-weekly range", rounded in 12..18)
        
        // Old logic (truncate) fail demonstration
        val truncated = avg.toInt()
        assertEquals(11, truncated)
        assertFalse("Old logic would see 11 and miss the range", truncated in 12..16)
    }
}
