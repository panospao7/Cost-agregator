package com.yourname.expensetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationProcessingPipelineOversizedAmountTest {

    @Test
    fun `routes oversized transaction-like notification to review candidate`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Paid EUR 1,200,000.00 at ACME Stores",
            text = "Card transaction approved",
            bigText = null
        )

        assertNotNull(candidate)
        assertTrue(candidate!!.amount > 1_000_000.0)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `ignores oversized number without transaction and currency context`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Order id 1200000 updated",
            text = "System notification",
            bigText = null
        )
        assertNull(candidate)
    }

    @Test
    fun `does not route normal high but valid amounts`() {
        val candidate = NotificationProcessingPipeline.detectOversizedAmountCandidate(
            title = "Paid EUR 999,999.99 at Store",
            text = "Card transaction",
            bigText = null
        )
        assertNull(candidate)
    }
}
