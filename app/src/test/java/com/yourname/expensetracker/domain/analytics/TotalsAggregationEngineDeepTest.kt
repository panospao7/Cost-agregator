package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.MonthlyTotal
import com.yourname.expensetracker.data.database.dao.WeeklyTotal
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TotalsAggregationEngineDeepTest {

    private lateinit var engine: TotalsAggregationEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, mockk(relaxed = true), mockk(relaxed = true), Dispatchers.Unconfined)
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
    }

    @Test
    fun `monthly weekly daily yearly totals map sums from repository`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns listOf(
            MonthlyTotal("2026-01", dateMs(2026, 1, 1), dateMs(2026, 2, 1), 100.0, 2),
            MonthlyTotal("2026-02", dateMs(2026, 2, 1), dateMs(2026, 3, 1), 200.0, 4)
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns listOf(
            WeeklyTotal("2026-W05", dateMs(2026, 1, 26), dateMs(2026, 2, 2), 70.0, 2),
            WeeklyTotal("2026-W06", dateMs(2026, 2, 2), dateMs(2026, 2, 9), 130.0, 3)
        )
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns listOf(
            DailyTotal(1L, dateMs(2026, 2, 2), dateMs(2026, 2, 3), 20.0, 1),
            DailyTotal(2L, dateMs(2026, 2, 3), dateMs(2026, 2, 4), 30.0, 1)
        )
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 25.0
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returnsMany listOf(0.0, 0.0, 1000.0, 1200.0, 1400.0, 1600.0, 1800.0)
        coEvery { expenseRepository.getTransactionCountForPeriod(any(), any()) } returns 1

        val monthly = engine.getMonthlyTotals(2026)
        val weekly = engine.getWeeklyTotals(2026, 2)
        val daily = engine.getDailyTotalsForRange(dateMs(2026, 2, 2), dateMs(2026, 2, 9))
        val yearly = engine.getYearlyTotals()

        assertApproxEquals(300.0, monthly.first().sumOf { it.totalAmount })
        assertApproxEquals(200.0, weekly.first().sumOf { it.totalAmount })
        assertApproxEquals(50.0, daily.first().sumOf { it.totalAmount })
        assertTrue(yearly.first().isNotEmpty())
    }

    @Test
    fun `category breakdown calculates percentage as category over grand total`() = runTest {
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns listOf(
            CategoryTotalResult(1L, "Food", "🍽️", "#f00", 300.0, 3),
            CategoryTotalResult(2L, "Transport", "🚌", "#0f0", 100.0, 1)
        )

        val result = engine.getCategoryBreakdown(dateMs(2026, 4, 1), dateMs(2026, 5, 1), "Apr")

        assertApproxEquals(75.0, result.first().first { it.category.id == 1L }.percentageOfTotal, 0.01)
        assertApproxEquals(25.0, result.first().first { it.category.id == 2L }.percentageOfTotal, 0.01)
        assertApproxEquals(100.0, result.first().sumOf { it.percentageOfTotal }, 0.01)
    }

    @Test
    fun `average monthly weekly and daily formulas are correct`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)

        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returnsMany listOf(
            listOf(
                MonthlyTotal("2025-12", dateMs(2025, 12, 1), dateMs(2026, 1, 1), 100.0, 1),
                MonthlyTotal("2026-01", dateMs(2026, 1, 1), dateMs(2026, 2, 1), 300.0, 2)
            ),
            listOf(
                MonthlyTotal("2026-01", dateMs(2026, 1, 1), dateMs(2026, 2, 1), 100.0, 1),
                MonthlyTotal("2026-02", dateMs(2026, 2, 1), dateMs(2026, 3, 1), 200.0, 1),
                MonthlyTotal("2026-03", dateMs(2026, 3, 1), dateMs(2026, 4, 1), 300.0, 1)
            )
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns listOf(
            WeeklyTotal("w1", dateMs(2026, 3, 2), dateMs(2026, 3, 9), 70.0, 1),
            WeeklyTotal("w2", dateMs(2026, 3, 9), dateMs(2026, 3, 16), 140.0, 2)
        )
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 11.5
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 200.0

        val yearAvg = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)
        val monthAvg = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)
        val weekAvg = engine.getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)
        val dayAvg = engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

        assertApproxEquals(200.0, yearAvg)
        assertApproxEquals(200.0, monthAvg)
        assertApproxEquals(105.0, weekAvg)
        assertApproxEquals(11.5, dayAvg)
    }

    @Test
    fun `status determination matches under over and no data`() {
        assertEquals(PeriodStatus.UNDER_AVERAGE, engine.getPeriodStatus(50.0, 100.0))
        assertEquals(PeriodStatus.OVER_AVERAGE, engine.getPeriodStatus(100.0, 100.0))
        assertEquals(PeriodStatus.OVER_AVERAGE, engine.getPeriodStatus(120.0, 100.0))
        assertEquals(PeriodStatus.NO_DATA, engine.getPeriodStatus(50.0, 0.0))
    }

    @Test
    fun `empty and boundary conditions do not crash and keep deterministic outputs`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        assertTrue(engine.getMonthlyTotals(2026).first().all { it.totalAmount == 0.0 && it.transactionCount == 0 })
        assertTrue(engine.getWeeklyTotals(2026, 1).first().all { it.totalAmount == 0.0 && it.transactionCount == 0 })
        assertTrue(engine.getDailyTotals(2026, 1).first().all { it.totalAmount == 0.0 && it.transactionCount == 0 })
        assertTrue(engine.getCategoryBreakdown(dateMs(2026, 4, 1), dateMs(2026, 5, 1), "Apr").first().isEmpty())
        assertApproxEquals(0.0, engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false))
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
