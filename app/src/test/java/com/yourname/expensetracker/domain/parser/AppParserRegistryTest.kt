package com.yourname.expensetracker.domain.parser

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
        io.mockk.every { normalize(any()) } returns "EUR"
        io.mockk.every { normalizeOrNull(any()) } returns "EUR"
    }
    private val merchantCleaner = io.mockk.mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    private val directionDetector = io.mockk.mockk<com.yourname.expensetracker.domain.parser.TransferDirectionDetector> {
        io.mockk.every { detectDirection(any(), any(), any(), any()) } returns null
        io.mockk.every { extractAccountName(any(), any(), any()) } returns null
    }

    private val registry = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR"),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider = io.mockk.mockk()),
        aiFallbackParser = io.mockk.mockk(),
        timeProvider = io.mockk.mockk(),
        currencySettingsRepository = homeCurrencyRepo("EUR")
    )

    private fun homeCurrencyRepo(code: String) =
        io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>().also {
            io.mockk.coEvery { it.resolveHomeCurrency() } returns
                com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode(code)
                )
        }

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
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
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

    @Test
    fun `test Revolut grouped amount parses via registry without fallback`() {
        val result = registry.parse(
            title = "Paid €1,234.56 at IKEA",
            text = null,
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull("Grouped amount should be parsed by RevolutParser, not fall through to generic", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals("IKEA", result.merchant)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        // Confidence should be from RevolutParser (0.95), not generic parser
        assertEquals(0.95f, result.confidence, 0.01f)
    }

    @Test
    fun `test SMS grouped amount parses via registry without fallback`() {
        val result = registry.parse(
            title = "NBG",
            text = "Αγορά 1,234.56 EUR στο SUPERMARKET στις 10/04",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped SMS amount should be parsed by SmsParser, not fall through to generic", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        // Confidence should be from SmsParser (0.85), not generic parser
        assertEquals(0.85f, result.confidence, 0.01f)
    }

    // ---- I-03-1: the Greek bank fallback currency is resolved per call, not frozen at construction ----

    private fun registryWithRepo(
        repo: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository,
        snapshotHomeCurrency: String = "GBP"
    ) = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = snapshotHomeCurrency),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider = io.mockk.mockk()),
        aiFallbackParser = io.mockk.mockk(),
        timeProvider = io.mockk.mockk(),
        currencySettingsRepository = repo
    )

    // No currency token in the text, so the currency is the home-currency fallback.
    private suspend fun AppParserRegistry.parseBareGreekAmount() = parseWithProvenance(
        title = "Ειδοποίηση",
        text = "12,50 στο SHOP 24",
        bigText = null,
        subText = null,
        packageName = "gr.nbg.mobilebanking"
    )

    @Test
    fun `I-03-1 home currency changing between calls changes the Greek bank fallback currency`() =
        kotlinx.coroutines.runBlocking {
            val repo = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>()
            io.mockk.coEvery { repo.resolveHomeCurrency() } returnsMany listOf(
                com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
                ),
                com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode("USD")
                )
            )
            // The construction snapshot is GBP, so neither assertion can pass from the snapshot.
            val registry = registryWithRepo(repo, snapshotHomeCurrency = "GBP")

            val first = registry.parseBareGreekAmount().parsed!!
            val second = registry.parseBareGreekAmount().parsed!!

            assertEquals(12.50, first.amount, 0.001)
            assertEquals("EUR", first.currency)
            assertEquals(false, first.isAmountAmbiguous)
            assertEquals("USD", second.currency)
            assertEquals(false, second.isAmountAmbiguous)
        }

    @Test
    fun `I-03-1 failed home currency resolution flags the Greek bank result ambiguous`() =
        kotlinx.coroutines.runBlocking {
            val repo = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>()
            io.mockk.coEvery { repo.resolveHomeCurrency() } returns
                com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Failed("read error")

            val parsed = registryWithRepo(repo, snapshotHomeCurrency = "GBP").parseBareGreekAmount().parsed!!

            // Amount is unchanged; the assumed (snapshot) currency is only usable through review.
            assertEquals(12.50, parsed.amount, 0.001)
            assertEquals("GBP", parsed.currency)
            assertEquals(true, parsed.isAmountAmbiguous)
        }

    @Test
    fun `hasParserFor is true for every package of a registered dedicated parser`() {
        val registered = listOf(
            "com.revolut.revolut",
            "gr.nbg.mobilebanking",
            "gr.alpha.mobile",
            "com.google.android.apps.walletnfcrel"
        ) + SmsParser.SMS_PACKAGES
        for (pkg in registered) {
            assertEquals("package=$pkg", true, registry.hasParserFor(pkg))
        }
    }

    @Test
    fun `hasParserFor is false for unknown and malformed package names`() {
        for (pkg in listOf("com.synthetic.unknown", "", " ", "COM.REVOLUT.REVOLUT", "com.revolut")) {
            assertEquals("package='$pkg'", false, registry.hasParserFor(pkg))
        }
    }

    @Test
    fun `hasParserFor is false for a package only the generic parser handles`() {
        // Control: the generic fallback does parse this package, yet it is not a dedicated parser.
        val parsed = registry.parse(
            title = "Transaction Alert",
            text = "You paid 50.00 EUR at Netflix",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(parsed)
        assertEquals(false, registry.hasParserFor("com.unknown.app"))
    }

    @Test
    fun `I-03-1 cancellation during home currency resolution propagates`() =
        kotlinx.coroutines.runBlocking {
            val repo = io.mockk.mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>()
            io.mockk.coEvery { repo.resolveHomeCurrency() } throws
                kotlinx.coroutines.CancellationException("cancelled")

            val thrown = runCatching { registryWithRepo(repo).parseBareGreekAmount() }.exceptionOrNull()

            assertEquals(true, thrown is kotlinx.coroutines.CancellationException)
        }
}