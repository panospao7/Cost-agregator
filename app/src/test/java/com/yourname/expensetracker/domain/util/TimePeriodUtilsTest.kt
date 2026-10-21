package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/**
 * General [TimePeriodUtils] contract tests.
 *
 * Time-zone policy: no test depends on the JVM default zone. The whole class runs
 * with the default zone pinned to [ZONE] by [pinnedZone] (process-wide
 * [GlobalTimeZoneTestLock] held for the duration of each test, original zone
 * restored afterwards). Tests that need another zone nest [withZone], which
 * re-enters the same (reentrant) lock and restores [ZONE] in `finally`.
 *
 * Tables are row-based: every failure message carries the row name, and expected
 * instants are built from explicit local date-times in [ZONE] (never from
 * production helpers).
 */
class TimePeriodUtilsTest {

    private companion object {
        const val ZONE = "Europe/Athens"
    }

    @get:Rule
    val pinnedZone = PinnedTimeZoneRule(ZONE)

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

    private fun ldt(
        year: Int, month: Int, day: Int,
        hour: Int = 0, minute: Int = 0, second: Int = 0, millis: Int = 0
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000)

    // Epoch millis of the local date-time [local] in the explicit zone [zoneId].
    private fun epoch(local: LocalDateTime, zoneId: String = ZONE): Long =
        local.atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    // ---------- Table row types ----------

    private data class BoundaryRow(
        val name: String,
        val op: (Long) -> Long,
        val input: LocalDateTime,
        val expected: LocalDateTime
    )

    private data class MembershipRow(
        val name: String,
        val rangeOp: (Long) -> Pair<Long, Long>,
        val timestamp: LocalDateTime
    )

    private data class IsInRangeRow(
        val name: String,
        val timestamp: Long,
        val start: Long,
        val end: Long,
        val expected: Boolean
    )

    private data class WeekRow(
        val name: String,
        val input: LocalDateTime,
        val expectedStart: LocalDateTime,
        val expectedEnd: LocalDateTime
    )

    private data class ArithmeticRow(
        val name: String,
        val op: (Long) -> Long,
        val input: LocalDateTime,
        val expected: LocalDateTime
    )

    private data class WeekYearRow(
        val name: String,
        val input: LocalDateTime,
        val expectedYear: Int,
        val allowedWeeks: Set<Int>
    )

    private data class ValueRow(
        val name: String,
        val op: (Long) -> Any,
        val timestamp: Long,
        val expected: Any
    )

    private data class CompatRow(
        val name: String,
        val canonical: (Long) -> Int,
        val legacy: (Long) -> Int
    )

    // ============================================================================
    // Start / end boundary helpers (exclusive-end convention)
    // ============================================================================

