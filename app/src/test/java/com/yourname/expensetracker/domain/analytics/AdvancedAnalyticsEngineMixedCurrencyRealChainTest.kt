package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-08-7 with NO mocked engine and NO mocked assembler: the real
 * [AnalyticsInputAssembler] (real normalizer, empty rate store) feeds the real
 * [AdvancedAnalyticsEngine]. A USD row with no stored rate is genuinely unconvertible.
 */
class AdvancedAnalyticsEngineMixedCurrencyRealChainTest {

    private val now = 1_718_452_800_000L // 2024-06-15 12:00 UTC
    private val txDate = 1_718_366_400_000L // 2024-06-14 12:00 UTC
    private val dayMs = 86_400_000L

    private val categories = listOf(
        Category(id = 1L, name = "Food", icon = "F", color = "#111111"),
        Category(id = 2L, name = "Travel", icon = "T", color = "#222222")
    )

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = now
    }

    private val period = PeriodRange(
        kind = PeriodKind.CUSTOM,
        startInclusiveMillis = txDate - 10 * dayMs,
        endExclusiveMillis = now,
        label = "TEST"
    )

    private val categoryRepository = mockk<CategoryRepository>().also {
        coEvery { it.getAll() } returns categories
    }

    private val engine = AdvancedAnalyticsEngine(
        mockk<ExpenseRepository>(),
        categoryRepository,
        mockk<BudgetRepository>(),
        TestCurrencySettingsRepository(),
        testAnalyticsCurrencyNormalizer(),
        timeProvider,
        Dispatchers.Unconfined,
        Dispatchers.Unconfined
    )

    private val assembler = AnalyticsInputAssembler(
        mockk<ExpenseRepository>(),
        testAnalyticsCurrencyNormalizer(),
        TestCurrencySettingsRepository(),
        timeProvider,
        categoryRepository
    )

    private fun purchase(id: Long, amount: Double, currency: String, categoryId: Long?) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Merchant$id",
        transactionType = TransactionType.PURCHASE,
        date = txDate,
        categoryId = categoryId
    )

    private suspend fun categoryAnalytics(expenses: List<Expense>): Pair<NormalizedAnalyticsInput, List<EnhancedCategoryAnalytics>> {
        val input = assembler.build(expenses, "EUR", period)
        val (enhanced, _) = engine.getCategoryAnalytics(input, null, categories, emptyList())
        return input to enhanced
    }

    @Test
    fun `N-08-7 control all rows convertible keeps both categories with exact totals`() = runTest {
        val (_, enhanced) = categoryAnalytics(
            listOf(purchase(1, 100.0, "EUR", 1L), purchase(2, 40.0, "EUR", 2L))
        )

        assertEquals(100.0, enhanced.single { it.category.id == 1L }.totalSpent, 0.001)
        assertEquals(40.0, enhanced.single { it.category.id == 2L }.totalSpent, 0.001)
    }

    @Test
    fun `N-08-7 USD-only Travel without a rate does not fail the section and Food keeps its real amount`() = runTest {
        val (input, enhanced) = categoryAnalytics(
            listOf(purchase(1, 100.0, "EUR", 1L), purchase(2, 80.0, "USD", 2L))
        )

        val food = enhanced.single { it.category.id == 1L }
        assertEquals(100.0, food.totalSpent, 0.001)
        val foodMoney = food.totalMoneyResult
        assertTrue("Food must be Available", foodMoney is MoneyAggregateResult.Available)
        assertFalse(
            "Travel's missing rate must not make Food partial",
            (foodMoney as MoneyAggregateResult.Available).aggregate.isPartial
        )
        // Travel has no convertible rows: it must not be fabricated as a 0 card ...
        assertTrue(
            "Unconvertible Travel must not appear as a fabricated available card",
            enhanced.none { it.category.id == 2L }
        )
        // ... and the unconvertible row stays on the input so the screen can list Travel as unavailable.
        assertEquals(listOf(2L), input.excludedExpenses.map { it.id })
        assertEquals(2L, input.excludedExpenses.single().categoryId)
    }

    @Test
    fun `N-08-7 uncategorized excluded row does not throw and Food stays Available`() = runTest {
        val (input, enhanced) = categoryAnalytics(
            listOf(purchase(1, 100.0, "EUR", 1L), purchase(3, 25.0, "USD", null))
        )

        assertEquals(1, input.excludedExpenses.size)
        val food = enhanced.single { it.category.id == 1L }
        assertEquals(100.0, food.totalSpent, 0.001)
        val money = food.totalMoneyResult
        assertTrue(money is MoneyAggregateResult.Available)
        assertFalse(
            "An uncategorized exclusion belongs to the uncategorized group, not to Food",
            (money as MoneyAggregateResult.Available).aggregate.isPartial
        )
    }

    @Test
    fun `N-08-7 category with one convertible and one unconvertible row keeps converted amount flagged partial`() = runTest {
        val (_, enhanced) = categoryAnalytics(
            listOf(
                purchase(1, 100.0, "EUR", 1L),
                purchase(2, 20.0, "EUR", 2L),
                purchase(4, 80.0, "USD", 2L)
            )
        )

        val travel = enhanced.single { it.category.id == 2L }
        assertEquals("only the converted 20 EUR is summed, USD 80 is not guessed", 20.0, travel.totalSpent, 0.001)
        val money = travel.totalMoneyResult
        assertNotNull(money)
        assertTrue((money as MoneyAggregateResult.Available).aggregate.isPartial)
        val food = enhanced.single { it.category.id == 1L }
        assertEquals(100.0, food.totalSpent, 0.001)
        assertFalse((food.totalMoneyResult as MoneyAggregateResult.Available).aggregate.isPartial)
    }

    private val previousPeriod = PeriodRange(
        kind = PeriodKind.CUSTOM,
        startInclusiveMillis = period.startInclusiveMillis - 10 * dayMs,
        endExclusiveMillis = period.startInclusiveMillis,
        label = "PREV"
    )

    private fun previousPurchase(id: Long, amount: Double, currency: String, categoryId: Long?) =
        purchase(id, amount, currency, categoryId).copy(date = previousPeriod.startInclusiveMillis + dayMs)

    private suspend fun categoryAnalyticsWithPreviousAndBudget(
        current: List<Expense>,
        previous: List<Expense>?,
        budgets: List<BudgetSnapshot> = emptyList()
    ): List<EnhancedCategoryAnalytics> {
        val currentInput = assembler.build(current, "EUR", period)
        val previousInput = previous?.let { assembler.build(it, "EUR", previousPeriod) }
        return engine.getCategoryAnalytics(currentInput, previousInput, categories, budgets).first
    }

    @Test
    fun `F-partial control complete previous and current rows give an exact change percent`() = runTest {
        val food = categoryAnalyticsWithPreviousAndBudget(
            current = listOf(purchase(1, 150.0, "EUR", 1L)),
            previous = listOf(previousPurchase(10, 100.0, "EUR", 1L))
        ).single { it.category.id == 1L }

        assertEquals(150.0, food.totalSpent, 0.001)
        assertNotNull(food.changePercent)
        assertEquals(50.0f, food.changePercent!!, 0.01f)
    }

    @Test
    fun `F-partial previous period with an unrated USD row has no change percent`() = runTest {
        val food = categoryAnalyticsWithPreviousAndBudget(
            current = listOf(purchase(1, 150.0, "EUR", 1L)),
            previous = listOf(
                previousPurchase(10, 100.0, "EUR", 1L),
                previousPurchase(11, 100.0, "USD", 1L)
            )
        ).single { it.category.id == 1L }

        assertEquals(150.0, food.totalSpent, 0.001)
        assertNull("a partial previous total must not yield a fabricated percent", food.changePercent)
    }

    @Test
    fun `F-partial control complete current total against a 200 budget gives 75 percent and 50 remaining`() = runTest {
        val food = categoryAnalyticsWithPreviousAndBudget(
            current = listOf(purchase(1, 150.0, "EUR", 1L)),
            previous = null,
            budgets = listOf(BudgetSnapshot(categoryId = 1L, amount = 200.0, currency = "EUR"))
        ).single { it.category.id == 1L }

        assertEquals(75.0f, food.budgetUtilizationPercent!!, 0.01f)
        assertEquals(50.0, food.budgetRemaining!!, 0.001)
        assertNotNull(food.budgetStatus)
    }

    @Test
    fun `F-partial current total with an unrated USD row has no budget utilization status or remaining`() = runTest {
        val food = categoryAnalyticsWithPreviousAndBudget(
            current = listOf(purchase(1, 150.0, "EUR", 1L), purchase(2, 100.0, "USD", 1L)),
            previous = null,
            budgets = listOf(BudgetSnapshot(categoryId = 1L, amount = 200.0, currency = "EUR"))
        ).single { it.category.id == 1L }

        assertEquals("only the converted 150 EUR is summed", 150.0, food.totalSpent, 0.001)
        assertNull(food.budgetUtilizationPercent)
        assertNull(food.budgetStatus)
        assertNull(food.budgetRemaining)
    }
}
