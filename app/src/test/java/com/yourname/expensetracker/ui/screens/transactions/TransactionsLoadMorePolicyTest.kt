package com.yourname.expensetracker.ui.screens.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * loan-installments S12a F3: the load-more predicate. Filter off must equal the pre-S12a rule
 * (all-tab, near the end, not loading more, not at the end; isLoading is NOT consulted). Filter on
 * keeps paging regardless of scroll position, but never while the initial load or a refresh runs.
 */
class TransactionsLoadMorePolicyTest {

    private fun policy(
        isAllTab: Boolean = true,
        filterOn: Boolean,
        nearEnd: Boolean = true,
        isLoading: Boolean = false,
        isLoadingMore: Boolean = false,
        hasReachedEnd: Boolean = false
    ) = TransactionsLoadMorePolicy.shouldLoadMore(isAllTab, filterOn, nearEnd, isLoading, isLoadingMore, hasReachedEnd)

    @Test
    fun `filter off equals the original rule for every combination`() {
        val bools = listOf(false, true)
        for (allTab in bools) for (near in bools) for (loading in bools) for (more in bools) for (end in bools) {
            val original = allTab && near && !more && !end
            assertEquals(
                "allTab=$allTab near=$near loading=$loading more=$more end=$end",
                original,
                policy(isAllTab = allTab, filterOn = false, nearEnd = near, isLoading = loading, isLoadingMore = more, hasReachedEnd = end)
            )
        }
    }

    @Test
    fun `filter on pages until the end but waits for the initial load or refresh`() {
        val bools = listOf(false, true)
        for (near in bools) for (loading in bools) for (more in bools) for (end in bools) {
            val expected = !loading && !more && !end
            assertEquals(
                "near=$near loading=$loading more=$more end=$end",
                expected,
                policy(filterOn = true, nearEnd = near, isLoading = loading, isLoadingMore = more, hasReachedEnd = end)
            )
        }
    }

    @Test
    fun `other tabs never page in either mode`() {
        assertEquals(false, policy(isAllTab = false, filterOn = true))
        assertEquals(false, policy(isAllTab = false, filterOn = false))
    }
}
