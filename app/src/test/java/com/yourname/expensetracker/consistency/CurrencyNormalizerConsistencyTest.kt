package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Ensures CurrencyNormalizer produces consistent results across all parsers.
 * Same currency string (€, E, EUR, etc.) must normalize to same ISO code regardless
 * of which parser extracts it. Prevents drift when parsers use different extraction logic.
 */
class CurrencyNormalizerConsistencyTest {

    private lateinit var currencyNormalizer: CurrencyNormalizer
    private lateinit var revolutParser: RevolutParser
    private lateinit var greekBankParser: GreekBankParser
    private lateinit var genericParser: GenericTransactionParser

    @Before
    fun setup() {
        currencyNormalizer = CurrencyNormalizer()
        val merchantCleaner = MerchantCleaner()
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner)
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR")
        genericParser = GenericTransactionParser(
            currencyNormalizer,
            merchantCleaner,
            TransferDirectionDetector()
        )
    }

    // ============================================================================
    // CURRENCY NORMALIZER DIRECT CONSISTENCY
    // ============================================================================

    @Test
    fun `consistency - same EUR variants normalize to EUR`() {
        val variants = listOf("€", "E", "EUR", "EURO", "eur", "  EUR  ")
        for (v in variants) {
            assertEquals("Variant '$v' must normalize to EUR", "EUR", currencyNormalizer.normalize(v))
        }
    }

    @Test
    fun `consistency - same USD variants normalize to USD`() {
        val variants = listOf("$", "USD", "DOLLAR", "usd")
        for (v in variants) {
            assertEquals("Variant '$v' must normalize to USD", "USD", currencyNormalizer.normalize(v))
        }
    }

    @Test
    fun `consistency - same GBP variants normalize to GBP`() {
        val variants = listOf("£", "GBP", "POUND", "gbp")
        for (v in variants) {
            assertEquals("Variant '$v' must normalize to GBP", "GBP", currencyNormalizer.normalize(v))
        }
    }

    @Test
    fun `consistency - same INR variants normalize to INR`() {
        val variants = listOf("₹", "INR", "RUPEE", "RUPEES", "inr")
        for (v in variants) {
            assertEquals("Variant '$v' must normalize to INR", "INR", currencyNormalizer.normalize(v))
        }
    }

    @Test
    fun `consistency - null or blank default to EUR`() {
        assertEquals("EUR", currencyNormalizer.normalize(null))
        assertEquals("EUR", currencyNormalizer.normalize(""))
        assertEquals("EUR", currencyNormalizer.normalize("   "))
    }

    @Test
    fun `consistency - valid 3-letter codes pass through`() {
        val codes = listOf("CHF", "JPY", "PLN", "RON")
        for (c in codes) {
            assertEquals("Valid code '$c' must pass through", c, currencyNormalizer.normalize(c))
        }
    }

    // ============================================================================
    // CROSS-PARSER CURRENCY CONSISTENCY
    // ============================================================================

    @Test
    fun `consistency - Revolut EUR notification produces EUR currency`() {
        val result = revolutParser.parse(
            title = "Paid €12.50 at SKLAVENITIS",
            text = null,
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals("EUR", result!!.currency)
    }

    @Test
    fun `consistency - Greek bank EUR notification produces EUR currency`() {
        val result = greekBankParser.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €6,30 σε PIZZA HOOD",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals("EUR", result!!.currency)
    }

    @Test
    fun `consistency - Generic parser EUR produces EUR`() {
        val result = genericParser.parse(
            title = "Payment",
            text = "You paid €5.50 at Starbucks",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals("EUR", result!!.currency)
    }

    @Test
    fun `consistency - parsers using E or EUR symbol produce same normalized currency`() {
        val revolutEur = revolutParser.parse(
            title = "Paid €10.00 at Store",
            text = null,
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        val greekEur = greekBankParser.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε 10,00 EUR σε Store",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(revolutEur)
        assertNotNull(greekEur)
        assertEquals(
            "Both parsers must produce same currency for EUR",
            revolutEur!!.currency,
            greekEur!!.currency
        )
        assertEquals("EUR", revolutEur.currency)
    }
}