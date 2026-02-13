package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleWalletParserTest {
    private lateinit var parser: GoogleWalletParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GoogleWalletParser(currencyNormalizer, merchantCleaner)
    }

    @Test
    fun `parse payment at merchant in text`() {
        val result = parser.parse(
            title = "Payment",
            text = "€4.20 at Coffee Island",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result!!.amount, 0.01)
        assertEquals("Coffee Island", result.merchant)
    }

    @Test
    fun `title is merchant when no at-pattern in text`() {
        val result = parser.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals("COFFEE ISLAND", result!!.merchant)
    }

    @Test
    fun `parse amount with currency suffix`() {
        val result = parser.parse(
            title = "Payment completed",
            text = "15.50 EUR at Lidl",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(15.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `reject add a card notification`() {
        assertNull(parser.parse(
            title = "Add a card to Google Wallet",
            text = "Tap to get started",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }

    @Test
    fun `reject loyalty offer`() {
        assertNull(parser.parse(
            title = "Loyalty reward available",
            text = "You have a new offer nearby",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }

    @Test
    fun `reject unrealistic amount over 50000`() {
        val result = parser.parse(
            title = "Payment",
            text = "€99999.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }

    @Test
    fun `reject unrealistic amount under 0_01`() {
        val result = parser.parse(
            title = "Payment",
            text = "€0.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }

    @Test
    fun `clean card info from merchant`() {
        val result = parser.parse(
            title = "Starbucks",
            text = "€3.50 - Mastercard ••4567",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.contains("Mastercard"))
        assertFalse(result.merchant.contains("4567"))
    }

    @Test
    fun `supports both wallet package variants`() {
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.walletnfcrel"))
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.nbu.paisa.user"))
    }
}
