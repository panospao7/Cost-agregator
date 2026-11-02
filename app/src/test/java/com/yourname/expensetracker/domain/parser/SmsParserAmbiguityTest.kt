package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * fix-1.0.4 closure P1 (FL-02): the SMS amount read is the FIRST currency amount, exactly as
 * before. FL-02 is closed by review, not by guessing: an SMS with two or more different
 * (amount, currency) pairs, or with a balance word as a whole token, is flagged
 * [ParsedTransaction.isAmountAmbiguous] and the router's ambiguity cap sends it to
 * NEEDS_REVIEW. Confidence stays 0.85. All inputs are synthetic.
 */
class SmsParserAmbiguityTest {

    private lateinit var parser: SmsParser
    private lateinit var router: ConfidenceRouter

    private val smsPackage = "com.google.android.apps.messaging"

    @Before
    fun setup() {
        val currencyNormalizer = CurrencyNormalizer()
        val merchantCleaner = mockk<MerchantCleaner> {
            every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = SmsParser(currencyNormalizer, merchantCleaner)

        val sourceStatsRepository = mockk<SourceStatsRepository>(relaxed = true)
        val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
        val classifier = mockk<TransactionClassifier>(relaxed = true)
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
        coEvery { userCorrectionRepository.getMerchantStats(any()) } returns
            UserCorrectionDao.MerchantCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.getPackageStats(any()) } returns
            UserCorrectionDao.PackageCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
        router = ConfidenceRouter(
            writeBarrier = writeBarrier,
            sourceStatsRepository = sourceStatsRepository,
            userCorrectionRepository = userCorrectionRepository,
            classifier = classifier,
            timeProvider = timeProvider
        )
    }

    private fun parse(text: String, title: String = "NBG", bigText: String? = null): ParsedTransaction? =
        parser.parse(title = title, text = text, bigText = bigText, subText = null, packageName = smsPackage)

