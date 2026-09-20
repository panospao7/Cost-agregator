package com.yourname.expensetracker.data.email.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppleReceiptParserTest {

    private val parser = AppleReceiptParser()

    @Test
    fun `parse handles standalone localized date and comma decimal amount`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT123456789
                Total €9,99
                15 mars 2026
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(9.99, receipt!!.amount, 0.001)
        assertEquals(expectedLocalDateMillis("2026-03-15"), receipt.date)
    }

    @Test
    fun `parse does not infer EUR from incidental substring tokens`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT123456789
                Total ${'$'}9.99
                ORDER DETAILS FOR MUSIC SUBSCRIPTION
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("USD", receipt!!.currency)
        assertNotEquals("EUR", receipt.currency)
    }

    // -------------------------------------------------------------------------
    // NEW-P11-2026-001: raw-string regexes were double-escaped so ORDER_ID_PATTERNS,
    // ITEM_PATTERN, buildMerchantName appPattern, and the \\s+ cleanup regex never
    // matched → orderNumber was always null and items were never extracted.
    // -------------------------------------------------------------------------

    @Test
    fun `parse extracts Order ID as orderNumber`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT123456789
                Total ${'$'}4.99
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("MT123456789", receipt!!.orderNumber)
    }

    @Test
    fun `parse extracts Document No as orderNumber`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Invoice
                Document No: 123456789012
                Total ${'$'}2.49
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("123456789012", receipt!!.orderNumber)
    }

    // NEW-P11-2026-002 residual pass: the HNY hardware-order pattern was still
    // double-escaped (looked for literal backslashes) and never matched. Now
    // unescaped like the rest of ORDER_ID_PATTERNS.
    @Test
    fun `parse extracts HNY hardware order number`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Hardware Order Confirmation
                HNY # 1234567890
                Total ${'$'}1299.00
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("1234567890", receipt!!.orderNumber)
    }

    @Test
    fun `parse extracts item line description and price`() {
        val receipt = parser.parse(
            emailBody = """
                App Store Receipt
                Document No: 555000111222
                App: Fantastic Puzzle Game
                Fantastic  Puzzle Game   ${'$'}4.99
                Total ${'$'}4.99
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertTrue("Expected at least one extracted item", receipt!!.items.isNotEmpty())
        val item = receipt.items.first()
        assertEquals("Fantastic Puzzle Game", item.description)
        assertEquals(4.99, item.unitPrice, 0.001)
        assertEquals(4.99, item.totalPrice, 0.001)
    }

    @Test
    fun `parse builds merchant from App line for App Store purchase`() {
        val receipt = parser.parse(
            emailBody = """
                Invoice for iap purchase
                Document No: 555000111223
                App: Fantastic Puzzle Game
                Total ${'$'}4.99
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals("Apple - Fantastic Puzzle Game", receipt!!.merchant)
    }

    // -------------------------------------------------------------------------
    // NEW-P11-2026-002: canParse must be sender-gated (Amazon/Uber precedent).
    // Body/subject signals alone must never accept non-Apple senders.
    // -------------------------------------------------------------------------

    @Test
    fun `canParse rejects non-Apple sender even with receipt subject and apple com body`() {
        val result = parser.canParse(
            sender = "newsletter@someother-shop.com",
            subject = "Your receipt from SomeOther Shop",
            body = "Thanks for your purchase. Visit apple.com or manage your apple id anytime."
        )

        assertFalse("Non-Apple sender must be rejected even with Apple-looking body", result)
    }

    @Test
    fun `canParse accepts do_not_reply apple com sender`() {
        val result = parser.canParse(
            sender = "do_not_reply@apple.com",
            subject = "Your receipt from Apple.",
            body = "Apple ID: order details below."
        )

        assertTrue("Known Apple sender must be accepted", result)
    }

    @Test
    fun `canParse accepts itunes apple com sender`() {
        val result = parser.canParse(
            sender = "itunes@apple.com",
            subject = "Your invoice from Apple.",
            body = "App Store purchase details below."
        )

        assertTrue("Known Apple sender must be accepted", result)
    }

    @Test
    fun `canParse accepts other Apple domain sender`() {
        val result = parser.canParse(
            sender = "someone@email.apple.com",
            subject = "Your order confirmation",
            body = "Order details below."
        )

        assertTrue("Apple subdomain sender must be accepted", result)
    }

    // -------------------------------------------------------------------------
    // RP-18 18-A: label hierarchy, strict total grammar, currency resolution.
    // -------------------------------------------------------------------------

    @Test
    fun `parse prefers Total Amount label over bare Total`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT777888999
                Total ${'$'}5.00
                Total Amount: ${'$'}8.00
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(8.00, receipt!!.amount, 0.001)
    }

    @Test
    fun `parse ignores vat suffix and selects the actual total`() {
        val receipt = parser.parse(
            emailBody = """
                Apple Services Receipt
                Order ID: MT998877665
                Total incl. 19% VAT: 23,80 €
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(23.80, receipt!!.amount, 0.001)
        assertEquals("EUR", receipt.currency)
    }

    @Test
    fun `parse does not select unit price forms as the total`() {
        val receipt = parser.parse(
            emailBody = """
                App Store Receipt
                Document No: 555000222333
                App: Note Taking App
                2 @ ${'$'}5.00
                Total ${'$'}10.00
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(10.00, receipt!!.amount, 0.001)
    }

    @Test
    fun `parse excludes aggregate summary rows from items`() {
        val receipt = parser.parse(
            emailBody = """
                App Store Receipt
                Document No: 555000999888
                App: Puzzle Pack
                Puzzle Pack 3 items ${'$'}12.34
                Total ${'$'}12.34
                apple.com/bill
            """.trimIndent(),
            receivedAt = 0L
        )

        assertNotNull(receipt)
        assertEquals(12.34, receipt!!.amount, 0.001)
        assertTrue(
            "Aggregate summary rows must never become line items",
            receipt.items.isEmpty()
        )
    }

    @Test
    fun `parse does not infer currency from everyday words it us de`() {
        val body = """
            Apple Services Receipt
            Order ID: MT111222333
            Total 7,00
            Read it, contact us, check de support page.
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

    @Test
    fun `parse skips with CURRENCY_UNRESOLVED when no currency signal exists`() {
        val body = """
            Apple Services Receipt
            Order ID: MT444555666
            Total 12,00
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
