package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppParserRegistryRoutingTest {
    private lateinit var registry: AppParserRegistry

    private val currencyNormalizer = io.mockk.mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        io.mockk.every { normalize(any()) } returns "EUR"
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    private val directionDetector = io.mockk.mockk<com.yourname.expensetracker.domain.parser.TransferDirectionDetector> {
        io.mockk.every { detectDirection(any(), any(), any(), any()) } returns null
        io.mockk.every { extractAccountName(any(), any(), any()) } returns null
    }

    @Before
    fun setup() {
        registry = AppParserRegistry(
            greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner),
            revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
            smsParser = SmsParser(currencyNormalizer, merchantCleaner),
            googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
            genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector),
            aiFallbackParser = io.mockk.mockk()
        )
    }

    @Test
    fun `routes revolut package to RevolutParser`() {
        val result = registry.parse(
            title = "Paid €10.00 at Shop",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `routes google wallet to GoogleWalletParser`() {
        val result = registry.parse(
            title = "Shop Name",
            text = "€5.00 at Shop Name",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertTrue(result.confidence >= 0.85f)
    }

    @Test
    fun `routes greek bank to GreekBankParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `routes unknown package to GenericTransactionParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "You paid €20.00 at Restaurant",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertTrue(result.confidence >= 0.60f)
    }

    @Test
    fun `falls back to generic when package parser returns null`() {
        val result = registry.parse(
            title = "Payment update",
            text = "Payment of €12,5 at Cafe",
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.5, result.amount, 0.01)
    }

    @Test
    fun `returns null when no parser matches`() {
        val result = registry.parse(
            title = "Hello",
            text = "How are you?",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNull(result)
    }
}
