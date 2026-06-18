package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Comprehensive validation tests for TimePeriodUtils to ensure all time-based
 * calculations are mathematically correct, not just consistent.
 * 
 * Tests cover:
 * 1. Known date verification
 * 2. Period boundary testing
 * 3. Rolling window accuracy
 * 4. DST transition handling
 * 5. Edge cases and boundary conditions
 */
class TimePeriodUtilsValidationTest {

    // Helper to create timestamps for specific dates
    private fun createDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun createDateAtMidnight(year: Int, month: Int, day: Int): Long {
        return createDate(year, month, day, 0, 0, 0)
    }

    private fun createDateAtEndOfDay(year: Int, month: Int, day: Int): Long {
        return createDate(year, month, day, 23, 59, 59)
    }

    // ========== SCENARIO 1: Known Data Verification ==========

    @Test
    fun `getStartOfDay returns exactly 00_00_00_000`() {
        // Given: A timestamp in the middle of the day
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate start of day
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        
        // Then: Should be exactly midnight
        val expected = createDate(2024, 4, 15, 0, 0, 0)
        assertEquals(expected, startOfDay)
    }

    @Test
    fun `getEndOfDay returns exactly 00_00_00_000 next day`() {
        // Given: A timestamp
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate end of day
        val endOfDay = TimePeriodUtils.getEndOfDay(timestamp)
        
        // Then: Should be midnight of next day
        val expected = createDate(2024, 4, 16, 0, 0, 0)
        assertEquals(expected, endOfDay)
    }

    @Test
    fun `getStartOfWeek returns Monday 00_00_00_000`() {
        // Given: A Wednesday (April 17, 2024)
        val timestamp = createDate(2024, 4, 17, 10, 0, 0)
        
        // When: Calculate start of week
        val startOfWeek = TimePeriodUtils.getStartOfWeek(timestamp)
        
        // Then: Should be Monday April 15, 2024 at 00:00:00
        val expected = createDate(2024, 4, 15, 0, 0, 0)
        assertEquals(expected, startOfWeek)
    }

    @Test
    fun `getStartOfWeek handles Sunday correctly`() {
        // Given: A Sunday (April 14, 2024)
        val timestamp = createDate(2024, 4, 14, 10, 0, 0)
        
        // When: Calculate start of week
        val startOfWeek = TimePeriodUtils.getStartOfWeek(timestamp)
        
        // Then: Should be Monday April 8, 2024 at 00:00:00
        val expected = createDate(2024, 4, 8, 0, 0, 0)
        assertEquals(expected, startOfWeek)
    }

    @Test
    fun `getStartOfMonth returns 1st at 00_00_00_000`() {
        // Given: A timestamp in the middle of the month
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate start of month
        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        
        // Then: Should be April 1, 2024 at 00:00:00
        val expected = createDate(2024, 4, 1, 0, 0, 0)
        assertEquals(expected, startOfMonth)
    }

    @Test
    fun `getEndOfMonth returns 1st of next month at 00_00_00_000`() {
        // Given: A timestamp in April
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate end of month
        val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
        
        // Then: Should be May 1, 2024 at 00:00:00
        val expected = createDate(2024, 5, 1, 0, 0, 0)
        assertEquals(expected, endOfMonth)
    }

    @Test
    fun `getMonthRange returns correct start and end for current month`() {
        // Given: A timestamp in April 2024
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate month range (0 offset = current month)
        val (start, end) = TimePeriodUtils.getMonthRange(timestamp, 0)
        
        // Then: Should be April 1 to May 1
        assertEquals(createDate(2024, 4, 1, 0, 0, 0), start)
        assertEquals(createDate(2024, 5, 1, 0, 0, 0), end)
    }

    @Test
    fun `getMonthRange returns correct start and end for previous month`() {
        // Given: A timestamp in April 2024
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate month range (-1 offset = previous month)
        val (start, end) = TimePeriodUtils.getMonthRange(timestamp, -1)
        
        // Then: Should be March 1 to April 1
        assertEquals(createDate(2024, 3, 1, 0, 0, 0), start)
        assertEquals(createDate(2024, 4, 1, 0, 0, 0), end)
    }

