package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericTransactionParserStressTest {
    private val parser = GenericTransactionParser(
        currencyNormalizer = CurrencyNormalizer(),
        merchantCleaner = MerchantCleaner(),
        directionDetector = TransferDirectionDetector(, timeProvider = mock())
    )

    @Test
    fun `parses generic purchase with amount and merchant`() {
        val result = parse("Payment of €25.00 at Starbucks")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(25.0, result.amount, 0.001)
        assertTrue(result.merchant.contains("Starbucks", ignoreCase = true))
    }

    @Test
    fun `parses incoming deposit signal as deposit type`() {
        val result = parse("Salary credited €1200.00 from ACME")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
    }

    @Test
    fun `does not reject greek deposit messages containing apo`() {
        val result = parse("Κατάθεση €120,00 από ACME LTD")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(120.0, result.amount, 0.001)
    }

    @Test
    fun `rejects non-financial notification`() {
        val result = parse("Reminder: meeting at 3 PM")
        assertNull(result)
    }

    @Test
    fun `rejects marketing message despite numbers`() {
        val result = parse("Flash sale 50% off - save up to €100")
        assertNull(result)
    }

    @Test
    fun `supports dollar currency normalization`() {
        val result = parse("You paid $10.00 at Walmart")
        assertNotNull(result)
        assertEquals("USD", result!!.currency)
    }

    @Test
    fun `accepts supported european amount format`() {
        val result = parse("Πληρωμή 12,50 EUR στο Μασούτης")
        assertNotNull(result)
        assertEquals(12.5, result!!.amount, 0.001)
    }

    @Test
    fun `accepts one decimal place amount with currency adjacent`() {
        val result = parse("Payment of €12,5 at Cafe")
        assertNotNull(result)
        assertEquals(12.5, result!!.amount, 0.001)
    }

    @Test
    fun `prefers transaction amount when multiple amounts are present`() {
        val result = parse("Available €1.245,00. Payment of €12,5 at Cafe")
        assertNotNull(result)
        assertEquals(12.5, result!!.amount, 0.001)
    }

    @Test
    fun `rejects malformed fraction-like amount`() {
        val result = parse("Payment 1/2 EUR at Store")
        assertNull(result)
    }

    @Test
    fun `is deterministic across repeated parses`() {
        val input = "Transaction of €44.10 at Lidl"
        val a = parse(input)
        val b = parse(input)
        assertEquals(a, b)
    }

    private fun parse(text: String) = parser.parse(
        title = "Alert",
        text = text,
        bigText = null,
        subText = null,
        packageName = "unknown.app"
    )
}