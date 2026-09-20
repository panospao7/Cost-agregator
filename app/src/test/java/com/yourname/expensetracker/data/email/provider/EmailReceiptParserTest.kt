package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EmailReceiptParserTest {

    private val parser = TestEmailParser()

    @Test
    fun `cleanHtml preserves meaningful line breaks and decodes entities`() {
        val html = """
            <div>Order&nbsp;Total:&nbsp;&euro;12,34</div>
            <p>Line&#10;Two &amp; Three</p>
            <table><tr><td>Item</td><td>Qty</td></tr></table>
        """.trimIndent()

        val cleaned = parser.exposeCleanHtml(html)

        assertEquals("Order Total: €12,34\n\nLine\nTwo & Three\n\nItem Qty", cleaned)
    }

    @Test
    fun `parseLocalizedAmount handles comma decimal and grouped values`() {
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("€1.234,56") ?: 0.0, 0.001)
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("1 234,56 EUR") ?: 0.0, 0.001)
        assertEquals(1234.56, parser.exposeParseLocalizedAmount("1,234.56") ?: 0.0, 0.001)
    }

    @Test
    fun `parseLocalizedDate supports non english month names`() {
        val parsed = parser.exposeParseLocalizedDate("15 mars 2026")

        assertNotNull(parsed)
        assertEquals(expectedSystemZoneMillis("yyyy-MM-dd", "2026-03-15"), parsed)
    }

    // -------------------------------------------------------------------------
    // RP-18 18-A: shared total-label hierarchy, strict total grammar,
    // currency resolution and summary-row exclusion.
    // -------------------------------------------------------------------------

    @Test
    fun `extractTotalAmount follows specific generic keyword hierarchy`() {
        assertEquals(
            50.00,
            parser.exposeExtractTotalAmount(
                "Subtotal: \$40.00\nTotal: \$45.90\nGrand Total: \$50.00",
                listOf("grand total")
            ) ?: 0.0,
            0.001
        )
        // Mid-line "Your Total:" is covered by the word-bounded keyword fallback
        assertEquals(
            12.34,
            parser.exposeExtractTotalAmount("Your Total: \$12.34") ?: 0.0,
            0.001
        )
        // "Subtotal" and value-less "Total items" lines are never selected
        assertNull(parser.exposeExtractTotalAmount("Subtotal: \$40.00"))
        assertNull(parser.exposeExtractTotalAmount("Total items: 3"))
    }

    @Test
    fun `extractTotalAmount rejects vat percentages and unit price forms`() {
        assertEquals(
            23.80,
            parser.exposeExtractTotalAmount("Total incl. 19% VAT: 23,80 €") ?: 0.0,
            0.001
        )
        assertNull(parser.exposeExtractTotalAmount("Total 19,00% discount"))
        assertNull(parser.exposeExtractTotalAmount("2 x 5,00 €"))
        assertNull(parser.exposeExtractTotalAmount("2 @ \$5.00"))
        // The qty-marker exclusion must also protect a line that BEARS the
        // total keyword: a unit-price form directly after "Total" is not one.
        assertNull(parser.exposeExtractTotalAmount("Total: 2 x 5,00 €"))
        assertNull(parser.exposeExtractTotalAmount("Total: 2 @ \$5.00"))
    }

    @Test
    fun `detectCurrencyCode uses symbols bounded iso and trusted domains only`() {
        assertEquals("EUR", parser.exposeDetectCurrency("Total €12,34"))
        assertEquals("GBP", parser.exposeDetectCurrency("Total £5.00"))
        assertEquals("USD", parser.exposeDetectCurrency("Total \$6.00"))
        assertEquals("EUR", parser.exposeDetectCurrency("Total 12,34 EUR"))
        assertEquals("USD", parser.exposeDetectCurrency("invoice total 5 usd"))
        assertEquals(
            "EUR",
            parser.exposeDetectCurrency("checkout at amazon.de", mapOf("amazon.de" to "EUR"))
        )
        // Everyday two-letter words are not currency signals
        assertNull(parser.exposeDetectCurrency("read it, contact us, from de — total 12,00"))
        // Conflicting ISO codes fail closed
        assertNull(parser.exposeDetectCurrency("USD or EUR"))
        // Untrusted domains are ignored
        assertNull(parser.exposeDetectCurrency("visit food.de", mapOf("amazon.de" to "EUR")))
    }

    @Test
    fun `isSummaryRow detects aggregate item count lines`() {
        assertTrue(parser.exposeIsSummaryRow("3 items, total 45,90"))
        assertTrue(parser.exposeIsSummaryRow("Puzzle Pack 3 items \$12.34"))
        assertTrue(parser.exposeIsSummaryRow("2 Artikel"))
        assertFalse(parser.exposeIsSummaryRow("Widget Blue"))
        assertFalse(parser.exposeIsSummaryRow("LEGO Classic 3 in 1 \$29.99"))
    }

    private fun expectedSystemZoneMillis(pattern: String, value: String): Long {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.US))
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private class TestEmailParser : BaseEmailParser() {
        override fun canParse(sender: String, subject: String, body: String): Boolean = false

        override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? = null

        fun exposeCleanHtml(text: String): String = cleanHtml(text)

        fun exposeParseLocalizedAmount(text: String): Double? = parseLocalizedAmount(text)

        fun exposeParseLocalizedDate(text: String): Long? = parseLocalizedDate(text)

        fun exposeExtractTotalAmount(
            text: String,
            specificLabels: List<String> = emptyList()
        ): Double? = extractTotalAmount(text, specificLabels)

        fun exposeDetectCurrency(
            text: String,
            trustedDomains: Map<String, String> = emptyMap()
        ): String? = detectCurrencyCode(text, trustedDomains)

        fun exposeIsSummaryRow(text: String): Boolean = isSummaryRow(text)
    }
}
