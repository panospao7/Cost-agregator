package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * T4C Batch 2C — locks the migrated java.time implementations of
 * [TimePeriodUtils.getStartOfMonth], [TimePeriodUtils.getEndOfMonth],
 * [TimePeriodUtils.getMonthRange] (both the timestamp+offset overload and the
 * year/month overload) and [TimePeriodUtils.parseMonthKeyToRange] to exact
 * expected values.
 *
 * The production month helpers now use:
 * ```
 * Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
 *     -> YearMonth.from(localDate).atDay(1).atStartOfDay(zone)
 * ```
 * with `YearMonth.plusMonths(1)` / `YearMonth.plusMonths(offset)` for the
 * exclusive end and offset shift. This preserves the previous `Calendar`
 * contract exactly:
 * - half-open `[startInclusive, endExclusive)` month ranges;
 * - month-end coercion (Jan 31 → Feb 28/29, Mar 31 → Feb 29 in leap years);
 * - Dec → Jan rollover across year boundaries;
 * - system default timezone;
 * - DST months contain 23/25-hour boundary days (never fixed `DAY_IN_MILLIS`);
 * - `monthOffset` semantics unchanged;
 * - the year/month overload stays 1-based with the legacy lenient behavior for
 *   out-of-range months (0 → previous December, 13 → next January).
 *
 * The suite also locks the **pre-Gregorian compatibility seam**: production
 * intentionally delegates timestamps strictly before the legacy
 * [java.util.GregorianCalendar] cutover (`1582-10-15T00:00:00Z`) to the legacy
 * `Calendar` algorithm so the pre-migration behavior (Julian date rules and the
 * timezone's standard offset) is reproduced exactly. That pre-cutover
 * compatibility is tested here by asserting production start/end/range equals
 * the independent legacy `Calendar` oracle at year-1000/1400/1500 and
 * cutover-adjacent 1582 dates for UTC, America/New_York, Asia/Kolkata and
 * Europe/Athens, for month offsets -2..+2. The **exact seam boundary** is
 * also locked with fixed instants around 1582-10-14T12:00Z /
 * 1582-10-15T00:00Z: the last millisecond before the cutover and everything
 * before it stay on the legacy path (matching the legacy `Calendar` oracle),
 * while the cutover instant itself (`1582-10-15T00:00:00Z`), the first
 * millisecond after and 1582-10-15T12:00Z switch to the java.time path
 * (matching the explicit `java.time` oracle). Those boundary tests also
 * assert the half-open ranges stay contiguous (no overlap/gap) and that the
 * month labels of the input instant, the range start and the last instant
 * inside the range all stay "1582-10" for every zone.
 *
 * Design rules (mirrors TimePeriodUtilsT4CBatch2BTest):
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
 * - DST boundary months are asserted by wall-clock duration, never by fixed
 *   `DAY_IN_MILLIS` arithmetic.
 * - At the `Long` extremes: `Long.MAX_VALUE` inputs stay on the modern
 *   java.time path — `getStartOfMonth(Long.MAX_VALUE)` succeeds and matches the
 *   explicit java.time oracle, while `getEndOfMonth`/`getMonthRange` fail
 *   deterministically with `ArithmeticException` (documented controlled
 *   failure). `Long.MIN_VALUE` inputs are pre-cutover, so all month helpers
 *   follow the legacy Calendar seam and return its deterministic values (no
 *   exception); neither extreme ever silently wraps.
 */
class TimePeriodUtilsT4CBatch2CTest {

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
    private val juneNoonUtc = Instant.parse("2024-06-15T12:00:00Z").toEpochMilli()
    private val jan31LeapUtc = Instant.parse("2024-01-31T12:00:00Z").toEpochMilli()
    private val jan31NonLeapUtc = Instant.parse("2023-01-31T12:00:00Z").toEpochMilli()
    private val mar31LeapUtc = Instant.parse("2024-03-31T12:00:00Z").toEpochMilli()
    private val dec31Utc = Instant.parse("2024-12-31T12:00:00Z").toEpochMilli()
    private val springMonthUtc = Instant.parse("2024-03-10T12:00:00Z").toEpochMilli() // US spring-forward month
    private val fallMonthUtc = Instant.parse("2024-11-03T12:00:00Z").toEpochMilli()  // US fall-back month
    private val leapDay2024Utc = Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()
    private val feb2023Utc = Instant.parse("2023-02-15T12:00:00Z").toEpochMilli()
    private val epochZero = 0L
    private val minusOneSecond = -1000L
    private val preEpoch1950 = Instant.parse("1950-01-01T00:00:00Z").toEpochMilli()

    // Exact legacy GregorianCalendar cutover instant (1582-10-15T00:00:00Z).
    // This is the same hardcoded constant the production seam compares
    // against (`timestamp < cutover` => legacy path); asserted to equal
    // Instant.parse in the cutover-boundary tests.
    private val cutoverEpochMillis: Long = -12219292800000L
    // Fixed instants immediately around the cutover (all fixed UTC instants):
    // 1582-10-14T12:00Z and the last millisecond before the cutover are
    // strictly pre-cutover; the cutover instant, the first millisecond after,
    // and 1582-10-15T12:00Z are at/after the cutover.
    private val preCutoverNoonUtc = Instant.parse("1582-10-14T12:00:00Z").toEpochMilli()
    private val cutoverMinusOne = cutoverEpochMillis - 1
    private val cutoverExact = cutoverEpochMillis
    private val cutoverPlusOne = cutoverEpochMillis + 1
    private val postCutoverNoonUtc = Instant.parse("1582-10-15T12:00:00Z").toEpochMilli()

    // ============================================================================
    // Independent oracles (never call the production helpers)
    // ============================================================================

    /** Legacy Calendar oracle reproducing the previous getStartOfMonth algorithm. */
    private fun legacyStartOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getEndOfMonth algorithm. */
    private fun legacyEndOfMonth(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfMonth(timestamp)
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getMonthRange algorithm. */
    private fun legacyMonthRange(timestamp: Long, monthOffset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (monthOffset != 0) {
            cal.add(Calendar.MONTH, monthOffset)
        }
        val start = legacyStartOfMonth(cal.timeInMillis)
        val end = legacyEndOfMonth(cal.timeInMillis)
        return start to end
    }

    /** Legacy Calendar oracle for the year/month overload (1-based month input). */
    private fun legacyMonthRangeForYearMonth(year: Int, month: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return legacyMonthRange(cal.timeInMillis, 0)
    }

    /**
     * Legacy Calendar oracle for the wrapped timestamp the (year, month)
     * overload feeds into the pre-Gregorian compatibility seam: the lenient
     * [Calendar] construction with the raw int [year]/[month] values normalized
     * to `timeInMillis`. Used only to decide which independent oracle family
     * validates an Int-extreme range (legacy Calendar when the wrapped
     * timestamp is before the cutover, explicit java.time at/after it).
     */
    private fun legacyYearMonthWrappedTimestamp(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Explicit java.time oracle for start-of-month. Built with an explicit
     * [ZoneId.of] on the case's zone id — never via `ZoneId.systemDefault()`.
     */
    private fun explicitStartOfMonth(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return YearMonth.from(localDate).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for the exclusive month end (plusMonths(1)). */
    private fun explicitEndOfMonth(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return YearMonth.from(localDate).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for a month range with [monthOffset]. */
    private fun explicitMonthRange(timestamp: Long, zoneId: String, monthOffset: Int): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        val month = YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate())
            .plusMonths(monthOffset.toLong())
        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    /** Explicit java.time oracle for the year/month overload (1-based month input). */
    private fun explicitMonthRangeForYearMonth(year: Int, month: Int, zoneId: String): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        val month = YearMonth.of(year, month)
        val startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    /**
     * Legacy Calendar month-label oracle (`yyyy-MM`): the year/month the
     * independent [Calendar] reports for [timestamp] in the current default
     * zone. Used to assert that pre-cutover ranges keep the correct month
     * label through the Julian calendar before the seam switches.
     */
    private fun legacyMonthLabel(timestamp: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return String.format("%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /**
     * Explicit java.time month-label oracle (`yyyy-MM`): the proleptic
     * Gregorian [YearMonth] of [timestamp] in [zoneId]. Used to assert that
     * cutover/post-cutover ranges keep the correct month label through the
     * modern java.time path.
     */
    private fun explicitMonthLabel(timestamp: Long, zoneId: String): String {
        return YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(zoneId))).toString()
    }

    // ============================================================================
    // Normal month boundaries
    // ============================================================================

    @Test
    fun `normal month boundaries are exact hardcoded instants per zone`() {
        // Month June 2024 (post-DST in Europe/Athens and America/New_York):
        //   UTC              06-01T00:00Z … 07-01T00:00Z
        //   Asia/Kolkata     05-31T18:30Z … 06-30T18:30Z  (+05:30)
        //   Europe/Athens    05-31T21:00Z … 06-30T21:00Z  (+03:00 EEST)
        //   America/New_York 06-01T04:00Z … 07-01T04:00Z  (-04:00 EDT)
        data class MonthCase(val zoneId: String, val startIso: String, val endIso: String)
        val cases = listOf(
            MonthCase("UTC", "2024-06-01T00:00:00Z", "2024-07-01T00:00:00Z"),
            MonthCase("Asia/Kolkata", "2024-05-31T18:30:00Z", "2024-06-30T18:30:00Z"),
            MonthCase("Europe/Athens", "2024-05-31T21:00:00Z", "2024-06-30T21:00:00Z"),
            MonthCase("America/New_York", "2024-06-01T04:00:00Z", "2024-07-01T04:00:00Z")
        )
        for (case in cases) {
            withZone(case.zoneId) {
                assertEquals("start ${case.zoneId}", Instant.parse(case.startIso).toEpochMilli(), TimePeriodUtils.getStartOfMonth(juneNoonUtc))
                assertEquals("end ${case.zoneId}", Instant.parse(case.endIso).toEpochMilli(), TimePeriodUtils.getEndOfMonth(juneNoonUtc))
            }
        }
    }

    @Test
    fun `normal month boundaries match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val ts = juneNoonUtc
                assertEquals("legacy start $zoneId", legacyStartOfMonth(ts), TimePeriodUtils.getStartOfMonth(ts))
                assertEquals("explicit start $zoneId", explicitStartOfMonth(ts, zoneId), TimePeriodUtils.getStartOfMonth(ts))
                assertEquals("legacy end $zoneId", legacyEndOfMonth(ts), TimePeriodUtils.getEndOfMonth(ts))
                assertEquals("explicit end $zoneId", explicitEndOfMonth(ts, zoneId), TimePeriodUtils.getEndOfMonth(ts))
            }
        }
    }

    // ============================================================================
    // Month offset semantics (-2 .. +2)
    // ============================================================================

    @Test
    fun `month offsets -2 through +2 stay contiguous and match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val offsets = (-2..2).toList()
                val ranges = offsets.map { TimePeriodUtils.getMonthRange(juneNoonUtc, it) }

                for ((i, offset) in offsets.withIndex()) {
                    assertEquals("offset $offset legacy $zoneId", legacyMonthRange(juneNoonUtc, offset), ranges[i])
                    assertEquals("offset $offset explicit $zoneId", explicitMonthRange(juneNoonUtc, zoneId, offset), ranges[i])
                }
                for (offset in -2..1) {
                    val idx = offset - (-2)
                    assertEquals("contiguity $offset->${offset + 1} $zoneId", ranges[idx + 1].first, ranges[idx].second)
                }
            }
        }
    }

    @Test
    fun `month offset -1 from any reference day lands on the previous month`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Reference mid-June 2024 and reference Dec 31 2024.
                for (ts in listOf(juneNoonUtc, dec31Utc)) {
                    val (start, end) = TimePeriodUtils.getMonthRange(ts, -1)
                    assertEquals("prev legacy $zoneId ts=$ts", legacyMonthRange(ts, -1), start to end)
                    assertEquals("prev explicit $zoneId ts=$ts", explicitMonthRange(ts, zoneId, -1), start to end)
                }
            }
        }
    }

    // ============================================================================
    // Month-end coercion (Jan 31 / Feb leap / non-leap / Mar 31)
    // ============================================================================

    @Test
    fun `Jan 31 offset shifts land on the correct month in leap and non-leap years`() {
        withZone("UTC") {
            // Leap 2024: Jan 31 + 1 month -> February 2024 (29 days), + 2 -> March 2024.
            assertEquals(Instant.parse("2024-02-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31LeapUtc, 1).first)
            assertEquals(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31LeapUtc, 1).second)
            assertEquals(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31LeapUtc, 2).first)

            // Non-leap 2023: Jan 31 + 1 month -> February 2023 (28 days), + 2 -> March 2023.
            assertEquals(Instant.parse("2023-02-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31NonLeapUtc, 1).first)
            assertEquals(Instant.parse("2023-03-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31NonLeapUtc, 1).second)
            assertEquals(Instant.parse("2023-03-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(jan31NonLeapUtc, 2).first)

            // Mar 31 - 1 month -> February 2024 (leap year, Feb 29 exists).
            assertEquals(Instant.parse("2024-02-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(mar31LeapUtc, -1).first)
            assertEquals(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(mar31LeapUtc, -1).second)
        }
    }

    @Test
    fun `February ranges are 29 or 28 days and match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val febLeap = TimePeriodUtils.getMonthRange(leapDay2024Utc)
                val febNonLeap = TimePeriodUtils.getMonthRange(feb2023Utc)
                assertEquals("leap start $zoneId", legacyStartOfMonth(leapDay2024Utc), febLeap.first)
                assertEquals("leap end $zoneId", legacyEndOfMonth(leapDay2024Utc), febLeap.second)
                assertEquals("non-leap start $zoneId", legacyStartOfMonth(feb2023Utc), febNonLeap.first)
                assertEquals("non-leap end $zoneId", legacyEndOfMonth(feb2023Utc), febNonLeap.second)
            }
        }
        withZone("UTC") {
            assertEquals(29L * 24 * 60 * 60 * 1000, TimePeriodUtils.getMonthRange(leapDay2024Utc).second - TimePeriodUtils.getMonthRange(leapDay2024Utc).first)
            assertEquals(28L * 24 * 60 * 60 * 1000, TimePeriodUtils.getMonthRange(feb2023Utc).second - TimePeriodUtils.getMonthRange(feb2023Utc).first)
        }
    }

    // ============================================================================
    // Dec -> Jan rollover
    // ============================================================================

    @Test
    fun `Dec to Jan rollover keeps contiguous half-open month ranges`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val decRange = TimePeriodUtils.getMonthRange(dec31Utc, 0)
                val janRange = TimePeriodUtils.getMonthRange(dec31Utc, 1)
                assertEquals("dec legacy $zoneId", legacyMonthRange(dec31Utc, 0), decRange)
                assertEquals("jan legacy $zoneId", legacyMonthRange(dec31Utc, 1), janRange)
                assertEquals("contiguity $zoneId", janRange.first, decRange.second)
            }
        }
        withZone("UTC") {
            assertEquals(Instant.parse("2024-12-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfMonth(dec31Utc))
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfMonth(dec31Utc))
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfMonth(TimePeriodUtils.addDays(dec31Utc, 1)))
        }
    }

    // ============================================================================
    // DST month boundaries (America/New_York 2024)
    // ============================================================================

    @Test
    fun `DST spring forward month has a 31 day minus 1 hour duration`() {
        withZone("America/New_York") {
            // March 2024: Mar 1 00:00 EST = 05:00Z; Apr 1 00:00 EDT = 04:00Z.
            val start = TimePeriodUtils.getStartOfMonth(springMonthUtc)
            val end = TimePeriodUtils.getEndOfMonth(springMonthUtc)
            assertEquals(Instant.parse("2024-03-01T05:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-04-01T04:00:00Z").toEpochMilli(), end)
            assertEquals(31L * 24 * 60 * 60 * 1000 - 3_600_000L, end - start)
            assertNotEquals("must not be fixed 31*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 31, end - start)

            assertEquals(explicitStartOfMonth(springMonthUtc, "America/New_York"), start)
            assertEquals(explicitEndOfMonth(springMonthUtc, "America/New_York"), end)
            assertEquals(legacyStartOfMonth(springMonthUtc), start)
            assertEquals(legacyEndOfMonth(springMonthUtc), end)
            // The previous month ends exactly at this month's start; the next starts at this month's end.
            assertEquals(start, TimePeriodUtils.getMonthRange(springMonthUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getMonthRange(springMonthUtc, 1).first)
        }
    }

    @Test
    fun `DST fall back month has a 30 day plus 1 hour duration`() {
        withZone("America/New_York") {
            // November 2024: Nov 1 00:00 EDT = 04:00Z; Dec 1 00:00 EST = 05:00Z.
            val start = TimePeriodUtils.getStartOfMonth(fallMonthUtc)
            val end = TimePeriodUtils.getEndOfMonth(fallMonthUtc)
            assertEquals(Instant.parse("2024-11-01T04:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-12-01T05:00:00Z").toEpochMilli(), end)
            assertEquals(30L * 24 * 60 * 60 * 1000 + 3_600_000L, end - start)
            assertNotEquals("must not be fixed 30*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 30, end - start)

            assertEquals(explicitStartOfMonth(fallMonthUtc, "America/New_York"), start)
            assertEquals(explicitEndOfMonth(fallMonthUtc, "America/New_York"), end)
            assertEquals(legacyStartOfMonth(fallMonthUtc), start)
            assertEquals(legacyEndOfMonth(fallMonthUtc), end)
            assertEquals(start, TimePeriodUtils.getMonthRange(fallMonthUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getMonthRange(fallMonthUtc, 1).first)
        }
    }

    @Test
    fun `month boundaries stay at local midnight and first of month`() {
        withZone("America/New_York") {
            for (ts in listOf(springMonthUtc, fallMonthUtc, juneNoonUtc)) {
                val startCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getStartOfMonth(ts) }
                val endCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getEndOfMonth(ts) }
                assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
                assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, startCal.get(Calendar.MINUTE))
                assertEquals(0, startCal.get(Calendar.SECOND))
                assertEquals(0, startCal.get(Calendar.MILLISECOND))
                assertEquals(1, endCal.get(Calendar.DAY_OF_MONTH))
                assertEquals(0, endCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(0, endCal.get(Calendar.MINUTE))
                assertEquals(0, endCal.get(Calendar.SECOND))
                assertEquals(0, endCal.get(Calendar.MILLISECOND))
            }
        }
    }

    // ============================================================================
    // Pre-Gregorian-cutover dates (legacy compatibility seam)
    // ============================================================================

    @Test
    fun `pre Gregorian cutover month boundaries match the legacy Calendar oracle across zones and offsets`() {
        // Pre-Gregorian compatibility seam: timestamps strictly before the
        // legacy GregorianCalendar cutover (1582-10-15T00:00:00Z) are delegated
        // to the private legacy month helpers, so the pre-migration Calendar
        // results (Julian date rules + the timezone's standard offset) are
        // reproduced exactly. Year-1000/1400/1500 and cutover-adjacent
        // 1582-10-01 / 1582-10-10 timestamps are all pre-cutover, so production
        // must equal the independent legacy Calendar oracle — NOT the proleptic
        // java.time result, which applies historical Local Mean Time offsets
        // and therefore diverges for non-UTC zones (America/New_York -04:56:02
        // LMT vs -05:00 standard, Asia/Kolkata +05:53:28 LMT vs +05:30
        // standard). Exercised for month offsets -2..+2.
        val cases = listOf(
            Pair("UTC", "1000-01-01T12:00:00Z"),
            Pair("UTC", "1400-06-15T12:00:00Z"),
            Pair("UTC", "1500-02-28T12:00:00Z"),
            Pair("UTC", "1500-06-15T12:00:00Z"),
            Pair("UTC", "1582-10-01T12:00:00Z"),
            Pair("UTC", "1582-10-10T12:00:00Z"),
            Pair("America/New_York", "1000-01-01T12:00:00Z"),
            Pair("America/New_York", "1400-06-15T12:00:00Z"),
            Pair("America/New_York", "1500-02-28T12:00:00Z"),
            Pair("America/New_York", "1500-06-15T12:00:00Z"),
            Pair("America/New_York", "1582-10-01T12:00:00Z"),
            Pair("America/New_York", "1582-10-10T12:00:00Z"),
            Pair("Asia/Kolkata", "1000-01-01T12:00:00Z"),
            Pair("Asia/Kolkata", "1400-06-15T12:00:00Z"),
            Pair("Asia/Kolkata", "1500-02-28T12:00:00Z"),
            Pair("Asia/Kolkata", "1500-06-15T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-01T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-10T12:00:00Z"),
            Pair("Europe/Athens", "1000-01-01T12:00:00Z"),
            Pair("Europe/Athens", "1400-06-15T12:00:00Z"),
            Pair("Europe/Athens", "1500-02-28T12:00:00Z"),
            Pair("Europe/Athens", "1500-06-15T12:00:00Z"),
            Pair("Europe/Athens", "1582-10-01T12:00:00Z"),
            Pair("Europe/Athens", "1582-10-10T12:00:00Z")
        )
        for ((zoneId, iso) in cases) {
            withZone(zoneId) {
                val ts = Instant.parse(iso).toEpochMilli()
                assertEquals("start legacy $zoneId $iso", legacyStartOfMonth(ts), TimePeriodUtils.getStartOfMonth(ts))
                assertEquals("end legacy $zoneId $iso", legacyEndOfMonth(ts), TimePeriodUtils.getEndOfMonth(ts))
                for (offset in -2..2) {
                    assertEquals(
                        "range legacy $zoneId $iso offset=$offset",
                        legacyMonthRange(ts, offset),
                        TimePeriodUtils.getMonthRange(ts, offset)
                    )
                }
            }
        }
    }

    @Test
    fun `pre Gregorian cutover year month overload matches the legacy Calendar oracle`() {
        // The (year, month) overload constructs the month's first midnight with
        // the legacy Calendar (Julian rules before the cutover), then flows
        // through the pre-Gregorian seam, so year-1500 and cutover-adjacent
        // 1582-10 months must equal the independent legacy oracle — NOT the
        // proleptic java.time YearMonth.of.
        val cases = listOf(
            Triple("UTC", 1500, 1),
            Triple("UTC", 1500, 2),
            Triple("UTC", 1500, 6),
            Triple("UTC", 1582, 10),
            Triple("America/New_York", 1500, 1),
            Triple("America/New_York", 1500, 2),
            Triple("America/New_York", 1500, 6),
            Triple("America/New_York", 1582, 10),
            Triple("Asia/Kolkata", 1500, 1),
            Triple("Asia/Kolkata", 1500, 2),
            Triple("Asia/Kolkata", 1500, 6),
            Triple("Asia/Kolkata", 1582, 10),
            Triple("Europe/Athens", 1500, 1),
            Triple("Europe/Athens", 1500, 2),
            Triple("Europe/Athens", 1500, 6),
            Triple("Europe/Athens", 1582, 10)
        )
        for ((zoneId, year, month) in cases) {
            withZone(zoneId) {
                val expected = legacyMonthRangeForYearMonth(year, month)
                assertEquals("year/month legacy $zoneId $year-$month", expected, TimePeriodUtils.getMonthRange(year, month))
            }
        }
    }

    @Test
    fun `pre Gregorian cutover month boundaries intentionally diverge from proleptic java time`() {
        // Sanity check of the seam's purpose: at a pre-cutover date the legacy
        // Calendar result (Julian rules + standard offset) is NOT the same as
        // the proleptic java.time result for non-UTC zones (historical LMT
        // offsets), so the seam is what preserves the old behavior. Production
        // follows the legacy result.
        withZone("America/New_York") {
            val ts = Instant.parse("1500-02-28T12:00:00Z").toEpochMilli()
            val legacy = legacyStartOfMonth(ts)
            val proleptic = explicitStartOfMonth(ts, "America/New_York")
            assertNotEquals("legacy and proleptic must diverge pre-cutover", proleptic, legacy)
            assertEquals("production follows the legacy seam", legacy, TimePeriodUtils.getStartOfMonth(ts))
        }
    }

    @Test
    fun `cutover boundary constant matches the legacy GregorianCalendar cutover instant`() {
        // Pin the seam constant used by the boundary tests to the documented
        // legacy GregorianCalendar default cutover (1582-10-15T00:00:00Z) so a
        // typo in the literal can never silently move the seam.
        assertEquals(-12219292800000L, cutoverEpochMillis)
        assertEquals("parsed cutover must equal the literal", Instant.parse("1582-10-15T00:00:00Z").toEpochMilli(), cutoverEpochMillis)
        assertEquals("one millisecond before", -12219292800001L, cutoverMinusOne)
        assertEquals("one millisecond after", -12219292799999L, cutoverPlusOne)
    }

    @Test
    fun `pre cutover instants at the exact boundary match the legacy Calendar oracle`() {
        // The exact seam boundary: 1582-10-14T12:00Z and the last millisecond
        // before the cutover (1582-10-14T23:59:59.999Z) are strictly
        // pre-cutover, so production delegates to the legacy Calendar seam and
        // must equal the independent legacy Calendar oracle for month start,
        // end and the -2..+2 offset matrix. The legacy month start of any
        // October 1582 instant resolves to Julian Oct 1 (Gregorian Oct 11 in
        // UTC), NOT the proleptic Gregorian Oct 1 — the seam's purpose — so
        // the discriminator below proves this is a real boundary case and not
        // a case where both oracles happen to agree.
        val instants = listOf(
            "1582-10-14T12:00:00Z" to preCutoverNoonUtc,
            "1582-10-14T23:59:59.999Z" to cutoverMinusOne
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for ((iso, ts) in instants) {
                    assertNotEquals(
                        "legacy and explicit must diverge pre-cutover $zoneId $iso",
                        explicitStartOfMonth(ts, zoneId),
                        legacyStartOfMonth(ts)
                    )
                    assertEquals("start legacy $zoneId $iso", legacyStartOfMonth(ts), TimePeriodUtils.getStartOfMonth(ts))
                    assertEquals("end legacy $zoneId $iso", legacyEndOfMonth(ts), TimePeriodUtils.getEndOfMonth(ts))
                    for (offset in -2..2) {
                        assertEquals(
                            "range legacy $zoneId $iso offset=$offset",
                            legacyMonthRange(ts, offset),
                            TimePeriodUtils.getMonthRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `cutover and immediately post cutover instants match the explicit java time oracle`() {
        // The seam switches exactly at the cutover instant: production checks
        // `timestamp < cutover`, so 1582-10-15T00:00:00Z, the first millisecond
        // after, and 1582-10-15T12:00Z all take the modern java.time path
        // (proleptic Gregorian + historical LMT offsets for non-UTC zones).
        // For UTC the legacy GregorianCalendar is already proleptic Gregorian
        // at/after the cutover (no LMT offset), so the legacy and explicit
        // oracles agree exactly and are asserted equal. For non-UTC zones the
        // independent legacy Calendar oracle applies the zone's standard
        // offset while java.time applies the historical LMT offset, so the
        // two oracles diverge — that divergence is independently verified and
        // asserted only for the non-UTC zones exercised here. Asserting
        // production equals the explicit java.time oracle proves the path
        // switch happens at the exact boundary rather than one millisecond
        // later.
        val instants = listOf(
            "1582-10-15T00:00:00Z" to cutoverExact,
            "1582-10-15T00:00:00.001Z" to cutoverPlusOne,
            "1582-10-15T12:00:00Z" to postCutoverNoonUtc
        )
        // Non-UTC zones whose legacy-vs-explicit divergence at/after the
        // cutover is independently verified: java.time applies the historical
        // LMT offset while the legacy Calendar applies the zone's standard
        // offset (America/New_York -04:56:02 LMT vs -05:00, Asia/Kolkata
        // +05:53:28 vs +05:30, Europe/Athens +01:34:52 vs +02:00).
        val verifiedDivergentZones = setOf("America/New_York", "Asia/Kolkata", "Europe/Athens")
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for ((iso, ts) in instants) {
                    if (zoneId == "UTC") {
                        assertEquals(
                            "legacy and explicit must agree at/after cutover $zoneId $iso",
                            legacyStartOfMonth(ts),
                            explicitStartOfMonth(ts, zoneId)
                        )
                        assertEquals(
                            "legacy end and explicit end must agree at/after cutover $zoneId $iso",
                            legacyEndOfMonth(ts),
                            explicitEndOfMonth(ts, zoneId)
                        )
                        assertEquals(
                            "legacy range and explicit range must agree at/after cutover $zoneId $iso",
                            legacyMonthRange(ts, 0),
                            explicitMonthRange(ts, zoneId, 0)
                        )
                    } else {
                        assertTrue(
                            "zone $zoneId is not independently verified as divergent at/after cutover",
                            zoneId in verifiedDivergentZones
                        )
                        assertNotEquals(
                            "legacy and explicit must diverge at/after cutover $zoneId $iso",
                            legacyStartOfMonth(ts),
                            explicitStartOfMonth(ts, zoneId)
                        )
                    }
                    assertEquals("start explicit $zoneId $iso", explicitStartOfMonth(ts, zoneId), TimePeriodUtils.getStartOfMonth(ts))
                    assertEquals("end explicit $zoneId $iso", explicitEndOfMonth(ts, zoneId), TimePeriodUtils.getEndOfMonth(ts))
                    for (offset in -2..2) {
                        assertEquals(
                            "range explicit $zoneId $iso offset=$offset",
                            explicitMonthRange(ts, zoneId, offset),
                            TimePeriodUtils.getMonthRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `cutover boundary ranges are contiguous with no overlap or gap and month labels stay correct`() {
        // The half-open month range contract holds for every selected
        // cutover-adjacent instant on both paths: the offset -1 month ends
        // exactly at the offset-0 start and the offset-0 end exactly at the
        // offset +1 start (no overlap, no gap). The input instant, the range
        // start and the last instant inside the range all keep the same month
        // label, "1582-10" — checked with the same oracle family as the path
        // production uses (independent legacy Calendar pre-cutover, explicit
        // java.time at/after the cutover) plus the production label helper.
        data class InstantCase(val iso: String, val ts: Long, val preCutover: Boolean)
        val instants = listOf(
            InstantCase("1582-10-14T12:00:00Z", preCutoverNoonUtc, true),
            InstantCase("1582-10-14T23:59:59.999Z", cutoverMinusOne, true),
            InstantCase("1582-10-15T00:00:00Z", cutoverExact, false),
            InstantCase("1582-10-15T00:00:00.001Z", cutoverPlusOne, false),
            InstantCase("1582-10-15T12:00:00Z", postCutoverNoonUtc, false)
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (case in instants) {
                    val ts = case.ts
                    val (start, end) = TimePeriodUtils.getMonthRange(ts, 0)
                    // No overlap/gap: the neighbors are exactly contiguous.
                    assertEquals(
                        "prev month end == this start $zoneId ${case.iso}",
                        start,
                        TimePeriodUtils.getMonthRange(ts, -1).second
                    )
                    assertEquals(
                        "this end == next month start $zoneId ${case.iso}",
                        end,
                        TimePeriodUtils.getMonthRange(ts, 1).first
                    )
                    // The input instant is inside its own half-open month.
                    assertTrue("ts in range $zoneId ${case.iso}", TimePeriodUtils.isInRange(ts, start, end))
                    // Month labels stay correct: input, start and last-in-range
                    // all report October 1582 through the path-appropriate
                    // independent oracle, and the production label helper
                    // agrees for the input instant.
                    val labelTs = if (case.preCutover) legacyMonthLabel(ts) else explicitMonthLabel(ts, zoneId)
                    val labelStart = if (case.preCutover) legacyMonthLabel(start) else explicitMonthLabel(start, zoneId)
                    val labelEndMinusOne = if (case.preCutover) legacyMonthLabel(end - 1) else explicitMonthLabel(end - 1, zoneId)
                    assertEquals("input month label $zoneId ${case.iso}", "1582-10", labelTs)
                    assertEquals("range start month label $zoneId ${case.iso}", "1582-10", labelStart)
                    assertEquals("range end-1 month label $zoneId ${case.iso}", "1582-10", labelEndMinusOne)
                    assertEquals("formatMonthKey $zoneId ${case.iso}", "1582-10", TimePeriodUtils.formatMonthKey(ts))
                }
            }
        }
    }

    // ============================================================================
    // Year/month overload — 1-based input and lenient out-of-range months
    // ============================================================================

    @Test
    fun `year month overload is 1-based and matches both oracles for valid months`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // 1 = January, 12 = December (no off-by-one from the internal
                // 0-based Calendar month conversion).
                for ((year, month) in listOf(2026 to 1, 2026 to 6, 2026 to 12, 2024 to 2, 1999 to 1)) {
                    val actual = TimePeriodUtils.getMonthRange(year, month)
                    assertEquals("year/month legacy $zoneId $year-$month", legacyMonthRangeForYearMonth(year, month), actual)
                    assertEquals("year/month explicit $zoneId $year-$month", explicitMonthRangeForYearMonth(year, month, zoneId), actual)
                }
            }
        }
        withZone("UTC") {
            assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(2026, 1).first)
            assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(2026, 1).second)
            assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(2026, 12).first)
            assertEquals(Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getMonthRange(2026, 12).second)
        }
    }

    @Test
    fun `year month overload preserves lenient out of range month normalization`() {
        // The legacy Calendar construction was lenient: month 0 normalizes to
        // December of the previous year and month 13 to January of the next
        // year. The migrated overload must preserve that exact behavior.
        withZone("UTC") {
            val monthZero = TimePeriodUtils.getMonthRange(2026, 0)
            assertEquals("2026-0 legacy", legacyMonthRangeForYearMonth(2026, 0), monthZero)
            assertEquals(Instant.parse("2025-12-01T00:00:00Z").toEpochMilli(), monthZero.first)
            assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), monthZero.second)

            val monthThirteen = TimePeriodUtils.getMonthRange(2026, 13)
            assertEquals("2026-13 legacy", legacyMonthRangeForYearMonth(2026, 13), monthThirteen)
            assertEquals(Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), monthThirteen.first)
            assertEquals(Instant.parse("2027-02-01T00:00:00Z").toEpochMilli(), monthThirteen.second)
        }
    }

    // ============================================================================
    // parseMonthKeyToRange (composes parseMonthKey + getMonthRange(year, month))
    // ============================================================================

    @Test
    fun `parseMonthKeyToRange matches the year month overload and rejects invalid keys`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for ((key, year, month) in listOf(
                    Triple("2026-04", 2026, 4),
                    Triple("2024-02", 2024, 2),
                    Triple("2025-12", 2025, 12)
                )) {
                    val expected = explicitMonthRangeForYearMonth(year, month, zoneId)
                    assertEquals("parseMonthKeyToRange $zoneId $key", expected, TimePeriodUtils.parseMonthKeyToRange(key))
                }
            }
        }
        for (key in listOf("", "2026", "2026-13", "2026-00", "abc-01", "2026-ab")) {
            assertThrows(IllegalArgumentException::class.java) { TimePeriodUtils.parseMonthKeyToRange(key) }
        }
    }

    // ============================================================================
    // Half-open exact end and extreme values
    // ============================================================================

    @Test
    fun `month end is exclusive and the last millisecond is included`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc, springMonthUtc, fallMonthUtc)) {
                    val start = TimePeriodUtils.getStartOfMonth(ts)
                    val end = TimePeriodUtils.getEndOfMonth(ts)
                    assertTrue("ts=$ts >= start", ts >= start)
                    assertTrue("ts=$ts < end", ts < end)
                    assertFalse("end excluded ts=$ts", TimePeriodUtils.isInRange(end, start, end))
                    assertTrue("end-1 included ts=$ts", TimePeriodUtils.isInRange(end - 1, start, end))
                    // The exact end instant belongs to the NEXT month.
                    assertEquals("end maps to next month ts=$ts", end, TimePeriodUtils.getStartOfMonth(end))
                }
            }
        }
    }

    @Test
    fun `extreme Long values behave deterministically without wrapping`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Long.MAX_VALUE lies *after* the cutover, so the modern
                // java.time path applies: the 1st-of-month midnight is still
                // representable (strictly before the input), so start-of-month
                // succeeds and matches the explicit java.time oracle. The
                // exclusive end (next month) lies beyond Long.MAX_VALUE, so
                // end/range fail deterministically with ArithmeticException.
                val maxStart = TimePeriodUtils.getStartOfMonth(Long.MAX_VALUE)
                assertEquals("max start explicit $zoneId", explicitStartOfMonth(Long.MAX_VALUE, zoneId), maxStart)
                assertTrue("max start before input", maxStart < Long.MAX_VALUE)
                assertTrue("max start fits in Long", maxStart >= Long.MIN_VALUE)

                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getEndOfMonth(Long.MAX_VALUE) }
                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getMonthRange(Long.MAX_VALUE) }

                // Long.MIN_VALUE lies *before* the cutover, so the legacy
                // pre-Gregorian compatibility seam applies: all month helpers
                // delegate to the legacy Calendar implementation and return its
                // deterministic values (no exception), exactly like the day and
                // week seams. Expected values come from the independent legacy
                // Calendar oracle (offset 0; the -2..+2 offset matrix is
                // covered by the dedicated pre-Gregorian test).
                assertEquals(
                    "min start legacy $zoneId",
                    legacyStartOfMonth(Long.MIN_VALUE),
                    TimePeriodUtils.getStartOfMonth(Long.MIN_VALUE)
                )
                assertEquals(
                    "min end legacy $zoneId",
                    legacyEndOfMonth(Long.MIN_VALUE),
                    TimePeriodUtils.getEndOfMonth(Long.MIN_VALUE)
                )
                assertEquals(
                    "min range legacy $zoneId",
                    legacyMonthRange(Long.MIN_VALUE, 0),
                    TimePeriodUtils.getMonthRange(Long.MIN_VALUE, 0)
                )
            }
        }
    }

    @Test
    fun `year 9999 month boundaries are exact proleptic instants`() {
        withZone("UTC") {
            val ts = LocalDateTime.of(9999, 12, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            assertEquals(Instant.parse("9999-12-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfMonth(ts))
            assertEquals(Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfMonth(ts))
        }
    }

    // ============================================================================
    // Consistency
    // ============================================================================

    @Test
    fun `getMonthRange is consistent with getStartOfMonth and getEndOfMonth`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, jan31LeapUtc, mar31LeapUtc, dec31Utc, springMonthUtc, fallMonthUtc, epochZero, preEpoch1950)) {
                    val (start, end) = TimePeriodUtils.getMonthRange(ts, 0)
                    assertEquals("start $zoneId ts=$ts", TimePeriodUtils.getStartOfMonth(ts), start)
                    assertEquals("end $zoneId ts=$ts", TimePeriodUtils.getEndOfMonth(ts), end)
                }
            }
        }
    }

    // ============================================================================
    // Regression: migrated implementation must match the independent oracles
    // (post-cutover samples only; see class docs for the pre-cutover note)
    // ============================================================================

    @Test
    fun `migrated month boundaries match the legacy Calendar oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            juneNoonUtc,
            jan31LeapUtc,
            jan31NonLeapUtc,
            mar31LeapUtc,
            dec31Utc,
            springMonthUtc,
            fallMonthUtc,
            leapDay2024Utc,
            Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), // spring-forward Sunday pre-gap
            Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), // spring-forward Sunday post-gap
            Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), // fall-back overlap first occurrence
            Instant.parse("2024-11-03T06:30:00Z").toEpochMilli()  // fall-back overlap second occurrence
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("legacy getStartOfMonth $zoneId ts=$ts", legacyStartOfMonth(ts), TimePeriodUtils.getStartOfMonth(ts))
                    assertEquals("legacy getEndOfMonth $zoneId ts=$ts", legacyEndOfMonth(ts), TimePeriodUtils.getEndOfMonth(ts))
                    for (offset in -2..2) {
                        assertEquals(
                            "legacy getMonthRange $zoneId ts=$ts offset=$offset",
                            legacyMonthRange(ts, offset),
                            TimePeriodUtils.getMonthRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `migrated month boundaries match the explicit java time oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            juneNoonUtc,
            jan31LeapUtc,
            jan31NonLeapUtc,
            mar31LeapUtc,
            dec31Utc,
            springMonthUtc,
            fallMonthUtc,
            leapDay2024Utc
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("explicit getStartOfMonth $zoneId ts=$ts", explicitStartOfMonth(ts, zoneId), TimePeriodUtils.getStartOfMonth(ts))
                    assertEquals("explicit getEndOfMonth $zoneId ts=$ts", explicitEndOfMonth(ts, zoneId), TimePeriodUtils.getEndOfMonth(ts))
                    for (offset in -2..2) {
                        assertEquals(
                            "explicit getMonthRange $zoneId ts=$ts offset=$offset",
                            explicitMonthRange(ts, zoneId, offset),
                            TimePeriodUtils.getMonthRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    // ============================================================================
    // Year/month overload — Int extremes (lenient Calendar wrap, no throw)
    // ============================================================================

    @Test
    fun `year and month Int extremes return valid ordered half-open month ranges`() {
        // The (year, month) overload builds a lenient Calendar from the raw int
        // values, so at the Int extremes the Calendar arithmetic wraps
        // deterministically instead of throwing (verified on the host JDK: none of
        // the extreme combinations raise DateTimeException/ArithmeticException).
        // The wrapped timestamp lands either before the cutover (legacy Calendar
        // seam) or after it (java.time path), and on both paths the documented
        // contract survives: a valid half-open `[start, end)` month range where
        // start is the 1st-of-month local midnight and end is the 1st of the next
        // month. Exact wrapped millisecond literals are deliberately NOT pinned
        // here — they are JDK-specific artifacts of the lenient Calendar overflow —
        // so these assertions lock the JDK-independent structural contract instead.
        // The expected boundaries are validated independently: the legacy
        // Calendar oracle for pre-cutover wrapped timestamps and the explicit
        // java.time oracle (ZonedDateTime/YearMonth) for modern ones — never by
        // re-calling the production month helpers.
        val extremeYears = listOf(Int.MIN_VALUE, Int.MAX_VALUE)
        val extremeMonths = listOf(Int.MIN_VALUE, 0, 1, 12, 13, Int.MAX_VALUE)
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (year in extremeYears) {
                    for (month in extremeMonths) {
                        val actual = TimePeriodUtils.getMonthRange(year, month)
                        val label = "year=$year month=$month $zoneId"
                        // The year/month overload hands the lenient Calendar's
                        // wrapped timestamp to the pre-Gregorian compatibility
                        // seam, so the oracle family mirrors that seam: legacy
                        // Calendar for pre-cutover wrapped timestamps, explicit
                        // java.time at/after the cutover.
                        val wrappedTs = legacyYearMonthWrappedTimestamp(year, month)
                        // Independent expected pair computed from the same
                        // wrapped timestamp — never re-calls the production
                        // month helpers through the system-default zone.
                        val expected = if (wrappedTs < cutoverEpochMillis) {
                            legacyMonthRange(wrappedTs, 0)
                        } else {
                            explicitMonthRange(wrappedTs, zoneId, 0)
                        }
                        assertEquals("$label: first matches independent oracle", expected.first, actual.first)
                        assertEquals("$label: second matches independent oracle", expected.second, actual.second)
                        val start = actual.first
                        val end = actual.second
                        assertTrue("$label: ordered range", start < end)
                        if (wrappedTs < cutoverEpochMillis) {
                            assertEquals(
                                "$label: start is 1st-of-month midnight (legacy)",
                                start,
                                legacyStartOfMonth(start)
                            )
                            assertEquals(
                                "$label: end is 1st-of-next-month midnight (legacy)",
                                end,
                                legacyEndOfMonth(start)
                            )
                        } else {
                            val startZoned = Instant.ofEpochMilli(start).atZone(ZoneId.of(zoneId))
                            assertEquals("$label: start is day 1", 1, startZoned.dayOfMonth)
                            assertEquals("$label: start is local midnight", 0, startZoned.hour)
                            assertEquals("$label: start has zero minutes", 0, startZoned.minute)
                            assertEquals("$label: start has zero seconds", 0, startZoned.second)
                            assertEquals("$label: start has zero nanos", 0, startZoned.nano)
                            assertEquals(
                                "$label: end is 1st-of-next-month midnight",
                                explicitEndOfMonth(start, zoneId),
                                end
                            )
                        }
                        assertTrue("$label: start inclusive", TimePeriodUtils.isInRange(start, start, end))
                        assertTrue("$label: last millisecond inclusive", TimePeriodUtils.isInRange(end - 1, start, end))
                        assertFalse("$label: end exclusive", TimePeriodUtils.isInRange(end, start, end))
                    }
                }
            }
        }
    }

    @Test
    fun `year and month Int extremes are deterministic across repeated calls`() {
        // Lock repeatability of the lenient Calendar wrap: the result depends only
        // on (year, month) and the default zone, so every call must agree exactly.
        val extremeYears = listOf(Int.MIN_VALUE, Int.MAX_VALUE)
        val extremeMonths = listOf(Int.MIN_VALUE, 0, 1, 12, 13, Int.MAX_VALUE)
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (year in extremeYears) {
                    for (month in extremeMonths) {
                        val label = "year=$year month=$month $zoneId"
                        assertEquals(
                            "$label: repeatable",
                            TimePeriodUtils.getMonthRange(year, month),
                            TimePeriodUtils.getMonthRange(year, month)
                        )
                    }
                }
            }
        }
    }
}
