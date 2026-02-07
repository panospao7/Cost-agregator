package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class SourceStatsTest {

    @Test
    fun `trustScore is 0 when no notifications`() {
        val stats = SourceStats("com.test", totalNotifications = 0, acceptedAsExpense = 0)
        assertEquals(0f, stats.trustScore, 0.01f)
    }

    @Test
    fun `trustScore is correct ratio`() {
        val stats = SourceStats("com.test", totalNotifications = 10, acceptedAsExpense = 7)
        assertEquals(0.7f, stats.trustScore, 0.01f)
    }

    @Test
    fun `isLikelySpam true when high volume low accept`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 2)
        assertTrue(stats.isLikelySpam)
    }

    @Test
    fun `isLikelySpam false when low volume`() {
        val stats = SourceStats("com.test", totalNotifications = 5, acceptedAsExpense = 0)
        assertFalse(stats.isLikelySpam)
    }

    @Test
    fun `isLikelySpam false when good trust score`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 80)
        assertFalse(stats.isLikelySpam)
    }
}
