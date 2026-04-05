package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RevolutParserTest {
    private lateinit var parser: RevolutParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { 
                val symbol = firstArg<String?>()
                when (symbol) {
                    "€" -> "EUR"
                    "$" -> "USD"
                    "£" -> "GBP"
                    else -> symbol ?: "EUR"
                }
            }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { 
                var name = firstArg<String?>() ?: "Unknown"
                if (name.length > 40) name = name.substring(0, 40)
                name.removeSuffix(".")
            }
        }
        parser = RevolutParser(currencyNormalizer, merchantCleaner)
    }

    // === PURCHASE PARSING ===

    @Test
    fun `parse standard purchase with euro symbol`() {
        val result = parser.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `parse purchase with comma decimal separator`() {
        val result = parser.parse(
            title = "Paid €8,99 at Netflix",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(8.99, result!!.amount, 0.01)
        assertEquals("Netflix", result.merchant)
    }

    @Test
    fun `parse purchase with USD currency`() {
        val result = parser.parse(
            title = "Paid $25.00 at Amazon",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `parse purchase with GBP currency`() {
        val result = parser.parse(
            title = "Paid £15.00 at Tesco",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals("GBP", result!!.currency)
    }

    @Test
    fun `parse sent to person`() {
        val result = parser.parse(
            title = "Sent €5.00 to John",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(5.00, result!!.amount, 0.01)
        assertEquals("John", result.merchant)
        assertEquals(ParsedTransactionType.TRANSFER, result.type)
    }

    // === DEPOSIT PARSING ===

    @Test
    fun `parse received money`() {
        val result = parser.parse(
            title = "Received €100.00 from Maria",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(100.00, result!!.amount, 0.01)
        assertEquals("Maria", result.merchant)
        assertEquals(ParsedTransactionType.TRANSFER, result.type)
    }

    // === ATM PARSING ===

    @Test
    fun `parse ATM withdrawal`() {
        val result = parser.parse(
            title = "ATM withdrawal: €50.00",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.01)
        assertEquals("ATM Withdrawal", result.merchant)
        assertEquals(ParsedTransactionType.WITHDRAWAL, result.type)
    }

    // === REJECTION TESTS ===

    @Test
    fun `reject exchange rate notification`() {
        val result = parser.parse(
            title = "Your exchange rate for EUR/USD has changed",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject weekly report`() {
        val result = parser.parse(
            title = "Your weekly report is ready",
            text = "You spent €150 this week",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject special offer`() {
        val result = parser.parse(
            title = "Special offer: Get cashback!",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject security notification`() {
        val result = parser.parse(
            title = "Security alert",
            text = "Please verify your identity",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject savings vault notification`() {
        val result = parser.parse(
            title = "Savings vault update",
            text = "Your savings vault has reached €500",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    // === EDGE CASES ===

    @Test
    fun `handle null title and text`() {
        val result = parser.parse(
            title = null, text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `handle empty strings`() {
        val result = parser.parse(
            title = "", text = "", bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `merchant name truncated at 40 chars`() {
        val result = parser.parse(
            title = "Paid €10.00 at THIS IS A VERY LONG MERCHANT NAME THAT EXCEEDS FORTY CHARACTERS EASILY",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.length <= 40)
    }

    @Test
    fun `merchant cleaned of trailing punctuation`() {
        val result = parser.parse(
            title = "Paid €10.00 at Starbucks.",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.endsWith("."))
    }

    // === SUPPORTED PACKAGES ===

    @Test
    fun `only supports revolut package`() {
        assertEquals(setOf("com.revolut.revolut"), parser.supportedPackages)
    }
}