    @Test
    fun `getLastNDaysRange returns correct 30-day range`() {
        // Given: April 2, 2024 at noon
        val now = createDate(2024, 4, 2, 12, 0, 0)
        
        // When: Calculate last 30 days range
        val (start, end) = TimePeriodUtils.getLastNDaysRange(now, 30)
        
        // Then: Should be March 3, 2024 at 00:00:00 to April 2, 2024 at 12:00:00
        assertEquals(createDate(2024, 3, 3, 0, 0, 0), start)
        assertEquals(now, end)
    }

    @Test
    fun `getLastNDaysRange returns correct 7-day range`() {
        // Given: April 2, 2024 at noon
        val now = createDate(2024, 4, 2, 12, 0, 0)
        
        // When: Calculate last 7 days range
        val (start, end) = TimePeriodUtils.getLastNDaysRange(now, 7)
        
        // Then: Should be March 26, 2024 at 00:00:00 to April 2, 2024 at 12:00:00
        assertEquals(createDate(2024, 3, 26, 0, 0, 0), start)
        assertEquals(now, end)
    }

    // ========== SCENARIO 2: Period Boundary Testing ==========

    @Test
    fun `transaction at exactly 00_00_00_000 is included in that day`() {
        // Given: Transaction exactly at midnight
        val transactionTime = createDate(2024, 4, 15, 0, 0, 0)
        
        // When: Calculate day boundaries
        val startOfDay = TimePeriodUtils.getStartOfDay(transactionTime)
        val endOfDay = TimePeriodUtils.getEndOfDay(transactionTime)
        
        // Then: Transaction should be in range [startOfDay, endOfDay)
        assertTrue(transactionTime >= startOfDay)
        assertTrue(transactionTime < endOfDay)
    }

    @Test
    fun `transaction at 23_59_59_999 is included in that day`() {
        // Given: Transaction at 23:59:59.999
        val cal = Calendar.getInstance()
        cal.set(2024, 3, 15, 23, 59, 59) // April 15
        cal.set(Calendar.MILLISECOND, 999)
        val transactionTime = cal.timeInMillis
        
        // When: Calculate day boundaries
        val startOfDay = TimePeriodUtils.getStartOfDay(transactionTime)
        val endOfDay = TimePeriodUtils.getEndOfDay(transactionTime)
        
        // Then: Transaction should be in range [startOfDay, endOfDay)
        assertTrue(transactionTime >= startOfDay)
        assertTrue(transactionTime < endOfDay)
    }

    @Test
    fun `transaction on month boundary 31st is included in that month`() {
        // Given: Transaction on March 31, 2024 at 23:59:59
        val transactionTime = createDateAtEndOfDay(2024, 3, 31)
        
        // When: Calculate month boundaries for March
        val (start, end) = TimePeriodUtils.getMonthRange(transactionTime, 0)
        
        // Then: Transaction should be in range [start, end)
        assertTrue(transactionTime >= start)
        assertTrue(transactionTime < end)
    }

    @Test
    fun `transaction on month boundary 1st is included in that month`() {
        // Given: Transaction on April 1, 2024 at 00:00:00
        val transactionTime = createDateAtMidnight(2024, 4, 1)
        
        // When: Calculate month boundaries for April
        val (start, end) = TimePeriodUtils.getMonthRange(transactionTime, 0)
        
        // Then: Transaction should be in range [start, end)
        assertTrue(transactionTime >= start)
        assertTrue(transactionTime < end)
    }

    @Test
    fun `transaction on year boundary Dec 31 is included in that year`() {
        // Given: Transaction on December 31, 2024 at 23:59:59
        val transactionTime = createDateAtEndOfDay(2024, 12, 31)
        
        // When: Calculate year boundaries for 2024
        val (start, end) = TimePeriodUtils.getYearRange(transactionTime, 0)
        
        // Then: Transaction should be in range [start, end)
        assertTrue(transactionTime >= start)
        assertTrue(transactionTime < end)
    }

    @Test
    fun `transaction on year boundary Jan 1 is included in that year`() {
        // Given: Transaction on January 1, 2024 at 00:00:00
        val transactionTime = createDateAtMidnight(2024, 1, 1)
        
        // When: Calculate year boundaries for 2024
        val (start, end) = TimePeriodUtils.getYearRange(transactionTime, 0)
        
        // Then: Transaction should be in range [start, end)
        assertTrue(transactionTime >= start)
        assertTrue(transactionTime < end)
    }

    // ========== SCENARIO 3: Rolling Window Accuracy ==========

