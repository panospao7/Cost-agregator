package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GreekBankParserTest {
    private lateinit var parser: GreekBankParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner

    /** Parser wired with REAL collaborators (formerly the "stress" suite). */
    private val realParser = GreekBankParser(
        currencyNormalizer = CurrencyNormalizer(),
        merchantCleaner = MerchantCleaner(),
        homeCurrency = "EUR"
    )

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
            io.mockk.every { normalizeOrNull(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR")
    }

    private data class MockedAcceptCase(
        val id: String,
        val title: String,
        val text: String,
        val packageName: String,
        val expectedAmount: Double,
        val expectedCurrency: String? = null,
        val expectedType: ParsedTransactionType? = null
    )

    private data class RejectCase(
        val id: String,
        val useRealCollaborators: Boolean,
        val title: String,
        val text: String,
        val packageName: String
    )

    private data class RealAcceptCase(
        val id: String,
        val text: String,
        val expectedAmount: Double? = null,
        val expectedType: ParsedTransactionType? = null,
        val expectedMerchantContains: String? = null,
        val directionNullOrOutgoing: Boolean = false
    )

    private fun parseReal(notification: String) = realParser.parse(
        title = "Τράπεζα",
        text = notification,
        bigText = null,
        subText = null,
        packageName = "com.eurobank.mobile"
    )

    // === ACCEPTED NOTIFICATIONS (mocked collaborators) ===

    @Test
    fun `mocked collaborators - accepted notifications yield expected amount currency and type`() {
        val cases = listOf(
            MockedAcceptCase(
                "agora pattern", "Ειδοποίηση", "Αγορά 12,50 EUR στο SKLAVENITIS", "gr.nbg.mobilebanking",
                expectedAmount = 12.50, expectedCurrency = "EUR", expectedType = ParsedTransactionType.PURCHASE
            ),
            MockedAcceptCase("euro symbol prefix", "Πληρωμή", "€6,30 στο PIZZA HOOD", "gr.nbg.mobilebanking", expectedAmount = 6.30),
            MockedAcceptCase("card charge pattern", "Alert", "χρέωση κάρτας: 25,00 EUR - VODAFONE", "gr.alpha.mobile", expectedAmount = 25.00),
            MockedAcceptCase("single decimal digit", "Ειδοποίηση", "Αγορά 12,5 EUR στο SKLAVENITIS", "gr.nbg.mobilebanking", expectedAmount = 12.5)
        )
        cases.forEach { c ->
            val result = parser.parse(
                title = c.title,
                text = c.text,
                bigText = null, subText = null,
                packageName = c.packageName
            )
            assertNotNull("case '${c.id}' should parse", result)
            assertEquals("case '${c.id}' amount", c.expectedAmount, result!!.amount, 0.01)
            c.expectedCurrency?.let { assertEquals("case '${c.id}' currency", it, result.currency) }
            c.expectedType?.let { assertEquals("case '${c.id}' type", it, result.type) }
        }
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
    fun `supports all Greek bank packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("gr.nbg.mobilebanking"))
        assertTrue(packages.contains("gr.alpha.mobile"))
        assertTrue(packages.contains("com.eurobank.mobile"))
        assertTrue(packages.contains("com.winbank.mobile"))
    }

    // === REJECTIONS (mocked and real collaborators) ===

    @Test
    fun `rejection - balance otp promotional and non-transaction update messages`() {
        val cases = listOf(
            RejectCase("balance notification", false, "Υπόλοιπο", "Το υπόλοιπο σας είναι 1250,00 EUR", "gr.nbg.mobilebanking"),
            RejectCase("OTP code", false, "Κωδικός", "Ο κωδικός σας είναι 123456", "gr.nbg.mobilebanking"),
            RejectCase("promotional offer", false, "Προσφορά", "Νέα προσφορά: Δωρεάν μεταφορά χρημάτων", "gr.nbg.mobilebanking"),
            RejectCase(
                "non-transaction update message (real collaborators)", true,
                "Τράπεζα", "Ενημέρωση λογαριασμού: υπόλοιπο διαθέσιμο", "com.eurobank.mobile"
            )
        )
        cases.forEach { c ->
            val target = if (c.useRealCollaborators) realParser else parser
            val result = target.parse(
                title = c.title,
                text = c.text,
                bigText = null, subText = null,
                packageName = c.packageName
            )
            assertNull("case '${c.id}' should be rejected", result)
        }
    }

    // === REAL COLLABORATORS (formerly the "stress" suite) ===

    @Test
    fun `real collaborators - accepted notifications yield expected type amount and merchant`() {
        val cases = listOf(
            RealAcceptCase(
                "purchase notification", "Αγορά €50.00 στο Starbucks με κάρτα *****1234",
                expectedType = ParsedTransactionType.PURCHASE, expectedAmount = 50.0
            ),
            RealAcceptCase(
                "greek deposit notification", "Κατάθεση €500.00 στον λογαριασμό σας",
                expectedType = ParsedTransactionType.DEPOSIT
            ),
            RealAcceptCase(
                "transfer notification and sets direction", "Μεταφορά 100,00 EUR σε Μαρία",
                expectedType = ParsedTransactionType.TRANSFER, expectedAmount = 100.0, directionNullOrOutgoing = true
            ),
            RealAcceptCase(
                "eurobank-like charge format", "Χρέωση κάρτας €30.00 - Σκλαβενίτης",
                expectedAmount = 30.0, expectedMerchantContains = "Σκλαβενίτης"
            ),
            RealAcceptCase("european decimal comma", "Συναλλαγή €12,34 - Κατάστημα: Lidl", expectedAmount = 12.34),
            RealAcceptCase("single decimal in deposit format", "Κατάθεση €120,5 από ACME", expectedAmount = 120.5),
            RealAcceptCase("single decimal in transfer format", "Χ 50,5 EUR προς Μαρία", expectedAmount = 50.5),
            RealAcceptCase(
                "greek merchant text", "Αγορά €50.00 στο Κατάστημα ΑΒ Βασιλόπουλος",
                expectedMerchantContains = "Βασιλόπουλος"
            ),
            RealAcceptCase(
                "merchant names with special characters", "Αγορά 18,90 EUR στο H&M / 7-Eleven",
                expectedMerchantContains = "h&m"
            )
        )
        cases.forEach { c ->
            val result = parseReal(c.text)
            assertNotNull("case '${c.id}' should parse", result)
            c.expectedType?.let { assertEquals("case '${c.id}' type", it, result!!.type) }
            c.expectedAmount?.let { assertEquals("case '${c.id}' amount", it, result!!.amount, 0.001) }
            c.expectedMerchantContains?.let {
                assertTrue(
                    "case '${c.id}' merchant '${result!!.merchant}' should contain '$it'",
                    result!!.merchant.contains(it, ignoreCase = true)
                )
            }
            if (c.directionNullOrOutgoing) {
                assertTrue(
                    "case '${c.id}': transfer direction should be detected when possible",
                    result!!.transferDirection == null || result.transferDirection == ParsedTransferDirection.OUTGOING
                )
            }
        }
    }

    @Test
    fun `real collaborators - is deterministic for same notification`() {
        val n = "Αγορά €45.00 στο Lidl"
        val a = parseReal(n)
        val b = parseReal(n)
        assertNotNull("first parse must produce a result", a)
        assertNotNull("second parse must produce a result", b)
        assertEquals(a, b)
    }

    // === REGRESSION: NBG over-matched merchant (formerly NBGReproTest) ===

    @Test
    fun `regression NBG - overmatched merchant is reduced to Paymonade`() {
        // Currency normalizer passes the currency through; MerchantCleaner is the real one
        // so the over-matched merchant text would be visible if the bug regressed.
        val nbgCurrencyNormalizer: CurrencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
            io.mockk.every { normalizeOrNull(any()) } answers { firstArg() }
        }
        val nbgParser = GreekBankParser(nbgCurrencyNormalizer, MerchantCleaner(), homeCurrency = "EUR")

        val result = nbgParser.parse(
            title = "💳 Πληρώσατε €10,00 σε Paymonade",
            text = "Πληρώσατε €10,00 από την κάρτα *1554 σε Paymonade",
            bigText = null,
            subText = null,
            packageName = "mbanking.NBG"
        )

        assertNotNull("Result should not be null", result)
        assertEquals(10.00, result!!.amount, 0.01)
        // Previously the merchant came out as "Paymonade Πληρώσατε €10,00 από την κάρτ" (over-matched).
        assertEquals("Paymonade", result.merchant)
    }
}
