package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimePeriodUtilsTest {

    @Test
    fun `getStartOfDay returns midnight`() {
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JANUARY, 15, 14, 30, 45) // Jan 15, 2:30:45 PM
        val timestamp = cal.timeInMillis

        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)

        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = startOfDay

        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getEndOfDay returns almost midnight`() {
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JANUARY, 15, 10, 0, 0)
        val timestamp = cal.timeInMillis

        val endOfDay = TimePeriodUtils.getEndOfDay(timestamp)

        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = endOfDay

        assertEquals(23, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, resultCal.get(Calendar.MINUTE))
        assertEquals(59, resultCal.get(Calendar.SECOND))
        assertEquals(999, resultCal.get(Calendar.MILLISECOND))
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `getStartOfWeek handles mid-week correctly`() {
        // Wednesday, Jan 18, 2023
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JANUARY, 18, 12, 0, 0)
        
        // Expected start: Monday, Jan 16
        val expectedCal = Calendar.getInstance()
        expectedCal.set(2023, Calendar.JANUARY, 16, 0, 0, 0)
        expectedCal.set(Calendar.MILLISECOND, 0)
        
        val startOfWeek = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
        
        // Allow slight tolerance if timezone quirks, but logic should be millisecond precise
        assertEquals(expectedCal.timeInMillis, startOfWeek)
    }

    @Test
    fun `getStartOfWeek handles Sunday correctly`() {
        // Sunday, Jan 22, 2023
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JANUARY, 22, 12, 0, 0)
        
        // Expected start: Monday, Jan 16 (Previous Monday)
        val expectedCal = Calendar.getInstance()
        expectedCal.set(2023, Calendar.JANUARY, 16, 0, 0, 0)
        expectedCal.set(Calendar.MILLISECOND, 0)
        
        val startOfWeek = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
        
        assertEquals(expectedCal.timeInMillis, startOfWeek)
    }

    @Test
    fun `getStartOfWeek handles Monday correctly`() {
        // Monday, Jan 16, 2023 - already start
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JANUARY, 16, 12, 0, 0) // noon
        
        // Expected start: Monday, Jan 16, 00:00
        val expectedCal = Calendar.getInstance()
        expectedCal.set(2023, Calendar.JANUARY, 16, 0, 0, 0)
        expectedCal.set(Calendar.MILLISECOND, 0)
        
        val startOfWeek = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
        
        assertEquals(expectedCal.timeInMillis, startOfWeek)
    }

    @Test
    fun `getStartOfMonth handles leap year correctly`() {
        // Feb 29, 2024 (Leap Day)
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.FEBRUARY, 29, 12, 0, 0)
        
        val startOfMonth = TimePeriodUtils.getStartOfMonth(cal.timeInMillis)
        
        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = startOfMonth
        
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, resultCal.get(Calendar.MONTH))
        assertEquals(2024, resultCal.get(Calendar.YEAR))
    }

    @Test
    fun `getMonthRange handles negative offset`() {
        // Currently June
        val cal = Calendar.getInstance()
        cal.set(2023, Calendar.JUNE, 15, 0, 0, 0)
        
        // We override "now" by mocking? No, Utils uses Cal.getInstance inside without injection.
        // Wait, TimePeriodUtils methods take timestamps usually, but getMonthRange takes an offset from "System.now".
        // Ah, TimePeriodUtils.getMonthRange(offset) -> cal = Calendar.getInstance(); cal.add(offset).
        // This is hard to test deterministically without mocking time.
        // BUT, we can verify relative logic:
        
        // Let's rely on range duration being roughly correct (28-31 days).
        val (start, end) = TimePeriodUtils.getMonthRange(-1)
        val duration = end - start
        
        // Previous month duration should be between 28 and 31 days
        val days = duration / 86400000.0
        assert(days >= 28.0 && days <= 31.0)
    }
}
