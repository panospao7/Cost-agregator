package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.parser.provenance.ParserSource
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FL-02: an SMS may be auto-accepted only when [SmsParser] itself read one clean amount.
 * Generic and AI fallback results for an SMS package are flagged ambiguous; amounts and
 * confidence are unchanged; non-SMS packages are unaffected.
 */
class AppParserRegistrySmsFallbackReviewTest {

    private val smsPackage = "com.google.android.apps.messaging"
    private val financePackage = "com.revolut.revolut"

    private val currencyNormalizer = mockk<com.yourname.expensetracker.domain.util.CurrencyNormalizer> {
        every { normalize(any()) } returns "EUR"
        every { normalizeOrNull(any()) } returns "EUR"
    }
    private val merchantCleaner = mockk<com.yourname.expensetracker.domain.util.MerchantCleaner> {
        every { clean(any()) } answers { firstArg() ?: "Unknown" }
    }
    private val directionDetector = mockk<TransferDirectionDetector> {
        every { detectDirection(any(), any(), any(), any()) } returns null
        every { extractAccountName(any(), any(), any()) } returns null
    }

    private val genericMock = mockk<GenericTransactionParser>()
    private val aiMock = mockk<NotificationFallbackParser>()

    private fun txn(confidence: Float, amount: Double = 12.5, merchant: String = "Attica") =
        ParsedTransaction(
            amount = amount,
            currency = "EUR",
            merchant = merchant,
            type = ParsedTransactionType.PURCHASE,
            confidence = confidence
        )

