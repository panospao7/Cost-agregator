package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone

/**
 * T4C Batch 2A — locks the migrated java.time implementations of
 * [TimePeriodUtils.getStartOfDay] and [TimePeriodUtils.getEndOfDay] to exact
 * expected values.
 *
 * The suite also locks the **pre-Gregorian compatibility seam**: production
 * intentionally delegates timestamps strictly before the legacy
 * [java.util.GregorianCalendar] cutover (`1582-10-15T00:00:00Z`) to the legacy
 * `Calendar` algorithm so the pre-migration behavior (Julian date rules and the
 * timezone's standard offset) is reproduced exactly. That pre-cutover
 * compatibility is intentional and tested here by asserting production
 * start/end equals the independent legacy `Calendar` oracle at year-1500 dates
 * for UTC, America/New_York, and Asia/Kolkata.
 *
 * Design rules (mirrors TimePeriodUtilsT4CBatch1Test):
 * - Every timestamp is a **fixed UTC instant**; no wall clock, no sleep.
 * - Any test that reads a zone-dependent value mutates the JVM default timezone
 *   under [GlobalTimeZoneTestLock] and restores the original zone in `finally`.
 * - Expected values are asserted **exactly**, either as hardcoded literals, as
 *   explicit `java.time` constructions (`ZonedDateTime.of` on an explicit zone),
 *   or as values computed with the independent **legacy `Calendar` oracle** —
 *   never by re-calling the production helpers.
 * - DST boundary days are asserted by wall-clock duration (23h / 25h), never by
 *   fixed `DAY_IN_MILLIS` arithmetic.
 * - At the `Long` extremes: `getStartOfDay(Long.MIN_VALUE)` follows the legacy
 *   Calendar seam and returns its deterministic value (no exception), while
 *   `getEndOfDay(Long.MAX_VALUE)` fails deterministically with the documented
 *   `ArithmeticException`; neither ever silently wraps.
 */
class TimePeriodUtilsT4CBatch2ATest {

    private companion object {
        // Zone names exercised by the timezone matrix.
        val ZONES = listOf("UTC", "Asia/Kolkata", "Europe/Athens", "America/New_York")

        /** Hardcoded expected start/end instants for one (zone, timestamp) pair. */
        data class NormalDayCase(val zoneId: String, val ts: Long, val startIso: String, val endIso: String)
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

    // ============================================================================
    // Independent oracles (never call the production helpers)
    // ============================================================================

    // Note: there is intentionally NO java.time "start-of-day" oracle here. Any
    // oracle written as `instant.atZone(zone).toLocalDate().atStartOfDay(zone)`
    // is an exact duplicate of the production implementation and adds no
    // independent signal. Expected values come from hardcoded literals, explicit
    // ZonedDateTime construction, or the legacy Calendar oracle below.

    /** Legacy Calendar oracle reproducing the previous production algorithm. */
    private fun legacyStartOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Legacy Calendar oracle reproducing the previous production algorithm. */
    private fun legacyEndOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = legacyStartOfDay(timestamp)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * Explicit java.time construction of local midnight for the given local date
     * in [zoneId]. Independent of the production helpers: built with
     * [ZonedDateTime.of] on an explicit [ZoneId] — never via
     * `LocalDate.atStartOfDay` on the system default zone.
     */
    private fun explicitLocalMidnight(year: Int, month: Int, day: Int, zoneId: String): Long {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneId.of(zoneId))
            .toInstant().toEpochMilli()
    }

    // Fixed UTC instants used throughout the suite.
    private val normalNoonUtc = Instant.parse("2024-06-15T12:00:00Z").toEpochMilli()
    private val normalLateUtc = Instant.parse("2024-06-15T23:45:00Z").toEpochMilli()
    private val springNoonUtc = Instant.parse("2024-03-10T12:00:00Z").toEpochMilli()      // US spring-forward day
    private val fallNoonUtc = Instant.parse("2024-11-03T12:00:00Z").toEpochMilli()       // US fall-back day
    private val epochZero = 0L
    private val minusOneSecond = -1000L
    private val minusOneDay = -86_400_000L
    private val preEpoch1950 = Instant.parse("1950-01-01T00:00:00Z").toEpochMilli()
    private val yearBoundary2024 = Instant.parse("2024-12-31T12:00:00Z").toEpochMilli()  // NY: Dec 31, 2024

