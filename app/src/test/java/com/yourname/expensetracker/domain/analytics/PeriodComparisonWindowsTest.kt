package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** F-R20b-a: previous calendar month comparison windows (pure; zone pinned under the global lock). */
class PeriodComparisonWindowsTest {

    private fun <T> inZone(id: String, block: (ZoneId) -> T): T = GlobalTimeZoneTestLock.withLock {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block(ZoneId.of(id))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun ms(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, sec: Int = 0, nano: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min, sec, nano).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `march 31 compares against the whole of february not a 31 day block`() = inZone("America/New_York") { zone ->
        // Home passes the full month [Mar 1, Apr 1).
        val window = PeriodComparisonWindows.previousWindowForRange(ms(zone, 2026, 3, 1), ms(zone, 2026, 4, 1))

        assertEquals(ms(zone, 2026, 2, 1), window.startInclusive)
        // Cut is clamped to the end of February (Mar 1 00:00 exclusive); never reaches into March.
        assertEquals(ms(zone, 2026, 3, 1), window.endExclusive)
    }

    @Test
    fun `march 31 in a leap year clamps to the 29 day february`() = inZone("Europe/Athens") { zone ->
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2028, 3, 1),
            cut = ms(zone, 2028, 3, 31, 9, 0),
            cutInclusive = true
        )

        assertEquals(ms(zone, 2028, 2, 1), window.startInclusive)
        assertEquals(ms(zone, 2028, 3, 1), window.endExclusive)
        assertTrue(ms(zone, 2028, 2, 29, 12, 0) < window.endExclusive)
    }

    @Test
    fun `march 15 noon maps to february 15 noon inclusive`() = inZone("America/New_York") { zone ->
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2026, 3, 1),
            cut = ms(zone, 2026, 3, 15, 12, 0),
            cutInclusive = true
        )

        assertEquals(ms(zone, 2026, 2, 1), window.startInclusive)
        assertEquals(ms(zone, 2026, 2, 15, 12, 0) + 1L, window.endExclusive)
    }

    @Test
    fun `february full month window is cut at jan 29 so 28 days compare with 28 days`() = inZone("Europe/Athens") { zone ->
        val window = PeriodComparisonWindows.previousWindowForRange(ms(zone, 2026, 2, 1), ms(zone, 2026, 3, 1))

        assertEquals(ms(zone, 2026, 1, 1), window.startInclusive)
        // Feb 28 (last day) maps to Jan 28; exclusive end is Jan 29 00:00, not the 31 day January.
        assertEquals(ms(zone, 2026, 1, 29), window.endExclusive)
    }

    @Test
    fun `feb 28 end of day inclusive cut maps to jan 28 end of day`() = inZone("Europe/Athens") { zone ->
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2026, 2, 1),
            cut = ms(zone, 2026, 2, 28, 23, 59, 59, 999_000_000),
            cutInclusive = true
        )

        assertEquals(ms(zone, 2026, 1, 1), window.startInclusive)
        assertEquals(ms(zone, 2026, 1, 29), window.endExclusive)
    }

    @Test
    fun `dst gap in the prior month resolves forward and elapsed days stay calendar based`() = inZone("America/New_York") { zone ->
        // April 8 02:30 exists; March 8 02:30 does not (spring forward) and resolves to 03:30 EDT.
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2026, 4, 1),
            cut = ms(zone, 2026, 4, 8, 2, 30),
            cutInclusive = true
        )

        assertEquals(ms(zone, 2026, 3, 1), window.startInclusive)
        assertEquals(ms(zone, 2026, 3, 8, 3, 30) + 1L, window.endExclusive)
    }

    @Test
    fun `month containing the dst change still maps by calendar day`() = inZone("America/New_York") { zone ->
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2026, 3, 1),
            cut = ms(zone, 2026, 3, 9, 8, 0),
            cutInclusive = true
        )

        assertEquals(ms(zone, 2026, 2, 1), window.startInclusive)
        assertEquals(ms(zone, 2026, 2, 9, 8, 0) + 1L, window.endExclusive)
    }

    @Test
    fun `a row in the current minute is counted by the inclusive cut`() = inZone("Europe/Athens") { zone ->
        val rowInPriorMinute = ms(zone, 2026, 4, 10, 14, 30, 45)
        val window = PeriodComparisonWindows.previousMonthWindow(
            currentMonthStart = ms(zone, 2026, 5, 1),
            // End of the comparison minute 14:30:59.999 on May 10.
            cut = ms(zone, 2026, 5, 10, 14, 30, 59, 999_000_000),
            cutInclusive = true
        )

        assertTrue(rowInPriorMinute >= window.startInclusive && rowInPriorMinute < window.endExclusive)
        assertEquals(ms(zone, 2026, 4, 10, 14, 31), window.endExclusive)
    }

    @Test
    fun `non month ranges keep the legacy same length window`() = inZone("America/New_York") { zone ->
        val start = ms(zone, 2026, 3, 5)
        val end = ms(zone, 2026, 3, 15)

        val window = PeriodComparisonWindows.previousWindowForRange(start, end)

        assertEquals(ms(zone, 2026, 2, 23), window.startInclusive)
        assertEquals(start, window.endExclusive)
    }

    @Test
    fun `a range that only starts on a month boundary is not treated as a calendar month`() = inZone("America/New_York") { zone ->
        val start = ms(zone, 2026, 3, 1)
        val end = ms(zone, 2026, 3, 31)

        val window = PeriodComparisonWindows.previousWindowForRange(start, end)

        assertEquals(ms(zone, 2026, 1, 30), window.startInclusive)
        assertEquals(start, window.endExclusive)
    }
}
