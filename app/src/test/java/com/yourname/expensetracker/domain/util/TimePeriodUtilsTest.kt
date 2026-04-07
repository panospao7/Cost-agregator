package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimePeriodUtilsTest {

    @Test
    fun `getStartOfDay returns midnight of the given timestamp`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JANUARY, 15, 14, 30, 45)
        val timestamp = calendar.timeInMillis

        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        val resultCal = Calendar.getInstance().apply { timeInMillis = startOfDay }

        assertEquals(2024, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `getStartOfMonth returns first day of month at midnight`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.FEBRUARY, 20, 10, 0, 0)
        val timestamp = calendar.timeInMillis

        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        val resultCal = Calendar.getInstance().apply { timeInMillis = startOfMonth }

        assertEquals(2024, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `getEndOfMonth returns start of next month (exclusive end convention)`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.FEBRUARY, 10, 10, 0, 0) // Leap year 2024
        val timestamp = calendar.timeInMillis

        val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfMonth }

        // Production uses exclusive end: start of next month
        assertEquals(2024, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `getDaysRemainingInMonth returns correct count`() {
        // Feb 20 in a leap year (2024) should have 9 days remaining (21, 22, 23, 24, 25, 26, 27, 28, 29)
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.FEBRUARY, 20, 12, 0, 0)
        val timestamp = calendar.timeInMillis

        val remaining = TimePeriodUtils.getDaysRemainingInMonth(timestamp)
        assertEquals(9, remaining)

        // Last day of month
        calendar.set(2024, Calendar.FEBRUARY, 29, 23, 0, 0)
        assertEquals(0, TimePeriodUtils.getDaysRemainingInMonth(calendar.timeInMillis))
    }

    @Test
    fun `getStartOfYear returns Jan 1st at midnight`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JULY, 4, 12, 0, 0)
        val timestamp = calendar.timeInMillis

        val startOfYear = TimePeriodUtils.getStartOfYear(timestamp)
        val resultCal = Calendar.getInstance().apply { timeInMillis = startOfYear }

        assertEquals(2024, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `getEndOfYear returns start of next year (exclusive end convention)`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.MARCH, 1, 0, 0, 0)
        val timestamp = calendar.timeInMillis

        val endOfYear = TimePeriodUtils.getEndOfYear(timestamp)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfYear }

        // Production uses exclusive end: start of next year
        assertEquals(2025, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
    }
}