    @Test
    fun `month period calculation for April 2 shows last 30 days not just April`() {
        // Given: Today is April 2, 2024
        val today = createDate(2024, 4, 2, 12, 0, 0)
        
        // When: Calculate "Month" period (which uses getLastNDaysRange with 30 days)
        val (start, end) = TimePeriodUtils.getLastNDaysRange(today, 30)
        
        // Then: Should be March 3 to April 2 (not April 1 to April 2)
        assertEquals(createDate(2024, 3, 3, 0, 0, 0), start)
        assertEquals(today, end)
        
        // Verify this is 30 days back from April 2
        val daysBetween = TimePeriodUtils.daysBetween(start, end)
        assertEquals(30, daysBetween)
    }

    @Test
    fun `week period calculation for April 2 shows last 7 days`() {
        // Given: Today is April 2, 2024
        val today = createDate(2024, 4, 2, 12, 0, 0)
        
        // When: Calculate "Week" period (which uses getLastNDaysRange with 7 days)
        val (start, end) = TimePeriodUtils.getLastNDaysRange(today, 7)
        
        // Then: Should be March 26 to April 2
        assertEquals(createDate(2024, 3, 26, 0, 0, 0), start)
        assertEquals(today, end)
        
        // Verify this is 7 days back from April 2
        val daysBetween = TimePeriodUtils.daysBetween(start, end)
        assertEquals(7, daysBetween)
    }

    @Test
    fun `transactions outside 30-day window are excluded`() {
        // Given: A 30-day window from March 3 to April 2
        val now = createDate(2024, 4, 2, 12, 0, 0)
        val (windowStart, windowEnd) = TimePeriodUtils.getLastNDaysRange(now, 30)
        
        // When: Transaction is on March 2 (outside window)
        val outsideTransaction = createDate(2024, 3, 2, 10, 0, 0)
        
        // Then: Should not be in range
        assertTrue(outsideTransaction < windowStart)
    }

    @Test
    fun `transactions inside 30-day window are included`() {
        // Given: A 30-day window from March 3 to April 2
        val now = createDate(2024, 4, 2, 12, 0, 0)
        val (windowStart, windowEnd) = TimePeriodUtils.getLastNDaysRange(now, 30)
        
        // When: Transaction is on March 15 (inside window)
        val insideTransaction = createDate(2024, 3, 15, 10, 0, 0)
        
        // Then: Should be in range
        assertTrue(insideTransaction >= windowStart)
        assertTrue(insideTransaction < windowEnd)
    }

    // ========== SCENARIO 4: Daily Average Calculation ==========

    @Test
    fun `daysBetween calculates correctly for exact days`() {
        // Given: Start and end dates 10 days apart
        val start = createDate(2024, 4, 1, 0, 0, 0)
        val end = createDate(2024, 4, 11, 0, 0, 0)
        
        // When: Calculate days between
        val days = TimePeriodUtils.daysBetween(start, end)
        
        // Then: Should be exactly 10 days
        assertEquals(10, days)
    }

    @Test
    fun `daysBetween ignores time of day`() {
        // Given: Start at 23:59, end at 00:01 next day
        val start = createDate(2024, 4, 1, 23, 59, 0)
        val end = createDate(2024, 4, 2, 0, 1, 0)
        
        // When: Calculate days between
        val days = TimePeriodUtils.daysBetween(start, end)
        
        // Then: Should be 1 day (ignores time)
        assertEquals(1, days)
    }

    @Test
    fun `daysBetween handles DST transition correctly`() {
        // Given: Dates across DST transition (March 10, 2024 in US)
        val start = createDate(2024, 3, 9, 12, 0, 0)
        val end = createDate(2024, 3, 11, 12, 0, 0)
        
        // When: Calculate days between
        val days = TimePeriodUtils.daysBetween(start, end)
        
        // Then: Should be 2 days (DST doesn't affect day count)
        assertEquals(2, days)
    }

    // ========== SCENARIO 5: Empty Period Handling ==========

    @Test
    fun `getStartOfYear returns January 1st at midnight`() {
        // Given: Any timestamp in 2024
        val timestamp = createDate(2024, 6, 15, 14, 30, 45)
        
        // When: Calculate start of year
        val startOfYear = TimePeriodUtils.getStartOfYear(timestamp)
        
        // Then: Should be January 1, 2024 at 00:00:00
        val expected = createDate(2024, 1, 1, 0, 0, 0)
        assertEquals(expected, startOfYear)
    }