    private fun registryWithMockedFallbacks() = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR"),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = genericMock,
        aiFallbackParser = aiMock,
        timeProvider = mockk(),
        currencySettingsRepository = homeCurrencyRepo()
    )

    private fun homeCurrencyRepo() =
        mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>().also {
            coEvery { it.resolveHomeCurrency() } returns
                com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
                )
        }

    private fun registryWithRealGeneric() = AppParserRegistry(
        greekBankParser = GreekBankParser(currencyNormalizer, merchantCleaner, homeCurrency = "EUR"),
        revolutParser = RevolutParser(currencyNormalizer, merchantCleaner),
        smsParser = SmsParser(currencyNormalizer, merchantCleaner),
        googleWalletParser = GoogleWalletParser(currencyNormalizer, merchantCleaner),
        genericParser = GenericTransactionParser(currencyNormalizer, merchantCleaner, directionDetector, timeProvider = mockk()),
        aiFallbackParser = aiMock,
        timeProvider = mockk(),
        currencySettingsRepository = homeCurrencyRepo()
    )

    private fun stubGeneric(result: ParsedTransaction?) {
        every { genericMock.parse(any(), any(), any(), any(), any()) } returns result
    }

    private fun stubAi(result: ParsedTransaction?) {
        coEvery { aiMock.parse(any(), any(), any(), any()) } returns result
    }

    private fun newRouter(): ConfidenceRouter {
        val sourceStats = mockk<SourceStatsRepository>(relaxed = true)
        val corrections = mockk<UserCorrectionRepository>(relaxed = true)
        val classifier = mockk<TransactionClassifier>(relaxed = true)
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val clock = mockk<TimeProvider>(relaxed = true)
        every { clock.now() } returns 1_700_000_000_000L
        coEvery { sourceStats.getByPackage(any()) } returns null
        coEvery { corrections.getMerchantStats(any()) } returns
            UserCorrectionDao.MerchantCorrectionStats(total = 0, rejections = 0)
        coEvery { corrections.getPackageStats(any()) } returns
            UserCorrectionDao.PackageCorrectionStats(total = 0, rejections = 0)
        coEvery { corrections.getMerchantTotalCorrections(any()) } returns 0
        coEvery { corrections.getTotalCorrections(any()) } returns 0
        coEvery { corrections.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
        return ConfidenceRouter(barrier, sourceStats, corrections, classifier, clock)
    }

    // 1. parse(): SMS package, SmsParser null, generic result -> ambiguous, rest unchanged.
    @Test
    fun `FL-02 parse SMS package generic fallback is flagged ambiguous with amount and confidence unchanged`() {
        stubGeneric(txn(confidence = 0.7f, amount = 12.5))

        val result = registryWithMockedFallbacks().parse(
            title = "Attica", text = "Αγορά 12,50 EUR. Υπόλοιπο 500,00 EUR",
            bigText = null, subText = null, packageName = smsPackage
        )

        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        assertEquals(12.5, result.amount, 0.0)
        assertEquals(0.7f, result.confidence, 0.0f)
        assertEquals("Attica", result.merchant)
    }

    @Test
    fun `FL-02 parse SMS package real generic parser fallback is ambiguous and otherwise equals non-SMS result`() {
        val registry = registryWithRealGeneric()
        val title = "Attica"
        val text = "Αγορά 12,50 EUR. Υπόλοιπο 500,00 EUR"

        val sms = registry.parse(title, text, null, null, smsPackage)
        val nonSms = registry.parse(title, text, null, null, "com.completely.unknown.app")

        assertNotNull(sms)
        assertNotNull(nonSms)
        assertTrue(sms!!.isAmountAmbiguous)
        assertFalse(nonSms!!.isAmountAmbiguous)
        assertEquals(nonSms.copy(isAmountAmbiguous = true), sms)
    }

    // 2. parseWithProvenance(): generic win on SMS package.
    @Test
    fun `FL-02 parseWithProvenance SMS generic fallback is ambiguous and provenance unchanged`() = runBlocking {
        stubGeneric(txn(confidence = 0.7f))

        val outcome = registryWithMockedFallbacks().parseWithProvenance(
            title = "Attica", text = "Αγορά 12,50 EUR. Υπόλοιπο 500,00 EUR",
            bigText = null, subText = null, packageName = smsPackage
        )

        assertTrue(outcome is ParseOutcome.Parsed)
        val parsed = outcome.parsed!!
        assertTrue(parsed.isAmountAmbiguous)
        assertEquals(12.5, parsed.amount, 0.0)
        assertEquals(0.7f, parsed.confidence, 0.0f)
        assertEquals(ParserSource.GENERIC_DETERMINISTIC, outcome.provenance.source)
        assertEquals("GenericTransactionParser", outcome.provenance.winningParserId)
        assertEquals(0.7f, outcome.provenance.confidence!!, 0.0f)
        assertTrue(outcome.provenance.deterministicSucceeded)
        assertFalse(outcome.provenance.aiAttempted)
        coVerify(exactly = 0) { aiMock.parse(any(), any(), any(), any()) }
    }

    // 3. parseWithProvenance(): AI fallback win on SMS package.
    @Test
    fun `FL-02 parseWithProvenance SMS AI fallback is ambiguous with confidence 0_95 and AI source`() = runBlocking {
        stubGeneric(null)
        stubAi(txn(confidence = 0.95f))

        val outcome = registryWithMockedFallbacks().parseWithProvenance(
            title = "Attica", text = "Payment of 12,50 EUR. Balance 500,00 EUR",
            bigText = null, subText = null, packageName = smsPackage
        )

        assertTrue(outcome is ParseOutcome.Parsed)
        val parsed = outcome.parsed!!
        assertTrue(parsed.isAmountAmbiguous)
        assertEquals(0.95f, parsed.confidence, 0.0f)
        assertEquals(12.5, parsed.amount, 0.0)
        assertEquals(ParserSource.AI_FALLBACK, outcome.provenance.source)
        assertEquals(0.95f, outcome.provenance.confidence!!, 0.0f)
    }

    // 4. Deprecated parseWithAiFallback(): AI win on SMS package.
    @Suppress("DEPRECATION")
    @Test
    fun `FL-02 parseWithAiFallback SMS AI fallback is ambiguous with confidence unchanged`() = runBlocking {
        stubGeneric(null)
        stubAi(txn(confidence = 0.95f))

        val result = registryWithMockedFallbacks().parseWithAiFallback(
            title = "Attica", text = "Payment of 12,50 EUR. Balance 500,00 EUR",
            bigText = null, subText = null, packageName = smsPackage
        )

        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        assertEquals(0.95f, result.confidence, 0.0f)
        assertEquals(12.5, result.amount, 0.0)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `FL-02 parseWithAiFallback SMS generic fallback is ambiguous`() = runBlocking {
        stubGeneric(txn(confidence = 0.7f))

        val result = registryWithMockedFallbacks().parseWithAiFallback(
            title = "Attica", text = "Payment of 12,50 EUR",
            bigText = null, subText = null, packageName = smsPackage
        )

        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        assertEquals(0.7f, result.confidence, 0.0f)
    }

    // 5. Non-SMS package stays unflagged on every fallback path.
    @Test
    fun `FL-02 non-SMS package generic fallback stays not ambiguous in parse and parseWithProvenance`() = runBlocking {
        stubGeneric(txn(confidence = 0.7f))
        val registry = registryWithMockedFallbacks()

        val plain = registry.parse("Hello", "payment pending", null, null, financePackage)
        val outcome = registry.parseWithProvenance("Hello", "payment pending", null, null, financePackage)

        assertNotNull(plain)
        assertFalse(plain!!.isAmountAmbiguous)
        assertTrue(outcome is ParseOutcome.Parsed)
        assertFalse(outcome.parsed!!.isAmountAmbiguous)
        assertEquals(ParserSource.GENERIC_DETERMINISTIC, outcome.provenance.source)
    }

    @Test
    fun `FL-02 non-SMS package AI fallback stays not ambiguous in parseWithProvenance`() = runBlocking {
        stubGeneric(null)
        stubAi(txn(confidence = 0.95f))

        val outcome = registryWithMockedFallbacks()
            .parseWithProvenance("Hello", "payment pending", null, null, financePackage)

        assertTrue(outcome is ParseOutcome.Parsed)
        assertFalse(outcome.parsed!!.isAmountAmbiguous)
        assertEquals(0.95f, outcome.parsed!!.confidence, 0.0f)
        assertEquals(ParserSource.AI_FALLBACK, outcome.provenance.source)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `FL-02 non-SMS package AI fallback stays not ambiguous in parseWithAiFallback`() = runBlocking {
        stubGeneric(null)
        stubAi(txn(confidence = 0.95f))

        val result = registryWithMockedFallbacks()
            .parseWithAiFallback("Hello", "payment pending", null, null, financePackage)

        assertNotNull(result)
        assertFalse(result!!.isAmountAmbiguous)
        assertEquals(0.95f, result.confidence, 0.0f)
    }

    // 6. SmsParser's own results are passed through untouched.
    @Test
    fun `FL-02 SmsParser clean result stays not ambiguous and fallbacks are not consulted`() = runBlocking {
        val registry = registryWithMockedFallbacks()

        val plain = registry.parse("NBG", "AGORA 15,00 EUR STO KATASTIMA στις 07/02", null, null, smsPackage)
        val outcome = registry.parseWithProvenance("NBG", "AGORA 15,00 EUR STO KATASTIMA στις 07/02", null, null, smsPackage)

        assertNotNull(plain)
        assertFalse(plain!!.isAmountAmbiguous)
        assertEquals(15.0, plain.amount, 0.01)
        assertEquals(0.85f, plain.confidence, 0.0f)
        assertTrue(outcome is ParseOutcome.Parsed)
        assertFalse(outcome.parsed!!.isAmountAmbiguous)
        assertEquals(ParserSource.SPECIFIC_DETERMINISTIC, outcome.provenance.source)
        verify(exactly = 0) { genericMock.parse(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { aiMock.parse(any(), any(), any(), any()) }
    }

    @Test
    fun `FL-02 SmsParser ambiguous result stays ambiguous with amount unchanged`() = runBlocking {
        val registry = registryWithMockedFallbacks()
        val text = "AGORA 15,00 EUR STO KATASTIMA. Υπόλοιπο 500,00 EUR"

        val plain = registry.parse("NBG", text, null, null, smsPackage)
        val outcome = registry.parseWithProvenance("NBG", text, null, null, smsPackage)

        assertNotNull(plain)
        assertTrue(plain!!.isAmountAmbiguous)
        assertEquals(15.0, plain.amount, 0.01)
        assertTrue(outcome is ParseOutcome.Parsed)
        assertTrue(outcome.parsed!!.isAmountAmbiguous)
        assertEquals(15.0, outcome.parsed!!.amount, 0.01)
        assertEquals(ParserSource.SPECIFIC_DETERMINISTIC, outcome.provenance.source)
        verify(exactly = 0) { genericMock.parse(any(), any(), any(), any(), any()) }
    }

    // 7. End to end with a real ConfidenceRouter.
    @Test
    fun `FL-02 SMS AI fallback at 0_95 routes to NEEDS_REVIEW and same result on non-SMS package auto-accepts`() = runBlocking {
        stubGeneric(null)
        stubAi(txn(confidence = 0.95f))
        val registry = registryWithMockedFallbacks()
        val router = newRouter()

        val sms = registry.parseWithProvenance("Attica", "Payment of 12,50 EUR. Balance 500,00 EUR", null, null, smsPackage)
        val nonSms = registry.parseWithProvenance("Hello", "payment pending", null, null, financePackage)

        val smsRouted = router.route(sms.parsed!!, smsPackage)
        val nonSmsRouted = router.route(nonSms.parsed!!, financePackage)

        assertEquals(RoutingDecision.NEEDS_REVIEW, smsRouted.decision)
        assertEquals(RoutingDecision.AUTO_ACCEPT, nonSmsRouted.decision)
    }

    // Cancellation must propagate, never be swallowed by the fallback path.
    @Test
    fun `FL-02 parseWithProvenance rethrows CancellationException from AI fallback on SMS package`() = runBlocking {
        stubGeneric(null)
        coEvery { aiMock.parse(any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("cancelled")

        try {
            registryWithMockedFallbacks()
                .parseWithProvenance("Attica", "Payment of 12,50 EUR", null, null, smsPackage)
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }
}
