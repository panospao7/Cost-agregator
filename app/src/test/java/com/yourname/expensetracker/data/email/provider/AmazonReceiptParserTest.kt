package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AmazonReceiptParserTest {

    private val parser = AmazonReceiptParser()

    @Test
    fun `parse handles standalone localized date and comma decimal amount`() {
        val receipt = parser.parse(
            emailBody = """
                <html>
                <body>
                <div>Amazon.de Bestellung</div>
                <div>Order Total: €12,34</div>
                <div>15 mars 2026</div>
                </body>
                </html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(12.34, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
    }

    // -------------------------------------------------------------------------
    // RP-18 18-A: label hierarchy, strict total grammar, currency resolution.
    // -------------------------------------------------------------------------

    @Test
    fun `parse prefers specific labelled totals over subtotal and bare total`() {
        val receipt = parser.parse(
            emailBody = """
                <html><body>
                <div>Amazon.com order</div>
                <div>Subtotal: ${'$'}40.00</div>
                <div>Total: ${'$'}12.00</div>
                <div>Grand Total: ${'$'}45.90</div>
                </body></html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(45.90, receipt!!.amount, 0.001)
    }

    @Test
    fun `parse does not treat total-items lines or subtotal as the order total`() {
        val receipt = parser.parse(
            emailBody = """
                <html><body>
                <div>Amazon.com order</div>
                <div>Subtotal: ${'$'}40.00</div>
                <div>Total items: 3</div>
                <div>Total: ${'$'}45.90</div>
                </body></html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(45.90, receipt!!.amount, 0.001)
    }

    @Test
    fun `parse ignores vat annotation and selects the actual total`() {
        val receipt = parser.parse(
            emailBody = """
                <html><body>
                <div>Amazon.de Bestellung</div>
                <div>Total inkl. 19% MwSt: 23,80 €</div>
                </body></html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(23.80, receipt!!.amount, 0.001)
        assertEquals("EUR", receipt.currency)
    }

    @Test
    fun `parse ignores unit price forms when selecting the total`() {
        val receipt = parser.parse(
            emailBody = """
                <html><body>
                <div>Amazon.de Bestellung</div>
                <div>2 x 5,00 €</div>
                <div>Your order total is 45,90 €</div>
                </body></html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(45.90, receipt!!.amount, 0.001)
    }

    @Test
    fun `parse excludes total-tainted and summary lines from items`() {
        val receipt = parser.parse(
            emailBody = """
                <html><body>
                <div>Total order surcharge 2 5.00</div>
                <div>Widget Blue Qty: 2 10.00</div>
                <div>Order Total: ${'$'}45.90</div>
                </body></html>
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(45.90, receipt!!.amount, 0.001)
        assertEquals(
            "Summary/total lines must never become line items",
            1,
            receipt.items.size
        )
        assertTrue(
            "The remaining item must be the real line item",
            receipt.items.first().description.startsWith("Widget Blue")
        )
    }

    @Test
    fun `parse skips with CURRENCY_UNRESOLVED when no trusted currency signal exists`() {
        val body = """
            <html><body>
            <div>Order Total: 45,90</div>
            <div>Order # 302-1234567-9876543</div>
            <div>Track it online or contact us for details.</div>
            </body></html>
        """.trimIndent()

        val outcome = parser.parseWithOutcome(emailBody = body, receivedAt = 0L)

        assertTrue(
            "Expected CURRENCY_UNRESOLVED skip, got $outcome",
            outcome is EmailParseOutcome.Skipped &&
                outcome.reason == EmailParseSkipReason.CURRENCY_UNRESOLVED
        )
        assertNull(
            "Skipped parse must surface as null from parse()",
            parser.parse(emailBody = body, receivedAt = 0L)
        )
    }

    private fun expectedLocalDateMillis(value: String): Long {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