    @Test
    fun `getEndOfYear returns January 1st next year at midnight`() {
        // Given: Any timestamp in 2024
        val timestamp = createDate(2024, 6, 15, 14, 30, 45)
        
        // When: Calculate end of year
        val endOfYear = TimePeriodUtils.getEndOfYear(timestamp)
        
        // Then: Should be January 1, 2025 at 00:00:00
        val expected = createDate(2025, 1, 1, 0, 0, 0)
        assertEquals(expected, endOfYear)
    }

    @Test
    fun `getYearRange returns correct start and end for current year`() {
        // Given: Any timestamp in 2024
        val timestamp = createDate(2024, 6, 15, 14, 30, 45)
        
        // When: Calculate year range (0 offset = current year)
        val (start, end) = TimePeriodUtils.getYearRange(timestamp, 0)
        
        // Then: Should be Jan 1, 2024 to Jan 1, 2025
        assertEquals(createDate(2024, 1, 1, 0, 0, 0), start)
        assertEquals(createDate(2025, 1, 1, 0, 0, 0), end)
    }

    @Test
    fun `getYearRange returns correct start and end for previous year`() {
        // Given: Any timestamp in 2024
        val timestamp = createDate(2024, 6, 15, 14, 30, 45)
        
        // When: Calculate year range (-1 offset = previous year)
        val (start, end) = TimePeriodUtils.getYearRange(timestamp, -1)
        
        // Then: Should be Jan 1, 2023 to Jan 1, 2024
        assertEquals(createDate(2023, 1, 1, 0, 0, 0), start)
        assertEquals(createDate(2024, 1, 1, 0, 0, 0), end)
    }

    // ========== SCENARIO 6: Quarter Calculations ==========

    @Test
    fun `getStartOfQuarter returns correct for Q1`() {
        // Given: Timestamp in February 2024 (Q1)
        val timestamp = createDate(2024, 2, 15, 14, 30, 45)
        
        // When: Calculate start of quarter
        val startOfQuarter = TimePeriodUtils.getStartOfQuarter(timestamp)
        
        // Then: Should be January 1, 2024 at 00:00:00
        val expected = createDate(2024, 1, 1, 0, 0, 0)
        assertEquals(expected, startOfQuarter)
    }

    @Test
    fun `getStartOfQuarter returns correct for Q2`() {
        // Given: Timestamp in May 2024 (Q2)
        val timestamp = createDate(2024, 5, 15, 14, 30, 45)
        
        // When: Calculate start of quarter
        val startOfQuarter = TimePeriodUtils.getStartOfQuarter(timestamp)
        
        // Then: Should be April 1, 2024 at 00:00:00
        val expected = createDate(2024, 4, 1, 0, 0, 0)
        assertEquals(expected, startOfQuarter)
    }

    @Test
    fun `getStartOfQuarter returns correct for Q3`() {
        // Given: Timestamp in August 2024 (Q3)
        val timestamp = createDate(2024, 8, 15, 14, 30, 45)
        
        // When: Calculate start of quarter
        val startOfQuarter = TimePeriodUtils.getStartOfQuarter(timestamp)
        
        // Then: Should be July 1, 2024 at 00:00:00
        val expected = createDate(2024, 7, 1, 0, 0, 0)
        assertEquals(expected, startOfQuarter)
    }

    @Test
    fun `getStartOfQuarter returns correct for Q4`() {
        // Given: Timestamp in November 2024 (Q4)
        val timestamp = createDate(2024, 11, 15, 14, 30, 45)
        
        // When: Calculate start of quarter
        val startOfQuarter = TimePeriodUtils.getStartOfQuarter(timestamp)
        
        // Then: Should be October 1, 2024 at 00:00:00
        val expected = createDate(2024, 10, 1, 0, 0, 0)
        assertEquals(expected, startOfQuarter)
    }

    @Test
    fun `getEndOfQuarter returns correct for Q1`() {
        // Given: Timestamp in February 2024 (Q1)
        val timestamp = createDate(2024, 2, 15, 14, 30, 45)
        
        // When: Calculate end of quarter
        val endOfQuarter = TimePeriodUtils.getEndOfQuarter(timestamp)
        
        // Then: Should be April 1, 2024 at 00:00:00 (start of Q2)
        val expected = createDate(2024, 4, 1, 0, 0, 0)
        assertEquals(expected, endOfQuarter)
    }

