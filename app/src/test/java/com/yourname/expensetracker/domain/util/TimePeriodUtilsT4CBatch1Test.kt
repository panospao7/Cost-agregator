package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.TimeZone

/**
 * T4C Batch 1 — locks the migrated java.time implementations of the
 * [TimePeriodUtils] field accessors and week helpers to exact expected values.
 *
 * Design rules:
 * - Every timestamp is a **fixed UTC instant**; no wall clock, no sleep, no @Ignore.
 * - Any test that reads a zone-dependent value mutates the JVM default timezone
 *   under [GlobalTimeZoneTestLock] and restores the original zone in `finally`.
 * - Expected values are asserted **exactly**, either as hardcoded literals or as
 *   values computed independently with `java.time` — never by re-calling the
 *   production helpers.
 */
class TimePeriodUtilsT4CBatch1Test {

    private companion object {
        // Zone names exercised by the timezone matrix.
        val ZONES = listOf("UTC", "Asia/Kolkata", "America/New_York")
    }

    // Fixed UTC instants used throughout the suite. Epoch millis is a constant
    // for each instant regardless of the default timezone.
    private val jun15MidnightUtc = Instant.parse("2024-06-15T00:30:00Z").toEpochMilli()
    private val jun15LateUtc = Instant.parse("2024-06-15T23:45:00Z").toEpochMilli()
    private val jan1_2024MorningUtc = Instant.parse("2024-01-01T05:00:00Z").toEpochMilli()
    private val newYear2021Utc = Instant.parse("2021-01-01T00:30:00Z").toEpochMilli()
    private val jul4_2024MorningUtc = Instant.parse("2024-07-04T06:45:00Z").toEpochMilli()