    // Table (merged from the former getStartOfDay / getStartOfMonth / getEndOfMonth /
    // getStartOfYear / getEndOfYear / "contract - getEndOfDay|getEndOfMonth|getEndOfYear|
    // getEndOfQuarter is exclusive" / "contract - Q4 endExclusive is Jan 1 next year"
    // tests). Calendar-field assertions became exact local-midnight instants, which
    // are at least as strict (year, month, day, hour, minute, second, millisecond).
    @Test
    fun `boundary table - start helpers and exclusive end helpers return exact local midnights`() {
        val rows = listOf(
            BoundaryRow("getStartOfDay midnight of Jan 15", { TimePeriodUtils.getStartOfDay(it) }, ldt(2024, 1, 15, 14, 30, 45), ldt(2024, 1, 15)),
            BoundaryRow("getStartOfMonth first day of Feb", { TimePeriodUtils.getStartOfMonth(it) }, ldt(2024, 2, 20, 10), ldt(2024, 2, 1)),
            BoundaryRow("getEndOfMonth leap Feb is start of March (exclusive end)", { TimePeriodUtils.getEndOfMonth(it) }, ldt(2024, 2, 10, 10), ldt(2024, 3, 1)),
            BoundaryRow("getStartOfYear Jan 1", { TimePeriodUtils.getStartOfYear(it) }, ldt(2024, 7, 4, 12), ldt(2024, 1, 1)),
            BoundaryRow("getEndOfYear from Mar 1 00:00 is start of next year (exclusive end)", { TimePeriodUtils.getEndOfYear(it) }, ldt(2024, 3, 1), ldt(2025, 1, 1)),
            BoundaryRow("contract: getEndOfDay is exclusive (start of next day)", { TimePeriodUtils.getEndOfDay(it) }, ldt(2024, 6, 15, 17, 30), ldt(2024, 6, 16)),
            BoundaryRow("contract: getEndOfMonth is exclusive (1st of next month)", { TimePeriodUtils.getEndOfMonth(it) }, ldt(2024, 3, 15, 10), ldt(2024, 4, 1)),
            BoundaryRow("contract: getEndOfYear is exclusive (Jan 1 of next year)", { TimePeriodUtils.getEndOfYear(it) }, ldt(2024, 11, 10, 10), ldt(2025, 1, 1)),
            BoundaryRow("contract: getEndOfQuarter is exclusive (Q2 -> Jul 1)", { TimePeriodUtils.getEndOfQuarter(it) }, ldt(2024, 5, 15, 10), ldt(2024, 7, 1)),
            BoundaryRow("contract: Q4 endExclusive is Jan 1 next year", { TimePeriodUtils.getEndOfQuarter(it) }, ldt(2024, 11, 15, 10), ldt(2025, 1, 1))
        )
        assertEquals("boundary table size", 10, rows.size)

        for (row in rows) {
            assertEquals("${row.name} [$ZONE]", epoch(row.expected), row.op(epoch(row.input)))
        }
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

    // ============================================================================
    // HALF-OPEN [startInclusive, endExclusive) CONTRACT TESTS
    // ============================================================================

    // Table (merged from "contract - timestamp at midnight / 23_59_59_999 is included in
    // its own day", "isInRange - works with real day boundaries" and "isInRange - works
    // with real month boundaries"). Every row asserts inclusion of the timestamp and
    // exclusion of the exclusive end.
    @Test
    fun `membership table - timestamps on period edges are inside and the exclusive end is outside`() {
        val dayStartEnd: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getStartOfDay(it) to TimePeriodUtils.getEndOfDay(it) }
        val dayRange: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getDayRange(it) }
        val monthRange: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getMonthRange(it) }
        val rows = listOf(
            MembershipRow("midnight 00:00:00.000 is in its own day", dayStartEnd, ldt(2024, 6, 15)),
            MembershipRow("23:59:59.999 is in its own day", dayStartEnd, ldt(2024, 6, 15, 23, 59, 59, 999)),
            MembershipRow("14:30 is in its own getDayRange", dayRange, ldt(2024, 6, 15, 14, 30)),
            MembershipRow("last ms of March 31 is in the March range", monthRange, ldt(2024, 3, 31, 23, 59, 59, 999))
        )
        assertEquals("membership table size", 4, rows.size)

        for (row in rows) {
            val ts = epoch(row.timestamp)
            val (start, end) = row.rangeOp(ts)
            assertTrue("${row.name}: ts >= start [$ZONE]", ts >= start)
            assertTrue("${row.name}: ts < end [$ZONE]", ts < end)
            assertTrue("${row.name}: isInRange(ts) [$ZONE]", TimePeriodUtils.isInRange(ts, start, end))
            assertFalse("${row.name}: exclusive end is not in range [$ZONE]", TimePeriodUtils.isInRange(end, start, end))
        }
    }

    // ============================================================================
    // isInRange HALF-OPEN CONTAINMENT HELPER
    // ============================================================================

    // Table: the four former TPU cases (1000..2000) plus the four equivalent cases that
    // lived in TimePeriodUtilsValidationTest (100..200), kept here as rows.
    @Test
    fun `isInRange table - start inclusive, end exclusive, middle in, before start out`() {
        val rows = listOf(
            IsInRangeRow("1000..2000: timestamp at startInclusive is included", 1000L, 1000L, 2000L, true),
            IsInRangeRow("1000..2000: timestamp at endExclusive is excluded", 2000L, 1000L, 2000L, false),
            IsInRangeRow("1000..2000: timestamp in middle is included", 1500L, 1000L, 2000L, true),
            IsInRangeRow("1000..2000: timestamp before start is excluded", 999L, 1000L, 2000L, false),
            IsInRangeRow("100..200: includes startInclusive", 100L, 100L, 200L, true),
            IsInRangeRow("100..200: excludes endExclusive", 200L, 100L, 200L, false),
            IsInRangeRow("100..200: includes middle values", 150L, 100L, 200L, true),
            IsInRangeRow("100..200: excludes values before start", 99L, 100L, 200L, false)
        )
        assertEquals("isInRange table size", 8, rows.size)

        for (row in rows) {
            assertEquals(row.name, row.expected, TimePeriodUtils.isInRange(row.timestamp, row.start, row.end))
        }
    }

    // ============================================================================
    // getDayRange HELPER
    // ============================================================================

    // Table (merged from "contract - day range covers exactly one calendar day",
    // "getDayRange - returns same as getStartOfDay and getEndOfDay" and
    // "getDayRange - spans exactly 1 calendar day"; every row asserts all of them).
    @Test
    fun `getDayRange table - equals start and end of day and spans exactly one calendar day`() {
        val rows = listOf(
            ldt(2024, 6, 15, 12) to "Jun 15 12:00",
            ldt(2024, 6, 15, 14, 30) to "Jun 15 14:30",
            ldt(2024, 2, 29, 10) to "Feb 29 10:00 (leap day)"
        )
        assertEquals("getDayRange table size", 3, rows.size)

        for ((local, name) in rows) {
            val ts = epoch(local)
            val start = TimePeriodUtils.getStartOfDay(ts)
            val end = TimePeriodUtils.getEndOfDay(ts)
            val (rangeStart, rangeEnd) = TimePeriodUtils.getDayRange(ts)

            assertEquals("$name: getDayRange start [$ZONE]", start, rangeStart)
            assertEquals("$name: getDayRange end [$ZONE]", end, rangeEnd)
            assertEquals("$name: start/end of day spans 1 day [$ZONE]", 1, TimePeriodUtils.daysBetween(start, end))
            assertEquals("$name: getDayRange spans 1 day [$ZONE]", 1, TimePeriodUtils.daysBetween(rangeStart, rangeEnd))
        }
    }

    @Test
    fun `getDayRange - consecutive days are contiguous`() {
        val dec31 = epoch(ldt(2024, 12, 31, 12))
        val jan1 = epoch(ldt(2025, 1, 1, 12))
        val (_, day1End) = TimePeriodUtils.getDayRange(dec31)
        val (day2Start, _) = TimePeriodUtils.getDayRange(jan1)

        assertEquals("Year-boundary day ranges must be contiguous", day1End, day2Start)
    }

    // ============================================================================
    // Monday-start week helpers
    // ============================================================================

    // Table (merged from "getEndOfWeek - returns next Monday", "getEndOfWeek - consistent
    // with getWeekRange", "getEndOfWeek - is exactly 7 calendar days after getStartOfWeek",
    // "contract - getStartOfWeek always returns Monday" (Mon Jan 1 .. Sun Jan 7 rows),
    // "contract - getWeekRange returns Monday to next Monday" and "contract - Sunday
    // belongs to the previous Monday's week"). Each row asserts the exact Monday start,
    // the exact next-Monday exclusive end, getWeekRange agreement and a 7 calendar day span.
    @Test
    fun `week table - every day maps to its Monday start and next Monday exclusive end`() {
        val rows = listOf(
            WeekRow("Wednesday Jan 3", ldt(2024, 1, 3, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("Sunday Apr 14 belongs to the week of Monday Apr 8", ldt(2024, 4, 14, 10), ldt(2024, 4, 8), ldt(2024, 4, 15)),
            WeekRow("Wednesday Jun 12 15:00", ldt(2024, 6, 12, 15), ldt(2024, 6, 10), ldt(2024, 6, 17)),
            WeekRow("day offset 0 Monday Jan 1", ldt(2024, 1, 1, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 1 Tuesday Jan 2", ldt(2024, 1, 2, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 2 Wednesday Jan 3", ldt(2024, 1, 3, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 3 Thursday Jan 4", ldt(2024, 1, 4, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 4 Friday Jan 5", ldt(2024, 1, 5, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 5 Saturday Jan 6", ldt(2024, 1, 6, 12), ldt(2024, 1, 1), ldt(2024, 1, 8)),
            WeekRow("day offset 6 Sunday Jan 7", ldt(2024, 1, 7, 12), ldt(2024, 1, 1), ldt(2024, 1, 8))
        )
        assertEquals("week table size", 10, rows.size)

        for (row in rows) {
            val ts = epoch(row.input)
            val expectedStart = epoch(row.expectedStart)
            val expectedEnd = epoch(row.expectedEnd)
            val (rangeStart, rangeEnd) = TimePeriodUtils.getWeekRange(ts)

            assertEquals("${row.name}: getStartOfWeek [$ZONE]", expectedStart, TimePeriodUtils.getStartOfWeek(ts))
            assertEquals("${row.name}: getEndOfWeek [$ZONE]", expectedEnd, TimePeriodUtils.getEndOfWeek(ts))
            assertEquals("${row.name}: getWeekRange start [$ZONE]", expectedStart, rangeStart)
            assertEquals("${row.name}: getWeekRange end must match getEndOfWeek [$ZONE]", expectedEnd, rangeEnd)
            assertEquals("${row.name}: spans 7 calendar days [$ZONE]", 7, TimePeriodUtils.daysBetween(rangeStart, rangeEnd))
        }
    }

    @Test
    fun `contract - getStartOfWeek is locale-independent`() {
        // Pinned to a zone whose locale conventions start the week on Sunday.
        withZone("America/New_York") {
            val wednesday = epoch(ldt(2024, 1, 3, 12), "America/New_York")

            assertEquals(
                "Week start must be Monday regardless of locale [America/New_York]",
                epoch(ldt(2024, 1, 1), "America/New_York"),
                TimePeriodUtils.getStartOfWeek(wednesday)
            )
            val weekStartCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getStartOfWeek(wednesday) }
            assertEquals(
                "Week start must be Monday regardless of locale",
                Calendar.MONDAY, weekStartCal.get(Calendar.DAY_OF_WEEK)
            )
        }
    }

    // ============================================================================
    // Canonical week keys (anchors kept standalone)
    // ============================================================================

    @Test
    fun `getCanonicalWeekRangeFromKey returns Monday-start next-Monday-exclusive`() {
        // 2024-01-01 is a Monday, so week 01 starts Jan 1 and week 14 starts 13 weeks later:
        // Monday Apr 1 2024 00:00 Athens (EEST, UTC+3) = 2024-03-31T21:00:00Z, end exclusive
        // Monday Apr 8 2024 00:00 Athens = 2024-04-07T21:00:00Z. Hard-coded epochs.
        val expectedStart = 1_711_918_800_000L
        val expectedEnd = 1_712_523_600_000L

        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2024-14")
        assertEquals("2024-14 start = Apr 1 2024 00:00 Athens [$ZONE]", expectedStart, start)
        assertEquals("2024-14 end (exclusive) = Apr 8 2024 00:00 Athens [$ZONE]", expectedEnd, end)
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, TimePeriodUtils.daysBetween(start, end))
    }

    @Test
    fun `getCanonicalWeekRangeFromKey maps key 2026-01 to Monday Jan 5 through next Monday Jan 12 exclusive`() {
        // 2026-01-01 is a Thursday, so the first Monday (week 01 start) is Jan 5.
        val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey("2026-01")
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val endCal = Calendar.getInstance().apply { timeInMillis = end }

        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        assertEquals(5, startCal.get(Calendar.DAY_OF_MONTH))

        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(12, endCal.get(Calendar.DAY_OF_MONTH))
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

    // ============================================================================
    // CALENDAR-AWARE ARITHMETIC EDGE CASES
    // ============================================================================
    //
    // The former "addMonths - Jan 31 plus 1 month coerces to Feb 28 or 29" (leap and
    // non-leap), "addDays - crosses year boundary" and "addYears - Feb 29 plus 1 year
    // coerces to Feb 28" tests were removed as exact duplicates of rows of
    // TimePeriodUtilsValidationTest.`arithmetic table - addDays addMonths addYears keep
    // wall time and coerce month ends` (same local dates, same results, asserted there
    // as exact epochs in UTC, America/New_York and Asia/Kolkata).

    @Test
    fun `arithmetic table - addMonths coerces month ends and keeps the same day across a year boundary`() {
        val rows = listOf(
            ArithmeticRow("addMonths March 31 minus 1 month coerces to Feb 29 in a leap year", { TimePeriodUtils.addMonths(it, -1) }, ldt(2024, 3, 31, 12), ldt(2024, 2, 29, 12)),
            // 18-month lookback used by weekly-distribution history.
            ArithmeticRow("addMonths minus 18 months lands on the same day of month across a year boundary", { TimePeriodUtils.addMonths(it, -18) }, ldt(2026, 4, 15, 12), ldt(2024, 10, 15, 12))
        )
        assertEquals("arithmetic table size", 2, rows.size)

        for (row in rows) {
            assertEquals("${row.name} [$ZONE]", epoch(row.expected), row.op(epoch(row.input)))
        }
    }

    // ============================================================================
    // QUARTER BOUNDARY Q4 -> Q1 NEXT YEAR
    // ============================================================================

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
     * getWeekOfYear() uses Calendar-based week numbering (Monday-start,
     * minimalDaysInFirstWeek=1) so it is always consistent with getYear().
     *
     * Table (merged from four year-boundary / mid-year tests). Rows keep the original
     * per-row expectations: Jan 1 2021 and Jan 3 2016 are exactly week 1 of their
     * calendar year; Dec 31 2020 is deterministic and within {1, 52, 53}; a mid-year
     * date falls in weeks 20..30. getYear() always equals the calendar year.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `getWeekOfYear and getYear are consistent table - year boundaries and mid-year`() {
        val rows = listOf(
            WeekYearRow("Jan 1 2021 (Friday) is week 1 of 2021", ldt(2021, 1, 1, 12), 2021, setOf(1)),
            WeekYearRow("Dec 31 2020 (Thursday) is week 1, 52 or 53 of calendar year 2020", ldt(2020, 12, 31, 12), 2020, setOf(1, 52, 53)),
            WeekYearRow("Jan 3 2016 (Sunday) is still week 1 of 2016", ldt(2016, 1, 3, 12), 2016, setOf(1)),
            WeekYearRow("mid-year Jun 15 2024 is within weeks 20..30", ldt(2024, 6, 15, 12), 2024, (20..30).toSet())
        )
        assertEquals("week-of-year table size", 4, rows.size)

        for (row in rows) {
            val ts = epoch(row.input)
            assertEquals("${row.name}: calendar year [$ZONE]", row.expectedYear, TimePeriodUtils.getYear(ts))
            val week = TimePeriodUtils.getWeekOfYear(ts)
            assertTrue(
                "${row.name}: week should be in ${row.allowedWeeks} (got $week) [$ZONE]",
                week in row.allowedWeeks
            )
        }
    }

    /**
     * getWeekBasedYear() still returns ISO week-based year (for future consumer
     * migration).  For mid-year dates it matches getYear(); at boundaries it may
     * differ.  This test confirms the helper is still correct.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `getWeekBasedYear still returns ISO week-based year for Jan 1 2021`() {
        val jan1_2021 = epoch(ldt(2021, 1, 1, 12))

        // ISO week-based year for 2021-01-01 (Friday) is 2020 (ISO week 53/2020)
        assertEquals("ISO week-based year should be 2020", 2020,
            TimePeriodUtils.getWeekBasedYear(jan1_2021))
        // Calendar year is 2021
        assertEquals("Calendar year should be 2021", 2021,
            TimePeriodUtils.getYear(jan1_2021))
    }

    // ============================================================================
    // ISO-8601 & APP-CALENDAR WEEK HELPER TESTS (PR5)
    // ============================================================================

    // Table (merged from the eleven PR5 tests). The former "getIsoWeekNumber and
    // getIsoWeekBasedYear are consistent at year boundary" test (Jan 1 2021: 2020 / 53 /
    // "2020-W53") was removed as an exact duplicate of the first three rows (same UTC
    // instant, same expected values). All instants are 12:00 UTC and the table runs with
    // the default zone pinned to UTC.
    @Test
    fun `ISO and app calendar week table - Jan 1 2021 Jan 4 2021 and Dec 31 2020 in UTC`() {
        withZone("UTC") {
            val jan1 = epoch(ldt(2021, 1, 1, 12), "UTC")
            val jan4 = epoch(ldt(2021, 1, 4, 12), "UTC")
            val dec31 = epoch(ldt(2020, 12, 31, 12), "UTC")
            val rows = listOf(
                ValueRow("getIsoWeekNumber Jan 1 2021 is ISO week 53", { TimePeriodUtils.getIsoWeekNumber(it) }, jan1, 53),
                ValueRow("getIsoWeekBasedYear Jan 1 2021 is 2020", { TimePeriodUtils.getIsoWeekBasedYear(it) }, jan1, 2020),
                ValueRow("getIsoWeekKey Jan 1 2021 format", { TimePeriodUtils.getIsoWeekKey(it) }, jan1, "2020-W53"),
                ValueRow("getIsoWeekNumber Jan 4 2021 is week 1", { TimePeriodUtils.getIsoWeekNumber(it) }, jan4, 1),
                ValueRow("getAppCalendarWeekNumber Jan 1 2021 is 1", { TimePeriodUtils.getAppCalendarWeekNumber(it) }, jan1, 1),
                ValueRow("getAppCalendarWeekYear Jan 1 2021 is 2021", { TimePeriodUtils.getAppCalendarWeekYear(it) }, jan1, 2021),
                ValueRow("getAppCalendarWeekKey Jan 1 2021 format", { TimePeriodUtils.getAppCalendarWeekKey(it) }, jan1, "2021-W01"),
                ValueRow("getAppCalendarWeekYear Dec 31 2020 is 2020", { TimePeriodUtils.getAppCalendarWeekYear(it) }, dec31, 2020),
                ValueRow("getAppCalendarWeekNumber Dec 31 2020 is 53", { TimePeriodUtils.getAppCalendarWeekNumber(it) }, dec31, 53),
                ValueRow("getAppCalendarWeekKey Dec 31 2020 format", { TimePeriodUtils.getAppCalendarWeekKey(it) }, dec31, "2020-W53")
            )
            assertEquals("ISO/app week table size", 10, rows.size)

            for (row in rows) {
                assertEquals("${row.name} [UTC]", row.expected, row.op(row.timestamp))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `canonical ISO and app week helpers match the legacy helpers for compatibility`() {
        val rows = listOf(
            CompatRow(
                "getAppCalendarWeekNumber matches getWeekOfYear",
                { TimePeriodUtils.getAppCalendarWeekNumber(it) },
                { TimePeriodUtils.getWeekOfYear(it) }
            ),
            CompatRow(
                "getIsoWeekBasedYear matches getWeekBasedYear",
                { TimePeriodUtils.getIsoWeekBasedYear(it) },
                { TimePeriodUtils.getWeekBasedYear(it) }
            )
        )
        assertEquals("compat table size", 2, rows.size)

        withZone("UTC") {
            val jan1 = epoch(ldt(2021, 1, 1, 12), "UTC")
            for (row in rows) {
                assertEquals("${row.name} [UTC]", row.legacy(jan1), row.canonical(jan1))
            }
        }
    }
}
