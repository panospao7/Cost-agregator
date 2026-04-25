package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.CategorySpentTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BudgetRepositorySuggestionsBatchTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        repository = BudgetRepository(
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            expenseDao = expenseDao,
            budgetCalculator = budgetCalculator,
            timeProvider = timeProvider,
            offsetEngine = offsetEngine,
            timeBoundaryTicker = TimeBoundaryTicker(timeProvider)
        )
    }

    @Test
    fun `getSuggestions batches category totals in single grouped query`() = runTest {
        val now = utcMs(2026, Calendar.MARCH, 15)
        val categories = listOf(
            Category(id = 1L, name = "Food", icon = "🍔", color = "#f00", isDefault = false),
            Category(id = 2L, name = "Transport", icon = "🚌", color = "#0f0", isDefault = false),
            Category(id = 3L, name = "Games", icon = "🎮", color = "#00f", isDefault = false)
        )

        every { timeProvider.now() } returns now
        every { categoryDao.getAllFlow() } returns flowOf(categories)
        coEvery { budgetDao.getActiveBudgets() } returns listOf(activeBudget(categoryId = 2L))
        coEvery { expenseDao.getOldestExpenseDate() } returns utcMs(2025, Calendar.DECEMBER, 1)
        coEvery {
            expenseDao.getCategorySpentTotalsInPeriod(
                categoryIds = listOf(1L, 3L),
                startMs = any(),
                endMs = any()
            )
        } returns listOf(
            CategorySpentTotal(categoryId = 1L, total = 300.0),
            CategorySpentTotal(categoryId = 3L, total = 30.0)
        )

        val suggestions = repository.getSuggestions()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().categoryId).isEqualTo(1L)
        assertThat(suggestions.single().categoryName).isEqualTo("Food")

        coVerify(exactly = 1) { expenseDao.getCategorySpentTotalsInPeriod(listOf(1L, 3L), any(), any()) }
        coVerify(exactly = 0) { expenseDao.getCategorySpentInPeriod(any(), any(), any()) }
    }

    private fun activeBudget(categoryId: Long) = Budget(
        id = 10L,
        categoryId = categoryId,
        amount = 200.0,
        period = BudgetPeriod.MONTHLY,
        startDate = utcMs(2026, Calendar.MARCH, 1),
        isActive = true
    )

    private fun utcMs(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
