package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Stress Test Suite for TimePeriodUtils
 *
 * Goal: Break the date calculation logic with DST transitions,
 * timezone edge cases, and boundary conditions.
 *
 * All range assertions follow the [startInclusive, endExclusive) contract.
 *
 * Time-zone policy: no test depends on the JVM default zone. The whole class runs
 * with the default zone pinned to [ZONE] by [pinnedZone] (process-wide
 * [GlobalTimeZoneTestLock] held for the duration of each test, original zone
 * restored afterwards). Tests that need another zone nest [withZone], which
 * re-enters the same (reentrant) lock and restores [ZONE] in `finally`.
 *
 * Tables are row-based: every failure message carries the row name, and expected
 * instants are hard-coded ISO instants or explicit local date-times in an explicit
 * zone (never produced by the production helpers). Fuzz tests use a fixed seed.
 *
 * Duplicates removed in the consolidation pass (survivor in parentheses):
 * - non-leap Feb 28 (TimePeriodUtilsValidationTest."non-leap year February 28 is handled correctly")
 * - getStartOfWeek for each day of week (TimePeriodUtilsTest."week table - every day maps to
 *   its Monday start and next Monday exclusive end", rows "day offset 0..6")
 * - week starting on Sunday: it mutated the firstDayOfWeek of a throwaway Calendar instance
 *   (no effect on production code, vacuous setup) and re-asserted Wednesday Jan 3 -> Monday
 *   (TimePeriodUtilsTest."contract - getStartOfWeek is locale-independent")
 * - getWeekRange week end is next Monday (same table, row "Wednesday Jan 3")
 * - regression getStartOfMonth (same table in TimePeriodUtilsTest."boundary table - ...",
 *   row "getStartOfMonth first day of Feb", identical local date-time)
 * - regression getStartOfDay (this file, "zone midnight table", Athens row, same local date)
 *
 * @author Hostile QA Engineer
 */
class TimePeriodUtilsStressTest {

    private companion object {
        const val ZONE = "Europe/Athens"
        const val NY = "America/New_York"
        const val HOUR_MS = 60L * 60 * 1000
        // Fixed fuzz window upper bound (about March 2030), independent of the wall clock.
        const val FUZZ_UPPER_BOUND_MS = 1_900_000_000_000L
        // 2000-01-01T00:00:00Z; from here Athens follows the modern EU rules.
        const val MODERN_RULES_LOWER_BOUND_MS = 946_684_800_000L
        const val FUZZ_SEED = 20240615
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

    private fun instant(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ---------- Table row types ----------

    private data class DstDayRow(
        val name: String,
        val inputIso: String,
        val expectedStartIso: String,
        val expectedEndIso: String,
        val expectedHours: Long
    )

    private data class ZoneRow(val zoneId: String, val expectedIso: String)

    private data class OpRow(
        val name: String,
        val op: (Long) -> Long,
        val input: LocalDateTime,
        val expected: LocalDateTime
    )

    // ============================================================================
    // SECTION 1: DST TRANSITION TESTS
    // ============================================================================

    // Table (merged from the former "DST spring forward March 2024", "DST fall back
    // October 2024" and "multiple days around DST spring" tests, whose assertions were
    // only "hour of day == 0" and "day length between 82_000_000 and 90_500_000 ms").
    // Pinned to Europe/Athens: EU spring forward 2024-03-31 (23h day) and fall back
    // 2024-10-27 (25h day). Exact local-midnight instants and exact durations.
    @Test
    fun `stress - DST table Athens - exact local midnights and 23h spring day, 25h fall day`() {
        val rows = listOf(
            DstDayRow("Mar 30 (EET, day before spring forward)", "2024-03-30T10:00:00Z", "2024-03-29T22:00:00Z", "2024-03-30T22:00:00Z", 24),
            DstDayRow("Mar 31 (spring forward day)", "2024-03-31T09:00:00Z", "2024-03-30T22:00:00Z", "2024-03-31T21:00:00Z", 23),
            DstDayRow("Apr 1 (EEST, day after spring forward)", "2024-04-01T09:00:00Z", "2024-03-31T21:00:00Z", "2024-04-01T21:00:00Z", 24),
            DstDayRow("Oct 26 (EEST, day before fall back)", "2024-10-26T09:00:00Z", "2024-10-25T21:00:00Z", "2024-10-26T21:00:00Z", 24),
            DstDayRow("Oct 27 (fall back day)", "2024-10-27T10:00:00Z", "2024-10-26T21:00:00Z", "2024-10-27T22:00:00Z", 25),
            DstDayRow("Oct 28 (EET, day after fall back)", "2024-10-28T10:00:00Z", "2024-10-27T22:00:00Z", "2024-10-28T22:00:00Z", 24)
        )
        assertEquals("DST table size", 6, rows.size)

        for (row in rows) {
            val ts = instant(row.inputIso)
            val start = TimePeriodUtils.getStartOfDay(ts)
            val end = TimePeriodUtils.getEndOfDay(ts)
            assertEquals("${row.name}: start [$ZONE]", instant(row.expectedStartIso), start)
            assertEquals("${row.name}: end [$ZONE]", instant(row.expectedEndIso), end)
            assertEquals("${row.name}: duration [$ZONE]", row.expectedHours * HOUR_MS, end - start)
        }
    }

    @Test
    fun `stress - getStartOfWeek during DST transition`() {
        // Thursday 2024-03-28 12:00 EET; the week Mon Mar 25 .. Mon Apr 1 contains the
        // EU spring forward (Sunday Mar 31), so the week lasts 167 hours.
        val thursday = instant("2024-03-28T10:00:00Z")

        val startOfWeek = TimePeriodUtils.getStartOfWeek(thursday)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(thursday)

        assertEquals("start is Monday 00:00 EET [$ZONE]", instant("2024-03-24T22:00:00Z"), startOfWeek)
        assertEquals("end is next Monday 00:00 EEST [$ZONE]", instant("2024-03-31T21:00:00Z"), endOfWeek)
        assertEquals("week lasts 167h [$ZONE]", 167 * HOUR_MS, endOfWeek - startOfWeek)
        assertEquals(
            "getWeekRange agrees [$ZONE]",
            startOfWeek to endOfWeek,
            TimePeriodUtils.getWeekRange(thursday)
        )
    }

    @Test
    fun `stress - daysBetween is DST safe across spring forward`() {
        withZone(NY) {
            // US DST spring forward: March 10, 2024
            val march9 = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 9, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val march11 = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 11, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Should be 2 calendar days even though only ~47 real hours elapsed
            assertEquals(2, TimePeriodUtils.daysBetween(march9, march11))
        }
    }

    // ============================================================================
    // SECTION 2: TIMEZONE TESTS
    // ============================================================================

    // Table (merged from "different timezone calculations" and "timezone with half hour
    // offset"). Input is 2024-06-15 12:00 local in each row's zone; the expected value
    // is the hard-coded instant of local midnight June 15 in that zone.
    @Test
    fun `stress - zone midnight table - start of day is local midnight in every zone`() {
        val rows = listOf(
            ZoneRow("America/New_York", "2024-06-15T04:00:00Z"),   // UTC-4
            ZoneRow("Europe/Athens", "2024-06-14T21:00:00Z"),      // UTC+3
            ZoneRow("Asia/Tokyo", "2024-06-14T15:00:00Z"),         // UTC+9
            ZoneRow("Pacific/Honolulu", "2024-06-15T10:00:00Z"),   // UTC-10
            ZoneRow("Asia/Kolkata", "2024-06-14T18:30:00Z")        // UTC+5:30
        )
        assertEquals("zone midnight table size", 5, rows.size)

        for (row in rows) {
            withZone(row.zoneId) {
                val ts = epoch(ldt(2024, 6, 15, 12), row.zoneId)
                assertEquals(
                    "start of day [${row.zoneId}]",
                    instant(row.expectedIso),
                    TimePeriodUtils.getStartOfDay(ts)
                )
            }
        }
    }

    // ============================================================================
    // SECTION 3: LEAP YEAR TESTS
    // ============================================================================

    @Test
    fun `stress - February 29th leap year 2024`() {
        val ts = epoch(ldt(2024, 2, 29, 12))

        // 2024 is leap year: Feb 29 exists and its start of day is exactly local midnight.
        assertEquals("start of Feb 29 [$ZONE]", epoch(ldt(2024, 2, 29)), TimePeriodUtils.getStartOfDay(ts))
    }

    @Test
    fun `stress - end of month for all months`() {
        // Production uses exclusive end: returns start of NEXT month (2024 is a leap year).
        val rows = listOf(
            "January" to ldt(2024, 2, 1),
            "February" to ldt(2024, 3, 1),
            "March" to ldt(2024, 4, 1),
            "April" to ldt(2024, 5, 1),
            "May" to ldt(2024, 6, 1),
            "June" to ldt(2024, 7, 1),
            "July" to ldt(2024, 8, 1),
            "August" to ldt(2024, 9, 1),
            "September" to ldt(2024, 10, 1),
            "October" to ldt(2024, 11, 1),
            "November" to ldt(2024, 12, 1),
            "December" to ldt(2025, 1, 1)
        )
        assertEquals("end of month table size", 12, rows.size)

        rows.forEachIndexed { index, (name, expectedEnd) ->
            val ts = epoch(ldt(2024, index + 1, 15, 12))
            assertEquals("getEndOfMonth $name [$ZONE]", epoch(expectedEnd), TimePeriodUtils.getEndOfMonth(ts))
        }
    }

    @Test
    fun `stress - days in month for all months in leap year`() {
        val rows = listOf(
            "January" to 31,
            "February" to 29, // 2024 is leap year
            "March" to 31,
            "April" to 30,
            "May" to 31,
            "June" to 30,
            "July" to 31,
            "August" to 31,
            "September" to 30,
            "October" to 31,
            "November" to 30,
            "December" to 31
        )
        assertEquals("days in month table size", 12, rows.size)

        rows.forEachIndexed { index, (name, expected) ->
            val ts = epoch(ldt(2024, index + 1, 15, 12))
            assertEquals("getDaysInMonth $name 2024 [$ZONE]", expected, TimePeriodUtils.getDaysInMonth(ts))
        }
    }

    // ============================================================================
    // SECTION 4: WEEK CALCULATION TESTS (Monday-start, locale-independent)
    // ============================================================================

    // Table: Wednesday 2024-06-12 12:00 local in each zone maps to Monday 2024-06-10 00:00 local.
    @Test
    fun `stress - Monday-start week is locale-independent across timezones`() {
        val rows = listOf(
            ZoneRow("America/New_York", "2024-06-10T04:00:00Z"),
            ZoneRow("Europe/Athens", "2024-06-09T21:00:00Z"),
            ZoneRow("Asia/Tokyo", "2024-06-09T15:00:00Z")
        )
        assertEquals("week zone table size", 3, rows.size)

        for (row in rows) {
            withZone(row.zoneId) {
                val wednesday = epoch(ldt(2024, 6, 12, 12), row.zoneId)
                assertEquals(
                    "Week must start on Monday 00:00 in ${row.zoneId}",
                    instant(row.expectedIso),
                    TimePeriodUtils.getStartOfWeek(wednesday)
                )
            }
        }
    }

    // ============================================================================
    // SECTION 5: MONTH BOUNDARY TESTS
    // ============================================================================

    // Table (merged from the three "month boundary ..." tests): the first millisecond
    // after the last millisecond of a month belongs to the next month's start.
    @Test
    fun `stress - month boundary table - last ms plus one maps to next month start`() {
        val rows = listOf(
            "January to February" to (ldt(2024, 1, 31, 23, 59, 59, 999) to ldt(2024, 2, 1)),
            "February to March leap year" to (ldt(2024, 2, 29, 23, 59, 59, 999) to ldt(2024, 3, 1)),
            "December to January" to (ldt(2024, 12, 31, 23, 59, 59, 999) to ldt(2025, 1, 1))
        )
        assertEquals("month boundary table size", 3, rows.size)

        for ((name, pair) in rows) {
            val (lastMs, expectedStart) = pair
            assertEquals(
                "$name: getStartOfMonth(lastMs + 1) [$ZONE]",
                epoch(expectedStart),
                TimePeriodUtils.getStartOfMonth(epoch(lastMs) + 1)
            )
        }
    }

    // ============================================================================
    // SECTION 6: YEAR BOUNDARY TESTS
    // ============================================================================

    @Test
    fun `stress - year boundary December 31 to January 1`() {
        val dec31LastMs = epoch(ldt(2024, 12, 31, 23, 59, 59, 999))

        assertEquals(
            "getStartOfYear(lastMs + 1) [$ZONE]",
            epoch(ldt(2025, 1, 1)),
            TimePeriodUtils.getStartOfYear(dec31LastMs + 1)
        )
    }

    @Test
    fun `stress - getEndOfYear for different years`() {
        // Production uses exclusive end: returns start of NEXT year
        val rows = listOf(2020, 2021, 2022, 2023, 2024)
        assertEquals("end of year table size", 5, rows.size)

        rows.forEach { year ->
            assertEquals(
                "getEndOfYear $year [$ZONE]",
                epoch(ldt(year + 1, 1, 1)),
                TimePeriodUtils.getEndOfYear(epoch(ldt(year, 6, 15, 12)))
            )
        }
    }

    // ============================================================================
    // SECTION 7: CALENDAR-AWARE ADDITION TESTS (no raw millis math)
    // ============================================================================

    @Test
    fun `stress - getLastNDaysRange uses calendar-aware subtraction`() {
        // Range should end at "now" and start at the start of the day exactly N days earlier.
        val june15 = epoch(ldt(2024, 6, 15, 12))

        val range = TimePeriodUtils.getLastNDaysRange(june15, 7)

        assertEquals("range end is now [$ZONE]", june15, range.second)
        assertEquals("range start is June 8 00:00 [$ZONE]", epoch(ldt(2024, 6, 8)), range.first)
    }

    @Test
    fun `stress - getDayIndexFromMonthStart is calendar-based`() {
        val index = TimePeriodUtils.getDayIndexFromMonthStart(epoch(ldt(2024, 6, 15, 12)))

        // Should be 14 (0-indexed, so June 15 = day 14)
        assertEquals(14, index)
    }

    @Test
    fun `stress - addDays is DST safe`() {
        withZone(NY) {
            val march9 = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 9, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val march11 = TimePeriodUtils.addDays(march9, 2)
            val resultCal = Calendar.getInstance().apply { timeInMillis = march11 }

            assertEquals(11, resultCal.get(Calendar.DAY_OF_MONTH))
            assertEquals(Calendar.MARCH, resultCal.get(Calendar.MONTH))
        }
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    // Table (merged from "epoch timestamp", "very far future timestamp" and "negative
    // timestamp before epoch"; the latter only asserted year <= 1970). In Athens the
    // epoch instant and -1000 ms are both 1970-01-01 local (02:00 and 01:59:59).
    @Test
    fun `stress - edge timestamps table - epoch, just before epoch and year 2100`() {
        val rows = listOf(
            Triple("epoch 0", 0L, ldt(1970, 1, 1)),
            Triple("-1000 ms is still 1970-01-01 in Athens", -1000L, ldt(1970, 1, 1)),
            Triple("year 2100 Dec 31 12:00", epoch(ldt(2100, 12, 31, 12)), ldt(2100, 12, 31))
        )
        assertEquals("edge timestamp table size", 3, rows.size)

        for ((name, ts, expectedLocalMidnight) in rows) {
            assertEquals("$name: getStartOfDay [$ZONE]", epoch(expectedLocalMidnight), TimePeriodUtils.getStartOfDay(ts))
        }
    }

    // Table (merged from "midnight timestamp" and "end of day timestamp"): exact local
    // midnight maps to itself, and the last millisecond of a day ends at next midnight.
    @Test
    fun `stress - day edge table - midnight maps to itself and last ms ends at next midnight`() {
        val rows = listOf(
            OpRow("getStartOfDay of exact midnight", { TimePeriodUtils.getStartOfDay(it) }, ldt(2024, 6, 15), ldt(2024, 6, 15)),
            OpRow("getEndOfDay of 23:59:59.999 is next-day midnight", { TimePeriodUtils.getEndOfDay(it) }, ldt(2024, 6, 15, 23, 59, 59, 999), ldt(2024, 6, 16))
        )
        assertEquals("day edge table size", 2, rows.size)

        for (row in rows) {
            assertEquals("${row.name} [$ZONE]", epoch(row.expected), row.op(epoch(row.input)))
        }
    }

    // ============================================================================
    // SECTION 9: QUARTER TESTS
    // ============================================================================

    // Table (merged from "getStartOfQuarter for each quarter" and "getEndOfQuarter for
    // each quarter"). Start rows use the first month of each quarter, end rows the last
    // month; production uses an exclusive end (start of the NEXT quarter).
    @Test
    fun `stress - quarter table - start from first month and exclusive end from last month`() {
        val start: (Long) -> Long = { TimePeriodUtils.getStartOfQuarter(it) }
        val end: (Long) -> Long = { TimePeriodUtils.getEndOfQuarter(it) }
        val rows = listOf(
            OpRow("getStartOfQuarter from Jan 15", start, ldt(2024, 1, 15, 12), ldt(2024, 1, 1)),
            OpRow("getStartOfQuarter from Apr 15", start, ldt(2024, 4, 15, 12), ldt(2024, 4, 1)),
            OpRow("getStartOfQuarter from Jul 15", start, ldt(2024, 7, 15, 12), ldt(2024, 7, 1)),
            OpRow("getStartOfQuarter from Oct 15", start, ldt(2024, 10, 15, 12), ldt(2024, 10, 1)),
            OpRow("getEndOfQuarter Q1 (Mar 15) -> Apr 1", end, ldt(2024, 3, 15, 12), ldt(2024, 4, 1)),
            OpRow("getEndOfQuarter Q2 (Jun 15) -> Jul 1", end, ldt(2024, 6, 15, 12), ldt(2024, 7, 1)),
            OpRow("getEndOfQuarter Q3 (Sep 15) -> Oct 1", end, ldt(2024, 9, 15, 12), ldt(2024, 10, 1)),
            OpRow("getEndOfQuarter Q4 (Dec 15) -> Jan 1 next year", end, ldt(2024, 12, 15, 12), ldt(2025, 1, 1))
        )
        assertEquals("quarter table size", 8, rows.size)

        for (row in rows) {
            assertEquals("${row.name} [$ZONE]", epoch(row.expected), row.op(epoch(row.input)))
        }
    }

    // ============================================================================
    // SECTION 10: FUZZ TESTING (fixed seed, invariants only)
    // ============================================================================

    // The former unseeded "no exception" fuzz was replaced by a fixed-seed fuzz over the
    // modern-rules window (2000-01-01 .. ~2030), asserting cheap invariants of the
    // [start, end) contract: day/month membership, idempotent starts and bounded day length.
    @Test
    fun `stress - fuzz seeded timestamps keep half-open day and month invariants`() {
        val random = Random(FUZZ_SEED)
        repeat(1000) {
            val ts = random.nextLong(MODERN_RULES_LOWER_BOUND_MS, FUZZ_UPPER_BOUND_MS)

            val dayStart = TimePeriodUtils.getStartOfDay(ts)
            val dayEnd = TimePeriodUtils.getEndOfDay(ts)
            assertTrue("ts=$ts must be >= its day start", dayStart <= ts)
            assertTrue("ts=$ts must be < its day end", ts < dayEnd)
            assertEquals("ts=$ts start of day is idempotent", dayStart, TimePeriodUtils.getStartOfDay(dayStart))
            assertTrue(
                "ts=$ts day length ${dayEnd - dayStart} must be 23h, 24h or 25h",
                (dayEnd - dayStart) in listOf(23 * HOUR_MS, 24 * HOUR_MS, 25 * HOUR_MS)
            )

            val monthStart = TimePeriodUtils.getStartOfMonth(ts)
            val monthEnd = TimePeriodUtils.getEndOfMonth(ts)
            assertTrue("ts=$ts must be >= its month start", monthStart <= ts)
            assertTrue("ts=$ts must be < its month end", ts < monthEnd)
            assertEquals("ts=$ts start of month is a day start", monthStart, TimePeriodUtils.getStartOfDay(monthStart))
            assertTrue("ts=$ts days in month must be 28..31", TimePeriodUtils.getDaysInMonth(ts) in 28..31)
        }
    }

    // ============================================================================
    // SECTION 12: HALF-OPEN CONTIGUITY STRESS
    // ============================================================================

    @Test
    fun `stress - consecutive day ranges are contiguous`() {
        // Pinned to New York: the series 2024-03-09 .. 03-15 crosses the US spring forward (Mar 10).
        withZone(NY) {
            val start = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 9, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            for (i in 0..6) {
                val dayTs = TimePeriodUtils.addDays(start, i)
                val endOfDay = TimePeriodUtils.getEndOfDay(dayTs)
                val nextDayStart = TimePeriodUtils.getStartOfDay(TimePeriodUtils.addDays(dayTs, 1))
                assertEquals("Day $i end must equal day ${i+1} start (contiguous half-open)",
                    endOfDay, nextDayStart)
            }
        }
    }

    @Test
    fun `stress - consecutive month ranges are contiguous`() {
        val ref = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        for (offset in -6..5) {
            val (_, currEnd) = TimePeriodUtils.getMonthRange(ref, offset)
            val (nextStart, _) = TimePeriodUtils.getMonthRange(ref, offset + 1)
            assertEquals("Month $offset end must equal month ${offset + 1} start",
                currEnd, nextStart)
        }
    }

    // ============================================================================
    // SECTION 13: DST-AWARE WEEK CONTIGUITY
    // ============================================================================

    @Test
    fun `stress - consecutive week ranges contiguous across US DST spring forward`() {
        withZone(NY) {
            // US DST spring forward: March 10, 2024 — test weeks around this date
            val ref = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 10, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            for (offset in -2..2) {
                val (_, currEnd) = TimePeriodUtils.getWeekRange(ref, offset)
                val (nextStart, _) = TimePeriodUtils.getWeekRange(ref, offset + 1)
                assertEquals("Week $offset end must equal week ${offset + 1} start (DST spring)",
                    currEnd, nextStart)
            }
        }
    }

    @Test
    fun `stress - consecutive week ranges contiguous across EU DST spring forward`() {
        withZone("Europe/Athens") {
            // EU DST spring forward: March 31, 2024
            val ref = Calendar.getInstance().apply {
                set(2024, Calendar.MARCH, 31, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            for (offset in -2..2) {
                val (_, currEnd) = TimePeriodUtils.getWeekRange(ref, offset)
                val (nextStart, _) = TimePeriodUtils.getWeekRange(ref, offset + 1)
                assertEquals("Week $offset end must equal week ${offset + 1} start (EU DST spring)",
                    currEnd, nextStart)
            }
        }
    }

    @Test
    fun `stress - getEndOfWeek consistent with getWeekRange across multiple weeks`() {
        val ref = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        for (offset in -4..4) {
            val shifted = TimePeriodUtils.addDays(ref, offset * 7)
            val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(shifted)
            val endOfWeek = TimePeriodUtils.getEndOfWeek(shifted)
            assertEquals("getEndOfWeek and getWeekRange end must match for offset $offset",
                weekRangeEnd, endOfWeek)
        }
    }

    // ============================================================================
    // SECTION 14: isInRange FUZZ (fixed seed)
    // ============================================================================

    @Test
    fun `stress - isInRange consistent with manual half-open check across random timestamps`() {
        val random = Random(FUZZ_SEED)
        repeat(500) {
            val ts = random.nextLong(MODERN_RULES_LOWER_BOUND_MS, FUZZ_UPPER_BOUND_MS)
            val (start, end) = TimePeriodUtils.getDayRange(ts)

            // Timestamp must be in its own day
            assertTrue("ts=$ts should be in its day range",
                TimePeriodUtils.isInRange(ts, start, end))

            // End-exclusive must NOT be in the range
            assertFalse("dayEnd should not be in its own day range",
                TimePeriodUtils.isInRange(end, start, end))

            // Manual check matches
            assertEquals(ts >= start && ts < end,
                TimePeriodUtils.isInRange(ts, start, end))
        }
    }

    @Test
    fun `stress - isInRange month boundary - last ms included, first ms of next month excluded`() {
        for (month in 0..11) {
            val cal = Calendar.getInstance().apply {
                set(2024, month, 15, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(cal.timeInMillis)

            assertTrue("First ms of month $month should be in range",
                TimePeriodUtils.isInRange(monthStart, monthStart, monthEnd))

            assertFalse("First ms of next month should NOT be in range for month $month",
                TimePeriodUtils.isInRange(monthEnd, monthStart, monthEnd))

            // 1 ms before end is still in range
            assertTrue("Last ms of month $month should be in range",
                TimePeriodUtils.isInRange(monthEnd - 1, monthStart, monthEnd))
        }
    }
}
