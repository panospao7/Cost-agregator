package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmsParserTest {
    private lateinit var parser: SmsParser
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
        parser = SmsParser(currencyNormalizer, merchantCleaner)
    }

    @Test
    fun `parse bank SMS with Greek keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 15,00 EUR στο KATASTIMA στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse bank SMS with Greeklish keywords`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Agora 22,50 EUR sto SUPERMARKET",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(22.50, result!!.amount, 0.01)
    }

    @Test
    fun `reject non-bank sender`() {
        val result = parser.parse(
            title = "John",
            text = "Hey, can you send me 50 EUR?",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject bank sender without transaction keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Welcome to our new mobile app!",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject null title`() {
        val result = parser.parse(
            title = null,
            text = "Αγορά 15,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `amount bounds check - too small`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 0,05 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `supports all messaging packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("com.google.android.apps.messaging"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.android.mms"))
    }

    @Test
    fun `parse outgoing transfer SMS as transfer with outgoing direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 120,00 EUR sent to John Doe",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
    }

    // --- Grouped-amount regressions ---

    @Test
    fun `parse grouped US amount in purchase SMS`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 1,234.56 EUR στο IKEA στις 10/04",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped US amount should parse", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse grouped EU amount in purchase SMS`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Πληρωμή 1.234,56 EUR στο MEDIAMARKT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped EU amount should parse", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse grouped amount in transfer SMS`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 2,500.00 EUR sent to Maria Papadopoulou",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped amount in transfer should parse", result)
        assertEquals(2500.00, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.TRANSFER, result.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
    }

    @Test
    fun `parse grouped amount with currency prefix`() {
        val result = parser.parse(
            title = "Revolut",
            text = "Purchase EUR 1,100.50 at ZARA",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped amount after currency prefix should parse", result)
        assertEquals(1100.50, result!!.amount, 0.01)
    }

    // --- Ambiguous transfer direction regressions ---

    @Test
    fun `ambiguous transfer SMS returns null direction`() {
        // "transfer" keyword with no incoming/outgoing directional evidence
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 500,00 EUR account 1234",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertNull("Ambiguous transfer should have null direction", result.transferDirection)
    }

    @Test
    fun `ambiguous deposit SMS returns null direction`() {
        // "deposit" keyword without explicit incoming evidence
        val result = parser.parse(
            title = "NBG",
            text = "Deposit 300,00 EUR account 5678",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertNull("Ambiguous deposit should have null direction", result.transferDirection)
    }

    @Test
    fun `explicit incoming deposit retains INCOMING direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Deposit 800,00 EUR credited to your account",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
    }

    @Test
    fun `explicit incoming transfer retains INCOMING direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 450,00 EUR received from John Doe",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
    }

    @Test
    fun `ambiguous direction suppresses transferAccountName`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 200,00 EUR account 9876",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertNull("Ambiguous direction should suppress transferAccountName", result!!.transferAccountName)
    }
}
