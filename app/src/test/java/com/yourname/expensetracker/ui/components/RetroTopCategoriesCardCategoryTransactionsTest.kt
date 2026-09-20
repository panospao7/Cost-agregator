package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RP-05 review follow-up (P5-012): the RetroTopCategoriesCard detail dialog for
 * the "Uncategorized" pseudo-category (reserved id 0L) must resolve the
 * null-categoryId transactions its bucket was built from -- a plain
 * categoryId == 0L match returns zero rows.
 */
class RetroTopCategoriesCardCategoryTransactionsTest {

    private fun spending(id: Long, isUncategorized: Boolean = false) = CategorySpending(
        category = CategoryInfo(
            id = id,
            name = if (isUncategorized) "Uncategorized" else "C$id",
            icon = "?",
            color = "#808080",
            isIncome = false
        ),
        total = 10.0,
        percentage = 100f,
        currency = "EUR",
        isUncategorized = isUncategorized
    )

    private fun expense(id: Long, categoryId: Long?) = DashboardExpense(
        id = id,
        amount = 5.0,
        effectiveAmount = 5.0,
        merchant = "M$id",
        transactionType = DashboardTransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        categoryId = categoryId,
        isNotMine = false,
        isManualEntry = false
    )

    @Test
    fun `uncategorized pseudo-category resolves null-categoryId transactions`() {
        val pseudo = spending(id = 0L, isUncategorized = true)
        val transactions = listOf(
            expense(1L, categoryId = null),
            expense(2L, categoryId = 5L),
            // A 0L categoryId row is NOT the uncategorized convention -- it must
            // not leak into the pseudo-category's dialog by id collision.
            expense(3L, categoryId = 0L)
        )

        val resolved = transactionsForCategory(transactions, pseudo)

        assertEquals(listOf(1L), resolved.map { it.id })
    }

    @Test
    fun `real category resolves its own rows only`() {
        val category = spending(id = 5L)
        val transactions = listOf(
            expense(1L, categoryId = null),
            expense(2L, categoryId = 5L),
            expense(3L, categoryId = 7L)
        )

        val resolved = transactionsForCategory(transactions, category)

        assertEquals(listOf(2L), resolved.map { it.id })
    }
}
