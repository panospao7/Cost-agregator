package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.*

class ConfidenceRouterTest {

    private lateinit var router: ConfidenceRouter
    private val sourceStatsRepository = mockk<SourceStatsRepository>(relaxed = true)
    private val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val writeBarrier = mockk<com.yourname.expensetracker.data.backup.DatabaseWriteBarrier>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setup() {
        every { timeProvider.now() } returns fixedNow
        router = ConfidenceRouter(writeBarrier, sourceStatsRepository, userCorrectionRepository, classifier, timeProvider)

        // Default: no source stats, no corrections, classifier not ready
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
        coEvery { userCorrectionRepository.getMerchantStats(any()) } returns
            UserCorrectionDao.MerchantCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.getPackageStats(any()) } returns
            UserCorrectionDao.PackageCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionRepository.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
    }

    private fun makeParsed(confidence: Float, merchant: String = "TestMerchant") =
        ParsedTransaction(10.0, "EUR", merchant, ParsedTransactionType.PURCHASE, confidence)

    @Test
    fun `high confidence auto-accepts`() = runBlocking {
        val result = router.route(makeParsed(0.95f), "com.test")
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }

    @Test
    fun `medium confidence needs review`() = runBlocking {
        val result = router.route(makeParsed(0.70f), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `low confidence auto-rejects`() = runBlocking {
        val result = router.route(makeParsed(0.30f), "com.test")
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }

    @Test
    fun `unknown merchant penalty floors to REVIEW when penalty alone causes drop`() = runBlocking {
        val result = router.route(makeParsed(0.90f, "Unknown"), "com.test")
        // 0.90 * 0.5 = 0.45 < REVIEW, but floor applies → 0.50 → NEEDS_REVIEW
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
        assertTrue(result.adjustedConfidence >= ConfidenceRouter.REVIEW_THRESHOLD)
    }

    @Test
    fun `unknown merchant penalty does not drop below REVIEW_THRESHOLD when parser confidence is high`() = runBlocking {
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
        coEvery { userCorrectionRepository.getMerchantStats(any()) } returns
            UserCorrectionDao.MerchantCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.getPackageStats(any()) } returns
            UserCorrectionDao.PackageCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns false
        every { timeProvider.now() } returns fixedNow

        val unknownHighConfidence = router.route(
            ParsedTransaction(4.08, "EUR", "Unknown", ParsedTransactionType.PURCHASE, 0.90f),
            "com.test"
        )
        assertEquals(RoutingDecision.NEEDS_REVIEW, unknownHighConfidence.decision)
        assertTrue(unknownHighConfidence.adjustedConfidence >= ConfidenceRouter.REVIEW_THRESHOLD)

        val unknownLowConfidence = router.route(
            ParsedTransaction(4.08, "EUR", "Unknown", ParsedTransactionType.PURCHASE, 0.30f),
            "com.test"
        )
        assertEquals(RoutingDecision.AUTO_REJECT, unknownLowConfidence.decision)
        assertTrue(unknownLowConfidence.adjustedConfidence < ConfidenceRouter.REVIEW_THRESHOLD)

        val knownHighConfidence = router.route(
            ParsedTransaction(4.08, "EUR", "Starbucks", ParsedTransactionType.PURCHASE, 0.90f),
            "com.test"
        )
        assertEquals(RoutingDecision.AUTO_ACCEPT, knownHighConfidence.decision)
    }

    @Test
    fun `review floor does NOT override other penalties that already dropped confidence below REVIEW`() = runBlocking {
        // Simulate a spam source: trust modifier = 0.1
        // 0.90 * 0.1 (spam) = 0.09, then * 0.5 (unknown merchant) = 0.045
        // The pre-penalty confidence is already 0.09 < REVIEW_THRESHOLD,
        // so the floor should NOT apply — anti-spam signal must be respected.
        coEvery { sourceStatsRepository.getByPackage("com.spam") } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = "com.spam",
                totalNotifications = 100,
                acceptedAsExpense = 1,
                lastSeen = fixedNow
            )

        val result = router.route(
            ParsedTransaction(4.08, "EUR", "Unknown", ParsedTransactionType.PURCHASE, 0.90f),
            "com.spam"
        )
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
        assertTrue(result.adjustedConfidence < ConfidenceRouter.REVIEW_THRESHOLD)
    }

    @Test
    fun `previously approved merchant gets boost`() = runBlocking {
        coEvery { userCorrectionRepository.hasPreviousApprovals("TestMerchant", "com.test") } returns true
        val result = router.route(makeParsed(0.80f), "com.test")
        assertTrue(result.adjustedConfidence > 0.80f)
    }

    @Test
    fun `high merchant rejection rate reduces confidence`() = runBlocking {
        val merchant = "TestMerchant"
        coEvery { userCorrectionRepository.getMerchantStats(merchant) } returns 
            com.yourname.expensetracker.data.database.dao.UserCorrectionDao.MerchantCorrectionStats(
                total = 10,
                rejections = 9
            )

        val result = router.route(makeParsed(0.90f), "com.test")
        assertTrue(result.adjustedConfidence < 0.90f)
    }

    @Test
    fun `spam source dramatically reduces confidence`() = runBlocking {
        coEvery { sourceStatsRepository.getByPackage("com.spam") } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = "com.spam",
                totalNotifications = 100,
                acceptedAsExpense = 1,
                lastSeen = fixedNow
            )

        val result = router.route(makeParsed(0.90f), "com.spam")
        assertTrue(result.adjustedConfidence < 0.50f)
    }

    @Test
    fun `confidence is clamped to 0-1 range`() = runBlocking {
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns true

        val result = router.route(makeParsed(0.99f), "com.test")
        assertTrue(result.adjustedConfidence <= 1.0f)
        assertTrue(result.adjustedConfidence >= 0.0f)
    }

    // --- R-SMS-3 / FL-02: ambiguous-amount routing (parser emits 0.85 for ambiguous results) ---

    private fun makeAmbiguous(confidence: Float = 0.85f, merchant: String = "TestMerchant") =
        ParsedTransaction(
            amount = 10.0,
            currency = "EUR",
            merchant = merchant,
            type = ParsedTransactionType.PURCHASE,
            confidence = confidence,
            isAmountAmbiguous = true
        )

    private fun makeClean(confidence: Float = 0.85f, merchant: String = "TestMerchant") =
        makeAmbiguous(confidence, merchant).copy(isAmountAmbiguous = false)

    private fun mlReady(totalSamples: Int) {
        coEvery { classifier.getStats() } returns
            ClassifierStats(totalPositive = totalSamples / 2, totalNegative = totalSamples - totalSamples / 2, vocabularySize = 100, isReady = true)
        coEvery { classifier.predict(any()) } returns 0.1f
    }

    private fun trustedSource(pkg: String) {
        // trustScore = 95/100 = 0.95 > 0.8 -> trust modifier 1.1
        coEvery { sourceStatsRepository.getByPackage(pkg) } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = pkg,
                totalNotifications = 100,
                acceptedAsExpense = 95,
                lastSeen = fixedNow
            )
    }

    @Test
    fun `ambiguous 0_85 with no boosts is NEEDS_REVIEW not AUTO_ACCEPT`() = runBlocking {
        val result = router.route(makeAmbiguous(), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `ambiguous 0_85 with ML ready low weight and prediction 0_1 is NEEDS_REVIEW never AUTO_REJECT`() = runBlocking {
        // 60 samples -> ML weight 0.35: 0.85*0.65 + 0.1*0.35 = 0.5875 >= REVIEW_THRESHOLD
        mlReady(60)
        val result = router.route(makeAmbiguous(), "com.test", notificationText = "synthetic text")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
        assertNotEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }

    @Test
    fun `ambiguity adds no penalty - at ML weight 0_5 an ambiguous parse routes exactly like a clean one`() = runBlocking {
        // 150 samples -> ML weight 0.5: blend 0.85*0.5 + 0.1*0.5 = 0.475 < REVIEW_THRESHOLD for BOTH.
        // R-SMS-3 removed the parser-side ambiguity penalty. A well-trained classifier rejecting the
        // text is existing behaviour for clean parses too; the plan adds no floor, because a floor
        // would override anti-spam signals.
        mlReady(150)
        val ambiguous = router.route(makeAmbiguous(), "com.test", notificationText = "synthetic text")
        val clean = router.route(makeClean(), "com.test", notificationText = "synthetic text")
        assertEquals(clean.decision, ambiguous.decision)
        assertEquals(clean.adjustedConfidence, ambiguous.adjustedConfidence, 0.0001f)
    }

    @Test
    fun `ambiguity adds no penalty - at ML weight 0_6 an ambiguous parse routes exactly like a clean one`() = runBlocking {
        // 300 samples -> ML weight 0.6: blend 0.85*0.4 + 0.1*0.6 = 0.40 < REVIEW_THRESHOLD for BOTH.
        mlReady(300)
        val ambiguous = router.route(makeAmbiguous(), "com.test", notificationText = "synthetic text")
        val clean = router.route(makeClean(), "com.test", notificationText = "synthetic text")
        assertEquals(clean.decision, ambiguous.decision)
        assertEquals(clean.adjustedConfidence, ambiguous.adjustedConfidence, 0.0001f)
    }

    @Test
    fun `ambiguous with previous-approval boost and source trust adjusted at least 0_85 is NEEDS_REVIEW`() = runBlocking {
        coEvery { userCorrectionRepository.hasPreviousApprovals("TestMerchant", "com.trusted") } returns true
        trustedSource("com.trusted")

        val result = router.route(makeAmbiguous(), "com.trusted")
        assertTrue(
            "precondition: boosts must lift adjusted confidence to AUTO_ACCEPT range",
            result.adjustedConfidence >= ConfidenceRouter.AUTO_ACCEPT_THRESHOLD
        )
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `non-ambiguous 0_85 with same boosts still AUTO_ACCEPT`() = runBlocking {
        coEvery { userCorrectionRepository.hasPreviousApprovals("TestMerchant", "com.trusted") } returns true
        trustedSource("com.trusted")

        val result = router.route(makeClean(), "com.trusted")
        assertTrue(result.adjustedConfidence >= ConfidenceRouter.AUTO_ACCEPT_THRESHOLD)
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }

    @Test
    fun `non-ambiguous 0_85 without boosts is AUTO_ACCEPT`() = runBlocking {
        val result = router.route(makeClean(), "com.test")
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }

    @Test
    fun `ambiguous result from spam source is still AUTO_REJECT because anti-spam signal is respected`() = runBlocking {
        coEvery { sourceStatsRepository.getByPackage("com.spam") } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = "com.spam",
                totalNotifications = 100,
                acceptedAsExpense = 1,
                lastSeen = fixedNow
            )
        val result = router.route(makeAmbiguous(), "com.spam")
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }
}
