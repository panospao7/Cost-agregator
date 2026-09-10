package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.TimeZone

/**
 * T4C Batch 2B — locks the migrated java.time implementations of
 * [TimePeriodUtils.getStartOfWeek], [TimePeriodUtils.getEndOfWeek] and
 * [TimePeriodUtils.getWeekRange] to exact expected values.
 *
 * The production week helpers now use:
 * ```
 * Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
 *     .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
 *     .atStartOfDay(zone)
 * ```
 * with `plusWeeks(1)` / `plusWeeks(offset)` for the exclusive end and offset
 * shift. This preserves the previous `Calendar` contract exactly:
 * - Monday start, locale-independent;
 * - half-open `[startInclusive, endExclusive)` 7-calendar-day ranges;
 * - system default timezone;
 * - DST weeks are 23 or 25 hours of wall-clock time (never fixed `DAY_IN_MILLIS`);
 * - `weekOffset` semantics unchanged;
 * - negative / epoch values handled deterministically.
 *
 * The suite also locks the **pre-Gregorian compatibility seam**: production
 * intentionally delegates timestamps strictly before the legacy
 * [java.util.GregorianCalendar] cutover (`1582-10-15T00:00:00Z`) to the legacy
 * `Calendar` algorithm so the pre-migration behavior (Julian date rules and the
 * timezone's standard offset) is reproduced exactly. That pre-cutover
 * compatibility is tested here by asserting production start/end/range equals
 * the independent legacy `Calendar` oracle at pre-1582 dates for UTC,
 * America/New_York, and Asia/Kolkata, for week offsets -1, 0 and +1.
 *
 * Design rules (mirrors TimePeriodUtilsT4CBatch2ATest):
 * - Every timestamp is a **fixed UTC instant**; no wall clock, no sleep, no @Ignore.
 * - Any test that reads a zone-dependent value mutates the JVM default timezone
 *   under [GlobalTimeZoneTestLock] and restores the original zone in `finally`.
 * - Expected values are asserted **exactly**, either as hardcoded literals, as
 *   values computed with the independent **legacy `Calendar` oracle** (the
 *   original pre-migration algorithm), or as values computed with an **explicit
 *   `java.time` oracle** that builds `ZoneId.of(zoneId)` explicitly — never by
 *   re-calling the production helpers through the system-default zone.
 * - The legacy `Calendar` oracle is compared for **every** timestamp.
 *   Post-cutover timestamps (modern app dates) must match both the legacy
 *   oracle and the explicit `java.time` oracle. Pre-cutover timestamps
 *   intentionally follow the legacy `Calendar` algorithm (Julian date rules +
 *   the timezone's standard offset) through the pre-Gregorian compatibility
 *   seam, so they are asserted against the independent legacy `Calendar`
 *   oracle only.
 * - DST boundary weeks are asserted by wall-clock duration (167h / 169h), never
 *   by fixed `DAY_IN_MILLIS` arithmetic.
 * - At the `Long` extremes: `Long.MAX_VALUE` inputs stay on the modern
 *   java.time path — `getEndOfWeek`/`getWeekRange` fail deterministically with
 *   `ArithmeticException` (documented controlled failure). `Long.MIN_VALUE`
 *   inputs are pre-cutover, so all week helpers follow the legacy Calendar seam
 *   and return its deterministic values (no exception); neither extreme ever
 *   silently wraps.
 */
class TimePeriodUtilsT4CBatch2BTest {