    // Runs [block] under [zoneId] while holding the process-wide timezone lock.
    // The original default timezone is restored in `finally`.
    private fun <T> withZone(zoneId: String, block: () -> T): T {
        return GlobalTimeZoneTestLock.withLock {
            val original = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                block()
            } finally {
                TimeZone.setDefault(original)
            }
        }
    }

    // Deterministic epoch millis for the given calendar date at 12:00 UTC.
    private fun dateAtNoonUtc(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(LocalDate.of(year, month, day), LocalTime.NOON, ZoneOffset.UTC)
            .toInstant().toEpochMilli()

    // ============================================================================
    // getYear / getMonth / getDayOfMonth / getHourOfDay
    // ============================================================================

    @Test
    fun `getYear getMonth getDayOfMonth getHourOfDay match java time across zones`() {
        val samples = listOf(jun15MidnightUtc, jun15LateUtc, jan1_2024MorningUtc, newYear2021Utc)

        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    val zoned = Instant.ofEpochMilli(ts).atZone(ZoneId.of(zoneId))
                    assertEquals("getYear $zoneId ts=$ts", zoned.year, TimePeriodUtils.getYear(ts))
                    assertEquals("getMonth $zoneId ts=$ts", zoned.monthValue - 1, TimePeriodUtils.getMonth(ts))
                    assertEquals("getDayOfMonth $zoneId ts=$ts", zoned.dayOfMonth, TimePeriodUtils.getDayOfMonth(ts))
                    assertEquals("getHourOfDay $zoneId ts=$ts", zoned.hour, TimePeriodUtils.getHourOfDay(ts))
                }
            }
        }
    }

    @Test
    fun `getYear getMonth getDayOfMonth getHourOfDay match hardcoded local values`() {
        // Same instant 2024-06-15T00:30Z has different local dates/hours per zone.
        withZone("UTC") {
            assertEquals(2024, TimePeriodUtils.getYear(jun15MidnightUtc))
            assertEquals(5, TimePeriodUtils.getMonth(jun15MidnightUtc)) // June (0-based)
            assertEquals(15, TimePeriodUtils.getDayOfMonth(jun15MidnightUtc))
            assertEquals(0, TimePeriodUtils.getHourOfDay(jun15MidnightUtc))
        }
        withZone("Asia/Kolkata") {
            // +05:30 → 2024-06-15 06:00 local.
            assertEquals(2024, TimePeriodUtils.getYear(jun15MidnightUtc))
            assertEquals(5, TimePeriodUtils.getMonth(jun15MidnightUtc))
            assertEquals(15, TimePeriodUtils.getDayOfMonth(jun15MidnightUtc))
            assertEquals(6, TimePeriodUtils.getHourOfDay(jun15MidnightUtc))
        }
        withZone("America/New_York") {
            // June is EDT (UTC-4) → 2024-06-14 20:30 local.
            assertEquals(2024, TimePeriodUtils.getYear(jun15MidnightUtc))
            assertEquals(5, TimePeriodUtils.getMonth(jun15MidnightUtc))
            assertEquals(14, TimePeriodUtils.getDayOfMonth(jun15MidnightUtc))
            assertEquals(20, TimePeriodUtils.getHourOfDay(jun15MidnightUtc))
        }
        // Year boundary: 2021-01-01T00:30Z is still 2020-12-31 19:30 in New York (EST).
        withZone("America/New_York") {
            assertEquals(2020, TimePeriodUtils.getYear(newYear2021Utc))
            assertEquals(11, TimePeriodUtils.getMonth(newYear2021Utc)) // December
            assertEquals(31, TimePeriodUtils.getDayOfMonth(newYear2021Utc))
            assertEquals(19, TimePeriodUtils.getHourOfDay(newYear2021Utc))
        }
        withZone("Asia/Kolkata") {
            assertEquals(2021, TimePeriodUtils.getYear(newYear2021Utc))
            assertEquals(0, TimePeriodUtils.getMonth(newYear2021Utc)) // January
            assertEquals(1, TimePeriodUtils.getDayOfMonth(newYear2021Utc))
            assertEquals(6, TimePeriodUtils.getHourOfDay(newYear2021Utc))
        }
        // DST summer literal: 2024-07-04T06:45Z → EDT 02:45, Kolkata 12:15.
        withZone("America/New_York") {
            assertEquals(2, TimePeriodUtils.getHourOfDay(jul4_2024MorningUtc))
        }
        withZone("Asia/Kolkata") {
            assertEquals(12, TimePeriodUtils.getHourOfDay(jul4_2024MorningUtc))
        }
        // DST winter literal: 2024-01-01T05:00Z → EST 00:00.
        withZone("America/New_York") {
            assertEquals(0, TimePeriodUtils.getHourOfDay(jan1_2024MorningUtc))
        }
    }

    @Test
    fun `getHourOfDay matches legacy Calendar HOUR_OF_DAY semantics after migration`() {
        // Regression guard: the migrated java.time implementation must produce
        // exactly the same hours as the previous Calendar-based one, including
        // around DST transitions.
        val samples = listOf(
            jun15MidnightUtc,
            jun15LateUtc,
            jan1_2024MorningUtc,
            newYear2021Utc,
            jul4_2024MorningUtc,
            Instant.parse("2024-11-03T06:30:00Z").toEpochMilli(), // US fall-back day
            Instant.parse("2024-03-10T07:30:00Z").toEpochMilli()  // US spring-forward day
        )

        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    val legacy = Calendar.getInstance().apply { timeInMillis = ts }
                        .get(Calendar.HOUR_OF_DAY)
                    assertEquals("legacy HOUR_OF_DAY equivalence $zoneId ts=$ts", legacy, TimePeriodUtils.getHourOfDay(ts))
                }
            }
        }
    }

    // ============================================================================
    // getDayOfWeek — Calendar constants (SUNDAY = 1 … SATURDAY = 7)
    // ============================================================================

    @Test
    fun `getDayOfWeek preserves Calendar constants Sunday=1 through Saturday=7`() {
        withZone("UTC") {
            assertEquals(Calendar.MONDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 1))) // 2
            assertEquals(Calendar.TUESDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 2))) // 3
            assertEquals(Calendar.WEDNESDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 3))) // 4
            assertEquals(Calendar.THURSDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 4))) // 5
            assertEquals(Calendar.FRIDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 5))) // 6
            assertEquals(Calendar.SATURDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 6))) // 7
            assertEquals(Calendar.SUNDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2024, 1, 7))) // 1
        }
    }

    @Test
    fun `getDayOfWeek known boundary dates are Friday and Thursday`() {
        // Dates referenced by the utility's KDoc: 2021-01-01 is a Friday,
        // 2020-12-31 is a Thursday.
        withZone("UTC") {
            assertEquals(Calendar.FRIDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2021, 1, 1)))
            assertEquals(Calendar.THURSDAY, TimePeriodUtils.getDayOfWeek(dateAtNoonUtc(2020, 12, 31)))
        }
    }

    @Test
    fun `getDayOfWeek matches java time ordinal mapping for a full week`() {
        withZone("UTC") {
            for (dayOffset in 0..6) {
                val date = LocalDate.of(2024, 1, 1).plusDays(dayOffset.toLong())
                val ts = dateAtNoonUtc(date.year, date.monthValue, date.dayOfMonth)
                // DayOfWeek.MONDAY ordinal 0 → Calendar.MONDAY = 2; SUNDAY ordinal 6 → Calendar.SUNDAY = 1.
                val expectedCalendar = ((date.dayOfWeek.ordinal + 1) % 7) + 1
                assertEquals(expectedCalendar, TimePeriodUtils.getDayOfWeek(ts))
            }
        }
    }

    // ============================================================================
    // Leap / month fields, days remaining, day index
    // ============================================================================

    @Test
    fun `getDaysInMonth handles leap and non-leap February`() {
        withZone("UTC") {
            assertEquals(29, TimePeriodUtils.getDaysInMonth(dateAtNoonUtc(2024, 2, 15))) // leap
            assertEquals(28, TimePeriodUtils.getDaysInMonth(dateAtNoonUtc(2023, 2, 15))) // non-leap
            assertEquals(31, TimePeriodUtils.getDaysInMonth(dateAtNoonUtc(2024, 12, 31)))
            assertEquals(30, TimePeriodUtils.getDaysInMonth(dateAtNoonUtc(2024, 4, 15)))
        }
    }

    @Test
    fun `getDaysRemainingInMonth excludes the current day`() {
        withZone("UTC") {
            assertEquals(9, TimePeriodUtils.getDaysRemainingInMonth(dateAtNoonUtc(2024, 2, 20))) // Feb 21..29
            assertEquals(0, TimePeriodUtils.getDaysRemainingInMonth(dateAtNoonUtc(2024, 2, 29)))
            assertEquals(13, TimePeriodUtils.getDaysRemainingInMonth(dateAtNoonUtc(2023, 2, 15))) // Feb 16..28
            assertEquals(0, TimePeriodUtils.getDaysRemainingInMonth(dateAtNoonUtc(2024, 12, 31)))
        }
    }

    @Test
    fun `getDayIndexFromMonthStart is zero based`() {
        withZone("UTC") {
            assertEquals(0, TimePeriodUtils.getDayIndexFromMonthStart(dateAtNoonUtc(2024, 2, 1)))
            assertEquals(14, TimePeriodUtils.getDayIndexFromMonthStart(dateAtNoonUtc(2024, 2, 15)))
            assertEquals(28, TimePeriodUtils.getDayIndexFromMonthStart(dateAtNoonUtc(2024, 2, 29)))
            assertEquals(30, TimePeriodUtils.getDayIndexFromMonthStart(dateAtNoonUtc(2024, 12, 31)))
        }
    }

    @Test
    fun `days in month and remaining and index match java time oracle`() {
        withZone("UTC") {
            val years = listOf(2024, 2023, 2020, 1900)
            val samples = years.flatMap { year ->
                listOf(
                    dateAtNoonUtc(year, 1, 15),
                    dateAtNoonUtc(year, 2, 15),
                    dateAtNoonUtc(year, 2, 28),
                    dateAtNoonUtc(year, 4, 15),
                    dateAtNoonUtc(year, 12, 31)
                )
            }
            for (ts in samples) {
                val date = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate()
                assertEquals("getDaysInMonth ts=$ts", date.lengthOfMonth(), TimePeriodUtils.getDaysInMonth(ts))
                assertEquals(
                    "getDaysRemainingInMonth ts=$ts",
                    date.lengthOfMonth() - date.dayOfMonth,
                    TimePeriodUtils.getDaysRemainingInMonth(ts)
                )
                assertEquals(
                    "getDayIndexFromMonthStart ts=$ts",
                    date.dayOfMonth - 1,
                    TimePeriodUtils.getDayIndexFromMonthStart(ts)
                )
            }
        }
    }

    // ============================================================================
    // isSameMonth
    // ============================================================================

    @Test
    fun `isSameMonth true for same month and year regardless of time of day`() {
        withZone("UTC") {
            assertTrue(TimePeriodUtils.isSameMonth(dateAtNoonUtc(2024, 6, 1), dateAtNoonUtc(2024, 6, 30)))
            assertTrue(TimePeriodUtils.isSameMonth(jun15MidnightUtc, jun15LateUtc))
            assertTrue(
                TimePeriodUtils.isSameMonth(
                    Instant.parse("2024-06-15T00:00:00Z").toEpochMilli(),
                    Instant.parse("2024-06-15T23:59:59Z").toEpochMilli()
                )
            )
        }
    }

    @Test
    fun `isSameMonth false for different months or years`() {
        withZone("UTC") {
            assertFalse(TimePeriodUtils.isSameMonth(dateAtNoonUtc(2024, 6, 15), dateAtNoonUtc(2024, 7, 1)))
            assertFalse(TimePeriodUtils.isSameMonth(dateAtNoonUtc(2024, 6, 30), dateAtNoonUtc(2024, 7, 1)))
            assertFalse(TimePeriodUtils.isSameMonth(dateAtNoonUtc(2024, 6, 15), dateAtNoonUtc(2025, 6, 15)))
        }
    }

    @Test
    fun `isSameMonth depends on the local timezone at instant boundaries`() {
        val dec31LateUtc = Instant.parse("2020-12-31T23:30:00Z").toEpochMilli()
        val jan1EarlyUtc = Instant.parse("2021-01-01T00:15:00Z").toEpochMilli()

        // In UTC the two instants straddle a month boundary.
        withZone("UTC") {
            assertFalse(TimePeriodUtils.isSameMonth(dec31LateUtc, jan1EarlyUtc))
        }
        // In New York (EST, UTC-5) both instants are still Dec 31, 2020 locally.
        withZone("America/New_York") {
            assertTrue(TimePeriodUtils.isSameMonth(dec31LateUtc, jan1EarlyUtc))
        }
    }

    // ============================================================================
    // App-calendar week helpers (documented contract: Monday-start, week 1 is the
    // week containing January 1, week year == calendar year)
    // ============================================================================

    @Test
    fun `app calendar week preserves documented contract at year boundary`() {
        withZone("UTC") {
            val dec31 = dateAtNoonUtc(2020, 12, 31) // Thursday
            assertEquals(53, TimePeriodUtils.getAppCalendarWeekNumber(dec31))
            assertEquals(2020, TimePeriodUtils.getAppCalendarWeekYear(dec31))
            assertEquals("2020-W53", TimePeriodUtils.getAppCalendarWeekKey(dec31))

            val jan1 = dateAtNoonUtc(2021, 1, 1) // Friday
            assertEquals(1, TimePeriodUtils.getAppCalendarWeekNumber(jan1))
            assertEquals(2021, TimePeriodUtils.getAppCalendarWeekYear(jan1))
            assertEquals("2021-W01", TimePeriodUtils.getAppCalendarWeekKey(jan1))

            // Documented contract: the app-calendar week year always equals the
            // calendar year — no ISO-style week-based-year drift at boundaries.
            assertEquals(2020, TimePeriodUtils.getAppCalendarWeekYear(dec31))
            assertEquals(2021, TimePeriodUtils.getAppCalendarWeekYear(jan1))
        }
    }

    @Test
    fun `app calendar week number matches java time WeekFields Monday minimal 1`() {
        withZone("UTC") {
            val weekFields = WeekFields.of(DayOfWeek.MONDAY, 1)
            val samples = listOf(
                dateAtNoonUtc(2020, 12, 31),
                dateAtNoonUtc(2021, 1, 1),
                dateAtNoonUtc(2021, 1, 4),
                dateAtNoonUtc(2021, 12, 31),
                dateAtNoonUtc(2024, 6, 15)
            )
            for (ts in samples) {
                val date = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate()
                assertEquals("app week number ts=$ts", date.get(weekFields.weekOfYear()), TimePeriodUtils.getAppCalendarWeekNumber(ts))
                assertEquals("app week year ts=$ts", date.year, TimePeriodUtils.getAppCalendarWeekYear(ts))
            }
        }
    }

    // ============================================================================
    // formatMonthKey / parseMonthKey / buildMonthKeyRange
    // ============================================================================

    @Test
    fun `formatMonthKey timestamp uses local year and month across zones`() {
        val monthBoundary = Instant.parse("2024-07-01T00:30:00Z").toEpochMilli()
        withZone("UTC") {
            assertEquals("2024-07", TimePeriodUtils.formatMonthKey(monthBoundary))
        }
        withZone("Asia/Kolkata") {
            // +05:30 keeps the instant on July 1 local.
            assertEquals("2024-07", TimePeriodUtils.formatMonthKey(monthBoundary))
        }
        withZone("America/New_York") {
            // EDT (UTC-4) pushes the instant back to June 30 local.
            assertEquals("2024-06", TimePeriodUtils.formatMonthKey(monthBoundary))
            assertEquals("2024-06", TimePeriodUtils.formatMonthKey(Instant.parse("2024-06-30T23:59:00Z").toEpochMilli()))
        }
    }

    @Test
    fun `formatMonthKey timestamp matches java time year and month`() {
        val samples = listOf(jun15MidnightUtc, jun15LateUtc, jan1_2024MorningUtc, newYear2021Utc)
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    val zoned = Instant.ofEpochMilli(ts).atZone(ZoneId.of(zoneId))
                    val expected = String.format("%04d-%02d", zoned.year, zoned.monthValue)
                    assertEquals("formatMonthKey $zoneId ts=$ts", expected, TimePeriodUtils.formatMonthKey(ts))
                }
            }
        }
    }

    @Test
    fun `formatMonthKey year and month pads to yyyy-MM`() {
        assertEquals("2026-04", TimePeriodUtils.formatMonthKey(2026, 4))
        assertEquals("2020-12", TimePeriodUtils.formatMonthKey(2020, 12))
        assertEquals("2024-02", TimePeriodUtils.formatMonthKey(2024, 2))
        assertEquals("1999-01", TimePeriodUtils.formatMonthKey(1999, 1))
    }

    @Test
    fun `formatMonthKey rejects invalid month`() {
        for (badMonth in listOf(0, 13, -1)) {
            try {
                TimePeriodUtils.formatMonthKey(2026, badMonth)
                fail("Expected IllegalArgumentException for month $badMonth")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `parseMonthKey round trips with formatMonthKey`() {
        assertEquals(2026 to 4, TimePeriodUtils.parseMonthKey("2026-04"))
        assertEquals(2020 to 12, TimePeriodUtils.parseMonthKey("2020-12"))
        assertEquals(1999 to 1, TimePeriodUtils.parseMonthKey("1999-01"))

        // Each direction is checked against an explicit expected value; no
        // production helper is used as the oracle for another production helper.
        val cases = listOf(
            "2020-01" to (2020 to 1),
            "2024-02" to (2024 to 2),
            "2026-12" to (2026 to 12)
        )
        for ((key, expected) in cases) {
            assertEquals("parseMonthKey $key", expected, TimePeriodUtils.parseMonthKey(key))
            assertEquals("formatMonthKey $key", key, TimePeriodUtils.formatMonthKey(expected.first, expected.second))
        }
    }

    @Test
    fun `parseMonthKey rejects malformed keys`() {
        val badKeys = listOf("", "2026", "2026-13", "2026-00", "abc-01", "2026-ab")
        for (key in badKeys) {
            try {
                TimePeriodUtils.parseMonthKey(key)
                fail("Expected IllegalArgumentException for key '$key'")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `buildMonthKeyRange is inclusive on both ends`() {
        assertEquals(
            listOf("2026-02", "2026-03", "2026-04"),
            TimePeriodUtils.buildMonthKeyRange("2026-02", "2026-04")
        )
        // Crosses a year boundary.
        assertEquals(
            listOf("2025-11", "2025-12", "2026-01", "2026-02"),
            TimePeriodUtils.buildMonthKeyRange("2025-11", "2026-02")
        )
        // A single-month range includes its only element.
        assertEquals(
            listOf("2026-05"),
            TimePeriodUtils.buildMonthKeyRange("2026-05", "2026-05")
        )
    }

    @Test
    fun `buildMonthKeyRange rejects reversed bounds`() {
        try {
            TimePeriodUtils.buildMonthKeyRange("2026-04", "2026-02")
            fail("Expected IllegalArgumentException for reversed range")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `buildMonthKeyRange matches java time YearMonth enumeration`() {
        val startKey = "2024-11"
        val endKey = "2025-03"
        val start = YearMonth.of(2024, 11)
        val end = YearMonth.of(2025, 3)
        val expected = generateSequence(start) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(end) }
            .map { String.format("%04d-%02d", it.year, it.monthValue) }
            .toList()

        assertEquals(expected, TimePeriodUtils.buildMonthKeyRange(startKey, endKey))
    }
}
