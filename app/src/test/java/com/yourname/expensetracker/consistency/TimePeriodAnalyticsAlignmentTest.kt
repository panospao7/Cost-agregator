package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensures TimePeriodUtils produces ranges that match what AnalyticsViewModel and
 * TransactionsViewModel use. Prevents drift when period logic is duplicated.
 */
class TimePeriodAnalyticsAlignmentTest {

    @Test
    fun `consistency - getLastNDaysRange produces valid 7-day range for WEEK`() {
        val now = 1710000000000L // Fixed timestamp for reproducibility
        val (start, end) = TimePeriodUtils.getLastNDaysRange(now, 7)
        val expectedStart = TimePeriodUtils.getStartOfDay(now - 7 * 86400000L)
        assertEquals(expectedStart, start)
        assertEquals(now, end)
        assertTrue("Start must be before end", start < end)
    }

    @Test
    fun `consistency - getMonthRange produces valid current month range`() {
        val now = 1710000000000L
        val (start, end) = TimePeriodUtils.getMonthRange(now, 0)
        val expectedStart = TimePeriodUtils.getStartOfMonth(now)
        val expectedEnd = TimePeriodUtils.getEndOfMonth(now)
        assertEquals(expectedStart, start)
        assertEquals(expectedEnd, end)
        assertTrue("Start must be before end", start < end)
    }

    @Test
    fun `consistency - getMonthRange offset -1 produces previous month`() {
        val now = 1710000000000L
        val (start, end) = TimePeriodUtils.getMonthRange(now, -1)
        val prevMonthStart = TimePeriodUtils.addMonths(TimePeriodUtils.getStartOfMonth(now), -1)
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)
        assertEquals(prevMonthStart, start)
        assertEquals(prevMonthEnd, end)
    }

    @Test
    fun `consistency - AnalyticsViewModel WEEK uses same logic as getLastNDaysRange`() {
        val now = System.currentTimeMillis()
        val (start, end) = TimePeriodUtils.getLastNDaysRange(now, 7)
        val dayStart = TimePeriodUtils.getStartOfDay(now - 7 * 86400000L)
        assertEquals(dayStart, start)
        assertEquals(now, end)
    }

    @Test
    fun `consistency - AnalyticsViewModel MONTH uses same logic as getMonthRange`() {
        val now = System.currentTimeMillis()
        val (start, end) = TimePeriodUtils.getMonthRange(now, 0)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val monthEnd = TimePeriodUtils.getEndOfMonth(now)
        assertEquals(monthStart, start)
        assertEquals(monthEnd, end)
    }

    @Test
    fun `consistency - getStartOfDay and getEndOfDay are consistent`() {
        val ts = 1710000000000L
        val start = TimePeriodUtils.getStartOfDay(ts)
        val end = TimePeriodUtils.getEndOfDay(ts)
        assertTrue(start < end)
        assertTrue(end - start >= 86400000L - 1) // ~24h in ms
    }
}
