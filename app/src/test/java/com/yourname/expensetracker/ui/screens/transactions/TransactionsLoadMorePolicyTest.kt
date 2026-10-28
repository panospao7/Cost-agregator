package com.yourname.expensetracker.ui.screens.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun key(
        filterOn: Boolean,
        rows: Int = 20,
        end: Boolean = false,
        loading: Boolean = false
    ) = TransactionsLoadMorePolicy.effectKey(filterOn, rows, end, loading)

    @Test
    fun `effectKey filter off is the constant whatever the paging state`() {
        val constant = key(filterOn = false)
        val bools = listOf(false, true)
        for (rows in listOf(0, 20, 40)) for (end in bools) for (loading in bools) {
            assertEquals("rows=$rows end=$end loading=$loading", constant, key(false, rows, end, loading))
        }
        assertEquals(listOf<Any>(-1), constant)
    }

    @Test
    fun `effectKey filter on is stable across a failed page`() {
        // A failed page flips isLoadingMore only (not an input of the key): rows, end and loading stay.
        val before = key(filterOn = true, rows = 20)
        val afterFailure = key(filterOn = true, rows = 20)
        assertEquals(before, afterFailure)
    }

    @Test
    fun `effectKey filter on changes after a successful page, the end, or a refresh`() {
        val base = key(filterOn = true, rows = 20)
        assertNotEquals("a successful page adds rows", base, key(filterOn = true, rows = 40))
        assertNotEquals("the end was reached", base, key(filterOn = true, rows = 20, end = true))
        assertNotEquals("a refresh runs", base, key(filterOn = true, rows = 20, loading = true))
        assertNotEquals("toggling the filter restarts the effect", key(filterOn = false), base)
    }
}
