package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GreekBankParserTest {
    private lateinit var parser: GreekBankParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR")
    }

    @Test
    fun `parse Greek purchase notification - agora pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Αγορά 12,50 EUR στο SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse with euro symbol prefix`() {
        val result = parser.parse(
            title = "Πληρωμή",
            text = "€6,30 στο PIZZA HOOD",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result!!.amount, 0.01)
    }

    @Test
    fun `parse card charge pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "χρέωση κάρτας: 25,00 EUR - VODAFONE",
            bigText = null, subText = null,
            packageName = "gr.alpha.mobile"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse amount with single decimal digit`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Αγορά 12,5 EUR στο SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(12.5, result!!.amount, 0.01)
    }

    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Υπόλοιπο",
            text = "Το υπόλοιπο σας είναι 1250,00 EUR",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `reject OTP code`() {
        assertNull(parser.parse(
            title = "Κωδικός",
            text = "Ο κωδικός σας είναι 123456",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `reject promotional offer`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Νέα προσφορά: Δωρεάν μεταφορά χρημάτων",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `supports all Greek bank packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("gr.nbg.mobilebanking"))
        assertTrue(packages.contains("gr.alpha.mobile"))
        assertTrue(packages.contains("com.eurobank.mobile"))
        assertTrue(packages.contains("com.winbank.mobile"))
    }

    @Test
    fun `high confidence for parsed results`() {
        val result = parser.parse(
            title = "Payment",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertTrue(result!!.confidence >= 0.90f)
    }

    @Test
    fun `credited amount format is parsed as incoming deposit`() {
        val result = parser.parse(
            title = "Credit Alert",
            text = "Amount credited 250.00 EUR to your account",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
    }
}