    /**
     * Parses [text], asserts the first amount is read, the parse is ambiguous at the unchanged
     * 0.85 confidence, and the router demotes it to NEEDS_REVIEW (never AUTO_ACCEPT).
     */
    private fun assertFirstAmountAmbiguousReview(text: String, expectedAmount: Double) = runBlocking {
        val result = parse(text)
        assertNotNull("must parse: $text", result)
        assertEquals(expectedAmount, result!!.amount, 0.001)
        assertTrue("must be ambiguous: $text", result.isAmountAmbiguous)
        assertEquals(0.85f, result.confidence, 0.0001f)
        val decision = router.route(result, smsPackage).decision
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, decision)
        assertEquals(RoutingDecision.NEEDS_REVIEW, decision)
    }

    // --- Amount parity: the FIRST amount is read; the second amount makes it ambiguous ---

    @Test
    fun `FL-02 charged purchase followed by Bal reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Card charged EUR 12.50 at SHOP. Bal EUR 500.00", 12.50)
    }

    @Test
    fun `FL-02 purchase followed by Balance without punctuation reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Purchase EUR 12.50 Balance EUR 500.00", 12.50)
    }

    @Test
    fun `FL-02 purchase followed by Bal dot abbreviation reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Purchase EUR 12.50 Bal. EUR 500.00", 12.50)
    }

    @Test
    fun `FL-02 balance sentence after purchase reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Your balance after purchase of EUR 20.00 is EUR 480.00", 20.00)
    }

    @Test
    fun `FL-02 Greek purchase followed by abbreviated balance reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Αγορά 12,50 EUR Υπόλ. 500,00 EUR", 12.50)
    }

    @Test
    fun `FL-02 Greek charge with euro sign followed by abbreviated balance reads first amount and routes to review`() {
        assertFirstAmountAmbiguousReview("Χρέωση 25,30€ Υπόλ.: 480,00€", 25.30)
    }

    @Test
    fun `FL-02 two different non-balance amounts are ambiguous even without a balance word`() {
        assertFirstAmountAmbiguousReview("Purchase EUR 12.50 at SHOP, fee EUR 1.00", 12.50)
    }

    @Test
    fun `FL-02 two amounts in different currencies are ambiguous`() {
        assertFirstAmountAmbiguousReview("Purchase EUR 10.00 at SHOP, USD 10.00 equivalent", 10.00)
    }

    @Test
    fun `FL-02 ambiguous parse keeps the clean confidence of 0_85 so the router cap does the demotion`() {
        val result = parse("Card charged EUR 12.50 at SHOP. Bal EUR 500.00")
        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        assertEquals(0.85f, result.confidence, 0.0001f)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    // --- FL-02 core: a balance never silently becomes an expense ---

    // KNOWN LIMITATION: when the balance comes first the parser still reads the FIRST amount
    // (the balance), so the review screen is pre-filled with the balance, not the purchase.
    // The safety property pinned here is that such an SMS is never auto-accepted.

    @Test
    fun `FL-02 balance first English SMS is ambiguous and routes to review not auto accept`() = runBlocking {
        val result = parse("Bal EUR 500.00. Purchase EUR 12.50")
        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        val decision = router.route(result, smsPackage).decision
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, decision)
        assertEquals(RoutingDecision.NEEDS_REVIEW, decision)
    }

    @Test
    fun `FL-02 balance first Greek SMS is ambiguous and routes to review not auto accept`() = runBlocking {
        val result = parse("Υπόλοιπο 500,00 EUR. Αγορά 12,50 EUR")
        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        val decision = router.route(result, smsPackage).decision
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, decision)
        assertEquals(RoutingDecision.NEEDS_REVIEW, decision)
    }

    @Test
    fun `FL-02 single amount carrying a balance keyword is ambiguous and never auto accepted`() = runBlocking {
        val result = parse("Payment info: balance EUR 500.00")
        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        val decision = router.route(result, smsPackage).decision
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, decision)
        assertEquals(RoutingDecision.NEEDS_REVIEW, decision)
    }

    @Test
    fun `FL-02 Greek balance word flagged ambiguous even with a single amount`() {
        val result = parse("Αγορά 12,50 EUR. Δείτε το υπόλοιπο στην εφαρμογή")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `FL-02 all-caps Greek purchase and balance is read as the first amount and routes to review`() {
        // The gate lowercases the body, so "ΑΓΟΡΑ" satisfies "αγορ" and "ΥΠΟΛΟΙΠΟ" satisfies the
        // balance token "υπολοιπο"; the SMS is accepted and flagged.
        assertFirstAmountAmbiguousReview("ΑΓΟΡΑ 12,50 EUR ΥΠΟΛΟΙΠΟ 500,00 EUR", 12.50)
    }

    // --- Clean paths are unchanged ---

    @Test
    fun `FL-02 clean single-amount purchase stays 0_85 not ambiguous and auto accepts`() = runBlocking {
        val result = parse("Purchase of EUR 12.50 at COFFEE", title = "Revolut")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
        assertEquals(0.85f, result.confidence, 0.0001f)
        assertEquals(RoutingDecision.AUTO_ACCEPT, router.route(result, smsPackage).decision)
    }

    @Test
    fun `FL-02 control - text duplicated into bigText stays one clean amount and auto accepts`() = runBlocking {
        val body = "Purchase EUR 12.50 at COFFEE"
        val result = parse(body, title = "Revolut", bigText = body)
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
        assertEquals(RoutingDecision.AUTO_ACCEPT, router.route(result, smsPackage).decision)
    }

    @Test
    fun `FL-02 the same amount written twice in one SMS is not ambiguous`() {
        val result = parse("Purchase EUR 12.50 at SHOP. Total EUR 12.50", title = "Revolut")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
    }

    @Test
    fun `FL-02 amount directly after a comma separator parses`() {
        val result = parse("Purchase at SHOP,12.50 EUR")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
    }

    @Test
    fun `FL-02 currency-first amount followed by a comma parses`() {
        val result = parse("Purchase EUR 12.50, at COFFEE")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
    }

    // --- Balance-word token boundaries ---

    @Test
    fun `FL-02 bal inside GLOBAL is not a balance word`() {
        val result = parse("Purchase at GLOBAL EUR 5.00")
        assertNotNull(result)
        assertEquals(5.00, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
    }

    @Test
    fun `FL-02 bal at the start of Balmain is not a balance word`() {
        val result = parse("Purchase at Balmain EUR 5.00")
        assertNotNull(result)
        assertEquals(5.00, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
    }

    @Test
    fun `FL-02 lone amount after a whole-token BAL merchant word is kept but routes to review`() {
        assertFirstAmountAmbiguousReview("Purchase at BAL HARBOUR EUR 25.00", 25.00)
    }

    @Test
    fun `FL-02 lone amount after a limit word with a purchase cue routes to review`() {
        assertFirstAmountAmbiguousReview("Limit alert: purchase EUR 12.50", 12.50)
    }

    // --- Type and keyword gate are the original ones ---

    @Test
    fun `R-SMS-2 salary credited with paid by is a deposit`() {
        val result = parse("Salary credited EUR 1,500.00, paid by ACME")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 prepaid card credited is a deposit`() {
        val result = parse("Prepaid card credited EUR 50.00")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(50.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 Greek refund to debit card is a deposit`() {
        val result = parse("Επιστροφή 15,00 EUR στη χρεωστική *1234")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(15.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 deposit followed by Balance is a deposit of the first amount flagged ambiguous`() {
        val result = parse("Deposit EUR 1,500.00 Balance EUR 2,000.00")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.00, result.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-4 payment reminder with a date but no currency is rejected`() {
        assertNull(parse("NBG: card payment due 15.11"))
    }

    @Test
    fun `R-SMS-4 purchase keyword with currency-less amount is rejected`() {
        assertNull(parse("Purchase 12.50 at COFFEE"))
    }

    @Test
    fun `R-SMS-4 message with no transaction keyword is rejected`() {
        assertNull(parse("You paid EUR 5.00 at SHOP"))
    }

    // --- Comma and truncation ---

    @Test
    fun `R-SMS-5 grouped million is never truncated to 1500`() {
        val result = parse("Purchase EUR 1,500,000")
        assertTrue(result == null || result.amount != 1500.0)
    }
}
