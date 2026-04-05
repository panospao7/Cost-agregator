package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
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

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
        router = ConfidenceRouter(sourceStatsRepository, userCorrectionRepository, classifier, timeProvider)

        // Default: no source stats, no corrections, classifier not ready
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
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
    fun `unknown merchant gets confidence penalty`() = runBlocking {
        val result = router.route(makeParsed(0.90f, "Unknown"), "com.test")
        // 0.90 * 0.5 = 0.45, which is below REVIEW_THRESHOLD
        assertTrue(result.adjustedConfidence < 0.90f)
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
                acceptedAsExpense = 1
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
