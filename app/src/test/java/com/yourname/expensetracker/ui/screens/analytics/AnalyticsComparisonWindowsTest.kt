package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.domain.analytics.TimePeriod
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * V-E-12: like-for-like (period-to-date) comparison windows for Analytics.
 * Pure-function tests; the system default zone is pinned and restored.
 */
class AnalyticsComparisonWindowsTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun pinZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun useZone(id: String): ZoneId {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        return ZoneId.of(id)
    }

    private fun millis(zone: ZoneId, dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun currentRange(period: TimePeriod, now: Long): Pair<Long, Long> = when (period) {
        TimePeriod.TODAY -> TimePeriodUtils.getStartOfDay(now) to TimePeriodUtils.getEndOfDay(now)
        TimePeriod.WEEK -> TimePeriodUtils.getWeekRange(now, 0)
        TimePeriod.MONTH -> TimePeriodUtils.getMonthRange(now)
        TimePeriod.QUARTER -> TimePeriodUtils.getQuarterRange(now)
        TimePeriod.YEAR -> TimePeriodUtils.getYearRange(now)
        TimePeriod.ALL -> 0L to now
    }

    /** One 30.0 purchase every local day at [hour]:00, from [from] through [through]. */
    private fun steadyDailyExpenses(zone: ZoneId, from: LocalDate, through: LocalDate, hour: Int): List<Pair<Long, Double>> {
        val result = mutableListOf<Pair<Long, Double>>()
        var day = from
        while (!day.isAfter(through)) {
            result += millis(zone, day.atTime(hour, 0)) to 30.0
            day = day.plusDays(1)
        }
        return result
    }

    private fun sumIn(expenses: List<Pair<Long, Double>>, startInclusive: Long, endExclusive: Long): Double =
        expenses.filter { it.first >= startInclusive && it.first < endExclusive }.sumOf { it.second }

    private fun assertSteadySpendIsZeroPercent(zoneId: String, nowLocal: LocalDateTime) {
        val zone = useZone(zoneId)
        val now = millis(zone, nowLocal)
        val expenses = steadyDailyExpenses(
            zone,
            from = LocalDate.of(2024, 1, 1),
            through = nowLocal.toLocalDate(),
            hour = nowLocal.hour
        )
        for (period in listOf(TimePeriod.TODAY, TimePeriod.WEEK, TimePeriod.MONTH, TimePeriod.QUARTER, TimePeriod.YEAR)) {
            val (currentStart, currentEnd) = currentRange(period, now)
            val prior = AnalyticsComparisonWindows.priorWindow(period, currentStart, now)
            val current = sumIn(expenses, currentStart, currentEnd)
            val previous = sumIn(expenses, prior.startInclusive, prior.endExclusive)
            assertTrue("$period $zoneId: prior window must not be empty", !prior.isEmpty)
            assertEquals("$period $zoneId: steady spend must compare equal", current, previous, 0.0001)
            val changePercent = (current - previous) / previous * 100
            assertEquals("$period $zoneId: change percent", 0.0, changePercent, 0.0001)
        }
    }

    @Test
    fun `steady daily spend is 0 percent for every period in New York`() {
        assertSteadySpendIsZeroPercent("America/New_York", LocalDateTime.of(2026, 5, 20, 12, 0))
    }

    @Test
    fun `steady daily spend is 0 percent for every period in Athens`() {
        assertSteadySpendIsZeroPercent("Europe/Athens", LocalDateTime.of(2026, 5, 20, 12, 0))
    }

    @Test
    fun `steady daily spend is 0 percent across a DST transition in the current quarter`() {
        // Spring-forward (Mar 29 in Athens, Mar 8 in New York) falls inside the prior/current spans.
        assertSteadySpendIsZeroPercent("Europe/Athens", LocalDateTime.of(2026, 4, 10, 12, 0))
        assertSteadySpendIsZeroPercent("America/New_York", LocalDateTime.of(2026, 4, 10, 12, 0))
    }

    @Test
    fun `march 31 compares against the whole of february not a 31 day block`() {
        val zone = useZone("America/New_York")
        val now = millis(zone, LocalDateTime.of(2026, 3, 31, 15, 0))
        val (currentStart, _) = currentRange(TimePeriod.MONTH, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.MONTH, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2026, 2, 1, 0, 0)), prior.startInclusive)
        // Cut at the end of February (Mar 1 00:00 exclusive), never spilling into March.
        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 1, 0, 0)), prior.endExclusive)
    }

    @Test
    fun `march 31 in a leap year includes feb 29`() {
        val zone = useZone("Europe/Athens")
        val now = millis(zone, LocalDateTime.of(2028, 3, 31, 9, 0))
        val (currentStart, _) = currentRange(TimePeriod.MONTH, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.MONTH, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2028, 2, 1, 0, 0)), prior.startInclusive)
        assertEquals(millis(zone, LocalDateTime.of(2028, 3, 1, 0, 0)), prior.endExclusive)
        val feb29Noon = millis(zone, LocalDateTime.of(2028, 2, 29, 12, 0))
        assertTrue(feb29Noon >= prior.startInclusive && feb29Noon < prior.endExclusive)
    }

    @Test
    fun `mid month cut maps day and time into the previous calendar month`() {
        val zone = useZone("America/New_York")
        val now = millis(zone, LocalDateTime.of(2026, 5, 10, 14, 30))
        val (currentStart, _) = currentRange(TimePeriod.MONTH, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.MONTH, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2026, 4, 1, 0, 0)), prior.startInclusive)
        // Apr 10 14:30 inclusive -> exclusive bound 1 ms later.
        assertEquals(millis(zone, LocalDateTime.of(2026, 4, 10, 14, 30)) + 1L, prior.endExclusive)
    }

    @Test
    fun `week containing spring forward maps elapsed days by calendar not by 24h blocks`() {
        val zone = useZone("America/New_York")
        // Sunday Mar 8 2026 15:00 EDT (DST began at 02:00 that day); current week is Mar 2 - Mar 9.
        val now = millis(zone, LocalDateTime.of(2026, 3, 8, 15, 0))
        val (currentStart, _) = currentRange(TimePeriod.WEEK, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.WEEK, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2026, 2, 23, 0, 0)), prior.startInclusive)
        // 6 calendar days into the week -> Sun Mar 1 15:00 EST (not shifted by the lost hour).
        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 1, 15, 0)) + 1L, prior.endExclusive)
    }

    @Test
    fun `week following a spring forward week compares against the 167 hour prior week`() {
        val zone = useZone("America/New_York")
        // Monday Mar 9 2026 08:00; prior week Mar 2 - Mar 9 contains the DST change.
        val now = millis(zone, LocalDateTime.of(2026, 3, 9, 8, 0))
        val (currentStart, _) = currentRange(TimePeriod.WEEK, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.WEEK, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 2, 0, 0)), prior.startInclusive)
        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 2, 8, 0)) + 1L, prior.endExclusive)
    }

    @Test
    fun `today compares against yesterday cut at the same time even when yesterday has a DST gap`() {
        val zone = useZone("America/New_York")
        // Monday Mar 9 02:30 EDT; yesterday (Mar 8) has no 02:30 local time.
        val now = millis(zone, LocalDateTime.of(2026, 3, 9, 2, 30))
        val (currentStart, _) = currentRange(TimePeriod.TODAY, now)

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.TODAY, currentStart, now)

        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 8, 0, 0)), prior.startInclusive)
        // 02:30 in the gap resolves forward to 03:30 EDT.
        assertEquals(millis(zone, LocalDateTime.of(2026, 3, 8, 3, 30)) + 1L, prior.endExclusive)
    }

    @Test
    fun `prior window never exceeds the previous calendar period`() {
        val zone = useZone("Europe/Athens")
        val now = millis(zone, LocalDateTime.of(2026, 12, 31, 23, 59))
        for (period in listOf(TimePeriod.TODAY, TimePeriod.WEEK, TimePeriod.MONTH, TimePeriod.QUARTER, TimePeriod.YEAR)) {
            val (currentStart, _) = currentRange(period, now)
            val prior = AnalyticsComparisonWindows.priorWindow(period, currentStart, now)
            val (fullStart, fullEnd) = AnalyticsComparisonWindows.previousCalendarPeriod(period, now)
            assertEquals("$period start", fullStart, prior.startInclusive)
            assertTrue("$period end within previous period", prior.endExclusive <= fullEnd)
            assertTrue("$period end after start", prior.endExclusive > prior.startInclusive)
        }
    }

    @Test
    fun `ALL has no comparison baseline`() {
        useZone("America/New_York")
        val now = 1_780_000_000_000L

        val prior = AnalyticsComparisonWindows.priorWindow(TimePeriod.ALL, 0L, now)

        assertTrue(prior.isEmpty)
    }

    @Test
    fun `year over year counts only prior year dates before the same date one year ago`() {
        val zone = useZone("America/New_York")
        val now = millis(zone, LocalDateTime.of(2026, 5, 20, 12, 0))

        val cutoff = AnalyticsComparisonWindows.priorYearCutoff(now)

        assertEquals(millis(zone, LocalDateTime.of(2025, 5, 20, 12, 0)), cutoff)
        val priorYearDates = listOf(
            LocalDateTime.of(2025, 1, 15, 9, 0),
            LocalDateTime.of(2025, 5, 19, 23, 59),
            LocalDateTime.of(2025, 5, 20, 12, 0),
            LocalDateTime.of(2025, 5, 21, 9, 0),
            LocalDateTime.of(2025, 12, 31, 20, 0)
        ).map { millis(zone, it) }
        val counted = priorYearDates.filter { it < cutoff }
        assertEquals(priorYearDates.take(2), counted)
    }

    @Test
    fun `year over year cutoff on feb 29 resolves to feb 28 of the prior year`() {
        val zone = useZone("Europe/Athens")
        val now = millis(zone, LocalDateTime.of(2028, 2, 29, 10, 0))

        val cutoff = AnalyticsComparisonWindows.priorYearCutoff(now)

        assertEquals(millis(zone, LocalDateTime.of(2027, 2, 28, 10, 0)), cutoff)
    }

    @Test
    fun `year over year membership counts only current year and prior year dates before the cutoff`() {
        val zone = useZone("America/New_York")
        val now = millis(zone, LocalDateTime.of(2026, 5, 20, 12, 0))
        val cutoff = AnalyticsComparisonWindows.priorYearCutoff(now)
        fun inYoY(dt: LocalDateTime, year: Int) =
            AnalyticsComparisonWindows.countsInYearOverYear(millis(zone, dt), year, 2025, cutoff)

        assertTrue(inYoY(LocalDateTime.of(2025, 5, 19, 23, 59), 2025))
        assertTrue(!inYoY(LocalDateTime.of(2025, 5, 20, 12, 0), 2025))
        assertTrue(!inYoY(LocalDateTime.of(2025, 12, 31, 20, 0), 2025))
        assertTrue(inYoY(LocalDateTime.of(2026, 5, 20, 8, 0), 2026))
        assertTrue(!inYoY(LocalDateTime.of(2026, 5, 20, 8, 0), 2025))
        assertTrue(!inYoY(LocalDateTime.of(2024, 6, 1, 8, 0), 2025))
    }

    @Test
    fun `comparison minute truncates to the minute and changes the cache key and cut when the minute changes`() {
        val zone = useZone("America/New_York")
        val base = millis(zone, LocalDateTime.of(2026, 5, 20, 12, 30))
        val sameMinuteLater = base + 45_000L
        val nextMinute = base + 60_000L

        assertEquals(base, AnalyticsComparisonWindows.comparisonMinute(base + 59_999L))
        assertEquals(
            AnalyticsComparisonWindows.comparisonMinute(base),
            AnalyticsComparisonWindows.comparisonMinute(sameMinuteLater)
        )
        assertEquals(base + 60_000L, AnalyticsComparisonWindows.comparisonMinute(nextMinute))

        val (currentStart, _) = currentRange(TimePeriod.MONTH, base)
        val cutA = AnalyticsComparisonWindows.priorWindow(
            TimePeriod.MONTH, currentStart, AnalyticsComparisonWindows.comparisonMinute(base)
        )
        val cutSame = AnalyticsComparisonWindows.priorWindow(
            TimePeriod.MONTH, currentStart, AnalyticsComparisonWindows.comparisonMinute(sameMinuteLater)
        )
        val cutNext = AnalyticsComparisonWindows.priorWindow(
            TimePeriod.MONTH, currentStart, AnalyticsComparisonWindows.comparisonMinute(nextMinute)
        )
        assertEquals(cutA, cutSame)
        assertEquals(cutA.endExclusive + 60_000L, cutNext.endExclusive)
        assertEquals(
            AnalyticsComparisonWindows.priorYearCutoff(AnalyticsComparisonWindows.comparisonMinute(base)) + 60_000L,
            AnalyticsComparisonWindows.priorYearCutoff(AnalyticsComparisonWindows.comparisonMinute(nextMinute))
        )
    }

    @Test
    fun `current rows after now are excluded from the percentage comparison`() {
        val zone = useZone("Europe/Athens")
        val now = AnalyticsComparisonWindows.comparisonMinute(millis(zone, LocalDateTime.of(2026, 5, 10, 14, 30)))
        val rows = listOf(
            millis(zone, LocalDateTime.of(2026, 5, 3, 10, 0)) to 100.0,
            millis(zone, LocalDateTime.of(2026, 5, 10, 14, 30)) to 50.0,
            millis(zone, LocalDateTime.of(2026, 5, 10, 18, 0)) to 500.0, // future-dated
            millis(zone, LocalDateTime.of(2026, 5, 25, 9, 0)) to 500.0   // future-dated
        )
        val comparable = rows.filter { AnalyticsComparisonWindows.countsInComparison(it.first, now) }
        val comparableTotal = comparable.sumOf { it.second }

        assertEquals(2, comparable.size)
        assertEquals(150.0, comparableTotal, 0.0001)
        // Full-period total (displayed) is 1150; the % uses only the comparable 150 vs prior 100.
        assertEquals(50f, AnalyticsComparisonWindows.changePercent(TimePeriod.MONTH, comparableTotal, 100.0)!!, 0.001f)
    }

    @Test
    fun `ALL has no percentage and a null previous total`() {
        assertNull(AnalyticsComparisonWindows.changePercent(TimePeriod.ALL, 100.0, 50.0))
        assertNull(AnalyticsComparisonWindows.previousTotalOrNull(TimePeriod.ALL, 0.0))
        assertNull(AnalyticsComparisonWindows.previousTotalOrNull(TimePeriod.ALL, 75.0))
        assertEquals(75.0, AnalyticsComparisonWindows.previousTotalOrNull(TimePeriod.MONTH, 75.0)!!, 0.0)
        assertNull(AnalyticsComparisonWindows.changePercent(TimePeriod.MONTH, 100.0, 0.0))
        assertNull(AnalyticsComparisonWindows.changePercent(TimePeriod.MONTH, null, 50.0))
    }
}
