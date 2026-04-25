package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetHistorySeriesBuilderTest {

    @Test
    fun `build uses half-open end instant and includes current month key when end is month start daytime`() {
        val windowStart = ms("2026-01-01", hour = 0)
        val windowEndExclusive = ms("2026-04-01", hour = 10)

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = listOf(
                MonthlySpendingTotal("2026-01", 100.0, 1),
                MonthlySpendingTotal("2026-04", 300.0, 1)
            ),
            windowStartInclusive = windowStart,
            windowEndExclusive = windowEndExclusive
        )

        assertEquals(listOf("2026-01", "2026-02", "2026-03", "2026-04"), series.monthKeys)
        assertApproxEquals(100.0, series.values[0], 0.0001)
        assertApproxEquals(0.0, series.values[1], 0.0001)
        assertApproxEquals(0.0, series.values[2], 0.0001)
        assertApproxEquals(300.0, series.values[3], 0.0001)
        assertEquals(2, series.observedMonthCount)
        assertEquals(4, series.filledMonthCount)
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
    }

    private fun ms(date: String, hour: Int = 0): Long =
        LocalDate.parse(date)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
