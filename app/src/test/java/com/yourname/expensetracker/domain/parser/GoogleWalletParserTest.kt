package com.yourname.expensetracker.domain.parser

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
            io.mockk.every { normalize(any()) } answers { 
                val arg = firstArg<String?>()
                when (arg?.uppercase()) {
                    "€", "E", "EUR" -> "EUR"
                    "$", "USD" -> "USD"
                    "£", "GBP" -> "GBP"
                    "₹", "INR" -> "INR"
                    else -> arg ?: "EUR"
                }
            }
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
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse outgoing p2p send as transfer`() {
        val result = parser.parse(
            title = "Google Pay",
            text = "Sent €18.00 to Alex Johnson",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
        assertEquals("Alex Johnson", result.merchant)
        assertEquals("To: Alex Johnson", result.transferAccountName)
    }

    @Test
    fun `parse incoming p2p receive as transfer`() {
        val result = parser.parse(
            title = "Google Pay",
            text = "Received €12.00 from Maria",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
        assertEquals("Maria", result.merchant)
        assertEquals("From: Maria", result.transferAccountName)
    }

    @Test
    fun `keep paid to merchant wording as purchase`() {
        val result = parser.parse(
            title = "Google Pay",
            text = "Paid €18.00 to Coffee Island with Mastercard ••1234",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertNull(result.transferDirection)
        assertEquals("Coffee Island with Mastercard", result.merchant)
    }

    @Test
    fun `keep google pay merchant purchase wording as purchase`() {
        val result = parser.parse(
            title = "Google Pay",
            text = "Paid €22.50 to Zara with Visa ••4321",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertNull(result.transferDirection)
        assertEquals("Zara with Visa", result.merchant)
    }

    @Test
    fun `parse paid to friend wording as transfer`() {
        val result = parser.parse(
            title = "Google Pay",
            text = "Paid €18.00 to friend Alex",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
        assertEquals("friend Alex", result.merchant)
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

    @Test
    fun `parse amount with E prefix - corrupted euro symbol`() {
        // This replicates the issue where € becomes E in notifications
        val result = parser.parse(
            title = "K POLIANIDIS A TZANI O",
            text = "E8.00 with Mastercard ••1554",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(8.00, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals("K POLIANIDIS A TZANI O", result.merchant)
    }

    @Test
    fun `parse amount with euro symbol - normal case`() {
        val result = parser.parse(
            title = "K POLIANIDIS A TZANI O",
            text = "€8.00 with Mastercard ••1554",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(8.00, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `parse INR amount with rupee symbol and merchant`() {
        val result = parser.parse(
            title = "Payment",
            text = "₹123.45 at Merchant",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.nbu.paisa.user"
        )

        assertNotNull(result)
        assertEquals(123.45, result!!.amount, 0.01)
        assertEquals("INR", result.currency)
        assertEquals("Merchant", result.merchant)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse INR amount with code prefix`() {
        val result = parser.parse(
            title = "Payment",
            text = "INR 123.45",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.nbu.paisa.user"
        )

        assertNotNull(result)
        assertEquals(123.45, result!!.amount, 0.01)
        assertEquals("INR", result.currency)
    }
}
