package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * RP-08 (P6-004) series-builder contract tests.
 *
 * - Default [BudgetHistorySeriesBuilder.build] EXCLUDES incomplete edge
 *   months: a leading/trailing bucket only partially covered by the window
 *   is dropped (window trimmed to local month boundaries); no pro-rating.
 * - `completeMonthCount` is pinned alongside observed/filled counters.
 * - Passing `excludeIncompleteEdgeMonths = false` preserves the legacy
 *   edge-inclusive behavior for any caller that needs it explicitly.
 */
class BudgetHistorySeriesBuilderTest {

    @Test
    fun `default excludes incomplete trailing month when end is mid-month`() {
        // Window [Jan 1, Apr 10): April is only covered through the 10th → excluded.
        val windowStart = ms("2026-01-01", hour = 0)
        val windowEndExclusive = ms("2026-04-10", hour = 10)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 100.0, 1),
                MonthlySpendingTotal("2026-02", 200.0, 1),
                MonthlySpendingTotal("2026-03", 300.0, 1),
                MonthlySpendingTotal("2026-04", 999.0, 5)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertEquals(listOf("2026-01", "2026-02", "2026-03"), series.monthKeys)
        assertApproxEquals(100.0, series.values[0], 0.0001)
        assertApproxEquals(200.0, series.values[1], 0.0001)
        assertApproxEquals(300.0, series.values[2], 0.0001)
        assertEquals(3, series.observedMonthCount)
        assertEquals(3, series.filledMonthCount)
        assertEquals(3, series.completeMonthCount)
    }

    @Test
    fun `default excludes incomplete leading month when start is mid-month`() {
        // Window [Jan 15, Apr 1): January is only covered from the 15th → excluded.
        val windowStart = ms("2026-01-15", hour = 0)
        val windowEndExclusive = ms("2026-04-01", hour = 0)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 999.0, 5),
                MonthlySpendingTotal("2026-02", 200.0, 1),
                MonthlySpendingTotal("2026-03", 300.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertEquals(listOf("2026-02", "2026-03"), series.monthKeys)
        assertEquals(2, series.observedMonthCount)
        assertEquals(2, series.filledMonthCount)
        assertEquals(2, series.completeMonthCount)
    }

    @Test
    fun `boundary aligned window keeps both edge months`() {
        // Window [Jan 1, Apr 1): both edges land exactly on month boundaries.
        val windowStart = ms("2026-01-01", hour = 0)
        val windowEndExclusive = ms("2026-04-01", hour = 0)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 100.0, 1),
                MonthlySpendingTotal("2026-02", 100.0, 1),
                MonthlySpendingTotal("2026-03", 100.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertEquals(listOf("2026-01", "2026-02", "2026-03"), series.monthKeys)
        assertEquals(3, series.observedMonthCount)
        assertEquals(3, series.filledMonthCount)
        assertEquals(3, series.completeMonthCount)
    }

    @Test
    fun `excludeIncompleteEdgeMonths false preserves legacy edge-inclusive behavior`() {
        // Same mid-month end as the first test, but with edge exclusion off.
        val windowStart = ms("2026-01-01", hour = 0)
        val windowEndExclusive = ms("2026-04-10", hour = 10)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 100.0, 1),
                MonthlySpendingTotal("2026-04", 300.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive,
            excludeIncompleteEdgeMonths = false
        )

        assertEquals(listOf("2026-01", "2026-02", "2026-03", "2026-04"), series.monthKeys)
        assertApproxEquals(300.0, series.values[3], 0.0001)
        assertEquals(2, series.observedMonthCount)
        assertEquals(4, series.filledMonthCount)
        // completeMonthCount counts FULL months inside the ORIGINAL window even
        // in legacy mode: Jan/Feb/Mar are fully covered by [Jan 1, Apr 10),
        // April is not (its month end is May 1).
        assertEquals(3, series.completeMonthCount)
    }

    @Test
    fun `gaps between complete months are zero filled and counted`() {
        // Window [Jan 1, Apr 1): Feb missing → zero-filled; all 3 months complete.
        val windowStart = ms("2026-01-01", hour = 0)
        val windowEndExclusive = ms("2026-04-01", hour = 0)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 100.0, 1),
                MonthlySpendingTotal("2026-03", 300.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertEquals(listOf("2026-01", "2026-02", "2026-03"), series.monthKeys)
        assertApproxEquals(0.0, series.values[1], 0.0001)
        assertEquals(2, series.observedMonthCount)
        assertEquals(3, series.filledMonthCount)
        assertEquals(3, series.completeMonthCount)
    }

    @Test
    fun `window shorter than one full month yields empty series`() {
        // [Apr 5, Apr 20): no complete month inside the window at all.
        val windowStart = ms("2026-04-05", hour = 0)
        val windowEndExclusive = ms("2026-04-20", hour = 0)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(MonthlySpendingTotal("2026-04", 500.0, 3)),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertTrue(series.monthKeys.isEmpty())
        assertTrue(series.values.isEmpty())
        assertEquals(0, series.observedMonthCount)
        assertEquals(0, series.filledMonthCount)
        assertEquals(0, series.completeMonthCount)
    }

    @Test
    fun `build returns empty when no totals fall inside window month range`() {
        val windowStart = ms("2026-01-01")
        val windowEndExclusive = ms("2026-04-01")

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(MonthlySpendingTotal("2025-12", 50.0, 1)),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertTrue(series.monthKeys.isEmpty())
        assertTrue(series.values.isEmpty())
        assertEquals(0, series.observedMonthCount)
        assertEquals(0, series.filledMonthCount)
        assertEquals(0, series.completeMonthCount)
    }

    private fun ms(date: String, hour: Int = 0): Long =
        LocalDate.parse(date)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
