package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.BudgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetVsActualEngineTest {

    private val engine = BudgetVsActualEngine()

    @Test
    fun `overall budget actual includes categorized and uncategorized purchases without double counting totals`() {
        val actuals = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(
                expense(id = 1L, amount = 75.0, categoryId = 1L, categoryName = "Food"),
                expense(id = 2L, amount = 25.0, categoryId = 2L, categoryName = "Travel"),
                expense(id = 3L, amount = 10.0, categoryId = null, categoryName = "Other")
            )
        )

        val result = engine.compute(
            actuals = actuals,
            budgets = listOf(
                BudgetSnapshot(categoryId = null, amount = 100.0, currency = "EUR"),
                BudgetSnapshot(categoryId = 1L, amount = 80.0, currency = "EUR")
            ),
            homeCurrency = "EUR"
        )

        assertEquals(110.0, result.items.single { it.categoryId == null }.actualSpent, 0.001)
        assertEquals(75.0, result.items.single { it.categoryId == 1L }.actualSpent, 0.001)
        assertEquals(100.0, result.totalBudget, 0.001)
        assertEquals(110.0, result.totalActual, 0.001)
    }

    private fun expense(
        id: Long,
        amount: Double,
        categoryId: Long?,
        categoryName: String
    ) = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = "EUR",
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        date = 1_700_000_000_000L,
        merchant = "Merchant $id",
        merchantKey = null,
        categoryId = categoryId,
        categoryNameSnapshot = categoryName,
        transactionType = "PURCHASE",
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = null
    )
}