    // ============================================================================
    // Normal day
    // ============================================================================

    @Test
    fun `normal day boundaries are exact hardcoded instants for UTC NY and Kolkata`() {
        // Expected local-midnight instants, hardcoded per zone:
        //   UTC              offset +00:00
        //   America/New_York offset -04:00 (EDT, summer) / -05:00 (EST, winter)
        //   Asia/Kolkata     offset +05:30
        val expected = listOf(
            NormalDayCase("UTC", normalNoonUtc, "2024-06-15T00:00:00Z", "2024-06-16T00:00:00Z"),
            NormalDayCase("UTC", normalLateUtc, "2024-06-15T00:00:00Z", "2024-06-16T00:00:00Z"),
            NormalDayCase("UTC", yearBoundary2024, "2024-12-31T00:00:00Z", "2025-01-01T00:00:00Z"),
            NormalDayCase("UTC", preEpoch1950, "1950-01-01T00:00:00Z", "1950-01-02T00:00:00Z"),
            NormalDayCase("America/New_York", normalNoonUtc, "2024-06-15T04:00:00Z", "2024-06-16T04:00:00Z"),
            NormalDayCase("America/New_York", normalLateUtc, "2024-06-15T04:00:00Z", "2024-06-16T04:00:00Z"),
            NormalDayCase("America/New_York", yearBoundary2024, "2024-12-31T05:00:00Z", "2025-01-01T05:00:00Z"),
            NormalDayCase("America/New_York", preEpoch1950, "1949-12-31T05:00:00Z", "1950-01-01T05:00:00Z"),
            NormalDayCase("Asia/Kolkata", normalNoonUtc, "2024-06-14T18:30:00Z", "2024-06-15T18:30:00Z"),
            NormalDayCase("Asia/Kolkata", normalLateUtc, "2024-06-15T18:30:00Z", "2024-06-16T18:30:00Z"),
            NormalDayCase("Asia/Kolkata", yearBoundary2024, "2024-12-30T18:30:00Z", "2024-12-31T18:30:00Z")
        )
        for (case in expected) {
            withZone(case.zoneId) {
                assertEquals(
                    "getStartOfDay ${case.zoneId} ts=${case.ts}",
                    Instant.parse(case.startIso).toEpochMilli(),
                    TimePeriodUtils.getStartOfDay(case.ts)
                )
                assertEquals(
                    "getEndOfDay ${case.zoneId} ts=${case.ts}",
                    Instant.parse(case.endIso).toEpochMilli(),
                    TimePeriodUtils.getEndOfDay(case.ts)
                )
            }
        }
    }