    @Test
    fun `getQuarterRange returns correct start and end for current quarter`() {
        // Given: Timestamp in May 2024 (Q2)
        val timestamp = createDate(2024, 5, 15, 14, 30, 45)
        
        // When: Calculate quarter range (0 offset = current quarter)
        val (start, end) = TimePeriodUtils.getQuarterRange(timestamp, 0)
        
        // Then: Should be April 1 to July 1
        assertEquals(createDate(2024, 4, 1, 0, 0, 0), start)
        assertEquals(createDate(2024, 7, 1, 0, 0, 0), end)
    }

    @Test
    fun `getQuarterRange returns correct start and end for previous quarter`() {
        // Given: Timestamp in May 2024 (Q2)
        val timestamp = createDate(2024, 5, 15, 14, 30, 45)
        
        // When: Calculate quarter range (-1 offset = previous quarter)
        val (start, end) = TimePeriodUtils.getQuarterRange(timestamp, -1)
        
        // Then: Should be January 1 to April 1
        assertEquals(createDate(2024, 1, 1, 0, 0, 0), start)
        assertEquals(createDate(2024, 4, 1, 0, 0, 0), end)
    }

    // ========== SCENARIO 7: Leap Year Handling ==========

    @Test
    fun `leap year February 29 is handled correctly`() {
        // Given: 2024 is a leap year
        val timestamp = createDate(2024, 2, 29, 12, 0, 0)
        
        // When: Calculate month boundaries
        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
        
        // Then: February 2024 should have 29 days
        assertEquals(createDate(2024, 2, 1, 0, 0, 0), startOfMonth)
        assertEquals(createDate(2024, 3, 1, 0, 0, 0), endOfMonth)
        
        // Verify days in month
        val daysInMonth = TimePeriodUtils.getDaysInMonth(timestamp)
        assertEquals(29, daysInMonth)
    }

    @Test
    fun `non-leap year February 28 is handled correctly`() {
        // Given: 2023 is not a leap year
        val timestamp = createDate(2023, 2, 28, 12, 0, 0)
        
        // When: Calculate month boundaries
        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
        
        // Then: February 2023 should have 28 days
        assertEquals(createDate(2023, 2, 1, 0, 0, 0), startOfMonth)
        assertEquals(createDate(2023, 3, 1, 0, 0, 0), endOfMonth)
        
        // Verify days in month
        val daysInMonth = TimePeriodUtils.getDaysInMonth(timestamp)
        assertEquals(28, daysInMonth)
    }

    // ========== SCENARIO 8: Week Calculations ==========

    @Test
    fun `getWeekRange returns Monday to Monday`() {
        // Given: A Wednesday (April 17, 2024)
        val timestamp = createDate(2024, 4, 17, 10, 0, 0)
        
        // When: Calculate week range
        val (start, end) = TimePeriodUtils.getWeekRange(timestamp, 0)
        
        // Then: Should be Monday April 15 to Monday April 22
        assertEquals(createDate(2024, 4, 15, 0, 0, 0), start)
        assertEquals(createDate(2024, 4, 22, 0, 0, 0), end)
    }

    @Test
    fun `getWeekRange with offset -1 returns previous week`() {
        // Given: A Wednesday (April 17, 2024)
        val timestamp = createDate(2024, 4, 17, 10, 0, 0)
        
        // When: Calculate week range with -1 offset
        val (start, end) = TimePeriodUtils.getWeekRange(timestamp, -1)
        
        // Then: Should be Monday April 8 to Monday April 15
        assertEquals(createDate(2024, 4, 8, 0, 0, 0), start)
        assertEquals(createDate(2024, 4, 15, 0, 0, 0), end)
    }

    @Test
    fun `getCanonicalWeekRangeFromKey handles year rollover deterministically`() {
        val dec31 = createDate(2025, 12, 31, 12, 0, 0)
        val year = TimePeriodUtils.getAppCalendarWeekYear(dec31)
        val week = TimePeriodUtils.getAppCalendarWeekNumber(dec31).toString().padStart(2, '0')
        val key = "$year-$week"

        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey(key)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `getWeekOfYear returns correct week number`() {
        // Given: January 1, 2024 (should be week 1)
        val timestamp = createDate(2024, 1, 1, 12, 0, 0)
        
        // When: Get week of year
        val weekOfYear = TimePeriodUtils.getWeekOfYear(timestamp)
        
        // Then: Should be week 1
        assertEquals(1, weekOfYear)
    }

    // ========== SCENARIO 9: Day of Week and Month Calculations ==========

    @Test
    fun `getDayOfMonth returns correct day`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get day of month
        val dayOfMonth = TimePeriodUtils.getDayOfMonth(timestamp)
        
        // Then: Should be 15
        assertEquals(15, dayOfMonth)
    }

