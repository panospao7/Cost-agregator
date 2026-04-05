package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
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
    private lateinit var expenseRepository: ExpenseRepository
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
        expenseRepository = mockk()
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        engine = BudgetAutopilotEngine(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
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

        // Three months, two tx each month, total per month = 100.
        // If averaged per transaction, result would trend toward 50.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, 50.0, millis(2026, Calendar.JANUARY, 20), 1L),
            purchase(2L, 50.0, millis(2026, Calendar.JANUARY, 25), 1L),
            purchase(3L, 40.0, millis(2026, Calendar.FEBRUARY, 20), 1L),
            purchase(4L, 60.0, millis(2026, Calendar.FEBRUARY, 26), 1L),
            purchase(5L, 30.0, millis(2026, Calendar.MARCH, 21), 1L),
            purchase(6L, 70.0, millis(2026, Calendar.MARCH, 27), 1L)
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

        // Intentionally out of order input dates; engine should sort chronologically.
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(10L, 300.0, millis(2026, Calendar.MARCH, 5), 1L),
            purchase(11L, 100.0, millis(2026, Calendar.JANUARY, 5), 1L),
            purchase(12L, 200.0, millis(2026, Calendar.FEBRUARY, 5), 1L)
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

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            // Category 1 wants strong increase -> cap at 115
            purchase(1L, 300.0, millis(2026, Calendar.JANUARY, 20), 1L),
            purchase(2L, 300.0, millis(2026, Calendar.FEBRUARY, 20), 1L),
            purchase(3L, 300.0, millis(2026, Calendar.MARCH, 20), 1L),

            // Category 2 wants strong decrease -> cap at 85
            purchase(4L, 10.0, millis(2026, Calendar.JANUARY, 20), 2L),
            purchase(5L, 10.0, millis(2026, Calendar.FEBRUARY, 20), 2L),
            purchase(6L, 10.0, millis(2026, Calendar.MARCH, 20), 2L)
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
        // Category 2: [50,150,50,150] => CV 0.50 (high) => *1.15 => 115
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, 80.0, millis(2026, Calendar.JANUARY, 20), 1L),
            purchase(2L, 120.0, millis(2026, Calendar.FEBRUARY, 20), 1L),
            purchase(3L, 80.0, millis(2026, Calendar.MARCH, 20), 1L),
            purchase(4L, 120.0, millis(2026, Calendar.APRIL, 1), 1L),

            purchase(5L, 50.0, millis(2026, Calendar.JANUARY, 21), 2L),
            purchase(6L, 150.0, millis(2026, Calendar.FEBRUARY, 21), 2L),
            purchase(7L, 50.0, millis(2026, Calendar.MARCH, 21), 2L),
            purchase(8L, 150.0, millis(2026, Calendar.APRIL, 2), 2L)
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, 120.0, now - dayMs, 1L)
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, 100.0, millis(2026, Calendar.JANUARY, 20), 1L),
            purchase(2L, 100.0, millis(2026, Calendar.FEBRUARY, 20), 1L),
            purchase(3L, 100.0, millis(2026, Calendar.MARCH, 20), 1L)
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
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            purchase(1L, 100.0, millis(2026, Calendar.JANUARY, 20), 1L),
            purchase(2L, 200.0, millis(2026, Calendar.FEBRUARY, 20), 1L),
            purchase(3L, 300.0, millis(2026, Calendar.MARCH, 20), 1L)
        )

        val rec = engine.generateRecommendations().categoryRecommendations.single()

        assertEquals(BudgetTrend.INCREASING, rec.trend)
        assertTrue(rec.reason.contains("setting an initial budget", ignoreCase = true))
        assertTrue(!rec.reason.contains("NaN"))
        assertTrue(!rec.reason.contains("Infinity"))
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

    private fun purchase(id: Long, amount: Double, date: Long, categoryId: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = "M$id",
            transactionType = TransactionType.PURCHASE,
            date = date,
            categoryId = categoryId,
            isNotMine = false
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
