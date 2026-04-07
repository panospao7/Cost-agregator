package com.yourname.expensetracker.domain.analytics

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class TotalsAggregationEngineTest {
    private lateinit var engine: TotalsAggregationEngine
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, Dispatchers.Unconfined)
        every { timeProvider.now() } returns System.currentTimeMillis()
    }

    @Test
    fun `getMonthlyTotals returns empty list when no expenses`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getMonthlyTotals(2026)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getMonthlyTotals calculates correct totals from repository`() = runTest {
        val januaryStart = getStartOfMonth(2026, Calendar.JANUARY)
        val januaryEnd = getEndOfMonth(2026, Calendar.JANUARY)
        val monthlyTotals = listOf(
            MonthlyTotal(
                monthKey = "2026-01",
                startDate = januaryStart,
                endDate = januaryEnd,
                total = 150.0,
                txCount = 2
            )
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getMonthlyTotals(2026)

        assertEquals(1, result.size)
        val january = result[0]
        assertEquals("2026-01", january.periodKey)
        assertEquals(150.0, january.totalAmount, 0.01)
        assertEquals(2, january.transactionCount)
        assertEquals(PeriodType.MONTH, january.periodType)
    }

    @Test
    fun `getWeeklyTotals returns empty list when no expenses`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getWeeklyTotals(2026, 1)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWeeklyTotals groups by week correctly`() = runTest {
        val weekStart = getStartOfWeek(2026, 3)
        val weekEnd = getEndOfWeek(2026, 3)
        val weeklyTotals = listOf(
            WeeklyTotal(
                weekKey = "2026-W3",
                startDate = weekStart,
                endDate = weekEnd,
                total = 300.0,
                txCount = 5
            )
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns weeklyTotals
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getWeeklyTotals(2026, 1)

        assertEquals(1, result.size)
        val week1 = result[0]
        assertEquals("W1", week1.periodLabel)
        assertEquals("2026-W3", week1.periodKey)
        assertEquals(300.0, week1.totalAmount, 0.01)
        assertEquals(5, week1.transactionCount)
        assertEquals(PeriodType.WEEK, week1.periodType)
    }

    @Test
    fun `getDailyTotals returns empty list when no expenses`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getDailyTotals(2026, 3)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDailyTotals groups by day correctly`() = runTest {
        val dayStart = getStartOfDay(2026, 1, 12)
        val dayEnd = getEndOfDay(2026, 1, 12)
        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = 20260112L,
                startDate = dayStart,
                endDate = dayEnd,
                total = 50.0,
                txCount = 3
            )
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 40.0

        val result = engine.getDailyTotals(2026, 3)

        assertEquals(1, result.size)
        val day1 = result[0]
        assertEquals(PeriodType.DAY, day1.periodType)
        assertEquals(50.0, day1.totalAmount, 0.01)
        assertEquals(3, day1.transactionCount)
    }

    @Test
    fun `getCategoryBreakdown calculates percentages correctly`() = runTest {
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Groceries", 250.0, 10),
            createCategoryTotalResult(2L, "Entertainment", 150.0, 5),
            createCategoryTotalResult(3L, "Transport", 100.0, 3)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertEquals(3, result.size)
        assertEquals(250.0, result[0].totalAmount, 0.01)
        assertEquals(50.0f, result[0].percentageOfTotal, 0.01f)
        assertEquals(150.0, result[1].totalAmount, 0.01)
        assertEquals(30.0f, result[1].percentageOfTotal, 0.01f)
        assertEquals(100.0, result[2].totalAmount, 0.01)
        assertEquals(20.0f, result[2].percentageOfTotal, 0.01f)
        assertEquals("Jan", result[0].periodLabel)
    }

    @Test
    fun `getCategoryBreakdown handles empty results`() = runTest {
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns emptyList()

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCategoryBreakdown sorts by totalAmount descending`() = runTest {
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Small", 50.0, 2),
            createCategoryTotalResult(2L, "Large", 500.0, 10),
            createCategoryTotalResult(3L, "Medium", 150.0, 5)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertEquals("Large", result[0].category.name)
        assertEquals("Medium", result[1].category.name)
        assertEquals("Small", result[2].category.name)
    }

    @Test
    fun `getCategoryBreakdown handles null category fields`() = runTest {
        val categoryResults = listOf(
            com.yourname.expensetracker.data.database.dao.CategoryTotalResult(
                id = 1L,
                name = null,
                icon = null,
                color = null,
                total = 100.0,
                txCount = 5
            )
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertEquals(1, result.size)
        assertEquals("Unknown", result[0].category.name)
        assertEquals("?", result[0].category.icon)
        assertEquals("#808080", result[0].category.color)
    }

    @Test
    fun `getPeriodStatus returns UNDER_AVERAGE when below average`() {
        val status = engine.getPeriodStatus(50.0, 100.0)
        assertEquals(PeriodStatus.UNDER_AVERAGE, status)
    }

    @Test
    fun `getPeriodStatus returns OVER_AVERAGE when above average`() {
        val status = engine.getPeriodStatus(150.0, 100.0)
        assertEquals(PeriodStatus.OVER_AVERAGE, status)
    }

    @Test
    fun `getPeriodStatus returns OVER_AVERAGE when equal to average`() {
        val status = engine.getPeriodStatus(100.0, 100.0)
        assertEquals(PeriodStatus.OVER_AVERAGE, status)
    }

    @Test
    fun `getPeriodStatus returns NO_DATA when average is zero`() {
        val status = engine.getPeriodStatus(50.0, 0.0)
        assertEquals(PeriodStatus.NO_DATA, status)
    }

    @Test
    fun `getPeriodStatus returns NO_DATA when average is negative`() {
        val status = engine.getPeriodStatus(50.0, -10.0)
        assertEquals(PeriodStatus.NO_DATA, status)
    }

    @Test
    fun `getAverageForPeriodType handles repository exceptions`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)

        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `getMonthlyTotals handles repository exception`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getMonthlyTotals(2026)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWeeklyTotals handles repository exception`() = runTest {
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getWeeklyTotals(2026, 1)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDailyTotals handles repository exception`() = runTest {
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getDailyTotals(2026, 3)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCategoryBreakdown handles repository exception`() = runTest {
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCategoryBreakdown handles zero grand total`() = runTest {
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Empty", 0.0, 0)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan")

        assertEquals(1, result.size)
        assertEquals(0.0f, result[0].percentageOfTotal, 0.01f)
    }

    @Test
    fun `getWeeklyTotals calculates correct week labels`() = runTest {
        // Use real dates within January 2026 so production filter passes
        val w1Start = getStartOfWeek(2026, 2) // ISO week 2 = Jan 5-11
        val w1End = getEndOfWeek(2026, 2)
        val w2Start = getStartOfWeek(2026, 3) // ISO week 3 = Jan 12-18
        val w2End = getEndOfWeek(2026, 3)
        val w3Start = getStartOfWeek(2026, 4) // ISO week 4 = Jan 19-25
        val w3End = getEndOfWeek(2026, 4)
        val weeklyTotals = listOf(
            WeeklyTotal("2026-W2", w1Start, w1End, 100.0, 2),
            WeeklyTotal("2026-W3", w2Start, w2End, 200.0, 4),
            WeeklyTotal("2026-W4", w3Start, w3End, 150.0, 3)
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns weeklyTotals
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getWeeklyTotals(2026, 1)

        assertEquals(3, result.size)
        assertEquals("W1", result[0].periodLabel)
        assertEquals("W2", result[1].periodLabel)
        assertEquals("W3", result[2].periodLabel)
    }

    private fun createCategoryTotalResult(
        id: Long,
        name: String,
        total: Double,
        txCount: Int
    ) = com.yourname.expensetracker.data.database.dao.CategoryTotalResult(
        id = id,
        name = name,
        icon = "?",
        color = "#808080",
        total = total,
        txCount = txCount
    )

    private fun getStartOfMonth(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getEndOfMonth(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun getStartOfWeek(year: Int, weekOfYear: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.WEEK_OF_YEAR, weekOfYear)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getEndOfWeek(year: Int, weekOfYear: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.WEEK_OF_YEAR, weekOfYear)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_WEEK, 6)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun getStartOfDay(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun getEndOfDay(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
