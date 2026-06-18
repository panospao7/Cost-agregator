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
            title = "Paid 1,200,000.00 EUR at ACME Stores",
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

    @Test
    fun `detectTransactionSignalCandidate returns candidate for normal transaction-like text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Payment €4.08",
            text = "Transaction completed",
            bigText = null
        )

        assertNotNull(candidate)
        assertEquals(4.08, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `detectTransactionSignalCandidate returns null for non-transaction text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Hello",
            text = "World",
            bigText = null
        )

        assertNull(candidate)
    }

    @Test
    fun `detectTransactionSignalCandidate prefers currency-attached amount over bare numbers`() {
        // Text contains both a bare number (1234 from a masked PAN) and a
        // currency-attached transaction amount (€4.08). The detector should
        // select 4.08, not 1234.
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Card *1234",
            text = "Payment €4.08 completed",
            bigText = null
        )

        assertNotNull(candidate)
        assertEquals(4.08, candidate!!.amount, 0.0001)
    }

    @Test
    fun `detectTransactionSignalCandidate picks amount near transaction keyword`() {
        // Two decimal amounts in the text; the one closer to a transaction
        // keyword should win (€4.08 near "payment" vs 12.34 with no context).
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Balance 12.34€",
            text = "Payment €4.08 at store",
            bigText = null
        )

        assertNotNull(candidate)
        // €4.08 is near "Payment" keyword → higher score → should be selected
        assertEquals(4.08, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `detectTransactionSignalCandidate handles suffix currency and PAN tail`() {
        // Text with a masked PAN (*1234) and a transaction amount with suffix
        // currency. The PAN fragment should be penalised, the real amount
        // with suffix currency ("4 EUR") should win.
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Card *1234",
            text = "Payment 4 EUR completed",
            bigText = null
        )

        assertNotNull(candidate)
        assertEquals(4.0, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }
}
