package com.yourname.expensetracker.domain.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GenericTransactionParserTest {
    private lateinit var parser: GenericTransactionParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    private lateinit var directionDetector: com.yourname.expensetracker.domain.parser.TransferDirectionDetector

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        directionDetector = io.mockk.mockk {
            io.mockk.every { detectDirection(any(), any(), any(), any()) } returns null
            io.mockk.every { extractAccountName(any(), any(), any()) } returns null
        }
        parser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector)
    }

    // === SUCCESSFUL PARSING ===

    @Test
    fun `parse you paid pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("Starbucks", result.merchant)
    }

    @Test
    fun `parse payment of pattern`() {
        val result = parser.parse(
            title = "Notification",
            text = "Payment of €15.00 at Amazon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse charged pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Charged €10.50 at Shell Gas Station",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(10.50, result!!.amount, 0.01)
    }

    @Test
    fun `parse Greek payment pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Πληρωμή 30,00 EUR στο COSMOTE",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(30.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse Greeklish payment pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Pliromi 20,00 EUR sto MERCHANT",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(20.00, result!!.amount, 0.01)
    }

    @Test
    fun `lower confidence than app-specific parsers`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f)
    }

    // === NEGATIVE SIGNAL REJECTION ===

    @Test
    fun `reject offer notification`() {
        assertNull(parser.parse(
            title = "Special offer!",
            text = "You paid €0 - save up to €50 today! offer ends soon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject OTP notification`() {
        assertNull(parser.parse(
            title = "Verification code",
            text = "Your OTP code is 123456",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject tracking notification`() {
        assertNull(parser.parse(
            title = "Order update",
            text = "Your order has been shipped and is being tracked",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Balance update",
            text = "Your balance is €1500.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject sale promotion`() {
        assertNull(parser.parse(
            title = "Big Sale",
            text = "50% off everything! Sale ends tonight",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject Greek promotional notification`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Δωρεάν αποστολή σε παραγγελίες άνω των €30",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === NO STRONG SIGNAL ===

    @Test
    fun `reject notification without transaction signal`() {
        assertNull(parser.parse(
            title = "Random App",
            text = "€25.00 available in your account",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === AMOUNT BOUNDS ===

    @Test
    fun `reject amount below 0_10`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €0.05 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject amount above 25000`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €30000.00 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === MERCHANT EXTRACTION ===

    @Test
    fun `extract merchant after at`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €10.00 at Lidl Supermarket",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("Lidl"))
    }

    @Test
    fun `extract merchant after Greek preposition`() {
        val result = parser.parse(
            title = "Alert",
            text = "Πληρωμή 10,00€ στο EVEREST",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("EVEREST"))
    }

    @Test
    fun `fallback to Unknown when no merchant found`() {
        io.mockk.every { merchantCleaner.clean(any()) } answers { firstArg() ?: "Unknown" }
        val result = parser.parse(
            title = "Alert",
            text = "You paid €10.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals("Unknown", result!!.merchant)
    }

    @Test
    fun `enriches transfer metadata when detector returns direction and account`() {
        io.mockk.every {
            directionDetector.detectDirection(any(), any(), any(), any())
        } returns ParsedTransferDirection.INCOMING
        io.mockk.every {
            directionDetector.extractAccountName(any(), any(), any())
        } returns "Payroll Account"

        val result = parser.parse(
            title = "Salary credited",
            text = "Deposit €1500.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
        assertEquals("From: Payroll Account", result.transferAccountName)
    }
}