    private companion object {
        // Zone names exercised by the timezone matrix.
        val ZONES = listOf("UTC", "Asia/Kolkata", "Europe/Athens", "America/New_York")
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

    // Fixed UTC instants used throughout the suite.
    private val mondayNoonUtc = Instant.parse("2024-06-10T12:00:00Z").toEpochMilli()      // Monday
    private val sundayNoonUtc = Instant.parse("2024-06-16T12:00:00Z").toEpochMilli()      // Sunday, same week
    private val nextMondayNoonUtc = Instant.parse("2024-06-17T12:00:00Z").toEpochMilli()  // next Monday
    private val springWeekUtc = Instant.parse("2024-03-08T12:00:00Z").toEpochMilli()      // week Mon Mar 4 (US spring Mar 10)
    private val fallWeekUtc = Instant.parse("2024-10-30T12:00:00Z").toEpochMilli()        // week Mon Oct 28 (US fall Nov 3)
    private val newYear2025Utc = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()     // Wednesday, week Mon Dec 30 2024
    private val leapDay2024Utc = Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()
    private val feb2023Utc = Instant.parse("2023-02-15T12:00:00Z").toEpochMilli()
    private val epochZero = 0L
    private val minusOneSecond = -1000L
    private val preEpoch1950 = Instant.parse("1950-01-01T00:00:00Z").toEpochMilli()

    // ============================================================================
    // Independent oracles (never call the production helpers)
    // ============================================================================

    /** Legacy Calendar oracle reproducing the previous getStartOfWeek algorithm. */
    private fun legacyStartOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getEndOfWeek algorithm. */
    private fun legacyEndOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfWeek(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 7)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getWeekRange algorithm. */
    private fun legacyWeekRange(timestamp: Long, weekOffset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (weekOffset != 0) {
            cal.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
        }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 7)
        val endMs = cal.timeInMillis
        return startMs to endMs
    }

