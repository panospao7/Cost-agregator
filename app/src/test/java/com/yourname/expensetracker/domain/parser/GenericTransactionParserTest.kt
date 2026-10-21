package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GenericTransactionParserTest {
    private lateinit var parser: GenericTransactionParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    private lateinit var directionDetector: com.yourname.expensetracker.domain.parser.TransferDirectionDetector

    /** Parser wired with REAL collaborators (no mocks of normalizer/cleaner/detector). */
    private val realParser = GenericTransactionParser(
        currencyNormalizer = CurrencyNormalizer(),
        merchantCleaner = MerchantCleaner(),
        directionDetector = TransferDirectionDetector(),
        timeProvider = io.mockk.mockk()
    )

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
        parser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider = io.mockk.mockk())
    }

    private data class AcceptCase(
        val id: String,
        val title: String = "Alert",
        val text: String,
        val expectedAmount: Double? = null,
        val expectedExactMerchant: String? = null,
        val expectedMerchantContains: String? = null,
        val expectedType: ParsedTransactionType? = null,
        val expectedCurrency: String? = null
    )

    private data class RejectCase(val id: String, val title: String, val text: String)

    private fun parseMocked(title: String, text: String) = parser.parse(
        title = title,
        text = text,
        bigText = null, subText = null,
        packageName = "com.unknown.app"
    )

    private fun parseReal(text: String) = realParser.parse(
        title = "Alert",
        text = text,
        bigText = null,
        subText = null,
        packageName = "unknown.app"
    )

    // === SUCCESSFUL PARSING (mocked collaborators) ===

    @Test
    fun `successful parsing - amount patterns across english greek and greeklish`() {
        val cases = listOf(
            AcceptCase("you paid pattern", text = "You paid €25.00 at Starbucks", expectedAmount = 25.00, expectedExactMerchant = "Starbucks"),
            AcceptCase("payment of pattern", title = "Notification", text = "Payment of €15.00 at Amazon", expectedAmount = 15.00),
            AcceptCase("charged pattern", text = "Charged €10.50 at Shell Gas Station", expectedAmount = 10.50),
            AcceptCase("Greek payment pattern", title = "Ειδοποίηση", text = "Πληρωμή 30,00 EUR στο COSMOTE", expectedAmount = 30.00),
            AcceptCase("Greeklish payment pattern", text = "Pliromi 20,00 EUR sto MERCHANT", expectedAmount = 20.00)
        )
        cases.forEach { c ->
            val result = parseMocked(c.title, c.text)
            assertNotNull("case '${c.id}' should parse", result)
            assertEquals("case '${c.id}' amount", c.expectedAmount!!, result!!.amount, 0.01)
            c.expectedExactMerchant?.let { assertEquals("case '${c.id}' merchant", it, result.merchant) }
        }
    }

    @Test
    fun `parse transfer received as transfer not deposit`() {
        io.mockk.every {
            directionDetector.detectDirection(any(), any(), any(), ParsedTransactionType.TRANSFER)
        } returns ParsedTransferDirection.INCOMING
        io.mockk.every {
            directionDetector.extractAccountName(any(), any(), any())
        } returns "Alex"

        val result = parser.parse(
            title = "Transfer received",
            text = "Transfer received €75.00 from Alex",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
        assertEquals("From: Alex", result.transferAccountName)
    }

    @Test
    fun `lower confidence than app-specific parsers`() {
        val result = parseMocked("Alert", "You paid €25.00 at Starbucks")
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f)
    }

    @Test
    fun `ordinary purchase remains purchase`() {
        val result = parseMocked("Alert", "You paid €25.00 at Starbucks")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
    }

    // === REJECTION: NEGATIVE SIGNALS, NO STRONG SIGNAL, AMOUNT BOUNDS (mocked collaborators) ===

    @Test
    fun `rejection - negative signals missing signal and amount bounds`() {
        val cases = listOf(
            RejectCase("offer notification", "Special offer!", "You paid €0 - save up to €50 today! offer ends soon"),
            RejectCase("OTP notification", "Verification code", "Your OTP code is 123456"),
            RejectCase("tracking notification", "Order update", "Your order has been shipped and is being tracked"),
            RejectCase("balance notification", "Balance update", "Your balance is €1500.00"),
            RejectCase("sale promotion", "Big Sale", "50% off everything! Sale ends tonight"),
            RejectCase("Greek promotional notification", "Προσφορά", "Δωρεάν αποστολή σε παραγγελίες άνω των €30"),
            RejectCase("notification without transaction signal", "Random App", "€25.00 available in your account"),
            RejectCase("amount below 0_10", "Alert", "You paid €0.05 at Shop"),
            RejectCase("amount above 25000", "Alert", "You paid €30000.00 at Shop")
        )
        cases.forEach { c ->
            assertNull("case '${c.id}' should be rejected", parseMocked(c.title, c.text))
        }
    }

    // === MERCHANT EXTRACTION (mocked collaborators) ===

    @Test
    fun `merchant extraction - after at and after Greek preposition`() {
        val cases = listOf(
            AcceptCase("extract merchant after at", text = "You paid €10.00 at Lidl Supermarket", expectedMerchantContains = "Lidl"),
            AcceptCase("extract merchant after Greek preposition", text = "Πληρωμή 10,00€ στο EVEREST", expectedMerchantContains = "EVEREST")
        )
        cases.forEach { c ->
            val result = parseMocked(c.title, c.text)
            assertNotNull("case '${c.id}' should parse", result)
            assertTrue(
                "case '${c.id}' merchant '${result!!.merchant}' should contain '${c.expectedMerchantContains}'",
                result.merchant.contains(c.expectedMerchantContains!!)
            )
        }
    }

    @Test
    fun `fallback to Unknown when no merchant found`() {
        io.mockk.every { merchantCleaner.clean(any()) } answers { firstArg() ?: "Unknown" }
        val result = parseMocked("Alert", "You paid €10.00")
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

        val result = parseMocked("Salary credited", "Deposit €1500.00")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
        assertEquals("From: Payroll Account", result.transferAccountName)
    }

    // === REAL COLLABORATORS (formerly the "stress" suite) ===

    @Test
    fun `real collaborators - accepted messages yield expected type amount currency and merchant`() {
        val cases = listOf(
            AcceptCase(
                "generic purchase with amount and merchant",
                text = "Payment of €25.00 at Starbucks",
                expectedType = ParsedTransactionType.PURCHASE,
                expectedAmount = 25.0,
                expectedMerchantContains = "Starbucks"
            ),
            AcceptCase(
                "incoming deposit signal as deposit type",
                text = "Salary credited €1200.00 from ACME",
                expectedType = ParsedTransactionType.DEPOSIT
            ),
            AcceptCase(
                "greek deposit message containing apo is not rejected",
                text = "Κατάθεση €120,00 από ACME LTD",
                expectedType = ParsedTransactionType.DEPOSIT,
                expectedAmount = 120.0
            ),
            AcceptCase(
                "dollar currency normalization",
                text = "You paid $10.00 at Walmart",
                expectedCurrency = "USD"
            ),
            AcceptCase(
                "supported european amount format",
                text = "Πληρωμή 12,50 EUR στο Μασούτης",
                expectedAmount = 12.5
            ),
            AcceptCase(
                "one decimal place amount with currency adjacent",
                text = "Payment of €12,5 at Cafe",
                expectedAmount = 12.5
            ),
            AcceptCase(
                "prefers transaction amount when multiple amounts are present",
                text = "Available €1.245,00. Payment of €12,5 at Cafe",
                expectedAmount = 12.5
            )
        )
        cases.forEach { c ->
            val result = parseReal(c.text)
            assertNotNull("case '${c.id}' should parse", result)
            c.expectedType?.let { assertEquals("case '${c.id}' type", it, result!!.type) }
            c.expectedAmount?.let { assertEquals("case '${c.id}' amount", it, result!!.amount, 0.001) }
            c.expectedCurrency?.let { assertEquals("case '${c.id}' currency", it, result!!.currency) }
            c.expectedMerchantContains?.let {
                assertTrue(
                    "case '${c.id}' merchant '${result!!.merchant}' should contain '$it'",
                    result!!.merchant.contains(it, ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `real collaborators - non-financial marketing and malformed amount are rejected`() {
        val cases = listOf(
            "non-financial notification" to "Reminder: meeting at 3 PM",
            "marketing message despite numbers" to "Flash sale 50% off - save up to €100",
            "malformed fraction-like amount" to "Payment 1/2 EUR at Store"
        )
        cases.forEach { (id, text) ->
            assertNull("case '$id' should be rejected", parseReal(text))
        }
    }

    @Test
    fun `real collaborators - is deterministic across repeated parses`() {
        val input = "Transaction of €44.10 at Lidl"
        val a = parseReal(input)
        val b = parseReal(input)
        assertNotNull("first parse must produce a result", a)
        assertNotNull("second parse must produce a result", b)
        assertEquals(a, b)
    }
}
