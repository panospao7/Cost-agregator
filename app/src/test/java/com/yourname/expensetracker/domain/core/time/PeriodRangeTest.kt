package com.yourname.expensetracker.domain.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
}
