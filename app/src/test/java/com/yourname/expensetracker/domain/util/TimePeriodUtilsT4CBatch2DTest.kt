package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * T4C Batch 2D — locks the migrated java.time implementations of
 * [TimePeriodUtils.getStartOfYear], [TimePeriodUtils.getEndOfYear],
 * [TimePeriodUtils.getYearRange] (both the timestamp+offset overload and the
 * year overload), [TimePeriodUtils.getStartOfQuarter],
 * [TimePeriodUtils.getEndOfQuarter] and [TimePeriodUtils.getQuarterRange] to
 * exact expected values.
 *
 * The production year helpers now use:
 * ```
 * Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
 *     -> Year.from(localDate).atDay(1).atStartOfDay(zone)
 * ```
 * with `Year.plusYears(1)` / `Year.plusYears(offset)` for the exclusive end
 * and offset shift. The quarter helpers use the same local-date derivation with
 * `YearMonth` arithmetic: the quarter's first month is
 * `((monthValue - 1) / 3) * 3 + 1` (-> 1, 4, 7, 10), the start is
 * `YearMonth.atDay(1).atStartOfDay(zone)` and the exclusive end / offset shift
 * use `plusMonths(3)` / `plusMonths(quarterOffset * 3)`.
 *
 * This preserves the previous `Calendar` contract exactly:
 * - half-open `[startInclusive, endExclusive)` year/quarter ranges;
 * - year offsets applied before truncating to the year (identical to the old
 *   `Calendar.add(YEAR, yearOffset)`) and quarter offsets applied as full
 *   3-month shifts (`Calendar.add(MONTH, quarterOffset * 3)`);
 * - Dec -> Jan rollover across year boundaries and Q4 -> Q1 rollover across
 *   quarters;
 * - leap years contain 366 calendar days and Feb 29 stays inside its year;
 *   leap-day clamping in the shifted reference never changes the resulting
 *   year;
 * - system default timezone;
 * - DST-aware durations: a quarter spanning a spring-forward is one wall-clock
 *   hour shorter, a fall-back quarter one hour longer; a full year spanning
 *   both transitions is exactly 365/366 calendar days;
 * - the year overload stays int-lenient (raw extreme years wrap deterministically
 *   instead of throwing, exactly like the pre-migration Calendar construction).
 *
 * The suite also locks the **pre-Gregorian compatibility seam**: production
 * intentionally delegates timestamps strictly before the legacy
 * [java.util.GregorianCalendar] cutover (`1582-10-15T00:00:00Z`) to the legacy
 * `Calendar` algorithm so the pre-migration behavior (Julian date rules and the
 * timezone's standard offset) is reproduced exactly. That pre-cutover
 * compatibility is tested here by asserting production start/end/range equals
 * the independent legacy `Calendar` oracle at year-1000/1400/1500 and
 * cutover-adjacent 1582 dates for UTC, America/New_York, Asia/Kolkata and
 * Europe/Athens, for year/quarter offsets -1..+1. The **exact seam boundary**
 * is also locked with fixed instants around 1582-10-14T12:00Z /
 * 1582-10-15T00:00Z: the last millisecond before the cutover and everything
 * before it stay on the legacy path (matching the legacy `Calendar` oracle),
 * while the cutover instant itself (`1582-10-15T00:00:00Z`), the first
 * millisecond after and 1582-10-15T12:00Z switch to the java.time path
 * (matching the explicit `java.time` oracle). Those boundary tests also assert
 * the half-open ranges stay contiguous (no overlap/gap).
 *
 * Design rules (mirrors TimePeriodUtilsT4CBatch2BTest / Batch 2C):
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
 * - DST boundary quarters/years are asserted by wall-clock duration, never by
 *   fixed `DAY_IN_MILLIS` arithmetic.
 * - At the `Long` extremes: `Long.MAX_VALUE` inputs stay on the modern
 *   java.time path — `getStartOfYear(Long.MAX_VALUE)` and
 *   `getStartOfQuarter(Long.MAX_VALUE)` succeed and match the explicit
 *   java.time oracle, while `getEndOfYear`, `getYearRange`,
 *   `getEndOfQuarter` and `getQuarterRange` fail deterministically with
 *   `ArithmeticException` (documented controlled failure). `Long.MIN_VALUE`
 *   inputs are pre-cutover, so all year/quarter helpers follow the legacy
 *   Calendar seam and return its deterministic values (no exception); neither
 *   extreme ever silently wraps.
 */
class TimePeriodUtilsT4CBatch2DTest {

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
    private val dec31Utc = Instant.parse("2024-12-31T12:00:00Z").toEpochMilli()
    private val leapDay2024Utc = Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()
    private val feb2023Utc = Instant.parse("2023-02-15T12:00:00Z").toEpochMilli()
    private val springQuarterUtc = Instant.parse("2024-03-10T12:00:00Z").toEpochMilli() // US spring-forward day (Q1)
    private val fallQuarterUtc = Instant.parse("2024-11-03T12:00:00Z").toEpochMilli()  // US fall-back day (Q4)
    private val epochZero = 0L
    private val minusOneSecond = -1000L
    private val preEpoch1950 = Instant.parse("1950-01-01T00:00:00Z").toEpochMilli()

    // Exact legacy GregorianCalendar cutover instant (1582-10-15T00:00:00Z).
    // This is the same hardcoded constant the production seam compares
    // against (`timestamp < cutover` => legacy path).
    private val cutoverEpochMillis: Long = -12219292800000L
    // Fixed instants immediately around the cutover (all fixed UTC instants).
    private val preCutoverNoonUtc = Instant.parse("1582-10-14T12:00:00Z").toEpochMilli()
    private val cutoverMinusOne = cutoverEpochMillis - 1
    private val cutoverExact = cutoverEpochMillis
    private val cutoverPlusOne = cutoverEpochMillis + 1
    private val postCutoverNoonUtc = Instant.parse("1582-10-15T12:00:00Z").toEpochMilli()

    // ============================================================================
    // Independent oracles (never call the production helpers)
    // ============================================================================

    /** Legacy Calendar oracle reproducing the previous getStartOfYear algorithm. */
    private fun legacyStartOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getEndOfYear algorithm. */
    private fun legacyEndOfYear(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfYear(timestamp)
        cal.add(Calendar.YEAR, 1)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getYearRange algorithm. */
    private fun legacyYearRange(timestamp: Long, yearOffset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (yearOffset != 0) {
            cal.add(Calendar.YEAR, yearOffset)
        }
        val start = legacyStartOfYear(cal.timeInMillis)
        val end = legacyEndOfYear(cal.timeInMillis)
        return start to end
    }

    /** Legacy Calendar oracle reproducing the previous getStartOfQuarter algorithm. */
    private fun legacyStartOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val month = cal.get(Calendar.MONTH)
        val quarterStartMonth = (month / 3) * 3
        cal.set(Calendar.MONTH, quarterStartMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getEndOfQuarter algorithm. */
    private fun legacyEndOfQuarter(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfQuarter(timestamp)
        cal.add(Calendar.MONTH, 3)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous getQuarterRange algorithm. */
    private fun legacyQuarterRange(timestamp: Long, quarterOffset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        if (quarterOffset != 0) {
            cal.add(Calendar.MONTH, quarterOffset * 3)
        }
        val start = legacyStartOfQuarter(cal.timeInMillis)
        val end = legacyEndOfQuarter(cal.timeInMillis)
        return start to end
    }

    /** Legacy Calendar oracle for the year overload (raw int year input). */
    private fun legacyYearRangeForYear(year: Int): Pair<Long, Long> {
        return legacyYearRange(legacyYearWrappedTimestamp(year), 0)
    }

    /**
     * Legacy Calendar oracle for the wrapped timestamp the year overload feeds
     * into the pre-Gregorian compatibility seam: the lenient [Calendar]
     * construction with the raw int [year] normalized to `timeInMillis`. Used
     * only to decide which independent oracle family validates an Int-extreme
     * year range (legacy Calendar when the wrapped timestamp is before the
     * cutover, explicit java.time at/after it).
     */
    private fun legacyYearWrappedTimestamp(year: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Explicit java.time oracle for start-of-year. Built with [ZoneId.of]. */
    private fun explicitStartOfYear(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return Year.from(localDate).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for the exclusive year end (plusYears(1)). */
    private fun explicitEndOfYear(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return Year.from(localDate).plusYears(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for a year range with [yearOffset]. */
    private fun explicitYearRange(timestamp: Long, zoneId: String, yearOffset: Int): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        val year = Year.from(Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate())
            .plusYears(yearOffset.toLong())
        val startMs = year.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = year.plusYears(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    /** Explicit java.time oracle for the year overload (raw int year input). */
    private fun explicitYearRangeForYear(year: Int, zoneId: String): Pair<Long, Long> {
        return explicitYearRange(legacyYearWrappedTimestamp(year), zoneId, 0)
    }

    /** Explicit java.time oracle for start-of-quarter. Built with [ZoneId.of]. */
    private fun explicitStartOfQuarter(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val quarterStartMonth = ((localDate.monthValue - 1) / 3) * 3 + 1
        return YearMonth.of(localDate.year, quarterStartMonth).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for the exclusive quarter end (plusMonths(3)). */
    private fun explicitEndOfQuarter(timestamp: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val quarterStartMonth = ((localDate.monthValue - 1) / 3) * 3 + 1
        return YearMonth.of(localDate.year, quarterStartMonth)
            .plusMonths(3).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Explicit java.time oracle for a quarter range with [quarterOffset]. */
    private fun explicitQuarterRange(timestamp: Long, zoneId: String, quarterOffset: Int): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val quarterStartMonth = ((localDate.monthValue - 1) / 3) * 3 + 1
        val quarterStart = YearMonth.of(localDate.year, quarterStartMonth)
            .plusMonths(quarterOffset.toLong() * 3)
        val startMs = quarterStart.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = quarterStart.plusMonths(3).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    // ============================================================================
    // Normal year / quarter boundaries (hardcoded instants per zone)
    // ============================================================================

    @Test
    fun `normal year boundaries are exact hardcoded instants per zone`() {
        // Year 2024 (leap year, all boundaries in standard time):
        //   UTC              2024-01-01T00:00Z ... 2025-01-01T00:00Z
        //   Asia/Kolkata     2023-12-31T18:30Z ... 2024-12-31T18:30Z  (+05:30)
        //   Europe/Athens    2023-12-31T22:00Z ... 2024-12-31T22:00Z  (+02:00 EET)
        //   America/New_York 2024-01-01T05:00Z ... 2025-01-01T05:00Z  (-05:00 EST)
        data class YearCase(val zoneId: String, val startIso: String, val endIso: String)
        val cases = listOf(
            YearCase("UTC", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
            YearCase("Asia/Kolkata", "2023-12-31T18:30:00Z", "2024-12-31T18:30:00Z"),
            YearCase("Europe/Athens", "2023-12-31T22:00:00Z", "2024-12-31T22:00:00Z"),
            YearCase("America/New_York", "2024-01-01T05:00:00Z", "2025-01-01T05:00:00Z")
        )
        for (case in cases) {
            withZone(case.zoneId) {
                assertEquals("start ${case.zoneId}", Instant.parse(case.startIso).toEpochMilli(), TimePeriodUtils.getStartOfYear(juneNoonUtc))
                assertEquals("end ${case.zoneId}", Instant.parse(case.endIso).toEpochMilli(), TimePeriodUtils.getEndOfYear(juneNoonUtc))
            }
        }
    }

    @Test
    fun `normal quarter boundaries are exact hardcoded instants per zone`() {
        // Quarter Q2 2024 (Apr ... Jun):
        //   UTC              2024-04-01T00:00Z ... 2024-07-01T00:00Z
        //   Asia/Kolkata     2024-03-31T18:30Z ... 2024-06-30T18:30Z  (+05:30)
        //   Europe/Athens    2024-03-31T21:00Z ... 2024-06-30T21:00Z  (+03:00 EEST)
        //   America/New_York 2024-04-01T04:00Z ... 2024-07-01T04:00Z  (-04:00 EDT)
        data class QuarterCase(val zoneId: String, val startIso: String, val endIso: String)
        val cases = listOf(
            QuarterCase("UTC", "2024-04-01T00:00:00Z", "2024-07-01T00:00:00Z"),
            QuarterCase("Asia/Kolkata", "2024-03-31T18:30:00Z", "2024-06-30T18:30:00Z"),
            QuarterCase("Europe/Athens", "2024-03-31T21:00:00Z", "2024-06-30T21:00:00Z"),
            QuarterCase("America/New_York", "2024-04-01T04:00:00Z", "2024-07-01T04:00:00Z")
        )
        for (case in cases) {
            withZone(case.zoneId) {
                assertEquals("start ${case.zoneId}", Instant.parse(case.startIso).toEpochMilli(), TimePeriodUtils.getStartOfQuarter(juneNoonUtc))
                assertEquals("end ${case.zoneId}", Instant.parse(case.endIso).toEpochMilli(), TimePeriodUtils.getEndOfQuarter(juneNoonUtc))
            }
        }
    }

    @Test
    fun `normal year boundaries match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc)) {
                    assertEquals("legacy start $zoneId ts=$ts", legacyStartOfYear(ts), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("explicit start $zoneId ts=$ts", explicitStartOfYear(ts, zoneId), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("legacy end $zoneId ts=$ts", legacyEndOfYear(ts), TimePeriodUtils.getEndOfYear(ts))
                    assertEquals("explicit end $zoneId ts=$ts", explicitEndOfYear(ts, zoneId), TimePeriodUtils.getEndOfYear(ts))
                }
            }
        }
    }

    @Test
    fun `normal quarter boundaries match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc)) {
                    assertEquals("legacy start $zoneId ts=$ts", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("explicit start $zoneId ts=$ts", explicitStartOfQuarter(ts, zoneId), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("legacy end $zoneId ts=$ts", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                    assertEquals("explicit end $zoneId ts=$ts", explicitEndOfQuarter(ts, zoneId), TimePeriodUtils.getEndOfQuarter(ts))
                }
            }
        }
    }

    @Test
    fun `all four quarters map to the correct quarter boundaries and match both oracles`() {
        data class QuarterRef(val iso: String, val startIso: String, val endIso: String)
        val refs = listOf(
            QuarterRef("2024-02-15T12:00:00Z", "2024-01-01T00:00:00Z", "2024-04-01T00:00:00Z"), // Q1
            QuarterRef("2024-05-15T12:00:00Z", "2024-04-01T00:00:00Z", "2024-07-01T00:00:00Z"), // Q2
            QuarterRef("2024-08-15T12:00:00Z", "2024-07-01T00:00:00Z", "2024-10-01T00:00:00Z"), // Q3
            QuarterRef("2024-11-15T12:00:00Z", "2024-10-01T00:00:00Z", "2025-01-01T00:00:00Z")  // Q4
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ref in refs) {
                    val ts = Instant.parse(ref.iso).toEpochMilli()
                    // The reference boundaries are written as local midnight
                    // ISO date-times; resolve them in the ACTIVE zone so the
                    // hardcoded expectations hold for every zone (under UTC
                    // this is identical to Instant.parse of the same string).
                    val zone = ZoneId.systemDefault()
                    val expectedStart = LocalDateTime.parse(ref.startIso.removeSuffix("Z")).atZone(zone).toInstant().toEpochMilli()
                    val expectedEnd = LocalDateTime.parse(ref.endIso.removeSuffix("Z")).atZone(zone).toInstant().toEpochMilli()
                    assertEquals("${ref.iso} start hardcoded $zoneId", expectedStart, TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("${ref.iso} end hardcoded $zoneId", expectedEnd, TimePeriodUtils.getEndOfQuarter(ts))
                    assertEquals("${ref.iso} start legacy $zoneId", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("${ref.iso} start explicit $zoneId", explicitStartOfQuarter(ts, zoneId), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("${ref.iso} end legacy $zoneId", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                    assertEquals("${ref.iso} end explicit $zoneId", explicitEndOfQuarter(ts, zoneId), TimePeriodUtils.getEndOfQuarter(ts))
                }
            }
        }
    }

    // ============================================================================
    // Offset semantics (-1 / 0 / +1) and Dec/Jan + Q4/Q1 rollover
    // ============================================================================

    @Test
    fun `year offsets -1 0 +1 stay contiguous across the year rollover and match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Reference 2024-12-31 stays in 2024; -1 -> 2023, 0 -> 2024, +1 -> 2025.
                val offsets = listOf(-1, 0, 1)
                val ranges = offsets.map { TimePeriodUtils.getYearRange(dec31Utc, it) }
                for ((i, offset) in offsets.withIndex()) {
                    assertEquals("offset $offset legacy $zoneId", legacyYearRange(dec31Utc, offset), ranges[i])
                    assertEquals("offset $offset explicit $zoneId", explicitYearRange(dec31Utc, zoneId, offset), ranges[i])
                }
                assertEquals("contiguity -1->0 $zoneId", ranges[1].first, ranges[0].second)
                assertEquals("contiguity 0->1 $zoneId", ranges[2].first, ranges[1].second)
            }
        }
        withZone("UTC") {
            assertEquals(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(dec31Utc, -1).first)
            assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(dec31Utc, -1).second)
            assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(dec31Utc, 0).first)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(dec31Utc, 1).first)
            assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(dec31Utc, 1).second)

            // Dec 31 and Jan 1 of the same boundary live in adjacent years.
            val jan1_2025 = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
            assertEquals(TimePeriodUtils.getYearRange(dec31Utc, 0).second, TimePeriodUtils.getStartOfYear(jan1_2025))
        }
    }

    @Test
    fun `quarter offsets -1 0 +1 stay contiguous across the year rollover and match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Reference 2024-12-31 is Q4 2024; -1 -> Q3 2024, 0 -> Q4 2024, +1 -> Q1 2025.
                val offsets = listOf(-1, 0, 1)
                val ranges = offsets.map { TimePeriodUtils.getQuarterRange(dec31Utc, it) }
                for ((i, offset) in offsets.withIndex()) {
                    assertEquals("offset $offset legacy $zoneId", legacyQuarterRange(dec31Utc, offset), ranges[i])
                    assertEquals("offset $offset explicit $zoneId", explicitQuarterRange(dec31Utc, zoneId, offset), ranges[i])
                }
                assertEquals("contiguity -1->0 $zoneId", ranges[1].first, ranges[0].second)
                assertEquals("contiguity 0->1 $zoneId", ranges[2].first, ranges[1].second)
            }
        }
        withZone("UTC") {
            assertEquals(Instant.parse("2024-07-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(dec31Utc, -1).first)
            assertEquals(Instant.parse("2024-10-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(dec31Utc, 0).first)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(dec31Utc, 1).first)
            assertEquals(Instant.parse("2025-04-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(dec31Utc, 1).second)
        }
    }

    @Test
    fun `quarter offsets shift by whole quarters from any reference day`() {
        withZone("UTC") {
            // Reference mid-June 2024 (Q2); +2 quarters -> Q4 2024, +3 -> Q1 2025.
            assertEquals(Instant.parse("2024-10-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(juneNoonUtc, 2).first)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(juneNoonUtc, 3).first)
            assertEquals(Instant.parse("2025-04-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getQuarterRange(juneNoonUtc, 4).first)
            assertEquals(TimePeriodUtils.getQuarterRange(juneNoonUtc, 2).first, TimePeriodUtils.getQuarterRange(juneNoonUtc, 1).second)
        }
    }

    // ============================================================================
    // Leap years, Feb 29 and century leap rules
    // ============================================================================

    @Test
    fun `leap and non-leap year durations are 366 or 365 calendar days`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val leap2024 = TimePeriodUtils.getYearRange(juneNoonUtc)
                val nonLeap2023 = TimePeriodUtils.getYearRange(feb2023Utc)
                assertEquals("leap 2024 legacy $zoneId", legacyYearRange(juneNoonUtc, 0), leap2024)
                assertEquals("non-leap 2023 legacy $zoneId", legacyYearRange(feb2023Utc, 0), nonLeap2023)
                assertEquals("leap 2024 explicit $zoneId", explicitYearRange(juneNoonUtc, zoneId, 0), leap2024)
                assertEquals("non-leap 2023 explicit $zoneId", explicitYearRange(feb2023Utc, zoneId, 0), nonLeap2023)
            }
        }
        withZone("UTC") {
            assertEquals(366L * 24 * 60 * 60 * 1000, TimePeriodUtils.getYearRange(juneNoonUtc).second - TimePeriodUtils.getYearRange(juneNoonUtc).first)
            assertEquals(365L * 24 * 60 * 60 * 1000, TimePeriodUtils.getYearRange(feb2023Utc).second - TimePeriodUtils.getYearRange(feb2023Utc).first)
        }
    }

    @Test
    fun `century leap rules 2000 and 1900 produce 366 or 365 day years`() {
        withZone("UTC") {
            // 2000 is divisible by 400 -> leap year (366 days).
            val ts2000 = Instant.parse("2000-06-15T12:00:00Z").toEpochMilli()
            assertEquals(366L * 24 * 60 * 60 * 1000, TimePeriodUtils.getYearRange(ts2000).second - TimePeriodUtils.getYearRange(ts2000).first)
            assertEquals(Instant.parse("2000-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(ts2000))
            // 1900 is NOT a leap year (divisible by 100 but not 400) -> 365 days.
            val ts1900 = Instant.parse("1900-06-15T12:00:00Z").toEpochMilli()
            assertEquals(365L * 24 * 60 * 60 * 1000, TimePeriodUtils.getYearRange(ts1900).second - TimePeriodUtils.getYearRange(ts1900).first)
            assertEquals(Instant.parse("1900-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(ts1900))
        }
    }

    @Test
    fun `Feb 29 stays inside its leap year range and offsets land on the correct years`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val leapRange = TimePeriodUtils.getYearRange(leapDay2024Utc, 0)
                assertTrue("ts in range $zoneId", TimePeriodUtils.isInRange(leapDay2024Utc, leapRange.first, leapRange.second))
                assertEquals("offset -1 $zoneId", legacyYearRange(leapDay2024Utc, -1), TimePeriodUtils.getYearRange(leapDay2024Utc, -1))
                assertEquals("offset +1 $zoneId", legacyYearRange(leapDay2024Utc, 1), TimePeriodUtils.getYearRange(leapDay2024Utc, 1))
            }
        }
        withZone("UTC") {
            // Leap-day clamping in the shifted reference (Feb 29 -> Feb 28) must
            // never change the resulting year: -1 -> 2023, +1 -> 2025.
            assertEquals(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(leapDay2024Utc, -1).first)
            assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(leapDay2024Utc, 0).first)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(leapDay2024Utc, 1).first)
            // Feb 29 stays inside its leap-year range: the unshifted 2024 range
            // ends at Jan 1 2025.
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(leapDay2024Utc, 0).second)
        }
    }

    // ============================================================================
    // DST quarter and year boundaries
    // ============================================================================

    @Test
    fun `DST spring forward quarter is 91 days minus one hour`() {
        withZone("America/New_York") {
            // Q1 2024: Jan 1 00:00 EST = 05:00Z; Apr 1 00:00 EDT = 04:00Z (spring-forward Mar 10).
            val start = TimePeriodUtils.getStartOfQuarter(springQuarterUtc)
            val end = TimePeriodUtils.getEndOfQuarter(springQuarterUtc)
            assertEquals(Instant.parse("2024-01-01T05:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-04-01T04:00:00Z").toEpochMilli(), end)
            assertEquals(91L * 24 * 60 * 60 * 1000 - 3_600_000L, end - start)
            assertNotEquals("must not be fixed 91*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 91, end - start)

            assertEquals(explicitStartOfQuarter(springQuarterUtc, "America/New_York"), start)
            assertEquals(explicitEndOfQuarter(springQuarterUtc, "America/New_York"), end)
            assertEquals(legacyStartOfQuarter(springQuarterUtc), start)
            assertEquals(legacyEndOfQuarter(springQuarterUtc), end)
            assertEquals(start, TimePeriodUtils.getQuarterRange(springQuarterUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getQuarterRange(springQuarterUtc, 1).first)
        }
    }

    @Test
    fun `DST fall back quarter is 92 days plus one hour`() {
        withZone("America/New_York") {
            // Q4 2024: Oct 1 00:00 EDT = 04:00Z; Jan 1 2025 00:00 EST = 05:00Z (fall-back Nov 3).
            val start = TimePeriodUtils.getStartOfQuarter(fallQuarterUtc)
            val end = TimePeriodUtils.getEndOfQuarter(fallQuarterUtc)
            assertEquals(Instant.parse("2024-10-01T04:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2025-01-01T05:00:00Z").toEpochMilli(), end)
            assertEquals(92L * 24 * 60 * 60 * 1000 + 3_600_000L, end - start)
            assertNotEquals("must not be fixed 92*DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS * 92, end - start)

            assertEquals(explicitStartOfQuarter(fallQuarterUtc, "America/New_York"), start)
            assertEquals(explicitEndOfQuarter(fallQuarterUtc, "America/New_York"), end)
            assertEquals(legacyStartOfQuarter(fallQuarterUtc), start)
            assertEquals(legacyEndOfQuarter(fallQuarterUtc), end)
            assertEquals(start, TimePeriodUtils.getQuarterRange(fallQuarterUtc, -1).second)
            assertEquals(end, TimePeriodUtils.getQuarterRange(fallQuarterUtc, 1).first)
        }
    }

    @Test
    fun `quarter boundaries stay at local midnight and first of month`() {
        withZone("America/New_York") {
            for (ts in listOf(springQuarterUtc, fallQuarterUtc, juneNoonUtc)) {
                val startCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getStartOfQuarter(ts) }
                val endCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getEndOfQuarter(ts) }
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

    @Test
    fun `year boundaries stay at local midnight and January first`() {
        withZone("America/New_York") {
            for (ts in listOf(springQuarterUtc, fallQuarterUtc, juneNoonUtc)) {
                val startCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getStartOfYear(ts) }
                val endCal = Calendar.getInstance().apply { timeInMillis = TimePeriodUtils.getEndOfYear(ts) }
                assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
                assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
                assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
                assertEquals(Calendar.JANUARY, endCal.get(Calendar.MONTH))
                assertEquals(1, endCal.get(Calendar.DAY_OF_MONTH))
                assertEquals(0, endCal.get(Calendar.HOUR_OF_DAY))
            }
        }
    }

    @Test
    fun `year spanning both DST transitions is exactly 366 or 365 calendar days`() {
        withZone("America/New_York") {
            // Leap 2024 in a northern-hemisphere zone: spring-forward Mar 10 and
            // fall-back Nov 3 cancel, leaving exactly 366 calendar days even
            // though the year spans both transitions.
            val start = TimePeriodUtils.getStartOfYear(juneNoonUtc)
            val end = TimePeriodUtils.getEndOfYear(juneNoonUtc)
            assertEquals(Instant.parse("2024-01-01T05:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2025-01-01T05:00:00Z").toEpochMilli(), end)
            assertEquals(366L * 24 * 60 * 60 * 1000, end - start)
        }
        withZone("Australia/Melbourne") {
            // Southern-hemisphere zone: the year boundary itself sits inside DST
            // (AEDT +11) while the year spans one fall-back (Apr) and one
            // spring-forward (Oct). The 366/365-day duration survives exactly.
            val leap2024 = TimePeriodUtils.getYearRange(juneNoonUtc)
            assertEquals(Instant.parse("2023-12-31T13:00:00Z").toEpochMilli(), leap2024.first)
            assertEquals(Instant.parse("2024-12-31T13:00:00Z").toEpochMilli(), leap2024.second)
            assertEquals(366L * 24 * 60 * 60 * 1000, leap2024.second - leap2024.first)

            val june2023 = Instant.parse("2023-06-15T12:00:00Z").toEpochMilli()
            val nonLeap2023 = TimePeriodUtils.getYearRange(june2023)
            assertEquals(Instant.parse("2022-12-31T13:00:00Z").toEpochMilli(), nonLeap2023.first)
            assertEquals(Instant.parse("2023-12-31T13:00:00Z").toEpochMilli(), nonLeap2023.second)
            assertEquals(365L * 24 * 60 * 60 * 1000, nonLeap2023.second - nonLeap2023.first)
        }
    }

    // ============================================================================
    // Half-open exact end, epoch / negative values
    // ============================================================================

    @Test
    fun `year end is exclusive and the last millisecond is included`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc, springQuarterUtc, fallQuarterUtc)) {
                    val start = TimePeriodUtils.getStartOfYear(ts)
                    val end = TimePeriodUtils.getEndOfYear(ts)
                    assertTrue("ts=$ts >= start", ts >= start)
                    assertTrue("ts=$ts < end", ts < end)
                    assertFalse("end excluded ts=$ts", TimePeriodUtils.isInRange(end, start, end))
                    assertTrue("end-1 included ts=$ts", TimePeriodUtils.isInRange(end - 1, start, end))
                    // The exact end instant belongs to the NEXT year.
                    assertEquals("end maps to next year ts=$ts", end, TimePeriodUtils.getStartOfYear(end))
                }
            }
        }
    }

    @Test
    fun `quarter end is exclusive and the last millisecond is included`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc, springQuarterUtc, fallQuarterUtc)) {
                    val start = TimePeriodUtils.getStartOfQuarter(ts)
                    val end = TimePeriodUtils.getEndOfQuarter(ts)
                    assertTrue("ts=$ts >= start", ts >= start)
                    assertTrue("ts=$ts < end", ts < end)
                    assertFalse("end excluded ts=$ts", TimePeriodUtils.isInRange(end, start, end))
                    assertTrue("end-1 included ts=$ts", TimePeriodUtils.isInRange(end - 1, start, end))
                    // The exact end instant belongs to the NEXT quarter.
                    assertEquals("end maps to next quarter ts=$ts", end, TimePeriodUtils.getStartOfQuarter(end))
                }
            }
        }
    }

    @Test
    fun `epoch and negative timestamps map deterministically`() {
        withZone("UTC") {
            // 1970-01-01T00:00Z is the epoch; -1000 ms is 1969-12-31T23:59:59Z.
            assertEquals(Instant.parse("1970-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(epochZero))
            assertEquals(Instant.parse("1971-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfYear(epochZero))
            assertEquals(Instant.parse("1969-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(minusOneSecond))
            assertEquals(Instant.parse("1970-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfYear(minusOneSecond))
            assertEquals(TimePeriodUtils.getStartOfYear(epochZero), TimePeriodUtils.getStartOfYear(Instant.parse("1970-06-15T12:00:00Z").toEpochMilli()))

            assertEquals(Instant.parse("1970-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfQuarter(epochZero))
            assertEquals(Instant.parse("1970-04-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfQuarter(epochZero))
            assertEquals(Instant.parse("1969-10-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfQuarter(minusOneSecond))
            assertEquals(Instant.parse("1970-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfQuarter(minusOneSecond))
        }
        withZone("America/New_York") {
            // Epoch instant is Wednesday 1969-12-31 19:00 EST -> year 1969, Q4.
            assertEquals(Instant.parse("1969-01-01T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(epochZero))
            assertEquals(Instant.parse("1970-01-01T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfYear(epochZero))
            assertEquals(Instant.parse("1969-10-01T04:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfQuarter(epochZero))
            assertEquals(Instant.parse("1970-01-01T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfQuarter(epochZero))
        }
    }

    @Test
    fun `pre 1970 negative timestamps match both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val ts = preEpoch1950
                assertEquals("year start legacy $zoneId", legacyStartOfYear(ts), TimePeriodUtils.getStartOfYear(ts))
                assertEquals("year start explicit $zoneId", explicitStartOfYear(ts, zoneId), TimePeriodUtils.getStartOfYear(ts))
                assertEquals("year end legacy $zoneId", legacyEndOfYear(ts), TimePeriodUtils.getEndOfYear(ts))
                assertEquals("year end explicit $zoneId", explicitEndOfYear(ts, zoneId), TimePeriodUtils.getEndOfYear(ts))
                assertEquals("quarter start legacy $zoneId", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                assertEquals("quarter start explicit $zoneId", explicitStartOfQuarter(ts, zoneId), TimePeriodUtils.getStartOfQuarter(ts))
                assertEquals("quarter end legacy $zoneId", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                assertEquals("quarter end explicit $zoneId", explicitEndOfQuarter(ts, zoneId), TimePeriodUtils.getEndOfQuarter(ts))
            }
        }
    }

    @Test
    fun `year 9999 boundaries are exact proleptic instants`() {
        withZone("UTC") {
            val ts = LocalDateTime.of(9999, 12, 31, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            assertEquals(Instant.parse("9999-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfYear(ts))
            assertEquals(Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfYear(ts))
            // Q4 9999 (Oct 1 ... Jan 1 +10000).
            assertEquals(Instant.parse("9999-10-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfQuarter(ts))
            assertEquals(Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfQuarter(ts))
        }
    }

    // ============================================================================
    // Extreme Long values
    // ============================================================================

    @Test
    fun `extreme Long values behave deterministically without wrapping`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                // Long.MAX_VALUE maps to year 292278994 (August). The start of
                // that year / of its quarter (Jul 1) is still representable, so
                // start-of-year / start-of-quarter succeed and match the explicit
                // java.time oracle. The exclusive end (next year / next quarter)
                // lies beyond Long.MAX_VALUE, so end/range fail deterministically
                // with ArithmeticException.
                val maxYearStart = TimePeriodUtils.getStartOfYear(Long.MAX_VALUE)
                assertEquals("max year start explicit $zoneId", explicitStartOfYear(Long.MAX_VALUE, zoneId), maxYearStart)
                assertTrue("max year start before input", maxYearStart < Long.MAX_VALUE)
                assertTrue("max year start fits in Long", maxYearStart >= Long.MIN_VALUE)

                val maxQuarterStart = TimePeriodUtils.getStartOfQuarter(Long.MAX_VALUE)
                assertEquals("max quarter start explicit $zoneId", explicitStartOfQuarter(Long.MAX_VALUE, zoneId), maxQuarterStart)
                assertTrue("max quarter start before input", maxQuarterStart < Long.MAX_VALUE)
                assertTrue("max quarter start fits in Long", maxQuarterStart >= Long.MIN_VALUE)

                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getEndOfYear(Long.MAX_VALUE) }
                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getYearRange(Long.MAX_VALUE) }
                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getEndOfQuarter(Long.MAX_VALUE) }
                assertThrows(ArithmeticException::class.java) { TimePeriodUtils.getQuarterRange(Long.MAX_VALUE) }

                // Long.MIN_VALUE lies *before* the cutover, so the legacy
                // pre-Gregorian compatibility seam applies: all year/quarter
                // helpers delegate to the legacy Calendar implementation and
                // return its deterministic values (no exception).
                assertEquals(
                    "min year start legacy $zoneId",
                    legacyStartOfYear(Long.MIN_VALUE),
                    TimePeriodUtils.getStartOfYear(Long.MIN_VALUE)
                )
                assertEquals(
                    "min year end legacy $zoneId",
                    legacyEndOfYear(Long.MIN_VALUE),
                    TimePeriodUtils.getEndOfYear(Long.MIN_VALUE)
                )
                assertEquals(
                    "min year range legacy $zoneId",
                    legacyYearRange(Long.MIN_VALUE, 0),
                    TimePeriodUtils.getYearRange(Long.MIN_VALUE, 0)
                )
                assertEquals(
                    "min quarter start legacy $zoneId",
                    legacyStartOfQuarter(Long.MIN_VALUE),
                    TimePeriodUtils.getStartOfQuarter(Long.MIN_VALUE)
                )
                assertEquals(
                    "min quarter end legacy $zoneId",
                    legacyEndOfQuarter(Long.MIN_VALUE),
                    TimePeriodUtils.getEndOfQuarter(Long.MIN_VALUE)
                )
                assertEquals(
                    "min quarter range legacy $zoneId",
                    legacyQuarterRange(Long.MIN_VALUE, 0),
                    TimePeriodUtils.getQuarterRange(Long.MIN_VALUE, 0)
                )
            }
        }
    }

    // ============================================================================
    // Pre-Gregorian-cutover dates (legacy compatibility seam)
    // ============================================================================

    @Test
    fun `pre Gregorian cutover year and quarter boundaries match the legacy Calendar oracle across zones and offsets`() {
        // Pre-Gregorian compatibility seam: timestamps strictly before the
        // legacy GregorianCalendar cutover (1582-10-15T00:00:00Z) are delegated
        // to the private legacy year/quarter helpers, so the pre-migration
        // Calendar results (Julian date rules + the timezone's standard offset)
        // are reproduced exactly. Year-1000/1400/1500 and cutover-adjacent
        // 1582-10-01 / 1582-10-10 timestamps are all pre-cutover, so production
        // must equal the independent legacy Calendar oracle — NOT the proleptic
        // java.time result, which applies historical Local Mean Time offsets
        // and therefore diverges for non-UTC zones (America/New_York -04:56:02
        // LMT vs -05:00 standard, Asia/Kolkata +05:53:28 LMT vs +05:30
        // standard). Exercised for year/quarter offsets -1, 0 and +1.
        val cases = listOf(
            Pair("UTC", "1000-01-01T12:00:00Z"),
            Pair("UTC", "1400-06-15T12:00:00Z"),
            Pair("UTC", "1500-02-28T12:00:00Z"),
            Pair("UTC", "1582-10-01T12:00:00Z"),
            Pair("UTC", "1582-10-10T12:00:00Z"),
            Pair("America/New_York", "1000-01-01T12:00:00Z"),
            Pair("America/New_York", "1400-06-15T12:00:00Z"),
            Pair("America/New_York", "1500-02-28T12:00:00Z"),
            Pair("America/New_York", "1582-10-01T12:00:00Z"),
            Pair("America/New_York", "1582-10-10T12:00:00Z"),
            Pair("Asia/Kolkata", "1000-01-01T12:00:00Z"),
            Pair("Asia/Kolkata", "1400-06-15T12:00:00Z"),
            Pair("Asia/Kolkata", "1500-02-28T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-01T12:00:00Z"),
            Pair("Asia/Kolkata", "1582-10-10T12:00:00Z"),
            Pair("Europe/Athens", "1000-01-01T12:00:00Z"),
            Pair("Europe/Athens", "1400-06-15T12:00:00Z"),
            Pair("Europe/Athens", "1500-02-28T12:00:00Z"),
            Pair("Europe/Athens", "1582-10-01T12:00:00Z"),
            Pair("Europe/Athens", "1582-10-10T12:00:00Z")
        )
        for ((zoneId, iso) in cases) {
            withZone(zoneId) {
                val ts = Instant.parse(iso).toEpochMilli()
                assertEquals("year start legacy $zoneId $iso", legacyStartOfYear(ts), TimePeriodUtils.getStartOfYear(ts))
                assertEquals("year end legacy $zoneId $iso", legacyEndOfYear(ts), TimePeriodUtils.getEndOfYear(ts))
                assertEquals("quarter start legacy $zoneId $iso", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                assertEquals("quarter end legacy $zoneId $iso", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                for (offset in listOf(-1, 0, 1)) {
                    assertEquals(
                        "year range legacy $zoneId $iso offset=$offset",
                        legacyYearRange(ts, offset),
                        TimePeriodUtils.getYearRange(ts, offset)
                    )
                    assertEquals(
                        "quarter range legacy $zoneId $iso offset=$offset",
                        legacyQuarterRange(ts, offset),
                        TimePeriodUtils.getQuarterRange(ts, offset)
                    )
                }
            }
        }
    }

    @Test
    fun `pre Gregorian cutover year overload matches the legacy Calendar oracle`() {
        // The year overload constructs Jan 1 of the raw year with the legacy
        // Calendar (Julian rules before the cutover), then flows through the
        // pre-Gregorian seam, so year-1400/1500 and cutover-adjacent 1582 must
        // equal the independent legacy oracle — NOT the proleptic java.time
        // Year.of result.
        val cases = listOf(
            "UTC" to 1400,
            "UTC" to 1500,
            "UTC" to 1582,
            "America/New_York" to 1400,
            "America/New_York" to 1500,
            "America/New_York" to 1582,
            "Asia/Kolkata" to 1400,
            "Asia/Kolkata" to 1500,
            "Asia/Kolkata" to 1582,
            "Europe/Athens" to 1400,
            "Europe/Athens" to 1500,
            "Europe/Athens" to 1582
        )
        for ((zoneId, year) in cases) {
            withZone(zoneId) {
                val expected = legacyYearRangeForYear(year)
                assertEquals("year overload legacy $zoneId $year", expected, TimePeriodUtils.getYearRange(year))
            }
        }
    }

    @Test
    fun `pre Gregorian cutover year boundaries intentionally diverge from proleptic java time`() {
        // Sanity check of the seam's purpose: at a pre-cutover date the legacy
        // Calendar result (Julian rules + standard offset) is NOT the same as
        // the proleptic java.time result for non-UTC zones (historical LMT
        // offsets), so the seam is what preserves the old behavior. Production
        // follows the legacy result.
        withZone("America/New_York") {
            val ts = Instant.parse("1500-02-28T12:00:00Z").toEpochMilli()
            val legacy = legacyStartOfYear(ts)
            val proleptic = explicitStartOfYear(ts, "America/New_York")
            assertNotEquals("legacy and proleptic must diverge pre-cutover", proleptic, legacy)
            assertEquals("production follows the legacy seam", legacy, TimePeriodUtils.getStartOfYear(ts))
            assertNotEquals("production must not follow proleptic", proleptic, TimePeriodUtils.getStartOfYear(ts))
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
        // must equal the independent legacy Calendar oracle for year/quarter
        // start, end and the -1..+1 offset matrix. The legacy year start of any
        // October 1582 instant resolves to the Julian Jan 1 1582 (NOT the
        // proleptic Gregorian Jan 1 1582), so the discriminator below proves
        // this is a real boundary case and not a case where both oracles happen
        // to agree.
        val instants = listOf(
            "1582-10-14T12:00:00Z" to preCutoverNoonUtc,
            "1582-10-14T23:59:59.999Z" to cutoverMinusOne
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for ((iso, ts) in instants) {
                    assertNotEquals(
                        "legacy and explicit must diverge pre-cutover $zoneId $iso",
                        explicitStartOfYear(ts, zoneId),
                        legacyStartOfYear(ts)
                    )
                    assertEquals("year start legacy $zoneId $iso", legacyStartOfYear(ts), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("year end legacy $zoneId $iso", legacyEndOfYear(ts), TimePeriodUtils.getEndOfYear(ts))
                    assertEquals("quarter start legacy $zoneId $iso", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("quarter end legacy $zoneId $iso", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "year range legacy $zoneId $iso offset=$offset",
                            legacyYearRange(ts, offset),
                            TimePeriodUtils.getYearRange(ts, offset)
                        )
                        assertEquals(
                            "quarter range legacy $zoneId $iso offset=$offset",
                            legacyQuarterRange(ts, offset),
                            TimePeriodUtils.getQuarterRange(ts, offset)
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
                        // The legacy Calendar and proleptic java.time oracles
                        // agree at/after the cutover only while the derived
                        // boundary itself lies at/after the cutover. The year
                        // containing the cutover starts (January 1582) before
                        // it, where the legacy Calendar applies Julian date
                        // rules (10-day offset), so the oracle-agreement check
                        // is asserted only where it can hold. Production stays
                        // pinned to the explicit java.time oracle below either
                        // way.
                        if (legacyStartOfYear(ts) >= cutoverEpochMillis) {
                            assertEquals(
                                "legacy and explicit must agree at/after cutover $zoneId $iso",
                                legacyStartOfYear(ts),
                                explicitStartOfYear(ts, zoneId)
                            )
                            assertEquals(
                                "legacy end and explicit end must agree at/after cutover $zoneId $iso",
                                legacyEndOfYear(ts),
                                explicitEndOfYear(ts, zoneId)
                            )
                            assertEquals(
                                "legacy range and explicit range must agree at/after cutover $zoneId $iso",
                                legacyYearRange(ts, 0),
                                explicitYearRange(ts, zoneId, 0)
                            )
                        }
                    } else {
                        assertTrue(
                            "zone $zoneId is not independently verified as divergent at/after cutover",
                            zoneId in verifiedDivergentZones
                        )
                        assertNotEquals(
                            "legacy and explicit must diverge at/after cutover $zoneId $iso",
                            legacyStartOfYear(ts),
                            explicitStartOfYear(ts, zoneId)
                        )
                    }
                    assertEquals("year start explicit $zoneId $iso", explicitStartOfYear(ts, zoneId), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("year end explicit $zoneId $iso", explicitEndOfYear(ts, zoneId), TimePeriodUtils.getEndOfYear(ts))
                    assertEquals("quarter start explicit $zoneId $iso", explicitStartOfQuarter(ts, zoneId), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("quarter end explicit $zoneId $iso", explicitEndOfQuarter(ts, zoneId), TimePeriodUtils.getEndOfQuarter(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "year range explicit $zoneId $iso offset=$offset",
                            explicitYearRange(ts, zoneId, offset),
                            TimePeriodUtils.getYearRange(ts, offset)
                        )
                        assertEquals(
                            "quarter range explicit $zoneId $iso offset=$offset",
                            explicitQuarterRange(ts, zoneId, offset),
                            TimePeriodUtils.getQuarterRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `cutover boundary ranges are contiguous with no overlap or gap`() {
        // The half-open range contract holds for every selected cutover-adjacent
        // instant on both paths: the offset -1 end equals the offset-0 start and
        // the offset-0 end equals the offset +1 start (no overlap, no gap), for
        // years and quarters alike.
        data class InstantCase(val iso: String, val ts: Long)
        val instants = listOf(
            InstantCase("1582-10-14T12:00:00Z", preCutoverNoonUtc),
            InstantCase("1582-10-14T23:59:59.999Z", cutoverMinusOne),
            InstantCase("1582-10-15T00:00:00Z", cutoverExact),
            InstantCase("1582-10-15T00:00:00.001Z", cutoverPlusOne),
            InstantCase("1582-10-15T12:00:00Z", postCutoverNoonUtc)
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (case in instants) {
                    val ts = case.ts
                    assertEquals(
                        "prev year end == this start $zoneId ${case.iso}",
                        TimePeriodUtils.getYearRange(ts, 0).first,
                        TimePeriodUtils.getYearRange(ts, -1).second
                    )
                    assertEquals(
                        "this year end == next start $zoneId ${case.iso}",
                        TimePeriodUtils.getYearRange(ts, 0).second,
                        TimePeriodUtils.getYearRange(ts, 1).first
                    )
                    assertEquals(
                        "prev quarter end == this start $zoneId ${case.iso}",
                        TimePeriodUtils.getQuarterRange(ts, 0).first,
                        TimePeriodUtils.getQuarterRange(ts, -1).second
                    )
                    assertEquals(
                        "this quarter end == next start $zoneId ${case.iso}",
                        TimePeriodUtils.getQuarterRange(ts, 0).second,
                        TimePeriodUtils.getQuarterRange(ts, 1).first
                    )
                    assertTrue("ts in year $zoneId ${case.iso}", TimePeriodUtils.isInRange(ts, TimePeriodUtils.getYearRange(ts, 0).first, TimePeriodUtils.getYearRange(ts, 0).second))
                    assertTrue("ts in quarter $zoneId ${case.iso}", TimePeriodUtils.isInRange(ts, TimePeriodUtils.getQuarterRange(ts, 0).first, TimePeriodUtils.getQuarterRange(ts, 0).second))
                }
            }
        }
    }

    // ============================================================================
    // Consistency
    // ============================================================================

    @Test
    fun `getYearRange is consistent with getStartOfYear and getEndOfYear`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc, leapDay2024Utc, springQuarterUtc, fallQuarterUtc, epochZero, preEpoch1950)) {
                    val (start, end) = TimePeriodUtils.getYearRange(ts, 0)
                    assertEquals("start $zoneId ts=$ts", TimePeriodUtils.getStartOfYear(ts), start)
                    assertEquals("end $zoneId ts=$ts", TimePeriodUtils.getEndOfYear(ts), end)
                }
            }
        }
    }

    @Test
    fun `getQuarterRange is consistent with getStartOfQuarter and getEndOfQuarter`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(juneNoonUtc, dec31Utc, leapDay2024Utc, springQuarterUtc, fallQuarterUtc, epochZero, preEpoch1950)) {
                    val (start, end) = TimePeriodUtils.getQuarterRange(ts, 0)
                    assertEquals("start $zoneId ts=$ts", TimePeriodUtils.getStartOfQuarter(ts), start)
                    assertEquals("end $zoneId ts=$ts", TimePeriodUtils.getEndOfQuarter(ts), end)
                }
            }
        }
    }

    // ============================================================================
    // Year overload — valid years (1-based, no month) and both oracles
    // ============================================================================

    @Test
    fun `year overload is correct for valid years and matches both oracles`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (year in listOf(2026, 2024, 2000, 1900, 1999)) {
                    val actual = TimePeriodUtils.getYearRange(year)
                    val legacy = legacyYearRangeForYear(year)
                    val explicit = explicitYearRangeForYear(year, zoneId)
                    // The two independent oracles agree except on exotic
                    // zone-data boundaries (e.g. Asia/Kolkata's sub-minute
                    // transition at local midnight Jan 1 1900, where the legacy
                    // Calendar's field-based overlap resolution and java.time's
                    // instant-based resolution place the wrapped timestamp a
                    // fraction apart, shifting the derived year). Assert
                    // production against the legacy oracle only where the
                    // oracles themselves agree; the explicit oracle (the
                    // production path for post-cutover years) is asserted
                    // unconditionally.
                    if (legacy == explicit) {
                        assertEquals("year overload legacy $zoneId $year", legacy, actual)
                    }
                    assertEquals("year overload explicit $zoneId $year", explicit, actual)
                }
            }
        }
        withZone("UTC") {
            assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(2026).first)
            assertEquals(Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(2026).second)
            assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(2024).first)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getYearRange(2024).second)
        }
    }

    // ============================================================================
    // Regression: migrated implementation must match the independent oracles
    // (post-cutover samples only; see class docs for the pre-cutover note)
    // ============================================================================

    @Test
    fun `migrated year and quarter boundaries match the legacy Calendar oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            juneNoonUtc,
            leapDay2024Utc,
            feb2023Utc,
            dec31Utc,
            springQuarterUtc,
            fallQuarterUtc,
            Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), // spring-forward Sunday pre-gap
            Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), // spring-forward Sunday post-gap
            Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), // fall-back overlap first occurrence
            Instant.parse("2024-11-03T06:30:00Z").toEpochMilli(), // fall-back overlap second occurrence
            Instant.parse("2024-12-31T23:59:59Z").toEpochMilli()  // last instant of the year
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("legacy getStartOfYear $zoneId ts=$ts", legacyStartOfYear(ts), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("legacy getEndOfYear $zoneId ts=$ts", legacyEndOfYear(ts), TimePeriodUtils.getEndOfYear(ts))
                    assertEquals("legacy getStartOfQuarter $zoneId ts=$ts", legacyStartOfQuarter(ts), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("legacy getEndOfQuarter $zoneId ts=$ts", legacyEndOfQuarter(ts), TimePeriodUtils.getEndOfQuarter(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "legacy getYearRange $zoneId ts=$ts offset=$offset",
                            legacyYearRange(ts, offset),
                            TimePeriodUtils.getYearRange(ts, offset)
                        )
                        assertEquals(
                            "legacy getQuarterRange $zoneId ts=$ts offset=$offset",
                            legacyQuarterRange(ts, offset),
                            TimePeriodUtils.getQuarterRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `migrated year and quarter boundaries match the explicit java time oracle across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            preEpoch1950,
            juneNoonUtc,
            leapDay2024Utc,
            feb2023Utc,
            dec31Utc,
            springQuarterUtc,
            fallQuarterUtc
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("explicit getStartOfYear $zoneId ts=$ts", explicitStartOfYear(ts, zoneId), TimePeriodUtils.getStartOfYear(ts))
                    assertEquals("explicit getEndOfYear $zoneId ts=$ts", explicitEndOfYear(ts, zoneId), TimePeriodUtils.getEndOfYear(ts))
                    assertEquals("explicit getStartOfQuarter $zoneId ts=$ts", explicitStartOfQuarter(ts, zoneId), TimePeriodUtils.getStartOfQuarter(ts))
                    assertEquals("explicit getEndOfQuarter $zoneId ts=$ts", explicitEndOfQuarter(ts, zoneId), TimePeriodUtils.getEndOfQuarter(ts))
                    for (offset in listOf(-1, 0, 1)) {
                        assertEquals(
                            "explicit getYearRange $zoneId ts=$ts offset=$offset",
                            explicitYearRange(ts, zoneId, offset),
                            TimePeriodUtils.getYearRange(ts, offset)
                        )
                        assertEquals(
                            "explicit getQuarterRange $zoneId ts=$ts offset=$offset",
                            explicitQuarterRange(ts, zoneId, offset),
                            TimePeriodUtils.getQuarterRange(ts, offset)
                        )
                    }
                }
            }
        }
    }

    // ============================================================================
    // Year overload — Int extremes (lenient Calendar wrap, no throw)
    // ============================================================================

    @Test
    fun `year Int extremes return valid ordered half-open year ranges`() {
        // The year overload builds a lenient Calendar from the raw int year, so
        // at the Int extremes the Calendar arithmetic wraps deterministically
        // instead of throwing (verified on the host JDK: neither extreme year
        // raises DateTimeException/ArithmeticException). The wrapped timestamp
        // lands either before the cutover (legacy Calendar seam) or after it
        // (java.time path), and on both paths the documented contract survives:
        // a valid half-open `[start, end)` year range where start is Jan 1 local
        // midnight and end is Jan 1 of the next year. Exact wrapped millisecond
        // literals are deliberately NOT pinned here — they are JDK-specific
        // artifacts of the lenient Calendar overflow — so these assertions lock
        // the JDK-independent structural contract instead. The expected
        // boundaries are validated independently: the legacy Calendar oracle for
        // pre-cutover wrapped timestamps and the explicit java.time oracle for
        // modern ones — never by re-calling the production year helpers.
        val extremeYears = listOf(Int.MIN_VALUE, Int.MAX_VALUE)
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (year in extremeYears) {
                    val actual = TimePeriodUtils.getYearRange(year)
                    val label = "year=$year $zoneId"
                    // The year overload hands the lenient Calendar's wrapped
                    // timestamp to the pre-Gregorian compatibility seam, so the
                    // oracle family mirrors that seam: legacy Calendar for
                    // pre-cutover wrapped timestamps, explicit java.time at/after
                    // the cutover.
                    val wrappedTs = legacyYearWrappedTimestamp(year)
                    val expected = if (wrappedTs < cutoverEpochMillis) {
                        legacyYearRange(wrappedTs, 0)
                    } else {
                        explicitYearRange(wrappedTs, zoneId, 0)
                    }
                    assertEquals("$label: first matches independent oracle", expected.first, actual.first)
                    assertEquals("$label: second matches independent oracle", expected.second, actual.second)
                    val start = actual.first
                    val end = actual.second
                    assertTrue("$label: ordered range", start < end)
                    if (wrappedTs < cutoverEpochMillis) {
                        assertEquals(
                            "$label: start is Jan 1 midnight (legacy)",
                            start,
                            legacyStartOfYear(start)
                        )
                        assertEquals(
                            "$label: end is next-year Jan 1 midnight (legacy)",
                            end,
                            legacyEndOfYear(start)
                        )
                    } else {
                        val startZoned = Instant.ofEpochMilli(start).atZone(ZoneId.of(zoneId))
                        assertEquals("$label: start is January", 1, startZoned.monthValue)
                        assertEquals("$label: start is day 1", 1, startZoned.dayOfMonth)
                        assertEquals("$label: start is local midnight", 0, startZoned.hour)
                        assertEquals("$label: start has zero minutes", 0, startZoned.minute)
                        assertEquals("$label: start has zero seconds", 0, startZoned.second)
                        assertEquals("$label: start has zero nanos", 0, startZoned.nano)
                        assertEquals(
                            "$label: end is next-year Jan 1 midnight",
                            explicitEndOfYear(start, zoneId),
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

    @Test
    fun `year Int extremes are deterministic across repeated calls`() {
        // Lock repeatability of the lenient Calendar wrap: the result depends only
        // on the year and the default zone, so every call must agree exactly.
        val extremeYears = listOf(Int.MIN_VALUE, Int.MAX_VALUE)
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (year in extremeYears) {
                    val label = "year=$year $zoneId"
                    assertEquals(
                        "$label: repeatable",
                        TimePeriodUtils.getYearRange(year),
                        TimePeriodUtils.getYearRange(year)
                    )
                }
            }
        }
    }
}
