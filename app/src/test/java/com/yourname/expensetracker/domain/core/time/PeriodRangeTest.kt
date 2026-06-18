package com.yourname.expensetracker.domain.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone

/**
 * Tests for [PeriodRange] and [PeriodKind.toPeriodRange].
 *
 * Verifies the half-open `[startInclusive, endExclusive)` contract and
 * calendar-boundary correctness for various period kinds.
 */
class PeriodRangeTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `month period range covers correct start and end`() {
        // April 2026 in UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            set(2026, Calendar.APRIL, 15, 10, 0, 0)
        }
        val now = cal.timeInMillis

        val range = PeriodKind.THIS_MONTH.toPeriodRange(now, zoneId = utc)

        assertEquals(PeriodKind.THIS_MONTH, range.kind)
        // Start should be April 1, 2026 00:00:00 UTC
        val startCal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            timeInMillis = range.startInclusiveMillis
        }
        assertEquals(2026, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.APRIL, startCal.get(Calendar.MONTH))
        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
        assertEquals(0, startCal.get(Calendar.SECOND))
        assertEquals(0, startCal.get(Calendar.MILLISECOND))

        // End should be May 1, 2026 00:00:00 UTC (exclusive)
        val endCal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            timeInMillis = range.endExclusiveMillis
        }
        assertEquals(2026, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, endCal.get(Calendar.MONTH))
        assertEquals(1, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, endCal.get(Calendar.HOUR_OF_DAY))

        // April has 30 days
        val expectedDurationMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(expectedDurationMs, range.durationMillis)
    }

    @Test
    fun `week period range covers 7 days`() {
        // Monday, April 6, 2026 (a Monday) at noon UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            set(2026, Calendar.APRIL, 6, 12, 0, 0)
        }
        val now = cal.timeInMillis

        val range = PeriodKind.THIS_WEEK.toPeriodRange(now, zoneId = utc)

        assertEquals(PeriodKind.THIS_WEEK, range.kind)
        // Duration should be exactly 7 days = 604800000 ms
        assertEquals(7L * 24 * 60 * 60 * 1000, range.durationMillis)

        // Boundary: startInclusive <= now < endExclusive
        assertTrue(range.contains(now))
        assertTrue(range.contains(range.startInclusiveMillis))
        assertFalse(range.contains(range.endExclusiveMillis))
    }

    @Test
    fun `DST transition does not break day count`() {
        // Use a timezone that observes DST: Europe/Athens
        val athens = ZoneId.of("Europe/Athens")
        val tz = TimeZone.getTimeZone(athens)

        // March 29, 2026: DST "spring forward" occurs in Europe/Athens
        // Clocks jump from 03:00 to 04:00 on the last Sunday of March
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.MARCH, 29, 12, 0, 0)
        }
        val now = cal.timeInMillis

        // Create a day range around the DST transition
        val dayRange = PeriodKind.TODAY.toPeriodRange(now, zoneId = athens)

        // The day should still have meaningful boundaries
        assertTrue(dayRange.contains(now))
        assertTrue(dayRange.endExclusiveMillis > dayRange.startInclusiveMillis)

        // Even with DST, the wall-clock duration should be close to 24h
        // (it might be 23h or 25h depending on the transition direction,
        // but must still be in a valid range)
        val dayMs = dayRange.endExclusiveMillis - dayRange.startInclusiveMillis
        assertTrue("Day duration ($dayMs) should be between 23h and 25h during DST",
            dayMs in 23L * 60 * 60 * 1000..25L * 60 * 60 * 1000)
    }

    @Test
    fun `leap year february has correct boundary`() {
        // February 2024 is a leap year
        val cal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            set(2024, Calendar.FEBRUARY, 15, 0, 0, 0)
        }
        val now = cal.timeInMillis

        val range = PeriodKind.THIS_MONTH.toPeriodRange(now, zoneId = utc)

        // Leap year February has 29 days
        val expectedDurationMs = 29L * 24 * 60 * 60 * 1000
        assertEquals(expectedDurationMs, range.durationMillis)

        // Start: Feb 1, 2024
        val startCal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            timeInMillis = range.startInclusiveMillis
        }
        assertEquals(2024, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, startCal.get(Calendar.MONTH))
        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))

        // End: March 1, 2024 (exclusive)
        val endCal = Calendar.getInstance(TimeZone.getTimeZone(utc)).apply {
            timeInMillis = range.endExclusiveMillis
        }
        assertEquals(2024, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, endCal.get(Calendar.MONTH))
        assertEquals(1, endCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `customPeriod_usesExplicitBounds`() {
        val now = 1234567890L
        val customStart = 1000L
        val customEnd = 2000L
        val range = PeriodKind.CUSTOM.toPeriodRange(
            now = now,
            customStart = customStart,
            customEnd = customEnd
        )
        assertEquals(customStart, range.startInclusiveMillis)
        assertEquals(customEnd, range.endExclusiveMillis)
        assertEquals(PeriodKind.CUSTOM, range.kind)
        assertEquals("Custom", range.label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `customPeriod_withoutBoundsThrows`() {
        PeriodKind.CUSTOM.toPeriodRange(now = 1234567890L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `customPeriod_endBeforeStartThrows`() {
        PeriodKind.CUSTOM.toPeriodRange(
            now = 1234567890L,
            customStart = 2000L,
            customEnd = 1000L
        )
    }

    @Test
    fun `last7Days_containsExactly7LocalDatesIncludingToday`() {
        val ld = LocalDate.of(2026, 6, 15)
        val now = ld.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L // 1 hour into day

        val range = PeriodKind.LAST_7_DAYS.toPeriodRange(now, zoneId = utc)

        val startDate = Instant.ofEpochMilli(range.startInclusiveMillis).atZone(utc).toLocalDate()
        val endDate = Instant.ofEpochMilli(range.endExclusiveMillis).atZone(utc).toLocalDate()

        assertEquals("start should be today - 6 days", ld.minusDays(6), startDate)
        assertEquals("end should be tomorrow", ld.plusDays(1), endDate)
        assertEquals("should span exactly 7 calendar days", 7L, ChronoUnit.DAYS.between(startDate, endDate))
    }

    @Test
    fun `last30Days_containsExactly30LocalDatesIncludingToday`() {
        val ld = LocalDate.of(2026, 6, 15)
        val now = ld.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L // 1 hour into day

        val range = PeriodKind.LAST_30_DAYS.toPeriodRange(now, zoneId = utc)

        val startDate = Instant.ofEpochMilli(range.startInclusiveMillis).atZone(utc).toLocalDate()
        val endDate = Instant.ofEpochMilli(range.endExclusiveMillis).atZone(utc).toLocalDate()

        assertEquals("start should be today - 29 days", ld.minusDays(29), startDate)
        assertEquals("end should be tomorrow", ld.plusDays(1), endDate)
        assertEquals("should span exactly 30 calendar days", 30L, ChronoUnit.DAYS.between(startDate, endDate))
    }
}
