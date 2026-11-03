package com.yourname.expensetracker.ui.screens.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionScreenStateTest {

    @Test
    fun `empty state shows when no subscriptions and no candidates`() {
        assertTrue(showEmptyState(emptyList<String>(), emptyList<String>()))
    }

    @Test
    fun `detected candidates alone suppress the empty state`() {
        assertFalse(showEmptyState(emptyList<String>(), listOf("candidate")))
    }

    @Test
    fun `subscriptions alone suppress the empty state`() {
        assertFalse(showEmptyState(listOf("sub"), emptyList<String>()))
    }

    @Test
    fun `subscriptions and candidates suppress the empty state`() {
        assertFalse(showEmptyState(listOf("sub"), listOf("candidate")))
    }

    @Test
    fun `total card is hidden with no active subscriptions even when candidates exist`() {
        assertFalse(showTotalCostCard(activeCount = 0))
    }

    @Test
    fun `total card shows with at least one active subscription so a conversion failure keeps its message`() {
        assertTrue(showTotalCostCard(activeCount = 1))
        assertTrue(showTotalCostCard(activeCount = 3))
    }
}
