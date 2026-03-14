package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Stress tests for time period alignment.
 */
class TimePeriodAlignmentStressTest {

    private fun ts(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `stress - 100 random dates month range valid`() {
        for (i in 1..100) {
            val year = 2020 + (i % 5)
            val month = i % 12
            val day = (i % 28) + 1
            val ref = ts(year, month, day)
            val (start, end) = TimePeriodUtils.getMonthRange(ref)
            assertTrue("Start < end", start < end)
            assertTrue("Duration > 0", (end - start) > 0)
        }
    }

    @Test
    fun `stress - week boundaries 52 weeks`() {
        val ref = ts(2024, 0, 1)
        val seenStarts = mutableSetOf<Long>()
        for (i in 0..51) {
            val weekStart = TimePeriodUtils.getStartOfWeek(ref + i * 7 * 86400000L)
            seenStarts.add(weekStart)
        }
        assertTrue("Should have multiple distinct weeks", seenStarts.size > 1)
    }

    @Test
    fun `stress - quarter boundaries 4 quarters`() {
        val ref = ts(2024, 5, 15)
        val quarters = listOf(
            TimePeriodUtils.getStartOfQuarter(ts(2024, 0, 1)),
            TimePeriodUtils.getStartOfQuarter(ts(2024, 3, 1)),
            TimePeriodUtils.getStartOfQuarter(ts(2024, 6, 1)),
            TimePeriodUtils.getStartOfQuarter(ts(2024, 9, 1))
        )
        assertTrue("Q1 Jan", Calendar.getInstance().apply { timeInMillis = quarters[0] }.get(Calendar.MONTH) == Calendar.JANUARY)
        assertTrue("Q2 Apr", Calendar.getInstance().apply { timeInMillis = quarters[1] }.get(Calendar.MONTH) == Calendar.APRIL)
        assertTrue("Q3 Jul", Calendar.getInstance().apply { timeInMillis = quarters[2] }.get(Calendar.MONTH) == Calendar.JULY)
        assertTrue("Q4 Oct", Calendar.getInstance().apply { timeInMillis = quarters[3] }.get(Calendar.MONTH) == Calendar.OCTOBER)
    }

    @Test
    fun `stress - isSameMonth 200 pairs`() {
        for (i in 1..200) {
            val ref = ts(2024, 5, 15)
            val (start, end) = TimePeriodUtils.getMonthRange(ref, i % 4 - 2)
            val mid = (start + end) / 2
            assertTrue("Mid in same month", TimePeriodUtils.isSameMonth(start, mid))
            assertTrue("End in same month", TimePeriodUtils.isSameMonth(start, end))
        }
    }

    @Test
    fun `edge - getDaysInMonth all 12 months`() {
        val expected = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        for (m in 0..11) {
            val ref = ts(2024, m, 15)
            val days = TimePeriodUtils.getDaysInMonth(ref)
            org.junit.Assert.assertEquals("Month $m", expected[m], days)
        }
    }
}
