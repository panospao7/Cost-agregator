package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ============================================================================
    // HALF-OPEN [startInclusive, endExclusive) CONTRACT TESTS
    // ============================================================================

    @Test
    fun `contract - getEndOfDay is exclusive (start of next day)`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 17, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endOfDay = TimePeriodUtils.getEndOfDay(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfDay }

        assertEquals(16, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
        assertEquals(0, resultCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `contract - day range covers exactly one calendar day`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
        val end = TimePeriodUtils.getEndOfDay(cal.timeInMillis)
        assertEquals(1, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `contract - timestamp at midnight is included in its own day`() {
        val midnight = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val startOfDay = TimePeriodUtils.getStartOfDay(midnight)
        val endOfDay = TimePeriodUtils.getEndOfDay(midnight)

        assertTrue("Midnight should be >= startOfDay", midnight >= startOfDay)
        assertTrue("Midnight should be < endOfDay", midnight < endOfDay)
    }

    @Test
    fun `contract - timestamp at 23_59_59_999 is included in its own day`() {
        val lateNight = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val startOfDay = TimePeriodUtils.getStartOfDay(lateNight)
        val endOfDay = TimePeriodUtils.getEndOfDay(lateNight)

        assertTrue("23:59:59.999 should be >= startOfDay", lateNight >= startOfDay)
        assertTrue("23:59:59.999 should be < endOfDay", lateNight < endOfDay)
    }

    @Test
    fun `contract - getEndOfMonth is exclusive (1st of next month)`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 15, 10, 0, 0)
        }
        val endOfMonth = TimePeriodUtils.getEndOfMonth(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfMonth }

        assertEquals(Calendar.APRIL, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `contract - getEndOfYear is exclusive (Jan 1st of next year)`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.NOVEMBER, 10, 10, 0, 0)
        }
        val endOfYear = TimePeriodUtils.getEndOfYear(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfYear }

        assertEquals(2025, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `contract - getEndOfQuarter is exclusive (1st of next quarter)`() {
        // Q2 (April-June 2024)
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MAY, 15, 10, 0, 0)
        }
        val endOfQuarter = TimePeriodUtils.getEndOfQuarter(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = endOfQuarter }

        assertEquals(Calendar.JULY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
    }

    // ============================================================================
    // isInRange HALF-OPEN CONTAINMENT HELPER
    // ============================================================================

    @Test
    fun `isInRange - timestamp at startInclusive is included`() {
        val start = 1000L
        val end = 2000L
        assertTrue(TimePeriodUtils.isInRange(1000L, start, end))
    }

    @Test
    fun `isInRange - timestamp at endExclusive is excluded`() {
        val start = 1000L
        val end = 2000L
        assertFalse(TimePeriodUtils.isInRange(2000L, start, end))
    }

    @Test
    fun `isInRange - timestamp in middle is included`() {
        val start = 1000L
        val end = 2000L
        assertTrue(TimePeriodUtils.isInRange(1500L, start, end))
    }

    @Test
    fun `isInRange - timestamp before start is excluded`() {
        val start = 1000L
        val end = 2000L
        assertFalse(TimePeriodUtils.isInRange(999L, start, end))
    }

    @Test
    fun `isInRange - works with real day boundaries`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = cal.timeInMillis
        val (dayStart, dayEnd) = TimePeriodUtils.getDayRange(ts)

        assertTrue("Timestamp should be in its own day range",
            TimePeriodUtils.isInRange(ts, dayStart, dayEnd))

        // endExclusive (start of next day) should NOT be in this day range
        assertFalse("Exclusive end should not be in this day range",
            TimePeriodUtils.isInRange(dayEnd, dayStart, dayEnd))
    }

    @Test
    fun `isInRange - works with real month boundaries`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val ts = cal.timeInMillis
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(ts)

        assertTrue("Last ms of month should be in range",
            TimePeriodUtils.isInRange(ts, monthStart, monthEnd))
        assertFalse("Start of next month should NOT be in range",
            TimePeriodUtils.isInRange(monthEnd, monthStart, monthEnd))
    }

    // ============================================================================
    // getDayRange HELPER
    // ============================================================================

    @Test
    fun `getDayRange - returns same as getStartOfDay and getEndOfDay`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val ts = cal.timeInMillis
        val (start, end) = TimePeriodUtils.getDayRange(ts)

        assertEquals(TimePeriodUtils.getStartOfDay(ts), start)
        assertEquals(TimePeriodUtils.getEndOfDay(ts), end)
    }

    @Test
    fun `getDayRange - spans exactly 1 calendar day`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 29, 10, 0, 0) // Leap day
            set(Calendar.MILLISECOND, 0)
        }
        val (start, end) = TimePeriodUtils.getDayRange(cal.timeInMillis)
        assertEquals(1, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `getDayRange - consecutive days are contiguous`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val (_, day1End) = TimePeriodUtils.getDayRange(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_MONTH, 1) // Jan 1, 2025
        val (day2Start, _) = TimePeriodUtils.getDayRange(cal.timeInMillis)

        assertEquals("Year-boundary day ranges must be contiguous", day1End, day2Start)
    }

    // ============================================================================
    // getEndOfWeek STANDALONE HELPER
    // ============================================================================

    @Test
    fun `getEndOfWeek - returns next Monday`() {
        val wednesday = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 3, 12, 0, 0) // Wednesday
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfWeek = TimePeriodUtils.getEndOfWeek(wednesday)
        val endCal = Calendar.getInstance().apply { timeInMillis = endOfWeek }

        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, endCal.get(Calendar.MINUTE))
        assertEquals(0, endCal.get(Calendar.SECOND))
        assertEquals(0, endCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `getEndOfWeek - consistent with getWeekRange`() {
        val ts = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 12, 15, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(ts)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(ts)

        assertEquals("getEndOfWeek must match getWeekRange end", weekRangeEnd, endOfWeek)
    }

    @Test
    fun `getEndOfWeek - is exactly 7 calendar days after getStartOfWeek`() {
        val ts = Calendar.getInstance().apply {
            set(2024, Calendar.APRIL, 14, 10, 0, 0) // Sunday
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val startOfWeek = TimePeriodUtils.getStartOfWeek(ts)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(ts)
        assertEquals(7, TimePeriodUtils.daysBetween(startOfWeek, endOfWeek))
    }

    @Test
    fun `getCanonicalWeekRangeFromKey returns Monday-start next-Monday-exclusive`() {
        val year = 2024
        val week = 14
        val key = "$year-${week.toString().padStart(2, '0')}"

        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey(key)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `getCanonicalWeekRangeFromKey maps SQLite key 2024-53 to Dec 30 2024 Monday`() {
        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2024-53")
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(2024, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, startCal.get(Calendar.MONTH))
        assertEquals(30, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))

        assertEquals(2025, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
        assertEquals(6, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `getCanonicalWeekRangeFromKey maps SQLite key 2025-00 to last week of 2024`() {
        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2025-00")
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(2024, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, startCal.get(Calendar.MONTH))
        assertEquals(30, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))

        assertEquals(2025, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
        assertEquals(6, endCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `year rollover dates Dec30 Dec31 Jan1 Jan5 map to correct SQLite week keys`() {
        val dec30 = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 30, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dec31 = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val jan1 = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val jan5 = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 5, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val (week53Start, week53End) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2024-53")
        val (week00Start, week00End) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2025-00")

        assertTrue(TimePeriodUtils.isInRange(dec30, week53Start, week53End))
        assertTrue(TimePeriodUtils.isInRange(dec31, week53Start, week53End))
        assertTrue(TimePeriodUtils.isInRange(jan1, week00Start, week00End))
        assertTrue(TimePeriodUtils.isInRange(jan5, week00Start, week00End))
        assertEquals("Both SQLite keys should normalize to the same canonical week", week53Start, week00Start)
        assertEquals(week53End, week00End)
    }

    @Test
    fun `canonical week range supports empty week intervals with stable boundaries`() {
        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2025-02")
        val previousWeekEnd = TimePeriodUtils.addDays(start, -7)
        val nextWeekStart = end

        assertEquals(7, TimePeriodUtils.daysBetween(start, end))
        assertFalse(TimePeriodUtils.isInRange(previousWeekEnd, start, end))
        assertFalse(TimePeriodUtils.isInRange(nextWeekStart, start, end))
    }

    @Test
    fun `month key helpers format parse and build inclusive range`() {
        val key = TimePeriodUtils.formatMonthKey(2026, 4)
        assertEquals("2026-04", key)

        val (year, month) = TimePeriodUtils.parseMonthKey("2025-12")
        assertEquals(2025, year)
        assertEquals(12, month)

        val keys = TimePeriodUtils.buildMonthKeyRange("2026-02", "2026-04")
        assertEquals(listOf("2026-02", "2026-03", "2026-04"), keys)
    }

    // ============================================================================
    // MONDAY-START WEEK CONTRACT TESTS
    // ============================================================================

    @Test
    fun `contract - getStartOfWeek always returns Monday`() {
        // Test all 7 days of a week starting from Monday Jan 1, 2024
        val monday = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 12, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        for (dayOffset in 0..6) {
            val dayTs = TimePeriodUtils.addDays(monday, dayOffset)
            val weekStart = TimePeriodUtils.getStartOfWeek(dayTs)
            val weekStartCal = Calendar.getInstance().apply { timeInMillis = weekStart }

            assertEquals(
                "Day offset $dayOffset should map to Monday",
                Calendar.MONDAY,
                weekStartCal.get(Calendar.DAY_OF_WEEK)
            )
        }
    }

    @Test
    fun `contract - getStartOfWeek is locale-independent`() {
        val originalTz = TimeZone.getDefault()
        try {
            // Test with a locale where weeks traditionally start on Sunday
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            val wednesday = Calendar.getInstance().apply {
                set(2024, Calendar.JANUARY, 3, 12, 0, 0) // Wednesday
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val weekStart = TimePeriodUtils.getStartOfWeek(wednesday)
            val weekStartCal = Calendar.getInstance().apply { timeInMillis = weekStart }

            assertEquals("Week start must be Monday regardless of locale",
                Calendar.MONDAY, weekStartCal.get(Calendar.DAY_OF_WEEK))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `contract - getWeekRange returns Monday to next Monday`() {
        val wednesday = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 3, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val (start, end) = TimePeriodUtils.getWeekRange(wednesday)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals("Week range should span 7 calendar days",
            7, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `contract - Sunday belongs to the previous Monday's week`() {
        // Sunday April 14, 2024 belongs to week of Monday April 8
        val sunday = Calendar.getInstance().apply {
            set(2024, Calendar.APRIL, 14, 10, 0, 0) // Sunday
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val weekStart = TimePeriodUtils.getStartOfWeek(sunday)
        val weekStartCal = Calendar.getInstance().apply { timeInMillis = weekStart }

        assertEquals(Calendar.MONDAY, weekStartCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, weekStartCal.get(Calendar.DAY_OF_MONTH)) // Monday April 8
    }

    // ============================================================================
    // CALENDAR-AWARE ARITHMETIC EDGE CASES
    // ============================================================================

    @Test
    fun `addMonths - Jan 31 plus 1 month coerces to Feb 28 or 29`() {
        // Leap year: Jan 31 2024 + 1 month → Feb 29 2024
        val jan31Leap = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val resultLeap = TimePeriodUtils.addMonths(jan31Leap, 1)
        val calLeap = Calendar.getInstance().apply { timeInMillis = resultLeap }
        assertEquals(Calendar.FEBRUARY, calLeap.get(Calendar.MONTH))
        assertEquals(29, calLeap.get(Calendar.DAY_OF_MONTH))

        // Non-leap year: Jan 31 2023 + 1 month → Feb 28 2023
        val jan31NonLeap = Calendar.getInstance().apply {
            set(2023, Calendar.JANUARY, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val resultNonLeap = TimePeriodUtils.addMonths(jan31NonLeap, 1)
        val calNonLeap = Calendar.getInstance().apply { timeInMillis = resultNonLeap }
        assertEquals(Calendar.FEBRUARY, calNonLeap.get(Calendar.MONTH))
        assertEquals(28, calNonLeap.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addMonths - March 31 minus 1 month coerces to Feb 29 in leap year`() {
        val march31 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val result = TimePeriodUtils.addMonths(march31, -1)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addDays - crosses year boundary`() {
        val dec30 = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 30, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val result = TimePeriodUtils.addDays(dec30, 5)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(4, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addYears - Feb 29 plus 1 year coerces to Feb 28`() {
        val feb29 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 29, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val result = TimePeriodUtils.addYears(feb29, 1)
        val cal = Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }

    // ============================================================================
    // QUARTER BOUNDARY Q4 → Q1 NEXT YEAR
    // ============================================================================

    @Test
    fun `contract - Q4 endExclusive is Jan 1 next year`() {
        val nov = Calendar.getInstance().apply {
            set(2024, Calendar.NOVEMBER, 15, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endQ4 = TimePeriodUtils.getEndOfQuarter(nov)
        val cal = Calendar.getInstance().apply { timeInMillis = endQ4 }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `contract - Q4 range contiguous with Q1 next year`() {
        val dec = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 20, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val (_, q4End) = TimePeriodUtils.getQuarterRange(dec, 0)
        // Q1 of 2025
        val jan2025 = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (q1Start, _) = TimePeriodUtils.getQuarterRange(jan2025, 0)

        assertEquals("Q4 2024 end must equal Q1 2025 start", q4End, q1Start)
    }

    // ============================================================================
    // WEEK-OF-YEAR / YEAR CONSISTENCY (ISSUE-1 Batch-1 fix)
    // ============================================================================

    /**
     * getWeekOfYear() now uses Calendar-based week numbering (Monday-start,
     * minimalDaysInFirstWeek=1) so it is always consistent with getYear().
     *
     * 2021-01-01 (Friday) — under this definition the week containing Jan 1
     * is always week 1 of that calendar year, so getYear() and getWeekOfYear()
     * both point to 2021.  No year-boundary drift when pairing them.
     */
    @Test
    fun `getWeekOfYear and getYear are consistent at year boundary - Jan 1 2021`() {
        val jan1_2021 = Calendar.getInstance().apply {
            set(2021, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val calendarYear = TimePeriodUtils.getYear(jan1_2021)
        val weekOfYear   = TimePeriodUtils.getWeekOfYear(jan1_2021)

        // Calendar year and week-of-year are derived from the same Calendar,
        // so the composite key is always valid (no "2021-W53" drift).
        assertEquals("Calendar year should be 2021", 2021, calendarYear)
        // Week 1 because the week containing Jan 1 is always week 1 under
        // minimalDaysInFirstWeek=1.
        assertEquals("Week-of-year should be 1 (calendar-consistent)", 1, weekOfYear)
    }

    /**
     * 2020-12-31 (Thursday) — last day of calendar year 2020.
     * getYear() → 2020; with minimalDaysInFirstWeek = 1 and firstDayOfWeek = MONDAY,
     * Dec 31 falls in the Mon Dec 28 – Sun Jan 3 week which contains Jan 1,
     * so getWeekOfYear() → 1 (week 1 of 2021).
     * The test validates that the week value is deterministic and in a valid range.
     */
    @Test
    fun `getWeekOfYear and getYear are consistent at year boundary - Dec 31 2020`() {
        val dec31_2020 = Calendar.getInstance().apply {
            set(2020, Calendar.DECEMBER, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("Calendar year should be 2020", 2020, TimePeriodUtils.getYear(dec31_2020))
        // With minimalDaysInFirstWeek = 1, Dec 31 2020 is in week 1 (of 2021)
        // because the Mon-Sun week containing Jan 1 has ≥1 day in the new year.
        val week = TimePeriodUtils.getWeekOfYear(dec31_2020)
        assertTrue(
            "Week should be 1, 52, or 53 for Dec 31 2020 (got $week)",
            week == 1 || week == 52 || week == 53
        )
    }

    /**
     * 2016-01-03 (Sunday) — still the last day of calendar week 1 of 2016
     * under Monday-start, minimalDaysInFirstWeek=1 (the week containing Jan 1
     * is week 1).  getYear() and getWeekOfYear() are consistent.
     */
    @Test
    fun `getWeekOfYear and getYear are consistent at year boundary - Jan 3 2016`() {
        val jan3_2016 = Calendar.getInstance().apply {
            set(2016, Calendar.JANUARY, 3, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val calendarYear = TimePeriodUtils.getYear(jan3_2016)
        val weekOfYear   = TimePeriodUtils.getWeekOfYear(jan3_2016)

        assertEquals("Calendar year should be 2016", 2016, calendarYear)
        // Under Monday-start/minimalDays=1 the week containing Jan 1 (Fri) is
        // week 1, so Jan 3 (Sun) is also in week 1 of 2016.
        assertEquals("Week-of-year should be 1 (calendar-consistent)", 1, weekOfYear)
    }

    /**
     * A mid-year date: getYear() and getWeekOfYear() are always consistent.
     * getWeekBasedYear() (ISO) must also match for dates far from year boundaries.
     */
    @Test
    fun `getWeekOfYear and getYear are consistent for mid-year date`() {
        val juneTs = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val calendarYear = TimePeriodUtils.getYear(juneTs)
        val weekOfYear   = TimePeriodUtils.getWeekOfYear(juneTs)

        assertEquals("Calendar year should be 2024", 2024, calendarYear)
        // Jun 15 2024 is well within the year — both Calendar and ISO agree week ~24
        assertTrue("Week should be in mid-year range", weekOfYear in 20..30)
    }

    /**
     * getWeekBasedYear() still returns ISO week-based year (for future consumer
     * migration).  For mid-year dates it matches getYear(); at boundaries it may
     * differ.  This test confirms the helper is still correct.
     */
    @Test
    fun `getWeekBasedYear still returns ISO week-based year for Jan 1 2021`() {
        val jan1_2021 = Calendar.getInstance().apply {
            set(2021, Calendar.JANUARY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // ISO week-based year for 2021-01-01 (Friday) is 2020 (ISO week 53/2020)
        assertEquals("ISO week-based year should be 2020", 2020,
            TimePeriodUtils.getWeekBasedYear(jan1_2021))
        // Calendar year is 2021
        assertEquals("Calendar year should be 2021", 2021,
            TimePeriodUtils.getYear(jan1_2021))
    }
}