    @Test
    fun `normal day returns exact local midnight values`() {
        withZone("UTC") {
            assertEquals(Instant.parse("2024-06-15T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(normalNoonUtc))
            assertEquals(Instant.parse("2024-06-16T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(normalNoonUtc))
        }
        withZone("America/New_York") {
            // June is EDT (UTC-4): midnight local = 04:00Z, next midnight = 04:00Z next day.
            assertEquals(Instant.parse("2024-06-15T04:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(normalNoonUtc))
            assertEquals(Instant.parse("2024-06-16T04:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(normalNoonUtc))
        }
        withZone("Asia/Kolkata") {
            // +05:30: midnight local = previous day 18:30Z.
            assertEquals(Instant.parse("2024-06-14T18:30:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(normalNoonUtc))
            assertEquals(Instant.parse("2024-06-15T18:30:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(normalNoonUtc))
        }
    }

    @Test
    fun `normal day boundary duration is exactly 24 hours`() {
        withZone("UTC") {
            val start = TimePeriodUtils.getStartOfDay(normalNoonUtc)
            val end = TimePeriodUtils.getEndOfDay(normalNoonUtc)
            assertEquals(24L * 60 * 60 * 1000, end - start)
        }
    }

    // ============================================================================
    // Spring-forward day (23h boundary) — America/New_York 2024-03-10
    // ============================================================================

    @Test
    fun `spring forward day has a 23 hour boundary`() {
        withZone("America/New_York") {
            val start = TimePeriodUtils.getStartOfDay(springNoonUtc)
            val end = TimePeriodUtils.getEndOfDay(springNoonUtc)

            // Midnight EST = 05:00Z; next midnight EDT = 04:00Z (23h later).
            assertEquals(Instant.parse("2024-03-10T05:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(), end)
            assertEquals(23L * 60 * 60 * 1000, end - start)
            assertNotEquals("23h DST day must NOT equal fixed DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS, end - start)
        }
    }

    @Test
    fun `spring forward day matches explicit construction and legacy Calendar oracles`() {
        withZone("America/New_York") {
            // Expected boundaries built by explicit ZonedDateTime construction
            // (NOT by calling the production helpers): local midnight of
            // March 10 (EST, -05:00) and of March 11 (EDT, -04:00).
            val expectedStart = explicitLocalMidnight(2024, 3, 10, "America/New_York")
            val expectedEnd = explicitLocalMidnight(2024, 3, 11, "America/New_York")

            for (ts in listOf(
                Instant.parse("2024-03-10T05:00:00Z").toEpochMilli(), // exactly midnight EST
                Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), // 01:59:59 EST (last pre-gap instant)
                springNoonUtc,
                Instant.parse("2024-03-11T03:59:59Z").toEpochMilli()  // last ms of the 23h day
            )) {
                assertEquals("start explicit $ts", expectedStart, TimePeriodUtils.getStartOfDay(ts))
                assertEquals("start legacy $ts", legacyStartOfDay(ts), TimePeriodUtils.getStartOfDay(ts))
                assertEquals("end explicit $ts", expectedEnd, TimePeriodUtils.getEndOfDay(ts))
                assertEquals("end legacy $ts", legacyEndOfDay(ts), TimePeriodUtils.getEndOfDay(ts))
            }
        }
    }

    // ============================================================================
    // Fall-back day (25h boundary) — America/New_York 2024-11-03
    // ============================================================================

    @Test
    fun `fall back day has a 25 hour boundary`() {
        withZone("America/New_York") {
            val start = TimePeriodUtils.getStartOfDay(fallNoonUtc)
            val end = TimePeriodUtils.getEndOfDay(fallNoonUtc)

            // Midnight EDT = 04:00Z; next midnight EST = 05:00Z (25h later).
            assertEquals(Instant.parse("2024-11-03T04:00:00Z").toEpochMilli(), start)
            assertEquals(Instant.parse("2024-11-04T05:00:00Z").toEpochMilli(), end)
            assertEquals(25L * 60 * 60 * 1000, end - start)
            assertNotEquals("25h DST day must NOT equal fixed DAY_IN_MILLIS", TimePeriodUtils.DAY_IN_MILLIS, end - start)
        }
    }

    @Test
    fun `fall back day matches explicit construction and legacy Calendar oracles`() {
        withZone("America/New_York") {
            // Expected boundaries built by explicit ZonedDateTime construction
            // (NOT by calling the production helpers): local midnight of
            // November 3 (EDT, -04:00) and of November 4 (EST, -05:00).
            val expectedStart = explicitLocalMidnight(2024, 11, 3, "America/New_York")
            val expectedEnd = explicitLocalMidnight(2024, 11, 4, "America/New_York")

            for (ts in listOf(
                Instant.parse("2024-11-03T04:00:00Z").toEpochMilli(), // exactly midnight EDT
                Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), // 01:30 EDT (first occurrence)
                fallNoonUtc,
                Instant.parse("2024-11-04T04:59:59Z").toEpochMilli()  // last ms of the 25h day
            )) {
                assertEquals("start explicit $ts", expectedStart, TimePeriodUtils.getStartOfDay(ts))
                assertEquals("start legacy $ts", legacyStartOfDay(ts), TimePeriodUtils.getStartOfDay(ts))
                assertEquals("end explicit $ts", expectedEnd, TimePeriodUtils.getEndOfDay(ts))
                assertEquals("end legacy $ts", legacyEndOfDay(ts), TimePeriodUtils.getEndOfDay(ts))
            }
        }
    }

    // ============================================================================
    // Timestamps inside a DST gap / overlap
    // ============================================================================

    @Test
    fun `instant resolved from within the spring-forward gap maps to that day`() {
        withZone("America/New_York") {
            // 2024-03-10 02:30 local does not exist: java.time resolves it forward
            // through the gap to 03:30 EDT. Sanity-check the test construction.
            val resolved = ZonedDateTime.of(LocalDateTime.of(2024, 3, 10, 2, 30, 0), ZoneId.of("America/New_York"))
            assertEquals("2024-03-10", resolved.toLocalDate().toString())
            assertEquals(LocalTime.of(3, 30), resolved.toLocalTime())
            assertEquals(ZoneOffset.ofHours(-4), resolved.offset)
            val ts = resolved.toInstant().toEpochMilli()

            // It still belongs to March 10 — midnight of that day.
            assertEquals(Instant.parse("2024-03-10T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(ts))
            assertEquals(Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(ts))
            // Independent check via explicit ZonedDateTime construction.
            assertEquals(explicitLocalMidnight(2024, 3, 10, "America/New_York"), TimePeriodUtils.getStartOfDay(ts))
            assertEquals(explicitLocalMidnight(2024, 3, 11, "America/New_York"), TimePeriodUtils.getEndOfDay(ts))
        }
    }

    @Test
    fun `instants on both sides of the spring-forward gap share the same day`() {
        withZone("America/New_York") {
            val beforeGap = Instant.parse("2024-03-10T06:59:59Z").toEpochMilli() // 01:59:59 EST
            val afterGap = Instant.parse("2024-03-10T07:00:00Z").toEpochMilli()  // 03:00:00 EDT

            // Both instants must land on the same hardcoded day boundaries.
            assertEquals(Instant.parse("2024-03-10T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(beforeGap))
            assertEquals(Instant.parse("2024-03-10T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(afterGap))
            assertEquals(Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(beforeGap))
            assertEquals(Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(afterGap))
        }
    }

    @Test
    fun `both occurrences of the fall-back overlap hour map to the same day`() {
        withZone("America/New_York") {
            val first = Instant.parse("2024-11-03T05:30:00Z").toEpochMilli()  // 01:30 EDT (first occurrence)
            val second = Instant.parse("2024-11-03T06:30:00Z").toEpochMilli() // 01:30 EST (second occurrence)

            // Same local date → same hardcoded day boundaries for both occurrences.
            assertEquals(Instant.parse("2024-11-03T04:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(first))
            assertEquals(Instant.parse("2024-11-03T04:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(second))
            assertEquals(Instant.parse("2024-11-04T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(first))
            assertEquals(Instant.parse("2024-11-04T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(second))
            // Independent check via explicit ZonedDateTime construction.
            assertEquals(explicitLocalMidnight(2024, 11, 3, "America/New_York"), TimePeriodUtils.getStartOfDay(second))
            assertEquals(explicitLocalMidnight(2024, 11, 4, "America/New_York"), TimePeriodUtils.getEndOfDay(second))
        }
    }

    // ============================================================================
    // Epoch / negative timestamps
    // ============================================================================

    @Test
    fun `epoch timestamp maps to 1970-01-01`() {
        withZone("UTC") {
            assertEquals(epochZero, TimePeriodUtils.getStartOfDay(epochZero))
            assertEquals(Instant.parse("1970-01-02T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(epochZero))
        }
        withZone("America/New_York") {
            // Epoch instant is 1969-12-31 19:00 EST.
            assertEquals(Instant.parse("1969-12-31T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(epochZero))
            assertEquals(Instant.parse("1970-01-01T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(epochZero))
        }
    }

    @Test
    fun `negative timestamps before epoch are handled deterministically`() {
        withZone("UTC") {
            // -1000 ms = 1969-12-31T23:59:59Z.
            assertEquals(Instant.parse("1969-12-31T00:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(minusOneSecond))
            assertEquals(Instant.parse("1970-01-01T00:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(minusOneSecond))

            // -86_400_000 ms = exactly 1969-12-31T00:00:00Z.
            assertEquals(minusOneDay, TimePeriodUtils.getStartOfDay(minusOneDay))
            assertEquals(epochZero, TimePeriodUtils.getEndOfDay(minusOneDay))
        }
        withZone("Asia/Kolkata") {
            // -86_400_000 ms = 1969-12-31T05:30 IST → local date 1969-12-31,
            // so local midnight is 1969-12-30T18:30Z.
            assertEquals(Instant.parse("1969-12-30T18:30:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(minusOneDay))
            assertEquals(Instant.parse("1969-12-31T18:30:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(minusOneDay))
        }
        withZone("America/New_York") {
            // Pre-epoch date: 1950-01-01T00:00Z is 1949-12-31 19:00 EST.
            assertEquals(Instant.parse("1949-12-31T05:00:00Z").toEpochMilli(), TimePeriodUtils.getStartOfDay(preEpoch1950))
            assertEquals(Instant.parse("1950-01-01T05:00:00Z").toEpochMilli(), TimePeriodUtils.getEndOfDay(preEpoch1950))
        }
    }

    // ============================================================================
    // Min / max boundary timestamps
    // ============================================================================

    @Test
    fun `realistic minimum and maximum timestamps produce exact boundaries`() {
        // Practical extremes of the epoch-millis range used by real app data:
        // the LocalDate-representable year range (year 1 through 9999).
        val earliest = LocalDateTime.of(1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val earliestEnd = LocalDateTime.of(1, 1, 2, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val latest = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        val latestStart = LocalDateTime.of(9999, 12, 31, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val latestEnd = LocalDateTime.of(10000, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

        withZone("UTC") {
            assertEquals("earliest start", earliest, TimePeriodUtils.getStartOfDay(earliest))
            assertEquals("earliest end", earliestEnd, TimePeriodUtils.getEndOfDay(earliest))
            assertEquals("latest start", latestStart, TimePeriodUtils.getStartOfDay(latest))
            assertEquals("latest end", latestEnd, TimePeriodUtils.getEndOfDay(latest))
        }
    }

    @Test
    fun `extreme Long epoch millis follow the documented legacy seam behavior`() {
        // Long.MIN_VALUE / Long.MAX_VALUE are the representational limits of the
        // epoch-millis Long type. Under the pre-Gregorian compatibility seam the
        // two extremes behave differently, and neither silently wraps:
        //
        //   - getStartOfDay(Long.MIN_VALUE): Long.MIN_VALUE lies strictly before
        //     the legacy GregorianCalendar cutover (1582-10-15), so the seam
        //     delegates to the legacy Calendar implementation, which returns its
        //     deterministic local-midnight result (no exception). The expected
        //     value is computed by the independent legacy Calendar oracle.
        //   - getEndOfDay(Long.MAX_VALUE): Long.MAX_VALUE lies *after* the
        //     cutover, so the java.time path applies; next-day midnight would
        //     overflow the Long range and fails deterministically with
        //     ArithmeticException (documented controlled failure).
        for (zoneId in ZONES) {
            withZone(zoneId) {
                assertEquals(
                    "start(MIN) legacy $zoneId",
                    legacyStartOfDay(Long.MIN_VALUE),
                    TimePeriodUtils.getStartOfDay(Long.MIN_VALUE)
                )
                assertThrows(ArithmeticException::class.java) {
                    TimePeriodUtils.getEndOfDay(Long.MAX_VALUE)
                }
            }
        }
    }

    @Test
    fun `the other Long extremes remain deterministic and in range`() {
        // The complementary extremes stay representable and deterministic:
        //   - getStartOfDay(Long.MAX_VALUE): local midnight is *before* the input.
        //   - getEndOfDay(Long.MIN_VALUE): next-day midnight is *after* the input.
        //
        // Expected values below are hardcoded constants computed independently
        // with an explicit java.time oracle (never by re-calling the production
        // helpers). At the extreme years java.time applies the earliest/latest
        // known zone rule: for the future instant (MAX) the modern offsets
        // (UTC +00:00, Kolkata +05:30, Athens +03:00, New York -04:00); for the
        // past instant (MIN) the historical LMT offsets (+05:53:28, +01:34:52,
        // -04:56:02 respectively).
        val expectedMaxStart = mapOf(
            "UTC" to 9_223_372_036_828_800_000L,
            "Asia/Kolkata" to 9_223_372_036_809_000_000L,
            "Europe/Athens" to 9_223_372_036_818_000_000L,
            "America/New_York" to 9_223_372_036_843_200_000L
        )
        val expectedMinEnd = mapOf(
            "UTC" to -9_223_372_036_828_800_000L,
            "Asia/Kolkata" to -9_223_372_036_850_008_000L,
            "Europe/Athens" to -9_223_372_036_834_492_000L,
            "America/New_York" to -9_223_372_036_811_038_000L
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                val maxStart = TimePeriodUtils.getStartOfDay(Long.MAX_VALUE)
                assertEquals("start(MAX) $zoneId", expectedMaxStart.getValue(zoneId), maxStart)
                assertTrue("start(MAX)=$maxStart must be strictly before the input", maxStart < Long.MAX_VALUE)
                assertTrue("start(MAX)=$maxStart must fit in Long", maxStart <= Long.MAX_VALUE)

                val minEnd = TimePeriodUtils.getEndOfDay(Long.MIN_VALUE)
                assertEquals("end(MIN) $zoneId", expectedMinEnd.getValue(zoneId), minEnd)
                assertTrue("end(MIN)=$minEnd must be strictly after the input", minEnd > Long.MIN_VALUE)
                assertTrue("end(MIN)=$minEnd must fit in Long", minEnd >= Long.MIN_VALUE)
            }
        }
    }

    // ============================================================================
    // Exact end-exclusive and adjacent-day contiguity
    // ============================================================================

    @Test
    fun `day end is exclusive and the last millisecond is included`() {
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in listOf(normalNoonUtc, springNoonUtc, fallNoonUtc, epochZero, minusOneDay)) {
                    val start = TimePeriodUtils.getStartOfDay(ts)
                    val end = TimePeriodUtils.getEndOfDay(ts)

                    assertTrue("ts=$ts should be >= start", ts >= start)
                    assertTrue("ts=$ts should be < end", ts < end)
                    assertFalse("end should be exclusive for ts=$ts", TimePeriodUtils.isInRange(end, start, end))
                    assertTrue("end-1 should be in range for ts=$ts", TimePeriodUtils.isInRange(end - 1, start, end))
                }
            }
        }
    }

    @Test
    fun `adjacent days are contiguous across normal DST and year boundaries`() {
        // A day's end must equal the next local day's start.
        val cases = listOf(
            Triple("UTC", "2024-06-15T12:00:00Z", "2024-06-16T12:00:00Z"),
            Triple("America/New_York", "2024-03-10T12:00:00Z", "2024-03-11T12:00:00Z"), // spring forward
            Triple("America/New_York", "2024-11-03T12:00:00Z", "2024-11-04T12:00:00Z"), // fall back
            Triple("America/New_York", "2024-12-31T12:00:00Z", "2025-01-01T12:00:00Z")  // year boundary
        )
        for ((zoneId, day1, day2) in cases) {
            withZone(zoneId) {
                val day2Instant = Instant.parse(day2)
                val day2Local = day2Instant.atZone(ZoneId.of(zoneId)).toLocalDate()
                val expectedDay2Start = explicitLocalMidnight(
                    day2Local.year, day2Local.monthValue, day2Local.dayOfMonth, zoneId
                )

                val endDay1 = TimePeriodUtils.getEndOfDay(Instant.parse(day1).toEpochMilli())
                assertEquals("contiguity $zoneId $day1 -> $day2", expectedDay2Start, endDay1)
                // The day-2 noon instant itself maps to the same boundary.
                assertEquals(
                    "day2 start maps to its own boundary $zoneId $day2",
                    expectedDay2Start,
                    TimePeriodUtils.getStartOfDay(day2Instant.toEpochMilli())
                )
            }
        }
    }

    @Test
    fun `getEndOfDay equals getStartOfDay of the next calendar day instant`() {
        withZone("America/New_York") {
            for (ts in listOf(normalNoonUtc, springNoonUtc, fallNoonUtc, yearBoundary2024)) {
                val zone = ZoneId.of("America/New_York")
                val nextDayLocal = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().plusDays(1)
                val nextDayNoon = nextDayLocal.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

                // Expected boundary built with explicit ZonedDateTime construction
                // (NOT by re-calling the production helpers).
                val nextDayMidnight = explicitLocalMidnight(
                    nextDayLocal.year, nextDayLocal.monthValue, nextDayLocal.dayOfMonth, "America/New_York"
                )
                assertEquals("end==next-start ts=$ts", nextDayMidnight, TimePeriodUtils.getEndOfDay(ts))
                // The next-day noon instant itself maps to the same boundary.
                assertEquals("next-day-noon maps to its start ts=$ts", nextDayMidnight, TimePeriodUtils.getStartOfDay(nextDayNoon))
            }
        }
    }

    // ============================================================================
    // Pre-Gregorian-cutover dates (legacy compatibility seam)
    // ============================================================================

    @Test
    fun `pre Gregorian cutover dates match the legacy Calendar oracle across zones`() {
        // Pre-Gregorian compatibility seam: timestamps strictly before the
        // legacy GregorianCalendar cutover (1582-10-15T00:00:00Z) are delegated
        // to the private `legacyStartOfDay`/`legacyEndOfDay` helpers, so the
        // pre-migration Calendar results (Julian date rules + the timezone's
        // standard offset) are reproduced exactly. Year-1500 timestamps are
        // pre-cutover, so production must equal the independent legacy Calendar
        // oracle — NOT the proleptic java.time result, which applies historical
        // Local Mean Time offsets and therefore diverges for non-UTC zones
        // (America/New_York -04:56:02 LMT vs -05:00 standard, Asia/Kolkata
        // +05:53:28 LMT vs +05:30 standard).
        val cases = listOf(
            Triple("UTC", "1500-01-01T12:00:00Z"),
            Triple("UTC", "1500-06-15T12:00:00Z"),
            Triple("America/New_York", "1500-01-01T12:00:00Z"),
            Triple("America/New_York", "1500-06-15T12:00:00Z"),
            Triple("Asia/Kolkata", "1500-01-01T12:00:00Z"),
            Triple("Asia/Kolkata", "1500-06-15T12:00:00Z")
        )
        for ((zoneId, iso) in cases) {
            withZone(zoneId) {
                val ts = Instant.parse(iso).toEpochMilli()
                assertEquals("start legacy $zoneId $iso", legacyStartOfDay(ts), TimePeriodUtils.getStartOfDay(ts))
                assertEquals("end legacy $zoneId $iso", legacyEndOfDay(ts), TimePeriodUtils.getEndOfDay(ts))
            }
        }
    }

    // ============================================================================
    // Regression: migrated implementation must match the legacy Calendar algorithm
    // (post-cutover samples only; pre-cutover dates are covered by the dedicated
    // pre-Gregorian compatibility seam test above).
    // ============================================================================

    @Test
    fun `migrated day boundaries match the legacy Calendar implementation across samples and zones`() {
        val samples = listOf(
            epochZero,
            minusOneSecond,
            minusOneDay,
            preEpoch1950,
            normalNoonUtc,
            normalLateUtc,
            springNoonUtc,
            fallNoonUtc,
            yearBoundary2024,
            Instant.parse("2024-03-10T06:59:59Z").toEpochMilli(), // last pre-gap instant
            Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), // first post-gap instant
            Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), // overlap first occurrence
            Instant.parse("2024-11-03T06:30:00Z").toEpochMilli()  // overlap second occurrence
        )
        for (zoneId in ZONES) {
            withZone(zoneId) {
                for (ts in samples) {
                    assertEquals("legacy getStartOfDay $zoneId ts=$ts", legacyStartOfDay(ts), TimePeriodUtils.getStartOfDay(ts))
                    assertEquals("legacy getEndOfDay $zoneId ts=$ts", legacyEndOfDay(ts), TimePeriodUtils.getEndOfDay(ts))
                }
            }
        }
    }
}
