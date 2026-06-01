package com.yourname.expensetracker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

}
