package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsStressTest {

    @Test
    fun `analytics_month_10k_transactions_completes_within_budget`() = runTest {
        val database = mockk<AppDatabase>(relaxed = true)
        val expenseDao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)

        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        every { timeProvider.now() } returns ms(2026, 3, 31)

        val categories = (1L..100L).map { id ->
            val hex = ((id * 123457) % 0xFFFFFF).toInt().toString(16).padStart(6, '0')
            Category(id = id, name = "Category$id", icon = "•", color = "#$hex")
        }
        coEvery { categoryRepository.getAll() } returns categories
        every { categoryRepository.allCategories } returns flowOf(categories)
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        val expenses = ArrayList<Expense>(10_000)
        var expectedTotal = 0.0
        for (i in 0 until 10_000) {
            val amount = (i % 100 + 1).toDouble() // deterministic 1..100
            expectedTotal += amount
            expenses += Expense(
                id = i.toLong() + 1,
                amount = amount,
                merchant = "M${i % 250}",
                transactionType = TransactionType.PURCHASE,
                date = start + ((i % 31).toLong() * 24L * 60L * 60L * 1000L),
                categoryId = (i % 100 + 1).toLong()
            )
        }

        coEvery { expenseDao.getExpensesBetween(start, end) } returns expenses

        val repository = ExpenseRepository(
            database = database,
            expenseDao = expenseDao,
            userCorrectionDao = mockk(relaxed = true),
            pendingReviewDao = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true)
        )

        val engine = AdvancedAnalyticsEngine(
            expenseRepository = repository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            timeProvider = timeProvider
        )

        val startedAt = System.nanoTime()
        val analytics = engine.getCategoryAnalytics(
            PeriodRange(AnalyticsPeriod.CUSTOM, start, end, "Mar 2026", null)
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        val actualTotal = analytics.sumOf { it.totalSpent }
        assertApproxEquals(expectedTotal, actualTotal, 0.0001)
        assertTrue("Expected <= 10s, actual ${elapsedMs}ms", elapsedMs < 10_000)
        assertTrue(analytics.size <= 100)
    }

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
