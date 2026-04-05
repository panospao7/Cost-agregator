package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AdvancedAnalyticsEngineDeepTest {

    private lateinit var engine: AdvancedAnalyticsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var timeProvider: TimeProvider

    private val food = Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF0000")

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        engine = AdvancedAnalyticsEngine(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        coEvery { categoryRepository.getAll() } returns listOf(food)
        every { categoryRepository.allCategories } returns flowOf(listOf(food))
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = dateMs(2026, 4, 1))
        )
    }

    @Test
    fun `category analytics computes sum avg median and percentiles with interpolation`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 20)
        val period = PeriodRange(AnalyticsPeriod.MONTH, dateMs(2026, 4, 1), dateMs(2026, 5, 1), "Apr", null)

        val current = listOf(
            createExpense(date = "2026-04-01", amount = 10.0, category = "Food"),
            createExpense(date = "2026-04-02", amount = 20.0, category = "Food"),
            createExpense(date = "2026-04-03", amount = 30.0, category = "Food"),
            createExpense(date = "2026-04-04", amount = 40.0, category = "Food")
        )
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns current
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns current

        val result = engine.getCategoryAnalytics(period).first()

        assertApproxEquals(100.0, result.totalSpent)
        assertApproxEquals(25.0, result.averagePerTransaction)
        assertApproxEquals(25.0, result.medianTransaction)
        assertApproxEquals(17.5, result.percentile25)
        assertApproxEquals(32.5, result.percentile75)
    }

    @Test
    fun `statistical insights computes sample stddev cv volatility and day counters`() = runTest {
        val period = PeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 1), dateMs(2026, 4, 5), "4d", null)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 10.0),
            createExpense(date = "2026-04-01", amount = 20.0),
            createExpense(date = "2026-04-03", amount = 30.0),
            createExpense(date = "2026-04-03", amount = 40.0)
        )
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns expenses

        val stats = engine.getStatisticalInsights(period)

        // mean=25, sample stddev=sqrt(500/3)=12.9099
        assertApproxEquals(25.0, stats.meanTransaction)
        assertApproxEquals(12.9099, stats.standardDeviation, 0.01)
        assertApproxEquals((12.9099 / 25.0).toFloat(), stats.coefficientOfVariation, 0.01f)
        assertApproxEquals(((12.9099 / 25.0) * 100).toFloat(), stats.volatilityIndex, 0.5f)
        assertEquals(2, stats.daysWithSpending)
        assertEquals(2, stats.daysWithoutSpending)
        assertApproxEquals(70.0, stats.maxDailySpend)
        // canonical should be total/periodDays = 100/4 = 25
        assertApproxEquals(25.0, stats.averageDailySpend, 0.01)
    }

    @Test
    fun `histogram bins include max value and preserve total count`() = runTest {
        val period = PeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 1), dateMs(2026, 4, 8), "week", null)
        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 0.0),
            createExpense(date = "2026-04-02", amount = 50.0),
            createExpense(date = "2026-04-03", amount = 100.0)
        )
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns expenses

        val stats = engine.getStatisticalInsights(period)
        val totalCount = stats.histogramBins.sumOf { it.count }

        assertEquals(3, totalCount)
        assertTrue(stats.histogramBins.last().count >= 1) // max=100 must be in last bin
    }

    @Test
    fun `spending patterns map day of week weekend split and time slots`() = runTest {
        val period = PeriodRange(AnalyticsPeriod.WEEK, dateMs(2026, 4, 6), dateMs(2026, 4, 13), "week", null)
        val expenses = listOf(
            // Monday 08:00 -> EARLY_MORNING
            createExpenseAt(dateMs(2026, 4, 6, 8), 20.0),
            // Saturday 13:00 -> AFTERNOON
            createExpenseAt(dateMs(2026, 4, 11, 13), 50.0),
            // Sunday 22:00 -> NIGHT
            createExpenseAt(dateMs(2026, 4, 12, 22), 30.0)
        )
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns expenses

        val patterns = engine.getSpendingPatterns(period)

        assertApproxEquals(20.0, patterns.dayOfWeekStats[0]?.totalSpent ?: 0.0)
        assertApproxEquals(20.0, patterns.weekendVsWeekday.weekdayTotal)
        assertApproxEquals(80.0, patterns.weekendVsWeekday.weekendTotal)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.EARLY_MORNING] ?: 0.0) > 0)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.AFTERNOON] ?: 0.0) > 0)
        assertTrue((patterns.timeOfDayDistribution[TimeSlot.NIGHT] ?: 0.0) > 0)
    }

    @Test
    fun `merchant analytics computes visit frequency average days between and loyalty score`() = runTest {
        val period = PeriodRange(AnalyticsPeriod.YEAR, dateMs(2025, 5, 1), dateMs(2026, 5, 1), "year", null)
        val visits = (0..11).map { idx ->
            createExpense(date = LocalDate.of(2025, 5, 1).plusMonths(idx.toLong()).toString(), amount = 50.0, merchant = "Cafe")
        }
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns visits
        coEvery { expenseRepository.getExpensesSince(any()) } returns visits

        val merchant = engine.getMerchantAnalytics(period, 1).first()

        assertEquals(MerchantVisitFrequency.MONTHLY, merchant.visitFrequency)
        assertApproxEquals(30.0, merchant.averageDaysBetweenVisits ?: 0.0, 2.0)
        assertApproxEquals(85f, merchant.loyaltyScore, 0.1f)
    }

    @Test
    fun `empty datasets return safe zeros`() = runTest {
        val period = PeriodRange(AnalyticsPeriod.MONTH, dateMs(2026, 4, 1), dateMs(2026, 5, 1), "apr", null)
        coEvery { expenseRepository.getExpensesBetween(period.startMs, period.endMs) } returns emptyList()
        coEvery { expenseRepository.getExpensesSince(any()) } returns emptyList()

        val patterns = engine.getSpendingPatterns(period)
        val stats = engine.getStatisticalInsights(period)

        assertTrue(patterns.dayOfWeekStats.isEmpty())
        assertEquals(0, stats.daysWithSpending)
        assertApproxEquals(0.0, stats.meanTransaction)
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dateMs(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun createExpenseAt(dateMs: Long, amount: Double) =
        com.yourname.expensetracker.data.database.entity.Expense(
            amount = amount,
            merchant = "M",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = dateMs
        )
}
