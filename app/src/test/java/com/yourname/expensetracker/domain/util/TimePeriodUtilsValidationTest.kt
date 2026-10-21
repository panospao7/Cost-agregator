package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
 *
 * Design rules:
 * - No test depends on the JVM default time zone. Every zone-sensitive
 *   assertion runs under [withZone] (process-wide [GlobalTimeZoneTestLock],
 *   original zone restored in `finally`) for each zone of [ZONES], or for the
 *   explicit DST zone [NY].
 * - Tables are row-based: each row names its scenario, and every failure
 *   message carries the row name and the zone.
 * - Expected values are built by [epoch] from explicit local date-times resolved in
 *   the row's own zone (never from production helpers), or are hard-coded Instants
 *   for the DST rows.
 */
class TimePeriodUtilsValidationTest {

    private companion object {
        // Zones exercised by every table. None of these zones has a DST gap at
        // the local midnights / noons used by the rows below.
        val ZONES = listOf("UTC", "America/New_York", "Asia/Kolkata")
        const val NY = "America/New_York"
        const val HOUR_MS = 60L * 60 * 1000
    }

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

    private fun forEachZone(block: (String) -> Unit) {
        for (zoneId in ZONES) {
            withZone(zoneId) { block(zoneId) }
        }
    }

    private fun ldt(
        year: Int, month: Int, day: Int,
        hour: Int = 0, minute: Int = 0, second: Int = 0, millis: Int = 0
    ): LocalDateTime = LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000)

    // Epoch millis of the local date-time [local] in the explicit zone [zoneId].
    private fun epoch(zoneId: String, local: LocalDateTime): Long =
        local.atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    private fun instant(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ---------- Row types ----------

    private data class BoundaryRow(
        val name: String,
        val op: (Long) -> Long,
        val input: LocalDateTime,
        val expected: LocalDateTime
    )

    private data class RangeRow(
        val name: String,
        val op: (Long) -> Pair<Long, Long>,
        val input: LocalDateTime,
        val expectedStart: LocalDateTime,
        val expectedEnd: LocalDateTime
    )

    private data class RollingRow(val name: String, val days: Int, val expectedStart: LocalDateTime)

    private data class WindowRow(
        val name: String,
        val transaction: LocalDateTime,
        val expectedBeforeStart: Boolean,
        val expectedInside: Boolean
    )

    private data class InclusionRow(
        val name: String,
        val rangeOp: (Long) -> Pair<Long, Long>,
        val reference: LocalDateTime,
        val transaction: LocalDateTime,
        val expectedIncluded: Boolean
    )

    private data class DaysBetweenRow(
        val name: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val expected: Int
    )

    private data class FieldRow(
        val name: String,
        val op: (Long) -> Int,
        val input: LocalDateTime,
        val expected: Int
    )

    private data class ArithmeticRow(
        val name: String,
        val op: (Long) -> Long,
        val input: LocalDateTime,
        val expected: LocalDateTime
    )

    private data class SameMonthRow(
        val name: String,
        val first: LocalDateTime,
        val second: LocalDateTime,
        val expected: Boolean
    )

    private data class DstRow(
        val name: String,
        val op: (Long) -> Long,
        val inputIso: String,
        val expectedIso: String
    )

    // ========== SCENARIO 1/5/6/12/14: Known-data single-ended boundaries ==========

    @Test
    fun `boundary table - start and end helpers return exact local boundaries in every zone`() {
        val rows = listOf(
            BoundaryRow("getStartOfDay is 00:00:00.000", { TimePeriodUtils.getStartOfDay(it) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 4, 15)),
            BoundaryRow("getEndOfDay is next-day 00:00:00.000", { TimePeriodUtils.getEndOfDay(it) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 4, 16)),
            BoundaryRow("getStartOfWeek Wednesday -> Monday", { TimePeriodUtils.getStartOfWeek(it) }, ldt(2024, 4, 17, 10), ldt(2024, 4, 15)),
            BoundaryRow("getStartOfWeek Sunday -> previous Monday", { TimePeriodUtils.getStartOfWeek(it) }, ldt(2024, 4, 14, 10), ldt(2024, 4, 8)),
            BoundaryRow("getEndOfWeek Wednesday -> next Monday", { TimePeriodUtils.getEndOfWeek(it) }, ldt(2024, 4, 17, 10), ldt(2024, 4, 22)),
            BoundaryRow("getEndOfWeek Sunday -> next Monday", { TimePeriodUtils.getEndOfWeek(it) }, ldt(2024, 4, 14, 10), ldt(2024, 4, 15)),
            BoundaryRow("getStartOfMonth mid-month", { TimePeriodUtils.getStartOfMonth(it) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 4, 1)),
            BoundaryRow("getStartOfMonth on the 31st", { TimePeriodUtils.getStartOfMonth(it) }, ldt(2024, 1, 31, 12), ldt(2024, 1, 1)),
            BoundaryRow("getEndOfMonth is 1st of next month", { TimePeriodUtils.getEndOfMonth(it) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 5, 1)),
            BoundaryRow("getStartOfYear is Jan 1", { TimePeriodUtils.getStartOfYear(it) }, ldt(2024, 6, 15, 14, 30, 45), ldt(2024, 1, 1)),
            BoundaryRow("getEndOfYear is Jan 1 next year", { TimePeriodUtils.getEndOfYear(it) }, ldt(2024, 6, 15, 14, 30, 45), ldt(2025, 1, 1)),
            BoundaryRow("getStartOfQuarter Q1", { TimePeriodUtils.getStartOfQuarter(it) }, ldt(2024, 2, 15, 14, 30, 45), ldt(2024, 1, 1)),
            BoundaryRow("getStartOfQuarter Q2", { TimePeriodUtils.getStartOfQuarter(it) }, ldt(2024, 5, 15, 14, 30, 45), ldt(2024, 4, 1)),
            BoundaryRow("getStartOfQuarter Q3", { TimePeriodUtils.getStartOfQuarter(it) }, ldt(2024, 8, 15, 14, 30, 45), ldt(2024, 7, 1)),
            BoundaryRow("getStartOfQuarter Q4", { TimePeriodUtils.getStartOfQuarter(it) }, ldt(2024, 11, 15, 14, 30, 45), ldt(2024, 10, 1)),
            BoundaryRow("getEndOfQuarter Q1 is start of Q2", { TimePeriodUtils.getEndOfQuarter(it) }, ldt(2024, 2, 15, 14, 30, 45), ldt(2024, 4, 1))
        )
        assertEquals("boundary table size", 16, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                assertEquals(
                    "${row.name} [$zone]",
                    epoch(zone, row.expected),
                    row.op(epoch(zone, row.input))
                )
            }
        }
    }

    @Test
    fun `range table - offset range helpers return exact start and end in every zone`() {
        val rows = listOf(
            RangeRow("getMonthRange offset 0", { TimePeriodUtils.getMonthRange(it, 0) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 4, 1), ldt(2024, 5, 1)),
            RangeRow("getMonthRange offset -1", { TimePeriodUtils.getMonthRange(it, -1) }, ldt(2024, 4, 15, 14, 30, 45), ldt(2024, 3, 1), ldt(2024, 4, 1)),
            RangeRow("getYearRange offset 0", { TimePeriodUtils.getYearRange(it, 0) }, ldt(2024, 6, 15, 14, 30, 45), ldt(2024, 1, 1), ldt(2025, 1, 1)),
            RangeRow("getYearRange offset -1", { TimePeriodUtils.getYearRange(it, -1) }, ldt(2024, 6, 15, 14, 30, 45), ldt(2023, 1, 1), ldt(2024, 1, 1)),
            RangeRow("getQuarterRange offset 0 (Q2)", { TimePeriodUtils.getQuarterRange(it, 0) }, ldt(2024, 5, 15, 14, 30, 45), ldt(2024, 4, 1), ldt(2024, 7, 1)),
            RangeRow("getQuarterRange offset -1 (Q1)", { TimePeriodUtils.getQuarterRange(it, -1) }, ldt(2024, 5, 15, 14, 30, 45), ldt(2024, 1, 1), ldt(2024, 4, 1)),
            RangeRow("getWeekRange offset 0 is Monday to Monday", { TimePeriodUtils.getWeekRange(it, 0) }, ldt(2024, 4, 17, 10), ldt(2024, 4, 15), ldt(2024, 4, 22)),
            RangeRow("getWeekRange offset -1 is previous week", { TimePeriodUtils.getWeekRange(it, -1) }, ldt(2024, 4, 17, 10), ldt(2024, 4, 8), ldt(2024, 4, 15))
        )
        assertEquals("range table size", 8, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                val (start, end) = row.op(epoch(zone, row.input))
                assertEquals("${row.name} start [$zone]", epoch(zone, row.expectedStart), start)
                assertEquals("${row.name} end [$zone]", epoch(zone, row.expectedEnd), end)
            }
        }
    }

    // ========== SCENARIO 2: Period membership boundaries ==========

    @Test
    fun `inclusion table - transactions on period edges are included or excluded half-open`() {
        val dayOp: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getStartOfDay(it) to TimePeriodUtils.getEndOfDay(it) }
        val monthOp: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getMonthRange(it, 0) }
        val yearOp: (Long) -> Pair<Long, Long> = { TimePeriodUtils.getYearRange(it, 0) }
        val rows = listOf(
            InclusionRow("day: exactly 00:00:00.000 is in its day", dayOp, ldt(2024, 4, 15), ldt(2024, 4, 15), true),
            InclusionRow("day: 23:59:59.999 is in its day", dayOp, ldt(2024, 4, 15, 23, 59, 59, 999), ldt(2024, 4, 15, 23, 59, 59, 999), true),
            InclusionRow("month: 31st 23:59:59 is in March", monthOp, ldt(2024, 3, 31, 23, 59, 59), ldt(2024, 3, 31, 23, 59, 59), true),
            InclusionRow("month: 1st 00:00:00 is in April", monthOp, ldt(2024, 4, 1), ldt(2024, 4, 1), true),
            InclusionRow("year: Dec 31 23:59:59 is in 2024", yearOp, ldt(2024, 12, 31, 23, 59, 59), ldt(2024, 12, 31, 23, 59, 59), true),
            InclusionRow("year: Jan 1 00:00:00 is in 2024", yearOp, ldt(2024, 1, 1), ldt(2024, 1, 1), true),
            InclusionRow("month: April 1 00:00:00 is excluded from the March range", monthOp, ldt(2024, 3, 15, 12), ldt(2024, 4, 1), false)
        )
        assertEquals("inclusion table size", 7, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                val tx = epoch(zone, row.transaction)
                val (start, end) = row.rangeOp(epoch(zone, row.reference))
                if (row.expectedIncluded) {
                    assertTrue("${row.name}: tx >= start [$zone]", tx >= start)
                    assertTrue("${row.name}: tx < end [$zone]", tx < end)
                    assertTrue("${row.name}: isInRange [$zone]", TimePeriodUtils.isInRange(tx, start, end))
                } else {
                    assertFalse("${row.name}: isInRange [$zone]", TimePeriodUtils.isInRange(tx, start, end))
                }
            }
        }
    }

    // ========== SCENARIO 3: Rolling window accuracy ==========

    @Suppress("DEPRECATION")
    @Test
    fun `rolling window table - last N days starts N calendar days back at midnight and ends at now`() {
        val rows = listOf(
            RollingRow("30-day window (month period) from April 2 is March 3, not April 1", 30, ldt(2024, 3, 3)),
            RollingRow("7-day window (week period) from April 2 is March 26", 7, ldt(2024, 3, 26))
        )
        assertEquals("rolling window table size", 2, rows.size)

        forEachZone { zone ->
            val now = epoch(zone, ldt(2024, 4, 2, 12))
            for (row in rows) {
                val (start, end) = TimePeriodUtils.getLastNDaysRange(now, row.days)
                assertEquals("${row.name}: start [$zone]", epoch(zone, row.expectedStart), start)
                assertEquals("${row.name}: end is now [$zone]", now, end)
                assertEquals("${row.name}: daysBetween [$zone]", row.days, TimePeriodUtils.daysBetween(start, end))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `window table - transactions outside the 30-day window are excluded and inside are included`() {
        val rows = listOf(
            WindowRow("March 2 10:00 is outside (before March 3 start)", ldt(2024, 3, 2, 10), expectedBeforeStart = true, expectedInside = false),
            WindowRow("March 15 10:00 is inside", ldt(2024, 3, 15, 10), expectedBeforeStart = false, expectedInside = true)
        )
        assertEquals("window table size", 2, rows.size)

        forEachZone { zone ->
            val now = epoch(zone, ldt(2024, 4, 2, 12))
            val (windowStart, windowEnd) = TimePeriodUtils.getLastNDaysRange(now, 30)
            for (row in rows) {
                val tx = epoch(zone, row.transaction)
                assertEquals("${row.name}: before window start [$zone]", row.expectedBeforeStart, tx < windowStart)
                assertEquals("${row.name}: inside window [$zone]", row.expectedInside, tx >= windowStart && tx < windowEnd)
            }
        }
    }

    // ========== SCENARIO 4: Daily average calculation (calendar-day differences) ==========

    @Test
    fun `daysBetween table - counts calendar days and ignores time of day`() {
        val rows = listOf(
            DaysBetweenRow("exact days: April 1 00:00 to April 11 00:00", ldt(2024, 4, 1), ldt(2024, 4, 11), 10),
            DaysBetweenRow("ignores time of day: 23:59 to 00:01 next day", ldt(2024, 4, 1, 23, 59), ldt(2024, 4, 2, 0, 1), 1)
        )
        assertEquals("daysBetween table size", 2, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                assertEquals(
                    "${row.name} [$zone]",
                    row.expected,
                    TimePeriodUtils.daysBetween(epoch(zone, row.start), epoch(zone, row.end))
                )
            }
        }
    }

    // ========== SCENARIO 7: Leap year handling (standalone) ==========

    @Test
    fun `leap year February 29 is handled correctly`() {
        forEachZone { zone ->
            // 2024 is a leap year
            val timestamp = epoch(zone, ldt(2024, 2, 29, 12))
            assertEquals("start of Feb 2024 [$zone]", epoch(zone, ldt(2024, 2, 1)), TimePeriodUtils.getStartOfMonth(timestamp))
            assertEquals("end of Feb 2024 [$zone]", epoch(zone, ldt(2024, 3, 1)), TimePeriodUtils.getEndOfMonth(timestamp))
            assertEquals("days in Feb 2024 [$zone]", 29, TimePeriodUtils.getDaysInMonth(timestamp))
        }
    }

    @Test
    fun `non-leap year February 28 is handled correctly`() {
        forEachZone { zone ->
            // 2023 is not a leap year
            val timestamp = epoch(zone, ldt(2023, 2, 28, 12))
            assertEquals("start of Feb 2023 [$zone]", epoch(zone, ldt(2023, 2, 1)), TimePeriodUtils.getStartOfMonth(timestamp))
            assertEquals("end of Feb 2023 [$zone]", epoch(zone, ldt(2023, 3, 1)), TimePeriodUtils.getEndOfMonth(timestamp))
            assertEquals("days in Feb 2023 [$zone]", 28, TimePeriodUtils.getDaysInMonth(timestamp))
        }
    }

    // ========== SCENARIO 8: Canonical week key year rollover ==========

    @Test
    fun `getCanonicalWeekRangeFromKey handles year rollover deterministically`() {
        // Keys are hard-coded (never built from production week helpers). 2025-01-06 is the first
        // Monday of 2025, so "2025-53" = Jan 6 2025 + 52 weeks = Monday Jan 5 2026 (this is the
        // key the app-calendar helpers produce for Dec 31 2025), and 2026-01-05 is the first
        // Monday of 2026, so "2026-01" starts the same Monday. Both span Jan 5 -> Jan 12 2026.
        val keys = listOf("2025-53", "2026-01")
        forEachZone { zone ->
            val expectedStart = epoch(zone, ldt(2026, 1, 5))
            val expectedEnd = epoch(zone, ldt(2026, 1, 12))
            for (key in keys) {
                val (start, end) = TimePeriodUtils.getCanonicalWeekRangeFromKey(key)
                val startCal = Calendar.getInstance().apply { timeInMillis = start }
                val endCal = Calendar.getInstance().apply { timeInMillis = end }

                assertEquals("key $key start [$zone]", expectedStart, start)
                assertEquals("key $key end exclusive [$zone]", expectedEnd, end)
                assertEquals("key $key start is Monday [$zone]", Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
                assertEquals("key $key end is Monday [$zone]", Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
                assertEquals("key $key spans 7 days [$zone]", 7, TimePeriodUtils.daysBetween(start, end))
            }
        }
    }

    // ========== SCENARIO 9/12: Field extractors ==========

    @Suppress("DEPRECATION")
    @Test
    fun `field table - extractors return the local calendar field in every zone`() {
        val rows = listOf(
            FieldRow("getWeekOfYear Jan 1 2024 is week 1", { TimePeriodUtils.getWeekOfYear(it) }, ldt(2024, 1, 1, 12), 1),
            FieldRow("getDayOfMonth", { TimePeriodUtils.getDayOfMonth(it) }, ldt(2024, 4, 15, 12), 15),
            FieldRow("getDaysInMonth April has 30", { TimePeriodUtils.getDaysInMonth(it) }, ldt(2024, 4, 15, 12), 30),
            FieldRow("getDaysInMonth December has 31", { TimePeriodUtils.getDaysInMonth(it) }, ldt(2024, 12, 15, 12), 31),
            FieldRow("getDaysRemainingInMonth April 15 has 15", { TimePeriodUtils.getDaysRemainingInMonth(it) }, ldt(2024, 4, 15, 12), 15),
            FieldRow("getDayIndexFromMonthStart is 0-based", { TimePeriodUtils.getDayIndexFromMonthStart(it) }, ldt(2024, 4, 15, 12), 14),
            FieldRow("getYear on Dec 31 23:59:59", { TimePeriodUtils.getYear(it) }, ldt(2024, 12, 31, 23, 59, 59), 2024),
            FieldRow("getMonth is 0-indexed (April = 3)", { TimePeriodUtils.getMonth(it) }, ldt(2024, 4, 15, 12), 3),
            FieldRow("getDayOfWeek April 15 2024 is Monday", { TimePeriodUtils.getDayOfWeek(it) }, ldt(2024, 4, 15, 12), Calendar.MONDAY),
            FieldRow("getHourOfDay 14:30 is 14", { TimePeriodUtils.getHourOfDay(it) }, ldt(2024, 4, 15, 14, 30), 14)
        )
        assertEquals("field table size", 10, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                assertEquals("${row.name} [$zone]", row.expected, row.op(epoch(zone, row.input)))
            }
        }
    }

    // ========== SCENARIO 10/16: Date arithmetic ==========

    @Test
    fun `arithmetic table - addDays addMonths addYears keep wall time and coerce month ends`() {
        val rows = listOf(
            ArithmeticRow("addDays +10", { TimePeriodUtils.addDays(it, 10) }, ldt(2024, 4, 15, 12), ldt(2024, 4, 25, 12)),
            ArithmeticRow("addDays -10", { TimePeriodUtils.addDays(it, -10) }, ldt(2024, 4, 15, 12), ldt(2024, 4, 5, 12)),
            ArithmeticRow("addMonths +3", { TimePeriodUtils.addMonths(it, 3) }, ldt(2024, 4, 15, 12), ldt(2024, 7, 15, 12)),
            ArithmeticRow("addMonths +3 crosses the year boundary", { TimePeriodUtils.addMonths(it, 3) }, ldt(2024, 11, 15, 12), ldt(2025, 2, 15, 12)),
            ArithmeticRow("addYears +2", { TimePeriodUtils.addYears(it, 2) }, ldt(2024, 4, 15, 12), ldt(2026, 4, 15, 12)),
            ArithmeticRow("addMonths Jan 31 +1 is Feb 29 in a leap year", { TimePeriodUtils.addMonths(it, 1) }, ldt(2024, 1, 31, 12), ldt(2024, 2, 29, 12)),
            ArithmeticRow("addMonths Jan 31 +1 is Feb 28 in a non-leap year", { TimePeriodUtils.addMonths(it, 1) }, ldt(2023, 1, 31, 12), ldt(2023, 2, 28, 12)),
            ArithmeticRow("addDays Dec 30 +5 crosses the year boundary", { TimePeriodUtils.addDays(it, 5) }, ldt(2024, 12, 30, 12), ldt(2025, 1, 4, 12)),
            ArithmeticRow("addYears from leap day Feb 29 coerces to Feb 28", { TimePeriodUtils.addYears(it, 1) }, ldt(2024, 2, 29, 12), ldt(2025, 2, 28, 12))
        )
        assertEquals("arithmetic table size", 9, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                assertEquals("${row.name} [$zone]", epoch(zone, row.expected), row.op(epoch(zone, row.input)))
            }
        }
    }

    // ========== SCENARIO 11: Consistency checks ==========

    @Test
    fun `startOfDay endOfDay and startOfMonth endOfMonth are consistent on a non-DST date`() {
        forEachZone { zone ->
            val timestamp = epoch(zone, ldt(2024, 4, 15, 14, 30, 45))

            val startOfDay = TimePeriodUtils.getStartOfDay(timestamp)
            val endOfDay = TimePeriodUtils.getEndOfDay(timestamp)
            // endOfDay is exactly one calendar day after startOfDay (DST-safe contract check)
            assertEquals("one calendar day [$zone]", 1, TimePeriodUtils.daysBetween(startOfDay, endOfDay))
            // For a non-DST date, the difference is also exactly 24h
            assertEquals("exactly 24h [$zone]", startOfDay + 24 * HOUR_MS, endOfDay)

            val startOfMonth = TimePeriodUtils.getStartOfMonth(timestamp)
            val endOfMonth = TimePeriodUtils.getEndOfMonth(timestamp)
            assertEquals("endOfMonth is startOfMonth + 1 month [$zone]", TimePeriodUtils.addMonths(startOfMonth, 1), endOfMonth)
        }
    }

    @Test
    fun `isSameMonth table - same month is true and adjacent months are false`() {
        val rows = listOf(
            SameMonthRow("April 1 12:00 and April 30 23:59:59 share a month", ldt(2024, 4, 1, 12), ldt(2024, 4, 30, 23, 59, 59), true),
            SameMonthRow("April 30 23:59:59 and May 1 00:00 do not", ldt(2024, 4, 30, 23, 59, 59), ldt(2024, 5, 1), false)
        )
        assertEquals("isSameMonth table size", 2, rows.size)

        forEachZone { zone ->
            for (row in rows) {
                assertEquals(
                    "${row.name} [$zone]",
                    row.expected,
                    TimePeriodUtils.isSameMonth(epoch(zone, row.first), epoch(zone, row.second))
                )
            }
        }
    }

    // ========== SCENARIO 12: DST edge cases (standalone, pinned to New York) ==========

    @Test
    fun `DST table pinned to New York - 23 hour spring day, 25 hour fall day and calendar day count`() {
        // US spring forward: 2024-03-10 02:00 EST -> 03:00 EDT (07:00Z). Day is 23h.
        // US fall back:      2024-11-03 02:00 EDT -> 01:00 EST (06:00Z). Day is 25h.
        val startOfDay: (Long) -> Long = { TimePeriodUtils.getStartOfDay(it) }
        val endOfDay: (Long) -> Long = { TimePeriodUtils.getEndOfDay(it) }
        val rows = listOf(
            DstRow("spring day: start from 02:30-gap input (03:30 EDT)", startOfDay, "2024-03-10T07:30:00Z", "2024-03-10T05:00:00Z"),
            DstRow("spring day: end from 02:30-gap input (03:30 EDT)", endOfDay, "2024-03-10T07:30:00Z", "2024-03-11T04:00:00Z"),
            DstRow("fall day: start from first 01:30 (EDT)", startOfDay, "2024-11-03T05:30:00Z", "2024-11-03T04:00:00Z"),
            DstRow("fall day: end from first 01:30 (EDT)", endOfDay, "2024-11-03T05:30:00Z", "2024-11-04T05:00:00Z"),
            DstRow("fall day: start from second 01:30 (EST)", startOfDay, "2024-11-03T06:30:00Z", "2024-11-03T04:00:00Z"),
            DstRow("fall day: end from second 01:30 (EST)", endOfDay, "2024-11-03T06:30:00Z", "2024-11-04T05:00:00Z")
        )
        assertEquals("DST table size", 6, rows.size)

        withZone(NY) {
            for (row in rows) {
                assertEquals("${row.name} [$NY]", instant(row.expectedIso), row.op(instant(row.inputIso)))
            }

            val springNoon = instant("2024-03-10T16:00:00Z")
            assertEquals(
                "spring-forward day lasts 23h [$NY]",
                23 * HOUR_MS,
                TimePeriodUtils.getEndOfDay(springNoon) - TimePeriodUtils.getStartOfDay(springNoon)
            )
            val fallNoon = instant("2024-11-03T17:00:00Z")
            assertEquals(
                "fall-back day lasts 25h [$NY]",
                25 * HOUR_MS,
                TimePeriodUtils.getEndOfDay(fallNoon) - TimePeriodUtils.getStartOfDay(fallNoon)
            )

            // March 9 12:00 EST (17:00Z) to March 11 12:00 EDT (16:00Z): 47h but 2 calendar days.
            assertEquals(
                "daysBetween across spring forward counts calendar days [$NY]",
                2,
                TimePeriodUtils.daysBetween(instant("2024-03-09T17:00:00Z"), instant("2024-03-11T16:00:00Z"))
            )
        }
    }

    // ========== SCENARIO 13/14: Range helpers agree with single-ended helpers ==========

    @Test
    fun `range helpers agree with single-ended helpers and exclude the exclusive end`() {
        forEachZone { zone ->
            val dayTs = epoch(zone, ldt(2024, 4, 15, 14, 30, 45))
            val (dayStart, dayEnd) = TimePeriodUtils.getDayRange(dayTs)
            assertEquals("getDayRange start [$zone]", TimePeriodUtils.getStartOfDay(dayTs), dayStart)
            assertEquals("getDayRange end [$zone]", TimePeriodUtils.getEndOfDay(dayTs), dayEnd)
            assertTrue("getDayRange contains its timestamp [$zone]", TimePeriodUtils.isInRange(dayTs, dayStart, dayEnd))
            // end is start of next day; should NOT be in this day's range
            assertFalse("getDayRange excludes start of next day [$zone]", TimePeriodUtils.isInRange(dayEnd, dayStart, dayEnd))

            val weekTs = epoch(zone, ldt(2024, 4, 17, 10))
            val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(weekTs)
            assertEquals("getEndOfWeek matches getWeekRange end [$zone]", weekRangeEnd, TimePeriodUtils.getEndOfWeek(weekTs))
        }
    }
}
