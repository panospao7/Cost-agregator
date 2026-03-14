package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Stress Test Suite for TimePeriodUtils
 * 
 * Goal: Break the date calculation logic with DST transitions,
 * timezone edge cases, and boundary conditions.
 * 
 * @author Hostile QA Engineer
 */
class TimePeriodUtilsStressTest {

    // ============================================================================
    // SECTION 1: DST TRANSITION TESTS
    // ============================================================================

    @Test
    fun `stress - DST spring forward March 2024`() {
        // March 31, 2024 - DST starts in Europe (clocks forward)
        // At 2:00 AM, clocks skip to 3:00 AM
        
        // Test a date around DST transition
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 30, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timestamp = cal.timeInMillis
        
        // Start of day should be midnight (accounting for DST)
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
    }

    @Test
    fun `stress - DST fall back October 2024`() {
        // October 27, 2024 - DST ends in Europe (clocks back)
        // At 3:00 AM, clocks go back to 2:00 AM
        
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.OCTOBER, 26, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timestamp = cal.timeInMillis
        
        val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
        val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `stress - getStartOfWeek during DST transition`() {
        // Test start of week calculation during DST change
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 28, 12, 0, 0) // Thursday before DST
            set(Calendar.MILLISECOND, 0)
        }
        
        val startOfWeek = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
        val weekCal = Calendar.getInstance().apply { timeInMillis = startOfWeek }
        
