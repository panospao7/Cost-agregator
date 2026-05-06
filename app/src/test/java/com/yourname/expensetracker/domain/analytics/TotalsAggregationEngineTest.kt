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
import kotlinx.coroutines.flow.first
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
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, mockk(relaxed = true), mockk(relaxed = true), Dispatchers.Unconfined)
        every { timeProvider.now() } returns System.currentTimeMillis()
    }

    @Test
    fun `getMonthlyTotals returns empty list when no expenses`() = runTest {
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(12, result.size)
        assertTrue(result.all { it.totalAmount == 0.0 && it.transactionCount == 0 })
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

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(12, result.size)
        val january = result.first { it.periodKey == "2026-01" }
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

        val result = engine.getWeeklyTotals(2026, 1).first()

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.totalAmount == 0.0 && it.transactionCount == 0 })
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

        val result = engine.getWeeklyTotals(2026, 1).first()

        assertTrue(result.isNotEmpty())
        val week1 = result.first { it.periodKey == "2026-W3" }
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

        val result = engine.getDailyTotals(2026, 3).first()

        assertEquals(7, result.size)
        assertTrue(result.all { it.totalAmount == 0.0 && it.transactionCount == 0 })
    }

    @Test
    fun `getDailyTotalsForRange zero fills missing days`() = runTest {
        val dayStart = getStartOfDay(2026, Calendar.JANUARY, 12)
        val dayEnd = getEndOfDay(2026, Calendar.JANUARY, 12)
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns listOf(
            DailyTotal(
                dayEpoch = 20260112L,
                startDate = dayStart,
                endDate = dayEnd,
                total = 50.0,
                txCount = 2
            )
        )
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 25.0

        val result = engine.getDailyTotalsForRange(dayStart, getEndOfDay(2026, Calendar.JANUARY, 14)).first()

        assertEquals(3, result.size)
        assertEquals(50.0, result[0].totalAmount, 0.01)
        assertEquals(0.0, result[1].totalAmount, 0.01)
        assertEquals(0.0, result[2].totalAmount, 0.01)
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

        val result = engine.getDailyTotals(2026, 3).first()

        assertEquals(7, result.size)
        val day1 = result.first { it.totalAmount == 50.0 }
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

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

        assertEquals(3, result.size)
        assertEquals(250.0, result[0].totalAmount, 0.01)
        assertEquals(50.0, result[0].percentageOfTotal, 0.01)
        assertEquals(150.0, result[1].totalAmount, 0.01)
        assertEquals(30.0, result[1].percentageOfTotal, 0.01)
        assertEquals(100.0, result[2].totalAmount, 0.01)
        assertEquals(20.0, result[2].percentageOfTotal, 0.01)
        assertEquals("Jan", result[0].periodLabel)
    }

    @Test
    fun `getCategoryBreakdown handles empty results`() = runTest {
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns emptyList()

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

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

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

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

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

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

        val result = engine.getMonthlyTotals(2026).first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getWeeklyTotals handles repository exception`() = runTest {
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getWeeklyTotals(2026, 1).first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getDailyTotals handles repository exception`() = runTest {
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getDailyTotals(2026, 3).first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCategoryBreakdown handles repository exception`() = runTest {
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } throws RuntimeException("DB error")

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getCategoryBreakdown handles zero grand total`() = runTest {
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Empty", 0.0, 0)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Jan").first()

        assertEquals(1, result.size)
        assertEquals(0.0, result[0].percentageOfTotal, 0.01)
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

        val result = engine.getWeeklyTotals(2026, 1).first()

        assertEquals(5, result.size)
        assertEquals("W2", result.first { it.periodKey == "2026-W2" }.periodLabel)
        assertEquals("W3", result.first { it.periodKey == "2026-W3" }.periodLabel)
        assertEquals("W4", result.first { it.periodKey == "2026-W4" }.periodLabel)
    }

    // ========== A.10 Batch 7 — Purchase-only contract lock-in tests ==========

    @Test
    fun `getMonthlyTotals returns only repository purchase-filtered totals`() = runTest {
        // The repository layer already filters to PURCHASE-only via SQL.
        // This test locks in that TotalsAggregationEngine faithfully surfaces
        // repository results without adding or subtracting any transactions.
        val januaryStart = getStartOfMonth(2026, Calendar.JANUARY)
        val januaryEnd = getEndOfMonth(2026, Calendar.JANUARY)
        val purchaseOnlyTotal = 450.0 // Simulates PURCHASE-only sum from SQL
        val purchaseOnlyCount = 8

        val monthlyTotals = listOf(
            MonthlyTotal(
                monthKey = "2026-01",
                startDate = januaryStart,
                endDate = januaryEnd,
                total = purchaseOnlyTotal,
                txCount = purchaseOnlyCount
            )
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getMonthlyTotals(2026).first()

        assertEquals(12, result.size)
        val january = result.first { it.periodKey == "2026-01" }
        assertEquals(purchaseOnlyTotal, january.totalAmount, 0.01)
        assertEquals(purchaseOnlyCount, january.transactionCount)
    }

    @Test
    fun `getDailyTotalsForRange returns purchase-only totals from repository`() = runTest {
        val dayStart = getStartOfDay(2026, Calendar.MARCH, 10)
        val dayEnd = getEndOfDay(2026, Calendar.MARCH, 10)
        val purchaseOnlyDailyTotal = 75.0

        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = 20260310L,
                startDate = dayStart,
                endDate = dayEnd,
                total = purchaseOnlyDailyTotal,
                txCount = 3
            )
        )
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 50.0

        val result = engine.getDailyTotalsForRange(dayStart, dayEnd).first()

        assertEquals(1, result.size)
        assertEquals(purchaseOnlyDailyTotal, result[0].totalAmount, 0.01)
        assertEquals(3, result[0].transactionCount)
    }

    @Test
    fun `getCategoryBreakdown surfaces purchase-only data without deposits or transfers`() = runTest {
        // Simulates what the DB returns after SPENDING_TYPE_SQL filter:
        // Only PURCHASE amounts appear; deposits/transfers are excluded at SQL layer
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Groceries", 200.0, 5),
            createCategoryTotalResult(2L, "Dining", 100.0, 3)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Mar").first()

        assertEquals(2, result.size)
        val totalAmount = result.sumOf { it.totalAmount }
        assertEquals(300.0, totalAmount, 0.01)

        // Percentages based on purchase-only grand total
        val totalPercentage = result.sumOf { it.percentageOfTotal.toDouble() }
        assertEquals(100.0, totalPercentage, 0.01)
    }

    @Test
    fun `getCategoryBreakdown preserves descending sort by purchase amount`() = runTest {
        val categoryResults = listOf(
            createCategoryTotalResult(1L, "Small Category", 50.0, 2),
            createCategoryTotalResult(2L, "Large Category", 500.0, 10),
            createCategoryTotalResult(3L, "Medium Category", 200.0, 5)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(0L, System.currentTimeMillis(), "Mar").first()

        assertEquals("Large Category", result[0].category.name)
        assertEquals("Medium Category", result[1].category.name)
        assertEquals("Small Category", result[2].category.name)
    }

    @Test
    fun `getWeeklyTotals returns purchase-only totals and counts from repository`() = runTest {
        // Repository SQL layer already filters to PURCHASE via SPENDING_TYPE_SQL.
        // This test locks in that getWeeklyTotals faithfully surfaces those
        // purchase-only values without adding/subtracting transactions.
        val w1Start = getStartOfWeek(2026, 2) // ISO week 2 = within January
        val w1End = getEndOfWeek(2026, 2)
        val purchaseOnlyTotal = 275.0
        val purchaseOnlyCount = 6

        val weeklyTotals = listOf(
            WeeklyTotal(
                weekKey = "2026-W2",
                startDate = w1Start,
                endDate = w1End,
                total = purchaseOnlyTotal,
                txCount = purchaseOnlyCount
            )
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns weeklyTotals
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val result = engine.getWeeklyTotals(2026, 1).first()

        assertTrue(result.isNotEmpty())
        val targetWeek = result.first { it.periodKey == "2026-W2" }
        assertEquals(purchaseOnlyTotal, targetWeek.totalAmount, 0.01)
        assertEquals(purchaseOnlyCount, targetWeek.transactionCount)
        assertEquals(PeriodType.WEEK, targetWeek.periodType)
    }

    @Test
    fun `getDailyTotals primary path returns purchase-only totals and counts from repository`() = runTest {
        // The primary getDailyTotals(year, weekOfYear) path delegates to
        // getDailyTotalsWithDatesForPeriod which uses SPENDING_TYPE_SQL.
        // This test locks in that contract.
        val dayStart = getStartOfDay(2026, Calendar.JANUARY, 12)
        val dayEnd = getEndOfDay(2026, Calendar.JANUARY, 12)
        val purchaseOnlyTotal = 88.50
        val purchaseOnlyCount = 4

        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = 20260112L,
                startDate = dayStart,
                endDate = dayEnd,
                total = purchaseOnlyTotal,
                txCount = purchaseOnlyCount
            )
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 60.0

        val result = engine.getDailyTotals(2026, 3).first()

        assertEquals(7, result.size)
        val day = result.first { it.totalAmount == purchaseOnlyTotal }
        assertEquals(purchaseOnlyTotal, day.totalAmount, 0.01)
        assertEquals(purchaseOnlyCount, day.transactionCount)
        assertEquals(PeriodType.DAY, day.periodType)
    }

    @Test
    fun `getDailyTotalsForRange preserves purchase-only counts from repository`() = runTest {
        // Complements the existing amount-level test by also verifying
        // transactionCount is surfaced unchanged from the repository.
        val day1Start = getStartOfDay(2026, Calendar.FEBRUARY, 5)
        val day1End = getEndOfDay(2026, Calendar.FEBRUARY, 5)
        val day2Start = getStartOfDay(2026, Calendar.FEBRUARY, 6)
        val day2End = getEndOfDay(2026, Calendar.FEBRUARY, 6)

        val dailyTotals = listOf(
            DailyTotal(dayEpoch = 20260205L, startDate = day1Start, endDate = day1End, total = 45.0, txCount = 2),
            DailyTotal(dayEpoch = 20260206L, startDate = day2Start, endDate = day2End, total = 110.0, txCount = 5)
        )
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 70.0

        val result = engine.getDailyTotalsForRange(day1Start, day2End).first()

        assertEquals(2, result.size)
        assertEquals(45.0, result[0].totalAmount, 0.01)
        assertEquals(2, result[0].transactionCount)
        assertEquals(110.0, result[1].totalAmount, 0.01)
        assertEquals(5, result[1].transactionCount)
        // Total across all days should reflect purchase-only sums
        assertEquals(155.0, result.sumOf { it.totalAmount }, 0.01)
        assertEquals(7, result.sumOf { it.transactionCount })
    }

    // ========== A.10 Batch 7 — Purchase-only contract for getAverageForPeriodType ==========

    @Test
    fun `getAverageForPeriodType MONTH returns purchase-only average from repository`() = runTest {
        // Repository SQL already filters to PURCHASE via SPENDING_TYPE_SQL.
        // This test locks in that getAverageForPeriodType(MONTH) faithfully
        // surfaces purchase-only monthly averages.
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        val monthlyTotals = listOf(
            MonthlyTotal("2025-05", getStartOfMonth(2025, Calendar.MAY), getEndOfMonth(2025, Calendar.MAY), 1200.0, 10),
            MonthlyTotal("2025-06", getStartOfMonth(2025, Calendar.JUNE), getEndOfMonth(2025, Calendar.JUNE), 900.0, 8),
            MonthlyTotal("2025-07", getStartOfMonth(2025, Calendar.JULY), getEndOfMonth(2025, Calendar.JULY), 1500.0, 12)
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals

        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)

        // (1200 + 900 + 1500) / 3 = 1200.0 — purchase-only average
        assertEquals(1200.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType WEEK returns purchase-only average from repository`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        val weeklyTotals = listOf(
            WeeklyTotal("2026-W10", getStartOfWeek(2026, 10), getEndOfWeek(2026, 10), 250.0, 5),
            WeeklyTotal("2026-W11", getStartOfWeek(2026, 11), getEndOfWeek(2026, 11), 350.0, 7),
            WeeklyTotal("2026-W12", getStartOfWeek(2026, 12), getEndOfWeek(2026, 12), 300.0, 6)
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns weeklyTotals

        val average = engine.getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)

        // (250 + 350 + 300) / 3 = 300.0 — purchase-only average
        assertEquals(300.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType DAY returns purchase-only average from repository`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        // Repository getAverageDailySpend already uses SPENDING_TYPE_SQL
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 42.50

        val average = engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

        // Faithfully returns purchase-only daily average from repository
        assertEquals(42.50, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType YEAR returns purchase-only average from repository`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        // Repository getTotalForPeriod uses SPENDING_TYPE_SQL, returning purchase-only totals
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2023 -> 10000.0 // Purchase-only total
                2024 -> 14000.0 // Purchase-only total
                2025 -> 12000.0 // Purchase-only total
                else -> 0.0
            }
        }

        val average = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)

        // Years 2022-2025 are checked (currentYear-4 until currentYear).
        // 2022 = 0 (excluded because total <= 0), 2023=10000, 2024=14000, 2025=12000
        // Average of non-zero years: (10000 + 14000 + 12000) / 3 = 12000.0
        assertEquals(12000.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType MONTH excludeCurrent returns purchase-only average without current period`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        val monthlyTotals = listOf(
            MonthlyTotal("2025-06", getStartOfMonth(2025, Calendar.JUNE), getEndOfMonth(2025, Calendar.JUNE), 800.0, 8),
            MonthlyTotal("2025-07", getStartOfMonth(2025, Calendar.JULY), getEndOfMonth(2025, Calendar.JULY), 1000.0, 10),
            MonthlyTotal("2026-04", getStartOfMonth(2026, Calendar.APRIL), getEndOfMonth(2026, Calendar.APRIL), 400.0, 4) // current partial month
        )
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals

        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = true)

        // dropLast(1) removes current month: (800 + 1000) / 2 = 900.0
        assertEquals(900.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType WEEK excludeCurrent returns purchase-only average without current period`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        val weeklyTotals = listOf(
            WeeklyTotal("2026-W13", getStartOfWeek(2026, 13), getEndOfWeek(2026, 13), 200.0, 4),
            WeeklyTotal("2026-W14", getStartOfWeek(2026, 14), getEndOfWeek(2026, 14), 400.0, 8),
            WeeklyTotal("2026-W15", getStartOfWeek(2026, 15), getEndOfWeek(2026, 15), 150.0, 3) // current partial week
        )
        coEvery { expenseRepository.getWeeklyTotalsForPeriod(any(), any()) } returns weeklyTotals

        val average = engine.getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = true)

        // dropLast(1) removes current week: (200 + 400) / 2 = 300.0
        assertEquals(300.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType DAY returns zero when repository returns null`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns null

        val average = engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

        assertEquals(0.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType YEAR excludeCurrent true excludes current year from average`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        // Current year (2026) has a non-zero total that must be excluded
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2023 -> 10000.0
                2024 -> 14000.0
                2025 -> 12000.0
                2026 -> 5000.0 // Non-zero current year — must be excluded
                else -> 0.0
            }
        }

        val average = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = true)

        // Only 2022-2025 are considered; 2022 = 0 (filtered), 2023-2025 have data
        // Average = (10000 + 14000 + 12000) / 3 = 12000.0
        assertEquals(12000.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType YEAR excludeCurrent false includes current year in average`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        // Current year (2026) has a non-zero total that must be included
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2023 -> 10000.0
                2024 -> 14000.0
                2025 -> 12000.0
                2026 -> 5000.0 // Non-zero current year — must be included
                else -> 0.0
            }
        }

        val average = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)

        // Years 2022-2026 are considered; 2022 = 0 (filtered), 2023-2026 have data
        // Average = (10000 + 14000 + 12000 + 5000) / 4 = 10250.0
        assertEquals(10250.0, average, 0.01)
    }

    @Test
    fun `getAverageForPeriodType YEAR excludeCurrent true vs false produce different results with non-zero current year`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2024 -> 8000.0
                2025 -> 12000.0
                2026 -> 3000.0 // Non-zero current year
                else -> 0.0
            }
        }

        val avgExclude = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = true)
        val avgInclude = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)

        // excludeCurrent=true:  (8000 + 12000) / 2 = 10000.0
        assertEquals(10000.0, avgExclude, 0.01)
        // excludeCurrent=false: (8000 + 12000 + 3000) / 3 = 7666.67
        assertEquals(7666.67, avgInclude, 0.01)

        // They must differ when current year has non-zero data
        assertTrue("excludeCurrent=true and false must differ", avgExclude != avgInclude)
    }

    @Test
    fun `getAverageForPeriodType YEAR returns zero when no purchase data exists`() = runTest {
        val referenceDate = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns referenceDate

        // All years return 0 — no purchase data
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0

        val average = engine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)

        assertEquals(0.0, average, 0.01)
    }

    @Test
    fun `getYearlyTotals returns purchase-only totals via repository contract`() = runTest {
        val now = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns now

        // Repository returns purchase-only sums (SPENDING_TYPE_SQL in getTotalForPeriod)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2025 -> 12000.0  // Purchase-only total for 2025
                2026 -> 3500.0   // Purchase-only total for 2026 (partial year)
                else -> 0.0
            }
        }
        coEvery { expenseRepository.getTransactionCountForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2025 -> 120
                2026 -> 35
                else -> 0
            }
        }

        val result = engine.getYearlyTotals().first()

        val total2025 = result.find { it.periodKey == "2025" }
        val total2026 = result.find { it.periodKey == "2026" }
        assertNotNull(total2025)
        assertNotNull(total2026)
        assertEquals(12000.0, total2025!!.totalAmount, 0.01)
        assertEquals(120, total2025.transactionCount)
        assertEquals(PeriodType.YEAR, total2025.periodType)
        assertEquals(3500.0, total2026!!.totalAmount, 0.01)
        assertEquals(35, total2026.transactionCount)
        assertEquals(PeriodType.YEAR, total2026.periodType)
    }

    @Test
    fun `getYearlyTotals uses excludeCurrent true for status so partial current year does not skew average`() = runTest {
        val now = getStartOfDay(2026, Calendar.APRIL, 15)
        every { timeProvider.now() } returns now

        // 2024 and 2025 have full-year purchase-only totals; 2026 is a partial year.
        // Average for status must exclude 2026 (excludeCurrent = true),
        // so average = (10000 + 14000) / 2 = 12000.
        // 2024 (10000) < 12000 → UNDER_AVERAGE
        // 2025 (14000) > 12000 → OVER_AVERAGE
        // 2026 (2000)  < 12000 → UNDER_AVERAGE (partial year, below completed-year average)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2024 -> 10000.0
                2025 -> 14000.0
                2026 -> 2000.0  // Partial current year
                else -> 0.0
            }
        }
        coEvery { expenseRepository.getTransactionCountForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            when (cal.get(Calendar.YEAR)) {
                2024 -> 100
                2025 -> 140
                2026 -> 20
                else -> 0
            }
        }

        val result = engine.getYearlyTotals().first()

        val y2024 = result.find { it.periodKey == "2024" }
        val y2025 = result.find { it.periodKey == "2025" }
        val y2026 = result.find { it.periodKey == "2026" }
        assertNotNull(y2024)
        assertNotNull(y2025)
        assertNotNull(y2026)

        // Status computed against average of completed years only (excludeCurrent = true)
        assertEquals(PeriodStatus.UNDER_AVERAGE, y2024!!.status)
        assertEquals(PeriodStatus.OVER_AVERAGE, y2025!!.status)
        assertEquals(PeriodStatus.UNDER_AVERAGE, y2026!!.status)
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
