package com.yourname.expensetracker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCaptureServiceFallbackTest {

    @Test
    fun `blank bigText falls back to infoText`() {
        val result = resolveEffectiveBigText(
            bigText = "   ",
            infoText = "Account balance: €500.00",
            summaryText = "fallback summary"
        )

        assertEquals("Account balance: €500.00", result)
    }

    @Test
    fun `blank bigText and blank infoText falls back to summaryText`() {
        val result = resolveEffectiveBigText(
            bigText = "",
            infoText = " ",
            summaryText = "Transaction summary: €25.00"
        )

        assertEquals("Transaction summary: €25.00", result)
    }

    @Test
    fun `whitespace bigText with missing infoText falls back to summaryText`() {
        val result = resolveEffectiveBigText(
            bigText = " \n\t ",
            infoText = null,
            summaryText = "Summary transaction detail"
        )

        assertEquals("Summary transaction detail", result)
    }

    @Test
    fun `non blank bigText wins over fallback fields`() {
        val result = resolveEffectiveBigText(
            bigText = "Primary transaction detail",
            infoText = "Fallback info",
            summaryText = "Fallback summary"
        )

        assertEquals("Primary transaction detail", result)
    }

    @Test
    fun `all null or blank text fields return null`() {
        val result = resolveEffectiveBigText(
            bigText = "",
            infoText = null,
            summaryText = "  "
        )

        assertNull(result)
    }

    @Test
    fun `dedupe hash treats null and empty fields the same`() {
        val nullHash = computeNotificationContentHash(
            title = null,
            text = "Card charged 10.00 EUR",
            bigText = null
        )
        val emptyHash = computeNotificationContentHash(
            title = "",
            text = "Card charged 10.00 EUR",
            bigText = ""
        )

        assertEquals(emptyHash, nullHash)
    }

    @Test
    fun `dedupe hash changes when content changes`() {
        val originalHash = computeNotificationContentHash(
            title = "Bank Alert",
            text = "Card charged 10.00 EUR",
            bigText = null
        )
        val updatedHash = computeNotificationContentHash(
            title = "Bank Alert",
            text = "Card charged 12.00 EUR",
            bigText = null
        )

        assertNotEquals(originalHash, updatedHash)
    }
}