    @Test
    fun `getDaysInMonth returns correct for April`() {
        // Given: Any day in April 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get days in month
        val daysInMonth = TimePeriodUtils.getDaysInMonth(timestamp)
        
        // Then: April has 30 days
        assertEquals(30, daysInMonth)
    }

    @Test
    fun `getDaysInMonth returns correct for December`() {
        // Given: Any day in December 2024
        val timestamp = createDate(2024, 12, 15, 12, 0, 0)
        
        // When: Get days in month
        val daysInMonth = TimePeriodUtils.getDaysInMonth(timestamp)
        
        // Then: December has 31 days
        assertEquals(31, daysInMonth)
    }

    @Test
    fun `getDaysRemainingInMonth calculates correctly`() {
        // Given: April 15, 2024 (30 days in April)
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get days remaining
        val daysRemaining = TimePeriodUtils.getDaysRemainingInMonth(timestamp)
        
        // Then: Should be 15 days remaining (30 - 15)
        assertEquals(15, daysRemaining)
    }

    @Test
    fun `getDayIndexFromMonthStart returns 0-based index`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get day index from month start
        val dayIndex = TimePeriodUtils.getDayIndexFromMonthStart(timestamp)
        
        // Then: Should be 14 (0-based, so day 1 = index 0)
        assertEquals(14, dayIndex)
    }

    // ========== SCENARIO 10: Date Arithmetic ==========

    @Test
    fun `addDays adds correctly`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Add 10 days
        val result = TimePeriodUtils.addDays(timestamp, 10)
        
        // Then: Should be April 25, 2024
        assertEquals(createDate(2024, 4, 25, 12, 0, 0), result)
    }

    @Test
    fun `addDays subtracts correctly with negative value`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Subtract 10 days
        val result = TimePeriodUtils.addDays(timestamp, -10)
        
        // Then: Should be April 5, 2024
        assertEquals(createDate(2024, 4, 5, 12, 0, 0), result)
    }

    @Test
    fun `addMonths adds correctly`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Add 3 months
        val result = TimePeriodUtils.addMonths(timestamp, 3)
        
        // Then: Should be July 15, 2024
        assertEquals(createDate(2024, 7, 15, 12, 0, 0), result)
    }

    @Test
    fun `addMonths handles year boundary`() {
        // Given: November 15, 2024
        val timestamp = createDate(2024, 11, 15, 12, 0, 0)
        
        // When: Add 3 months
        val result = TimePeriodUtils.addMonths(timestamp, 3)
        
        // Then: Should be February 15, 2025
        assertEquals(createDate(2025, 2, 15, 12, 0, 0), result)
    }

    @Test
    fun `addYears adds correctly`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Add 2 years
        val result = TimePeriodUtils.addYears(timestamp, 2)
        
        // Then: Should be April 15, 2026
        assertEquals(createDate(2026, 4, 15, 12, 0, 0), result)
    }

    // ========== SCENARIO 11: Consistency Checks ==========

    @Test
    fun `startOfDay and endOfDay are consistent`() {
        // Given: Any timestamp (non-DST date)
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate start and end of day
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        val endOfDay = TimePeriodUtils.getEndOfDay(timestamp)
        
        // Then: endOfDay should be exactly one calendar day after startOfDay
        // Use daysBetween for the contract check (DST-safe)
        assertEquals(1, TimePeriodUtils.daysBetween(startOfDay, endOfDay))
        // For non-DST dates, the difference should also be exactly 24h
        assertEquals(startOfDay + 24 * 60 * 60 * 1000, endOfDay)
    }

    @Test
    fun `startOfMonth and endOfMonth are consistent`() {
        // Given: Any timestamp
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        
        // When: Calculate start and end of month
        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
        
        // Then: endOfMonth should be start of next month
        assertEquals(TimePeriodUtils.addMonths(startOfMonth, 1), endOfMonth)
    }

    @Test
    fun `isSameMonth returns true for same month`() {
        // Given: Two timestamps in same month
        val timestamp1 = createDate(2024, 4, 1, 12, 0, 0)
        val timestamp2 = createDate(2024, 4, 30, 23, 59, 59)
        
        // When: Check if same month
        val result = TimePeriodUtils.isSameMonth(timestamp1, timestamp2)
        
        // Then: Should be true
        assertTrue(result)
    }

    @Test
    fun `isSameMonth returns false for different months`() {
        // Given: Two timestamps in different months
        val timestamp1 = createDate(2024, 4, 30, 23, 59, 59)
        val timestamp2 = createDate(2024, 5, 1, 0, 0, 0)
        
        // When: Check if same month
        val result = TimePeriodUtils.isSameMonth(timestamp1, timestamp2)
        
        // Then: Should be false
        assertTrue(!result)
    }

    // ========== SCENARIO 12: Edge Cases ==========

    @Test
    fun `getStartOfDay handles DST spring forward`() {
        // Given: March 10, 2024 at 2:30 AM (during DST spring forward in US)
        // Note: This test may behave differently based on timezone
        val timestamp = createDate(2024, 3, 10, 2, 30, 0)
        
        // When: Calculate start of day
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        
        // Then: Should be March 10, 2024 at 00:00:00
        val expected = createDate(2024, 3, 10, 0, 0, 0)
        assertEquals(expected, startOfDay)
    }

    @Test
    fun `getStartOfDay handles DST fall back`() {
        // Given: November 3, 2024 at 1:30 AM (during DST fall back in US)
        // Note: This test may behave differently based on timezone
        val timestamp = createDate(2024, 11, 3, 1, 30, 0)
        
        // When: Calculate start of day
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        
        // Then: Should be November 3, 2024 at 00:00:00
        val expected = createDate(2024, 11, 3, 0, 0, 0)
        assertEquals(expected, startOfDay)
    }

    @Test
    fun `getStartOfMonth handles month with different days`() {
        // Given: January 31, 2024
        val timestamp = createDate(2024, 1, 31, 12, 0, 0)
        
        // When: Calculate start of month
        val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
        
        // Then: Should be January 1, 2024
        assertEquals(createDate(2024, 1, 1, 0, 0, 0), startOfMonth)
    }

    @Test
    fun `getYear returns correct year`() {
        // Given: December 31, 2024 at 23:59:59
        val timestamp = createDate(2024, 12, 31, 23, 59, 59)
        
        // When: Get year
        val year = TimePeriodUtils.getYear(timestamp)
        
        // Then: Should be 2024
        assertEquals(2024, year)
    }

    @Test
    fun `getMonth returns correct month (0-indexed)`() {
        // Given: April 15, 2024
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get month
        val month = TimePeriodUtils.getMonth(timestamp)
        
        // Then: Should be 3 (0-indexed, so April = 3)
        assertEquals(3, month)
    }

    @Test
    fun `getDayOfWeek returns correct day`() {
        // Given: April 15, 2024 is a Monday
        val timestamp = createDate(2024, 4, 15, 12, 0, 0)
        
        // When: Get day of week
        val dayOfWeek = TimePeriodUtils.getDayOfWeek(timestamp)
        
        // Then: Should be Monday (Calendar.MONDAY = 2)
        assertEquals(Calendar.MONDAY, dayOfWeek)
    }

    @Test
    fun `getHourOfDay returns correct hour`() {
        // Given: April 15, 2024 at 14:30
        val timestamp = createDate(2024, 4, 15, 14, 30, 0)
        
        // When: Get hour of day
        val hourOfDay = TimePeriodUtils.getHourOfDay(timestamp)
        
        // Then: Should be 14
        assertEquals(14, hourOfDay)
    }

    // ========== SCENARIO 13: getDayRange Helper ==========

    @Test
    fun `getDayRange returns same boundaries as individual start and end calls`() {
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        val (start, end) = TimePeriodUtils.getDayRange(timestamp)
        assertEquals(TimePeriodUtils.getStartOfDay(timestamp), start)
        assertEquals(TimePeriodUtils.getEndOfDay(timestamp), end)
    }

    @Test
    fun `getDayRange contains any timestamp within that day`() {
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        val (start, end) = TimePeriodUtils.getDayRange(timestamp)
        assertTrue(TimePeriodUtils.isInRange(timestamp, start, end))
    }

    @Test
    fun `getDayRange excludes start of next day`() {
        val timestamp = createDate(2024, 4, 15, 14, 30, 45)
        val (start, end) = TimePeriodUtils.getDayRange(timestamp)
        // end is start of next day; should NOT be in this day's range
        assertTrue(!TimePeriodUtils.isInRange(end, start, end))
    }

    // ========== SCENARIO 14: getEndOfWeek Standalone Helper ==========

    @Test
    fun `getEndOfWeek returns next Monday at midnight`() {
        // Given: A Wednesday (April 17, 2024)
        val timestamp = createDate(2024, 4, 17, 10, 0, 0)
        
        // When: Calculate end of week
        val endOfWeek = TimePeriodUtils.getEndOfWeek(timestamp)
        
        // Then: Should be Monday April 22, 2024 at 00:00:00
        val expected = createDate(2024, 4, 22, 0, 0, 0)
        assertEquals(expected, endOfWeek)
    }

    @Test
    fun `getEndOfWeek on Sunday returns next Monday`() {
        // Given: A Sunday (April 14, 2024) — still part of the Mon Apr 8 week
        val timestamp = createDate(2024, 4, 14, 10, 0, 0)
        
        // When: Calculate end of week
        val endOfWeek = TimePeriodUtils.getEndOfWeek(timestamp)
        
        // Then: Should be Monday April 15, 2024 at 00:00:00
        val expected = createDate(2024, 4, 15, 0, 0, 0)
        assertEquals(expected, endOfWeek)
    }

    @Test
    fun `getEndOfWeek consistent with getWeekRange`() {
        val timestamp = createDate(2024, 4, 17, 10, 0, 0)
        val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(timestamp)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(timestamp)
        assertEquals(weekRangeEnd, endOfWeek)
    }

    // ========== SCENARIO 15: isInRange Canonical Containment ==========

    @Test
    fun `isInRange includes startInclusive`() {
        assertTrue(TimePeriodUtils.isInRange(100L, 100L, 200L))
    }

    @Test
    fun `isInRange excludes endExclusive`() {
        assertTrue(!TimePeriodUtils.isInRange(200L, 100L, 200L))
    }

    @Test
    fun `isInRange includes middle values`() {
        assertTrue(TimePeriodUtils.isInRange(150L, 100L, 200L))
    }

    @Test
    fun `isInRange excludes values before start`() {
        assertTrue(!TimePeriodUtils.isInRange(99L, 100L, 200L))
    }

    @Test
    fun `isInRange works for real month boundary`() {
        // Transaction on March 31 at 23:59:59 should be in March
        val transaction = createDateAtEndOfDay(2024, 3, 31)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(transaction)
        assertTrue(TimePeriodUtils.isInRange(transaction, monthStart, monthEnd))
    }

    @Test
    fun `isInRange excludes transaction on 1st of next month`() {
        // Transaction on April 1 at 00:00:00 should NOT be in March range
        val marchRef = createDate(2024, 3, 15, 12, 0, 0)
        val (marchStart, marchEnd) = TimePeriodUtils.getMonthRange(marchRef)
        val april1Midnight = createDateAtMidnight(2024, 4, 1)
        assertTrue(!TimePeriodUtils.isInRange(april1Midnight, marchStart, marchEnd))
    }

    // ========== SCENARIO 16: Calendar-aware Arithmetic Edge Cases ==========

    @Test
    fun `addMonths Jan 31 plus 1 month is Feb 29 in leap year`() {
        val jan31 = createDate(2024, 1, 31, 12, 0, 0)
        val result = TimePeriodUtils.addMonths(jan31, 1)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addMonths Jan 31 plus 1 month is Feb 28 in non-leap year`() {
        val jan31 = createDate(2023, 1, 31, 12, 0, 0)
        val result = TimePeriodUtils.addMonths(jan31, 1)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addDays crosses year boundary correctly`() {
        val dec30 = createDate(2024, 12, 30, 12, 0, 0)
        val result = TimePeriodUtils.addDays(dec30, 5)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(4, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addYears from leap day Feb 29 coerces to Feb 28 in non-leap year`() {
        val feb29 = createDate(2024, 2, 29, 12, 0, 0)
        val result = TimePeriodUtils.addYears(feb29, 1)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }
}
