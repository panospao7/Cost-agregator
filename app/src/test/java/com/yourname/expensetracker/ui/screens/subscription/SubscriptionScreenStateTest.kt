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
}