    /**
     * Explicit java.time oracle for the Monday start. Built with an explicit
     * [ZoneId.of] on the case's zone id — never via `ZoneId.systemDefault()`.
     */
    private fun explicitStartOfWeek(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        return Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** Explicit java.time oracle for the exclusive Monday end (plusWeeks(1)). */
    private fun explicitEndOfWeek(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        return Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** Explicit java.time oracle for a week range with [weekOffset]. */
    private fun explicitWeekRange(timestamp: Long, zoneId: String, weekOffset: Int): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        val weekStartDate = Instant.ofEpochMilli(timestamp)
            .atZone(zone)
            .toLocalDate()
            .plusWeeks(weekOffset.toLong())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val startMs = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = weekStartDate.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    // ============================================================================
    // Monday / Sunday inputs
    // ============================================================================

    @Test
    fun `Monday and Sunday inputs map to the same Monday boundaries`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val expectedStart = explicitStartOfWeek(mondayNoonUtc, zoneId)
                val expectedEnd = explicitEndOfWeek(mondayNoonUtc, zoneId)

                assertEquals("monday start $zoneId", expectedStart, TimePeriodUtils.getStartOfWeek(mondayNoonUtc))
                assertEquals("monday end $zoneId", expectedEnd, TimePeriodUtils.getEndOfWeek(mondayNoonUtc))
                // Sunday belongs to the same week (previous Monday).
                assertEquals("sunday start $zoneId", expectedStart, TimePeriodUtils.getStartOfWeek(sundayNoonUtc))
                assertEquals("sunday end $zoneId", expectedEnd, TimePeriodUtils.getEndOfWeek(sundayNoonUtc))
                // The next Monday starts the following week.
                assertEquals(
                    "next monday start $zoneId",
                    explicitStartOfWeek(nextMondayNoonUtc, zoneId),
                    TimePeriodUtils.getStartOfWeek(nextMondayNoonUtc)
                )
                assertNotEquals("next monday is a new week $zoneId", expectedStart, TimePeriodUtils.getStartOfWeek(nextMondayNoonUtc))
                assertEquals(7, TimePeriodUtils.daysBetween(
                    TimePeriodUtils.getStartOfWeek(sundayNoonUtc),
                    TimePeriodUtils.getEndOfWeek(sundayNoonUtc)
                ))
            }
        }
    }

    @Test
    fun `every day of the week maps to the same Monday start`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val weekStart = TimePeriodUtils.getStartOfWeek(mondayNoonUtc)
                for (dayOffset in 0..6) {
                    val dayTs = TimePeriodUtils.addDays(mondayNoonUtc, dayOffset)
                    assertEquals("day $dayOffset start $zoneId", weekStart, TimePeriodUtils.getStartOfWeek(dayTs))
                    assertEquals("day $dayOffset end $zoneId", explicitEndOfWeek(dayTs, zoneId), TimePeriodUtils.getEndOfWeek(dayTs))
                }
            }
        }
    }

    @Test
    fun `normal week boundaries are exact hardcoded instants per zone`() {
        // Week Mon 2024-06-10 … Mon 2024-06-17 local midnight per zone:
        //   UTC              06-10T00:00Z … 06-17T00:00Z
        //   Asia/Kolkata     06-09T18:30Z … 06-16T18:30Z  (+05:30)
        //   Europe/Athens    06-09T21:00Z … 06-16T21:00Z  (+03:00 EEST)
        //   America/New_York 06-10T04:00Z … 06-17T04:00Z  (-04:00 EDT)
        data class WeekCase(val zoneId: String, val startIso: String, val endIso: String)
        val cases = listOf(
            WeekCase("UTC", "2024-06-10T00:00:00Z", "2024-06-17T00:00:00Z"),
            WeekCase("Asia/Kolkata", "2024-06-09T18:30:00Z", "2024-06-16T18:30:00Z"),
            WeekCase("Europe/Athens", "2024-06-09T21:00:00Z", "2024-06-16T21:00:00Z"),
            WeekCase("America/New_York", "2024-06-10T04:00:00Z", "2024-06-17T04:00:00Z")
        )
        for (case in cases) {
            withZone(case.zoneId) {
                assertEquals("start ${case.zoneId}", Instant.parse(case.startIso).toEpochMilli(), TimePeriodUtils.getStartOfWeek(mondayNoonUtc))
                assertEquals("end ${case.zoneId}", Instant.parse(case.endIso).toEpochMilli(), TimePeriodUtils.getEndOfWeek(mondayNoonUtc))
            }
        }
    }

    // ============================================================================
    // Offset semantics (-1 / 0 / +1) and year rollover
    // ============================================================================

    @Test
    fun `week offset -1 0 +1 stays contiguous across the year rollover`() {
        withZone("UTC") {
            // 2025-01-01 is a Wednesday in the week Mon 2024-12-30 … Mon 2025-01-06.
            val offsets = listOf(-1, 0, 1)
            val ranges = offsets.map { TimePeriodUtils.getWeekRange(newYear2025Utc, it) }

            for ((i, offset) in offsets.withIndex()) {
                assertEquals("offset $offset legacy", legacyWeekRange(newYear2025Utc, offset), ranges[i])
                assertEquals("offset $offset explicit", explicitWeekRange(newYear2025Utc, "UTC", offset), ranges[i])
            }
            // Contiguity: end(offset) == start(offset+1).
            assertEquals("contiguity -1->0", ranges[1].first, ranges[0].second)
            assertEquals("contiguity 0->1", ranges[2].first, ranges[1].second)

            // Dec 30, Dec 31 and Jan 1 all live in the offset-0 week; Jan 6 starts the next.
            val dec30 = Instant.parse("2024-12-30T12:00:00Z").toEpochMilli()
            val jan1 = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
            val jan6 = Instant.parse("2025-01-06T12:00:00Z").toEpochMilli()
            assertEquals(ranges[1].first, TimePeriodUtils.getStartOfWeek(dec30))
            assertEquals(ranges[1].first, TimePeriodUtils.getStartOfWeek(jan1))
            assertEquals(ranges[1].second, TimePeriodUtils.getStartOfWeek(jan6))
        }
        // Hardcoded UTC expectations for the year-boundary weeks.
        withZone("UTC") {
            assertEquals(Instant.parse("2024-12-23T00:00:00Z").toEpochMilli(), TimePeriodUtils.getWeekRange(newYear2025Utc, -1).first)
            assertEquals(Instant.parse("2024-12-30T00:00:00Z").toEpochMilli(), TimePeriodUtils.getWeekRange(newYear2025Utc, 0).first)
            assertEquals(Instant.parse("2025-01-06T00:00:00Z").toEpochMilli(), TimePeriodUtils.getWeekRange(newYear2025Utc, 1).first)
            assertEquals(Instant.parse("2025-01-13T00:00:00Z").toEpochMilli(), TimePeriodUtils.getWeekRange(newYear2025Utc, 1).second)
        }
    }

    @Test
    fun `week offset shifts by whole weeks from any reference day`() {
        withZone("UTC") {
            // Reference Wednesday 2024-06-12; week Mon 06-10 … Mon 06-17.
            val ref = Instant.parse("2024-06-12T12:00:00Z").toEpochMilli()
            val (start0, end0) = TimePeriodUtils.getWeekRange(ref, 0)
            assertEquals(Instant.parse("2024-06-10T00:00:00Z").toEpochMilli(), start0)
            assertEquals(Instant.parse("2024-06-17T00:00:00Z").toEpochMilli(), end0)

            val (startM1, endM1) = TimePeriodUtils.getWeekRange(ref, -1)
            assertEquals(Instant.parse("2024-06-03T00:00:00Z").toEpochMilli(), startM1)
            assertEquals(start0, endM1)

            val (startP1, endP1) = TimePeriodUtils.getWeekRange(ref, 1)
            assertEquals(end0, startP1)
            assertEquals(Instant.parse("2024-06-24T00:00:00Z").toEpochMilli(), endP1)
        }
    }

    // ============================================================================
    // DST weeks (America/New_York 2024) — 23h and 25h durations
    // ============================================================================

    @Test
    fun `DST spring forward week has a 167 hour duration and contiguous boundaries`() {
        withZone("America/New_York") {
            val start = TimePeriodUtils.getStartOfWeek(springWeekUtc)
            val end = TimePeriodUtils.getEndOfWeek(springWeekUtc)
            val (rangeStart, rangeEnd) = TimePeriodUtils.getWeekRange(springWeekUtc, 0)

            // Week Mon Mar 4 (EST) … Mon Mar 11 (EDT); US spring-forward Sunday Mar 10.
            assertEquals(Instant.parse("2024-03-04T05:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(), end)
            assertEquals(601_200_000L, end - start) // 167h = 7 calendar days - 1h
            assertNotEquals("must not be fixed 7*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 7, end - start)

            assertEquals(start, rangeStart)
            assertEquals(end, rangeEnd)

            // Explicit java.time oracle.
            assertEquals(explicitStartOfWeek(springWeekUtc, "America/New_York"), start)
            assertEquals(explicitEndOfWeek(springWeekUtc, "America/New_York"), end)
            // Legacy Calendar oracle.
            assertEquals(legacyStartOfWeek(springWeekUtc), start)
            assertEquals(legacyEndOfWeek(springWeekUtc), end)
            // The previous week ends exactly at this week's start; the next starts at this week's end.
            assertEquals(start, TimePeriodUtils.getWeekRange(springWeekUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getWeekRange(springWeekUtc, 1).first)
        }
    }

    @Test
    fun `DST fall back week has a 169 hour duration and contiguous boundaries`() {
        withZone("America/New_York") {
            val start = TimePeriodUtils.getStartOfWeek(fallWeekUtc)
            val end = TimePeriodUtils.getEndOfWeek(fallWeekUtc)
            val (rangeStart, rangeEnd) = TimePeriodUtils.getWeekRange(fallWeekUtc, 0)

            // Week Mon Oct 28 (EDT) … Mon Nov 4 (EST); US fall-back Sunday Nov 3.
            assertEquals(Instant.parse("2024-10-28T04:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-11-04T05:00:00Z").toEpochMilli(), end)
            assertEquals(608_400_000L, end - start) // 169h = 7 calendar days + 1h
            assertNotEquals("must not be fixed 7*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 7, end - start)

            assertEquals(start, rangeStart)
            assertEquals(end, rangeEnd)

            assertEquals(explicitStartOfWeek(fallWeekUtc, "America/New_York"), start)
            assertEquals(explicitEndOfWeek(fallWeekUtc, "America/New_York"), end)
            assertEquals(legacyStartOfWeek(fallWeekUtc), start)
            assertEquals(legacyEndOfWeek(fallWeekUtc), end)
            assertEquals(start, TimePeriodUtils.getWeekRange(fallWeekUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getWeekRange(fallWeekUtc, 1).first)
        }
    }

    @Test
    fun `DST week boundaries stay at local midnight and Monday`() {
        withZone("America/New_York") {
            for (ts in listOf(springWeekUtc, fallWeekUtc)) {
                val startCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getStartOfWeek(ts) }
                val endCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getEndOfWeek(ts) }
                assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
                assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, startCal.get(Calendar.MINUTE))
                assertEquals(0, startCal.get(Calendar.SECOND))
                assertEquals(0, startCal.get(Calendar.MILLISECOND))
                assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
                assertEquals(0, endCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, endCal.get(Calendar.MINUTE))
                assertEquals(0, endCal.get(Calendar.SECOND))
                assertEquals(0, endCal.get(Calendar.MILLISECOND))
            }
        }
    }

    // ============================================================================
    // Leap / year boundaries
    // ============================================================================

    @Test
    fun `leap day week boundaries are exact and contiguous`() {
        withZone("UTC") {
            // Week Mon 2024-02-26 … Mon 2024-03-04 contains Feb 29 (leap).
            val start = TimePeriodUtils.getStartOfWeek(leapDay2024Utc)
            val end = TimePeriodUtils.getEndOfWeek(leapDay2024Utc)
            assertEquals(Instant.parse("2024-02-26T00:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-03-04T00:00:00Z").toEpochMilli(), end)
            assertEquals(7, TimePeriodUtils.daysBetween(start, end))
            assertTrue(TimePeriodUtils.isInRange(leapDay2024Utc, start, end))

            // Non-leap February 2023: week Mon 2023-02-13 … Mon 2023-02-20.
            assertEquals(Instant.parse("2023-02-13T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(feb2023Utc))
            assertEquals(Instant.parse("2023-02-20T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfWeek(feb2023Utc))
        }
    }

    @Test
    fun `century leap rules 2000 and 1900 produce Monday boundaries`() {
        withZone("UTC") {
            // 2000 is divisible by 400 -> leap year. Feb 29 2000 lies in week Mon 2000-02-28.
            val ts2000 = Instant.parse("2000-02-29T12:00:00Z").toEpochMilli()
            assertEquals(Instant.parse("2000-02-28T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(ts2000))
            assertEquals(Instant.parse("2000-03-06T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfWeek(ts2000))

            // 1900 is NOT a leap year (divisible by 100 but not 400). Feb 1900 week Mon 1900-02-26.
            val ts1900 = Instant.parse("1900-02-28T12:00:00Z").toEpochMilli()
            assertEquals(Instant.parse("1900-02-26T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(ts1900))
            assertEquals(Instant.parse("1900-03-05T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfWeek(ts1900))
        }
    }

    @Test
    fun `year boundary Dec 31 and Jan 1 share the same week`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val dec31 = Instant.parse("2024-12-31T12:00:00Z").toEpochMilli()
                val jan1 = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
                val start = TimePeriodUtils.getStartOfWeek(dec31)
                val end = TimePeriodUtils.getEndOfWeek(dec31)
                assertEquals("shared start $zoneId", start, TimePeriodUtils.getStartOfWeek(jan1))
                assertEquals("shared end $zoneId", end, TimePeriodUtils.getEndOfWeek(jan1))
                assertEquals("7 days $zoneId", 7, TimePeriodUtils.daysBetween(start, end))
                // The following week begins exactly at this week's end.
                assertEquals("next week $zoneId", end, TimePeriodUtils.getStartOfWeek(
                    Instant.parse("2025-01-06T12:00:00Z").toEpochMilli()
                ))
            }
        }
    }

    @Test
    fun `year 9999 week boundaries are exact proleptic instants`() {
        withZone("UTC") {
            val ts = LocalDateTime.of(9999, 12, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            // 9999-12-31 is a Friday -> Monday 9999-12-27, end Monday 10000-01-03.
            assertEquals(Instant.parse("9999-12-27T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(ts))
            // The exclusive end overflows into year 10000, which ISO_INSTANT cannot
            // express without a +/- sign prefix — compute the expected instant
            // numerically instead of parsing an ISO string.
            assertEquals(LocalDateTime.of(10000, 1, 3, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), TimePeriodUtils.getEndOfWeek(ts))
        }
    }

    // ============================================================================
    // Epoch / negative values
    // ============================================================================

    @Test
    fun `epoch and negative timestamps map deterministically`() {
        withZone("UTC") {
            // 1970-01-01 is a Thursday -> week starts 1969-12-29.
            assertEquals(Instant.parse("1969-12-29T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(epochZero))
            assertEquals(Instant.parse("1970-01-05T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfWeek(epochZero))
            // -1000 ms = 1969-12-31T23:59:59Z -> same week.
            assertEquals(TimePeriodUtils.getStartOfWeek(epochZero), TimePeriodUtils.getStartOfWeek(minusOneSecond))
        }
        withZone("America/New_York") {
            // Epoch instant is Wednesday 1969-12-31 19:00 EST -> week Mon 1969-12-29 05:00Z.
            assertEquals(Instant.parse("1969-12-29T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfWeek(epochZero))
            assertEquals(Instant.parse("1970-01-05T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfWeek(epochZero))
        }
    }

    // ============================================================================
    // Half-open exact end and invalid behavior
    // ============================================================================

    @Test
    fun `week end is exclusive and the last millisecond is included`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val start = TimePeriodUtils.getStartOfWeek(mondayNoonUtc)
                val end = TimePeriodUtils.getEndOfWeek(mondayNoonUtc)
                assertTrue("ts >= start", mondayNoonUtc >= start)
                assertTrue("ts < end", mondayNoonUtc < end)
                assertFalse("end excluded", TimePeriodUtils.isInRange(end, start, end))
                assertTrue("end-1 included", TimePeriodUtils.isInRange(end - 1, start, end))
                // The exact end instant belongs to the NEXT week (its Monday maps to itself).
                assertEquals("end maps to next week", end, TimePeriodUtils.getStartOfWeek(end))
            }
        }
    }

    @Test
    fun `extreme Long values behave deterministically without wrapping`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Long.MAX_VALUE lies *after* the cutover, so the modern
                // java.time path applies: the Monday midnight of its week is
                // still representable (strictly before the input), so
                // start-of-week succeeds and matches the explicit java.time
                // oracle. The exclusive end (next Monday) lies beyond
                // Long.MAX_VALUE, so end/range fail deterministically with
                // ArithmeticException.
                val maxStart = TimePeriodUtils.getStartOfWeek(Long.MAX_VALUE)
                assertEquals("max start explicit $zoneId", explicitStartOfWeek(Long.MAX_VALUE, zoneId), maxStart)
                assertTrue("max start before input", maxStart < Long.MAX_VALUE)
                assertTrue("max start fits in Long", maxStart >= Long.MIN_VALUE)

                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getEndOfWeek(Long.MAX_VALUE) }
                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getWeekRange(Long.MAX_VALUE) }

                // Long.MIN_VALUE lies *before* the cutover, so the legacy
                // pre-Gregorian compatibility seam applies: all week helpers
                // delegate to the legacy Calendar implementation and return its
                // deterministic values (no exception), exactly like the day
                // seam. Expected values come from the independent legacy
                // Calendar oracle (offset 0; the -1/0/+1 offset matrix is
                // covered by the dedicated pre-Gregorian test).
                assertEquals(
                    "min start legacy $zoneId",
                    legacyStartOfWeek(Long.MIN_VALUE),
                    TimePeriodUtils.getStartOfWeek(Long.MIN_VALUE)
                )
                assertEquals(
                    "min end legacy $zoneId",
                    legacyEndOfWeek(Long.MIN_VALUE),
                    TimePeriodUtils.getEndOfWeek(Long.MIN_VALUE)
                )
                assertEquals(
                    "min range legacy $zoneId",
                    legacyWeekRange(Long.MIN_VALUE, 0),
                    TimePeriodUtils.getWeekRange(Long.MIN_VALUE, 0)
                )
            }
        }
    }

    // ============================================================================
    // Consistency
    // ============================================================================

    @Test
    fun `getWeekRange is consistent with getStartOfWeek and getEndOfWeek`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(mondayNoonUtc, sundayNoonUtc, springWeekUtc, fallWeekUtc, newYear2025Utc)) {
                    val (start, end) = TimePeriodUtils.getWeekRange(ts, 0)
                    assertEquals("start $zoneId ts=$ts", TimePeriodUtils.getStartOfWeek(ts), start)
                    assertEquals("end $zoneId ts=$ts", TimePeriodUtils.getEndOfWeek(ts), end)
                }
            }
        }
    }

    // ============================================================================
    // Pre-Gregorian-cutover dates (legacy compatibility seam)
    // ============================================================================

    @Test
    fun `pre Gregorian cutover dates match the legacy Calendar oracle across zones and offsets`() {
        // Pre-Gregorian compatibility seam: timestamps strictly before the
        // legacy GregorianCalendar cutover (1582-10-15T00:00:00Z) are delegated
        // to the private legacy week helpers, so the pre-migration Calendar
        // results (Julian date rules + the timezone's standard offset) are
        // reproduced exactly. Year-1000/1400/1500 and cutover-adjacent
        // 1582-10-04 / 1582-10-10 timestamps are all pre-cutover, so production
        // must equal the independent legacy Calendar oracle — NOT the proleptic
        // java.time result, which applies historical Local Mean Time offsets
        // and therefore diverges for non-UTC zones (America/New_York -04:56:02
        // LMT vs -05:00 standard, Asia/Kolkata +05:53:28 LMT vs +05:30
        // standard). Exercised for week offsets -1, 0 and +1.
        val cases = listOf(
            Pair("UTC", "1000-01-01T12:00:00Z"),
            Pair("UTC", "1400-06-15T12:00:00Z"),
            Pair("UTC", "1500-02-28T12:00:00Z"),
            Pair("UTC", "1582-10-04T12:00:00Z"),
            Pair("UTC", "1582-10-10T12:00:00Z"),
            Pair("America/New_York", "1000-01-01T12:00:00Z"),
            Pair("America/New_York", "1400-06-15T12:00:00Z"),
            Pair("America/New_York", "1500-02-28T12:00:00Z"),
            Pair("America/New_York", "1582-10-04T12:00:00Z"),
            Pair("America/New_York", "1582-10-10T12:00:00Z"),
            Pair("Asia/Kolkata", "1000-01-01T12:00:00Z"),
            Pair("Asia/Kolkata", "1400-06-15T12:00:00Z"),
            Pair("Asia/Kolkata", "1500-02-28T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-04T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-10T12:00:00Z")
        )
        for ((zoneId, iso) in cases) {
            withZone(zoneId) {
                val ts = Instant.parse(iso).toEpochMilli()
                assertEquals("start legacy $zoneId $iso", legacyStartOfWeek(ts), TimePeriodUtils.getStartOfWeek(ts))
                assertEquals("end legacy $zoneId $iso", legacyEndOfWeek(ts), TimePeriodUtils.getEndOfWeek(ts))
                for (offset in listOf(-1, 0, 1)) {
                    assertEquals(
                        "range legacy $zoneId $iso offset=$offset",
                        legacyWeekRange(ts, offset),
                        TimePeriodUtils.getWeekRange(ts, offset)
                    )
                }
            }
        }
    }

    // ============================================================================
    // Regression: migrated implementation must match the independent oracles
    // (post-cutover samples only; see class docs for the pre-cutover note)
    // ============================================================================

    @Test
    fun `migrated week boundaries match the legacy Calendar oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            mondayNoonUtc,
            sundayNoonUtc,
            nextMondayNoonUtc,
            springWeekUtc,
            fallWeekUtc,
            newYear2025Utc,
            leapDay2024Utc,
            feb2023Utc,
            Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), // spring-forward Sunday pre-gap
            Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), // spring-forward Sunday post-gap
            Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), // fall-back overlap first occurrence
            Instant.parse("2024-11-03T06:30:00Z").toEpochMilli(), // fall-back overlap second occurrence
            Instant.parse("2024-12-31T23:59:59Z").toEpochMilli()  // last instant of the year
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("legacy getStartOfWeek $zoneId ts=$ts", legacyStartOfWeek(ts), TimePeriodUtils.getStartOfWeek(ts))
                    assertEquals("legacy getEndOfWeek $zoneId ts=$ts", legacyEndOfWeek(ts), TimePeriodUtils.getEndOfWeek(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "legacy getWeekRange $zoneId ts=$ts offset=$offset",
                            legacyWeekRange(ts, offset),
                            TimePeriodUtils.getWeekRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `migrated week boundaries match the explicit java time oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            mondayNoonUtc,
            sundayNoonUtc,
            springWeekUtc,
            fallWeekUtc,
            newYear2025Utc,
            leapDay2024Utc
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("explicit getStartOfWeek $zoneId ts=$ts", explicitStartOfWeek(ts, zoneId), TimePeriodUtils.getStartOfWeek(ts))
                    assertEquals("explicit getEndOfWeek $zoneId ts=$ts", explicitEndOfWeek(ts, zoneId), TimePeriodUtils.getEndOfWeek(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "explicit getWeekRange $zoneId ts=$ts offset=$offset",
                            explicitWeekRange(ts, zoneId, offset),
                            TimePeriodUtils.getWeekRange(ts, offset)
                        )
                    }
                }
            }
        }
    }
}
