package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RP-05 review follow-up (P5-012): the dashboard "Uncategorized"
 * pseudo-category (reserved id 0L) must never be forwarded as a real
 * TransactionFilter.categoryId -- ExpenseDao would query categoryId=0 and return
 * an empty list. Null is the filter's unfiltered-by-category semantic.
 */
class TopCategoryTransactionFilterTest {

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

    @Test
    fun `uncategorized top row must not produce a categoryId zero filter`() {
        val top = spending(id = 0L, isUncategorized = true)
        assertEquals(null, topCategoryFilterId(top))
    }

    @Test
    fun `real category top row filters by its id`() {
        val top = spending(id = 42L)
        assertEquals(42L, topCategoryFilterId(top))
    }
}