        // Should be Monday
        assertEquals(Calendar.MONDAY, weekCal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `stress - multiple days around DST spring`() {
        // Test multiple consecutive days around DST spring forward
        val march30 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 30, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val march31 = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, 31, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val april1 = Calendar.getInstance().apply {
            set(2024, Calendar.APRIL, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        // All should return midnight times
        val start1 = TimePeriodUtils.getStartOfDay(march30)
        val start2 = TimePeriodUtils.getStartOfDay(march31)
        val start3 = TimePeriodUtils.getStartOfDay(april1)
        
        // Around DST transitions, a day can be 23h, 24h, or 25h.
        assertTrue(start2 - start1 in 82_000_000..90_500_000)
        assertTrue(start3 - start2 in 82_000_000..90_500_000)
    }

    // ============================================================================
    // SECTION 2: TIMEZONE TESTS
    // ============================================================================

    @Test
    fun `stress - different timezone calculations`() {
        val originalTz = TimeZone.getDefault()
        
        try {
            // Test with different timezones
            val timestamps = listOf(
                "America/New_York",    // UTC-5/UTC-4
                "Europe/Athens",      // UTC+2/UTC+3
                "Asia/Tokyo",         // UTC+9
                "Pacific/Honolulu"    // UTC-10
            )
            
            timestamps.forEach { tz ->
                TimeZone.setDefault(TimeZone.getTimeZone(tz))
                
                val cal = Calendar.getInstance().apply {
                    set(2024, Calendar.JUNE, 15, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val ts = cal.timeInMillis
                
                val startOfDay = TimePeriodUtils.getStartOfDay(ts)
                val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
                
                // Should always be midnight in the given timezone
                assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, startCal.get(Calendar.MINUTE))
            }
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `stress - timezone with half hour offset`() {
        // Test with timezone that has 30-minute offset (e.g., India)
        val originalTz = TimeZone.getDefault()
        
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) // UTC+5:30
            
            val cal = Calendar.getInstance().apply {
                set(2024, Calendar.JUNE, 15, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val ts = cal.timeInMillis
            
            val startOfDay = TimePeriodUtils.getStartOfDay(ts)
            val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
            
            assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, startCal.get(Calendar.MINUTE))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ============================================================================
    // SECTION 3: LEAP YEAR TESTS
    // ============================================================================

    @Test
    fun `stress - February 29th leap year 2024`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 29, 12, 0, 0) // 2024 is leap year
            set(Calendar.MILLISECOND, 0)
        }
        
        val startOfDay = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        assertEquals(29, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, startCal.get(Calendar.MONTH))
        assertEquals(2024, startCal.get(Calendar.YEAR))
    }

    @Test
    fun `stress - February 28th non-leap year 2023`() {
        val cal = Calendar.getInstance().apply {
            set(2023, Calendar.FEBRUARY, 28, 12, 0, 0) // 2023 is NOT leap year
            set(Calendar.MILLISECOND, 0)
        }
        
        val daysInMonth = TimePeriodUtils.getDaysInMonth(cal.timeInMillis)
        assertEquals(28, daysInMonth)
        
        val endOfMonth = TimePeriodUtils.getEndOfMonth(cal.timeInMillis)
        val endCal = Calendar.getInstance().apply { timeInMillis = endOfMonth }
        
        assertEquals(28, endCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `stress - end of month for all months`() {
        // Test that getEndOfMonth works for all months
        val year = 2024 // Leap year
        
        for (month in 0..11) {
            val cal = Calendar.getInstance().apply {
                set(year, month, 15, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            
            val endOfMonth = TimePeriodUtils.getEndOfMonth(cal.timeInMillis)
            val endCal = Calendar.getInstance().apply { timeInMillis = endOfMonth }
            
            assertEquals(month, endCal.get(Calendar.MONTH))
            assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY))
            assertEquals(59, endCal.get(Calendar.MINUTE))
        }
    }

    @Test
    fun `stress - days in month for all months in leap year`() {
        val expectedDays = mapOf(
            Calendar.JANUARY to 31,
            Calendar.FEBRUARY to 29, // 2024 is leap year
            Calendar.MARCH to 31,
            Calendar.APRIL to 30,
            Calendar.MAY to 31,
            Calendar.JUNE to 30,
            Calendar.JULY to 31,
            Calendar.AUGUST to 31,
            Calendar.SEPTEMBER to 30,
            Calendar.OCTOBER to 31,
            Calendar.NOVEMBER to 30,
            Calendar.DECEMBER to 31
        )
        
        val year = 2024
        expectedDays.forEach { (month, expected) ->
            val cal = Calendar.getInstance().apply {
                set(year, month, 15, 12, 0, 0)
            }
            
            val days = TimePeriodUtils.getDaysInMonth(cal.timeInMillis)
            assertEquals(expected.toInt(), days)
        }
    }

    // ============================================================================
    // SECTION 4: WEEK CALCULATION TESTS
    // ============================================================================

    @Test
    fun `stress - getStartOfWeek for each day of week`() {
        val weekStart = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 12, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        // Test for each day of the week
        for (dayOffset in 0..6) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = weekStart
                add(Calendar.DAY_OF_MONTH, dayOffset)
            }
            val ts = cal.timeInMillis
            
            val startOfWeek = TimePeriodUtils.getStartOfWeek(ts)
            val startCal = Calendar.getInstance().apply { timeInMillis = startOfWeek }
            
            // Should always be Monday (day 2 in Calendar)
            assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        }
    }

    @Test
    fun `stress - week starting on Sunday`() {
        // Test what happens when week starts on Sunday (US default)
        val originalFirstDay = Calendar.getInstance().firstDayOfWeek
        
        try {
            Calendar.getInstance().firstDayOfWeek = Calendar.SUNDAY
            
            val cal = Calendar.getInstance().apply {
                set(2024, Calendar.JANUARY, 3, 12, 0, 0) // Wednesday
                set(Calendar.MILLISECOND, 0)
            }
            
            val startOfWeek = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
            val startCal = Calendar.getInstance().apply { timeInMillis = startOfWeek }
            
            // The function uses MONDAY internally regardless of locale
            assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        } finally {
            Calendar.getInstance().firstDayOfWeek = originalFirstDay
        }
    }

    // ============================================================================
    // SECTION 5: MONTH BOUNDARY TESTS
    // ============================================================================

    @Test
    fun `stress - month boundary January to February`() {
        // Jan 31 to Feb 1
        val jan31 = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        
        val startFeb = TimePeriodUtils.getStartOfMonth(jan31 + 1)
        val febCal = Calendar.getInstance().apply { timeInMillis = startFeb }
        
        assertEquals(Calendar.FEBRUARY, febCal.get(Calendar.MONTH))
        assertEquals(1, febCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `stress - month boundary February to March leap year`() {
        // Feb 29 to Mar 1 (leap year)
        val feb29 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 29, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        
        val startMar = TimePeriodUtils.getStartOfMonth(feb29 + 1)
        val marCal = Calendar.getInstance().apply { timeInMillis = startMar }
        
        assertEquals(Calendar.MARCH, marCal.get(Calendar.MONTH))
        assertEquals(1, marCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `stress - month boundary December to January`() {
        // Dec 31 to Jan 1
        val dec31 = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        
        val startJan = TimePeriodUtils.getStartOfMonth(dec31 + 1)
        val janCal = Calendar.getInstance().apply { timeInMillis = startJan }
        
        assertEquals(Calendar.JANUARY, janCal.get(Calendar.MONTH))
        assertEquals(1, janCal.get(Calendar.DAY_OF_MONTH))
    }

    // ============================================================================
    // SECTION 6: YEAR BOUNDARY TESTS
    // ============================================================================

    @Test
    fun `stress - year boundary December 31 to January 1`() {
        val dec31 = Calendar.getInstance().apply {
            set(2024, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        
        val start2025 = TimePeriodUtils.getStartOfYear(dec31 + 1)
        val yearCal = Calendar.getInstance().apply { timeInMillis = start2025 }
        
        assertEquals(2025, yearCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, yearCal.get(Calendar.MONTH))
        assertEquals(1, yearCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `stress - getEndOfYear for different years`() {
        listOf(2020, 2021, 2022, 2023, 2024).forEach { year ->
            val cal = Calendar.getInstance().apply {
                set(year, Calendar.JUNE, 15, 12, 0, 0)
            }
            
            val endOfYear = TimePeriodUtils.getEndOfYear(cal.timeInMillis)
            val endCal = Calendar.getInstance().apply { timeInMillis = endOfYear }
            
            assertEquals(year, endCal.get(Calendar.YEAR))
            assertEquals(Calendar.DECEMBER, endCal.get(Calendar.MONTH))
            assertEquals(31, endCal.get(Calendar.DAY_OF_MONTH))
        }
    }

    // ============================================================================
    // SECTION 7: MAGIC CONSTANT 86400000 TESTS
    // ============================================================================

    @Test
    fun `stress - getLastNDaysRange assumes 24h days`() {
        // Range should end at "now" and start at the start of the day exactly N days earlier.
        val june15 = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val range = TimePeriodUtils.getLastNDaysRange(june15, 7)
        assertEquals(june15, range.second)
        val expectedStart = Calendar.getInstance().apply {
            timeInMillis = june15
            add(Calendar.DAY_OF_MONTH, -7)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedStart, range.first)
    }

    @Test
    fun `stress - getDayIndexFromMonthStart uses constant`() {
        // Documenting the bug: uses 86400000L constant
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val index = TimePeriodUtils.getDayIndexFromMonthStart(cal.timeInMillis)
        
        // Should be 14 (0-indexed, so June 15 = day 14)
        assertEquals(14, index)
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - epoch timestamp`() {
        val startOfDay = TimePeriodUtils.getStartOfDay(0L)
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        // Should be Jan 1, 1970
        assertEquals(1970, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `stress - very far future timestamp`() {
        // Year 2100
        val cal = Calendar.getInstance().apply {
            set(2100, Calendar.DECEMBER, 31, 12, 0, 0)
        }
        
        val startOfDay = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
        val startCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `stress - negative timestamp before epoch`() {
        // Slightly before epoch
        val startOfDay = TimePeriodUtils.getStartOfDay(-1000L)
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        // Should still work (returns Dec 31, 1969)
        assertTrue(cal.get(Calendar.YEAR) <= 1970)
    }

    @Test
    fun `stress - midnight timestamp`() {
        // Exactly midnight
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val startOfDay = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
        
        assertEquals(cal.timeInMillis, startOfDay)
    }

    @Test
    fun `stress - end of day timestamp`() {
        // Exactly end of day
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        
        val endOfDay = TimePeriodUtils.getEndOfDay(cal.timeInMillis)
        
        assertEquals(cal.timeInMillis, endOfDay)
    }

    // ============================================================================
    // SECTION 9: QUARTER TESTS
    // ============================================================================

    @Test
    fun `stress - getStartOfQuarter for each quarter`() {
        val testCases = listOf(
            Calendar.JANUARY to Calendar.JANUARY,
            Calendar.APRIL to Calendar.APRIL,
            Calendar.JULY to Calendar.JULY,
            Calendar.OCTOBER to Calendar.OCTOBER
        )
        
        testCases.forEach { (month, expectedQuarterStart) ->
            val cal = Calendar.getInstance().apply {
                set(2024, month, 15, 12, 0, 0)
            }
            
            val startOfQuarter = TimePeriodUtils.getStartOfQuarter(cal.timeInMillis)
            val qCal = Calendar.getInstance().apply { timeInMillis = startOfQuarter }
            
            assertEquals(expectedQuarterStart, qCal.get(Calendar.MONTH))
            assertEquals(1, qCal.get(Calendar.DAY_OF_MONTH))
        }
    }

    @Test
    fun `stress - getEndOfQuarter for each quarter`() {
        val expectedEndMonths = listOf(
            Calendar.MARCH to 31,
            Calendar.JUNE to 30,
            Calendar.SEPTEMBER to 30,
            Calendar.DECEMBER to 31
        )
        
        expectedEndMonths.forEach { (month, expectedDay) ->
            val cal = Calendar.getInstance().apply {
                set(2024, month, 15, 12, 0, 0)
            }
            
            val endOfQuarter = TimePeriodUtils.getEndOfQuarter(cal.timeInMillis)
            val qCal = Calendar.getInstance().apply { timeInMillis = endOfQuarter }
            
            assertEquals(month, qCal.get(Calendar.MONTH))
            assertEquals(expectedDay, qCal.get(Calendar.DAY_OF_MONTH))
        }
    }

    // ============================================================================
    // SECTION 10: FUZZ TESTING
    // ============================================================================

    @Test
    fun `stress - fuzz random timestamps`() {
        repeat(1000) {
            val ts = Random.nextLong(0, System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
            
            try {
                TimePeriodUtils.getStartOfDay(ts)
                TimePeriodUtils.getEndOfDay(ts)
                TimePeriodUtils.getStartOfMonth(ts)
                TimePeriodUtils.getEndOfMonth(ts)
                TimePeriodUtils.getDaysInMonth(ts)
            } catch (e: Exception) {
                fail("Crashed with timestamp: $ts")
            }
        }
    }

    // ============================================================================
    // SECTION 11: REGRESSION TESTS
    // ============================================================================

    @Test
    fun `regression - basic getStartOfDay still works`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 14, 30, 45)
        }
        
        val startOfDay = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = startOfDay }
        
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resultCal.get(Calendar.MINUTE))
        assertEquals(0, resultCal.get(Calendar.SECOND))
    }

    @Test
    fun `regression - getStartOfMonth still works`() {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 20, 10, 0, 0)
        }
        
        val startOfMonth = TimePeriodUtils.getStartOfMonth(cal.timeInMillis)
        val resultCal = Calendar.getInstance().apply { timeInMillis = startOfMonth }
        
        assertEquals(2024, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
    }

    // ============================================================================
    // SECTION 12: KNOWN BUGS DOCUMENTATION
    // ============================================================================

    @Test
    fun `bug - magic constant 86400000 used for day calculations`() {
        // BUG: Multiple functions use hardcoded 86400000L constant
        // This assumes every day is exactly 24 hours
        // During DST transitions, days can be 23 or 25 hours
        
        // This test documents the bug location
        // Functions affected:
        // - getStartOfWeek (line 73)
        // - getLastNDaysRange (line 129)
        // - getDayIndexFromMonthStart (line 221)
        
        // Test around DST to see if bug manifests
        val originalTz = TimeZone.getDefault()
        
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            
            // DST starts March 10, 2024
            val aroundDST = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 10, 12, 0, 0)
            }.timeInMillis
            
            // The calculation should use Calendar.add(Calendar.DAY_OF_MONTH, -n) instead
            // but currently uses: timestamp - (days * 86400000L)
            
            // This test passes but documents the potential issue
            val range = TimePeriodUtils.getLastNDaysRange(aroundDST, 7)
            
            // The difference should be approximately 7 days
            // but may be off by an hour during DST
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }
}
