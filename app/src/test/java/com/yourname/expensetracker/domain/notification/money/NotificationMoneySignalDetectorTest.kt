package com.yourname.expensetracker.domain.notification.money

import com.yourname.expensetracker.domain.currency.CurrencyResolution
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMoneySignalDetectorTest {

    private val detector = NotificationMoneySignalDetector(
        userCurrencyProvider = mockk<UserCurrencyProvider>(relaxed = true)
    )

    @Test
    fun `ambiguous kr resolves only from a SEK NOK or DKK home`() = runTest {
        listOf("SEK", "NOK", "DKK").forEach { homeCurrency ->
            val signal = detector.bestTransactionAmount("Payment 42 kr completed", homeCurrency)

            assertEquals(42.0, signal!!.amount, 0.0)
            assertEquals(homeCurrency, signal.currencyCode)
            assertEquals(setOf("SEK", "NOK", "DKK"), signal.currencyCandidates)
            assertEquals(CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME, signal.resolution)
            assertEquals(0.70f, signal.confidence, 0.0f)
            assertFalse(signal.ambiguous)
        }
    }

    @Test
    fun `ambiguous kr remains unresolved without a candidate home`() = runTest {
        listOf<String?>(null, "EUR", "XXX").forEach { homeCurrency ->
            val signal = detector.bestTransactionAmount("Payment 42 kr completed", homeCurrency)

            assertEquals(42.0, signal!!.amount, 0.0)
            assertNull(signal.currencyCode)
            assertEquals(CurrencyResolution.AMBIGUOUS_UNRESOLVED, signal.resolution)
            assertEquals(0.45f, signal.confidence, 0.0f)
            assertTrue(signal.ambiguous)
        }
    }

    @Test
    fun `explicit Scandinavian ISO prefix and suffix win over another home currency`() = runTest {
        listOf(
            Triple("Payment SEK 42 completed", "SEK", "NOK"),
            Triple("Payment 42 SEK completed", "SEK", "NOK"),
            Triple("Payment NOK 42 completed", "NOK", "DKK"),
            Triple("Payment 42 NOK completed", "NOK", "DKK"),
            Triple("Payment DKK 42 completed", "DKK", "SEK"),
            Triple("Payment 42 DKK completed", "DKK", "SEK"),
        ).forEach { (text, expectedCurrency, homeCurrency) ->
            val signal = detector.bestTransactionAmount(text, homeCurrency)

            assertEquals(expectedCurrency, signal!!.currencyCode)
            assertEquals(setOf(expectedCurrency), signal.currencyCandidates)
            assertEquals(CurrencyResolution.EXPLICIT_ISO_CODE, signal.resolution)
            assertEquals(0.95f, signal.confidence, 0.0f)
            assertFalse(signal.ambiguous)
        }
    }

    @Test
    fun `ambiguous kr matching is case and home normalization independent`() = runTest {
        val signal = detector.bestTransactionAmount("  Payment   42 Kr  completed ", " nok ")

        assertEquals("NOK", signal!!.currencyCode)
        assertEquals(CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME, signal.resolution)
    }

    @Test
    fun `qualified dollar aliases and unambiguous symbols retain explicit behavior`() = runTest {
        val cases = listOf(
            Triple("Paid US$ 42", "USD", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid C$ 42", "CAD", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid A$ 42", "AUD", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid €42", "EUR", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid £42", "GBP", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid ¥42", "JPY", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
            Triple("Paid ₺42", "TRY", CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL),
        )

        cases.forEach { (text, expectedCurrency, expectedResolution) ->
            val signal = detector.bestTransactionAmount(text, homeCurrency = null)
            assertEquals(expectedCurrency, signal!!.currencyCode)
            assertEquals(expectedResolution, signal.resolution)
            assertFalse(signal.ambiguous)
        }
    }

    @Test
    fun `qualified and alphabetic aliases allow adjacent prefix and suffix amounts`() = runTest {
        listOf(
            "Fr" to "CHF", "SFr" to "CHF", "lei" to "RON", "Ft" to "HUF",
            "US$" to "USD", "C$" to "CAD", "A$" to "AUD", "EURO" to "EUR",
            "zl" to "PLN", "TL" to "TRY", "Kc" to "CZK"
        ).forEach { (alias, expectedCurrency) ->
            listOf("${alias}42", "42$alias", "$alias 42", "42 $alias").forEach { notation ->
                val signal = detector.bestTransactionAmount("Paid $notation", homeCurrency = null)

                assertEquals(notation, expectedCurrency, signal!!.currencyCode)
                assertEquals(42.0, signal.amount, 0.0)
                assertEquals(setOf(expectedCurrency), signal.currencyCandidates)
                assertEquals(CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL, signal.resolution)
                assertEquals(0.90f, signal.confidence, 0.0f)
                assertFalse(signal.ambiguous)
            }
        }
    }

    @Test
    fun `alphabetic aliases reject word fragments at the outer edge`() = runTest {
        listOf("42 from", "42 francs", "from42", "αFr42", "42Frα", "42Fr42").forEach { text ->
            assertNull(text, detector.bestTransactionAmount("Paid $text", homeCurrency = "EUR"))
        }
    }

    @Test
    fun `bare dollar resolves only from a dollar candidate home`() = runTest {
        val resolved = detector.bestTransactionAmount("Paid $42", "cad")
        val unresolved = detector.bestTransactionAmount("Paid $42", "EUR")

        assertEquals("CAD", resolved!!.currencyCode)
        assertEquals(CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME, resolved.resolution)
        assertNull(unresolved!!.currencyCode)
        assertEquals(CurrencyResolution.AMBIGUOUS_UNRESOLVED, unresolved.resolution)
    }

    @Test
    fun `alphabetic aliases do not match word fragments before ambiguous currency resolution`() = runTest {
        val resolved = detector.bestTransactionAmount(
            "Payment $42 from account at ACME",
            "CAD"
        )
        val noCurrency = detector.bestTransactionAmount(
            "Payment 42 from account at ACME",
            "EUR"
        )

        assertEquals("CAD", resolved!!.currencyCode)
        assertEquals(CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME, resolved.resolution)
        assertNull(noCurrency)
    }
}
