package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppParserRegistryTest {

    private val currencyNormalizer = io.mockk.mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }

    private val registry = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner)
    )

    @Test
    fun `test Revolut parsing`() {
        val result = registry.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result?.amount!!, 0.01)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
    }

    @Test
    fun `test Greek Bank parsing (NBG)`() {
        val result = registry.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €6,30 σε PIZZA HOOD",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result?.amount!!, 0.01)
        assertEquals("PIZZA HOOD", result.merchant)
    }

    @Test
    fun `test Google Wallet parsing`() {
        val result = registry.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result?.amount!!, 0.01)
        assertEquals("COFFEE ISLAND", result.merchant)
    }

    @Test
    fun `test SMS Bank parsing`() {
        val result = registry.parse(
            title = "NBG",
            text = "AGORA 15,00 EUR STO KATASTIMA στις 07/02",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result?.amount!!, 0.01)
        assertEquals("KATASTIMA", result.merchant)
    }

    @Test
    fun `test generic fallback parsing`() {
        val result = registry.parse(
            title = "Transaction Alert",
            text = "You paid 50.00 EUR at Netflix",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(50.00, result?.amount!!, 0.01)
        assertEquals("Netflix", result.merchant)
    }

    @Test
    fun `test noise rejection (OTP)`() {
        val result = registry.parse(
            title = "Bank OTP",
            text = "Your verification code is 123456 for payment of 10.00",
            bigText = null,
            subText = null,
            packageName = "com.bank.app"
        )
        assertNull("Should reject OTP even if it contains 'payment' and numbers", result)
    }
}
