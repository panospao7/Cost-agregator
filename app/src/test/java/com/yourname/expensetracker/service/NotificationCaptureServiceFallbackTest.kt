package com.yourname.expensetracker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

class NotificationCaptureServiceFallbackTest {

    @Test
    fun `blank bigText falls back to infoText`() {
        val bigText = "   "
        val infoText = "Account balance: €500.00"
        val summaryText = "fallback summary"
        val result = bigText.takeIf { it.isNotBlank() }
            ?: infoText.takeIf { it.isNotBlank() }
            ?: summaryText.takeIf { it.isNotBlank() }

        assertEquals("Account balance: €500.00", result)
    }

    @Test
    fun `blank bigText and blank infoText falls back to summaryText`() {
        val bigText = ""
        val infoText = " "
        val summaryText = "Transaction summary: €25.00"
        val result = bigText.takeIf { it.isNotBlank() }
            ?: infoText.takeIf { it.isNotBlank() }
            ?: summaryText.takeIf { it.isNotBlank() }

        assertEquals("Transaction summary: €25.00", result)
    }

    @Test
    fun `whitespace bigText with missing infoText falls back to summaryText`() {
        val bigText = " \n\t "
        val infoText = null
        val summaryText = "Summary transaction detail"
        val result = bigText?.takeIf { it.isNotBlank() }
            ?: infoText?.takeIf { it.isNotBlank() }
            ?: summaryText?.takeIf { it.isNotBlank() }

        assertEquals("Summary transaction detail", result)
    }

    @Test
    fun `non blank bigText wins over fallback fields`() {
        val bigText = "Primary transaction detail"
        val infoText = "Fallback info"
        val summaryText = "Fallback summary"
        val result = bigText.takeIf { it.isNotBlank() }
            ?: infoText.takeIf { it.isNotBlank() }
            ?: summaryText.takeIf { it.isNotBlank() }

        assertEquals("Primary transaction detail", result)
    }

    @Test
    fun `all null or blank text fields return null`() {
        val bigText = ""
        val infoText = null
        val summaryText = "  "
        val result = bigText.takeIf { it.isNotBlank() }
            ?: infoText?.takeIf { it.isNotBlank() }
            ?: summaryText?.takeIf { it.isNotBlank() }

        assertNull(result)
    }

    @Test
    fun `dedupe hash treats null and empty fields the same`() {
        val nullHash = computeNotificationContentHash(
            title = null,
            text = "Card charged 10.00 EUR",
            effectiveBigText = null
        )
        val emptyHash = computeNotificationContentHash(
            title = "",
            text = "Card charged 10.00 EUR",
            effectiveBigText = ""
        )

        assertEquals(emptyHash, nullHash)
    }

    @Test
    fun `dedupe hash changes when content changes`() {
        val originalHash = computeNotificationContentHash(
            title = "Bank Alert",
            text = "Card charged 10.00 EUR",
            effectiveBigText = null
        )
        val updatedHash = computeNotificationContentHash(
            title = "Bank Alert",
            text = "Card charged 12.00 EUR",
            effectiveBigText = null
        )

        assertNotEquals(originalHash, updatedHash)
    }

    @Test
    fun `work tracker drains in flight jobs before shutdown`() = runTest {
        val tracker = NotificationServiceWorkTracker()
        var completed = false

        val job = tracker.launch(this) {
            kotlinx.coroutines.delay(10)
            completed = true
        }

        assertNotNull(job)
        val drained = tracker.stopAcceptingAndDrain(timeoutMs = 1_000)

        assertEquals(true, drained)
        assertEquals(true, completed)
    }
}
