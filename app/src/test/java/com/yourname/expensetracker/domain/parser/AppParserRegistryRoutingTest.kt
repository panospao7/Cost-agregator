package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppParserRegistryRoutingTest {
    private lateinit var registry: AppParserRegistry

    private val currencyNormalizer = io.mockk.mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        io.mockk.every { normalize(any()) } answers {
            when (firstArg<String?>()) {
                "€", "E", "e", "EUR" -> "EUR"
                "$", "USD" -> "USD"
                "£", "GBP" -> "GBP"
                null -> "EUR"
                else -> firstArg<String?>()?.takeIf { it.matches(Regex("^[A-Z]{3}$")) } ?: "EUR"
            }
        }
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    private val directionDetector = io.mockk.mockk<com.yourname.expensetracker.domain.parser.TransferDirectionDetector>(relaxed = true) {
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
            genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector)
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
        assertEquals(0.95f, result!!.confidence, 0.01f) // Revolut confidence
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
        assertEquals(0.90f, result!!.confidence, 0.01f) // Google Wallet confidence
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
        assertEquals(0.92f, result!!.confidence, 0.01f)
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
        assertEquals(0.60f, result!!.confidence, 0.01f) // Generic confidence
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
