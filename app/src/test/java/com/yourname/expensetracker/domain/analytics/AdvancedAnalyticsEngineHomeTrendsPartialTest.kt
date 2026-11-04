package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.toExpenseSnapshots
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The self-fetching getCategoryAnalytics overload (used by the Home category trends) passes no money
 * contexts, so a category with a row excluded from conversion must publish no comparison at all.
 * Real engine and real normalizer (empty rate store: a JPY row has no rate).
 */
class AdvancedAnalyticsEngineHomeTrendsPartialTest {

    private val now = 1_718_452_800_000L // 2024-06-15 12:00 UTC
    private val txDate = 1_718_366_400_000L // 2024-06-14 12:00 UTC
    private val dayMs = 86_400_000L
    private val prevDate = txDate - 30 * dayMs

    private val categories = listOf(
        Category(id = 1L, name = "Food", icon = "F", color = "#111111"),
        Category(id = 2L, name = "Travel", icon = "T", color = "#222222")
    )

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = now
    }

    private val previousRange = AnalyticsPeriodRange(
        AnalyticsPeriod.CUSTOM, txDate - 40 * dayMs, txDate - 20 * dayMs, "PREV", null
    )
    private val period = AnalyticsPeriodRange(
        AnalyticsPeriod.MONTH, txDate - dayMs, now, "THIS", previousRange
    )

    private fun purchase(id: Long, amount: Double, currency: String, categoryId: Long, date: Long) = Expense(
        id = id, amount = amount, currency = currency, merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date, categoryId = categoryId
    )

    private suspend fun trends(expenses: List<Expense>): List<EnhancedCategoryAnalytics> {
        val expenseRepository = mockk<ExpenseRepository>()
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } answers {
            val s = firstArg<Long>()
            val e = secondArg<Long>()
            expenses.filter { it.date in s until e }.toExpenseSnapshots()
        }
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getAll() } returns categories
        val budgetRepository = mockk<BudgetRepository>()
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns emptyList()
        val engine = AdvancedAnalyticsEngine(
            expenseRepository, categoryRepository, budgetRepository, TestCurrencySettingsRepository(),
            testAnalyticsCurrencyNormalizer(), timeProvider, Dispatchers.Unconfined, Dispatchers.Unconfined
        )
        @Suppress("DEPRECATION")
        return engine.getCategoryAnalytics(period, displayCurrency = "EUR").first
    }

    @Test
    fun `control all rows convertible keeps the category percent`() = runTest {
        val food = trends(
            listOf(purchase(1, 250.0, "EUR", 1L, txDate), purchase(2, 100.0, "EUR", 1L, prevDate))
        ).single { it.category.id == 1L }

        assertEquals(100.0, food.previousPeriodTotal!!, 0.001)
        assertEquals(150f, food.changePercent!!, 0.01f)
    }

    @Test
    fun `category with an unrated previous row publishes no percent and no previous total`() = runTest {
        val enhanced = trends(
            listOf(
                purchase(1, 250.0, "EUR", 1L, txDate),
                purchase(2, 100.0, "EUR", 1L, prevDate),
                purchase(3, 50_000.0, "JPY", 1L, prevDate),
                purchase(4, 40.0, "EUR", 2L, txDate),
                purchase(5, 20.0, "EUR", 2L, prevDate)
            )
        )

        val food = enhanced.single { it.category.id == 1L }
        assertNull("no fabricated +150% against an understated previous total", food.changePercent)
        assertNull(food.previousPeriodTotal)
        assertEquals(CategoryTrendDirection.STABLE, food.trendDirection)
        // A category with no excluded rows keeps its number.
        val travel = enhanced.single { it.category.id == 2L }
        assertNotNull(travel.changePercent)
        assertEquals(100f, travel.changePercent!!, 0.01f)
        assertEquals(20.0, travel.previousPeriodTotal!!, 0.001)
    }
}
