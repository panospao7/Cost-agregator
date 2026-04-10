package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class BudgetAutopilotEngineTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var timeProvider: TimeProvider

    private lateinit var engine: BudgetAutopilotEngine

    private val now = millis(2026, Calendar.APRIL, 15)
    private val dayMs = 24L * 60L * 60L * 1000L

    @Before
    fun setup() {
        budgetRepository = mockk()
        expenseDao = mockk(relaxed = true)
        categoryRepository = mockk()
        insightsEngine = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        timeProvider = mockk()

        every { timeProvider.now() } returns now
        every { categoryRepository.allCategories } returns flowOf(
            listOf(
                Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF5733"),
                Category(id = 2L, name = "Travel", icon = "✈️", color = "#3357FF")
            )
        )

        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()
        // Default: no spending data for any category
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(any(), any(), any()) } returns emptyList()
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns emptyList()

        engine = BudgetAutopilotEngine(
            budgetRepository = budgetRepository,
            expenseDao = expenseDao,
            categoryRepository = categoryRepository,
            insightsEngine = insightsEngine,
            spendingPaceCalculator = spendingPaceCalculator,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `generateRecommendations aggregates monthly totals not per-transaction averages`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )

        // Three months, total per month = 100. Aggregate DAO returns monthly totals directly.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 2),
            MonthlySpendingTotal("2026-02", 100.0, 2),
            MonthlySpendingTotal("2026-03", 100.0, 2)
        )

        val result = engine.generateRecommendations()
        val rec = result.categoryRecommendations.single()

        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetTrend.STABLE, rec.trend)
    }

    @Test
    fun `generateRecommendations detects increasing trend using chronological month order`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 200.0)
        )

        // DAO returns rows in chronological order (SQL ORDER BY); totals increase.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 200.0, 1),
            MonthlySpendingTotal("2026-03", 300.0, 1)
        )

        val result = engine.generateRecommendations()
        val rec = result.categoryRecommendations.single()

        assertEquals(BudgetTrend.INCREASING, rec.trend)
    }

    @Test
    fun `generateRecommendations enforces plus and minus fifteen percent delta caps`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0),
            budget(id = 2L, categoryId = 2L, amount = 100.0)
        )

        // Category 1 wants strong increase -> cap at 115
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 300.0, 1),
            MonthlySpendingTotal("2026-02", 300.0, 1),
            MonthlySpendingTotal("2026-03", 300.0, 1)
        )

        // Category 2 wants strong decrease -> cap at 85
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(2L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 10.0, 1),
            MonthlySpendingTotal("2026-02", 10.0, 1),
            MonthlySpendingTotal("2026-03", 10.0, 1)
        )

        val result = engine.generateRecommendations()
        val recByCategory = result.categoryRecommendations.associateBy { it.categoryId }

        assertApproxEquals(115.0, recByCategory.getValue(1L).recommendedBudget, 0.01)
        assertApproxEquals(85.0, recByCategory.getValue(2L).recommendedBudget, 0.01)
    }

    @Test
    fun `generateRecommendations applies volatility safety factor for medium and high volatility`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0),
            budget(id = 2L, categoryId = 2L, amount = 100.0)
        )

        // Category 1: [80,120,80,120] => CV ~0.20 (medium) => *1.08 => 108
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 80.0, 1),
            MonthlySpendingTotal("2026-02", 120.0, 1),
            MonthlySpendingTotal("2026-03", 80.0, 1),
            MonthlySpendingTotal("2026-04", 120.0, 1)
        )

        // Category 2: [50,150,50,150] => CV 0.50 (high) => *1.15 => 115
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(2L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 50.0, 1),
            MonthlySpendingTotal("2026-02", 150.0, 1),
            MonthlySpendingTotal("2026-03", 50.0, 1),
            MonthlySpendingTotal("2026-04", 150.0, 1)
        )

        val result = engine.generateRecommendations()
        val recByCategory = result.categoryRecommendations.associateBy { it.categoryId }

        assertApproxEquals(108.0, recByCategory.getValue(1L).recommendedBudget, 0.01)
        assertApproxEquals(115.0, recByCategory.getValue(2L).recommendedBudget, 0.01)
    }

    @Test
    fun `generateRecommendations edge case empty budgets returns empty recommendations`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        val result = engine.generateRecommendations()

        assertTrue(result.categoryRecommendations.isEmpty())
        assertApproxEquals(0.0, result.totalCurrentBudget, 0.01)
        assertApproxEquals(0.0, result.totalRecommendedBudget, 0.01)
        assertApproxEquals(0.0, result.overallDelta, 0.01)
        assertApproxEquals(0.0, result.confidence, 0.01)
    }

    @Test
    fun `generateRecommendations edge case empty spend history applies bounded decrease`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns emptyList()

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        // With no history, raw recommendation becomes 0 and is capped at -15%.
        assertApproxEquals(85.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetTrend.STABLE, rec.trend)
    }

    @Test
    fun `generateRecommendations edge case single month history remains stable and finite`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        // Single month: the engine receives [120.0], trend = 0 (< 2 months), avg = 120 * safety 1.0 = 120,
        // then delta-capped to [85, 115]. 120 > 115 so capped to 115.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-04", 120.0, 1)
        )

        val result = engine.generateRecommendations()
        val rec = result.categoryRecommendations.single()

        assertEquals(BudgetTrend.STABLE, rec.trend)
        assertTrue(!rec.recommendedBudget.isNaN())
        assertTrue(rec.recommendedBudget.isFinite())
    }

    @Test
    fun `generateRecommendations edge case stable spending keeps stable trend`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.STABLE, rec.trend)
        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
    }

    @Test
    fun `generateRecommendations with zero current budget uses safe initial budget phrasing`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 0.0)
        )
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 200.0, 1),
            MonthlySpendingTotal("2026-03", 300.0, 1)
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.INCREASING, rec.trend)
        assertTrue(rec.reason.contains("setting an initial budget", ignoreCase = true))
        assertTrue(!rec.reason.contains("NaN"))
        assertTrue(!rec.reason.contains("Infinity"))
    }

    @Test
    fun `generateRecommendations uses sparse months without gap infill`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = 1L, amount = 100.0)
        )
        // SQL returns Jan and Mar but not Feb — engine must NOT infill Feb=0.0
        // historicalSpend = [200.0, 200.0] => avg = 200
        // trend: firstHalf=[200.0], secondHalf=[200.0] => ratio=1.0 => STABLE
        // trendAdjustedSpend = 200 * (1 + 0*3) = 200
        // safetyFactor: CV of [200,200] => stdDev=0, CV=0 => LOW => 1.0
        // recommended = 200 * 1.0 = 200, capped at [85, 115] => 115
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 200.0, 2),
            MonthlySpendingTotal("2026-03", 200.0, 2)
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        // Sparse history: 2 months, avg=200 > 100+15=115 => capped at +15%
        assertApproxEquals(115.0, rec.recommendedBudget, 0.01)
        assertEquals(BudgetTrend.STABLE, rec.trend)
    }

    @Test
    fun `generateRecommendations for overall budget uses non-category DAO method`() = runTest {
        coEvery { budgetRepository.getActiveBudgets() } returns listOf(
            budget(id = 1L, categoryId = null, amount = 100.0)
        )
        // Overall budget (categoryId=null) uses getMonthlySpendingTotalsBetween
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 3),
            MonthlySpendingTotal("2026-02", 100.0, 3),
            MonthlySpendingTotal("2026-03", 100.0, 3)
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.STABLE, rec.trend)
        assertApproxEquals(100.0, rec.recommendedBudget, 0.01)
    }

    private fun budget(id: Long, categoryId: Long?, amount: Double): Budget {
        return Budget(
            id = id,
            categoryId = categoryId,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = now - 60 * dayMs
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
