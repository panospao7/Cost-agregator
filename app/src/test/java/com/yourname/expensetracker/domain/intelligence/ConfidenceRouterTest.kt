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
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setup() {
        every { timeProvider.now() } returns fixedNow
        router = ConfidenceRouter(sourceStatsRepository, userCorrectionRepository, classifier, timeProvider)

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

    @Test
    fun `thresholds are correct`() {
        assertEquals(0.85f, ConfidenceRouter.AUTO_ACCEPT_THRESHOLD)
        assertEquals(0.50f, ConfidenceRouter.REVIEW_THRESHOLD)
    }
}
