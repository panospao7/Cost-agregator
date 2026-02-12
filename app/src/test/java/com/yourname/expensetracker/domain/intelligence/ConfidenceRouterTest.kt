package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.*

class ConfidenceRouterTest {

    private lateinit var router: ConfidenceRouter
    private val sourceStatsDao = mockk<com.yourname.expensetracker.data.database.dao.SourceStatsDao>(relaxed = true)
    private val userCorrectionDao = mockk<com.yourname.expensetracker.data.database.dao.UserCorrectionDao>(relaxed = true)

    // Use Fake instead of MockK to avoid ClassCastException issues with interface mocking
    private val classifier = FakeClassifier()

    class FakeClassifier : ITransactionClassifier {
        override suspend fun initialize() {}
        override suspend fun predict(text: String): Float = 0.5f
        override suspend fun train(text: String, isTransaction: Boolean) {}
        override fun retrainFromCorrections() {}
        override fun getStats(): ClassifierStats = ClassifierStats(0, 0, 0, false)
        override val stats: StateFlow<ClassifierStats> = MutableStateFlow(ClassifierStats(0, 0, 0, false))
    }

    @Before
    fun setup() {
        router = ConfidenceRouter(sourceStatsDao, userCorrectionDao, classifier)

        // Default: no source stats, no corrections
        coEvery { sourceStatsDao.getByPackage(any()) } returns null
        coEvery { userCorrectionDao.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns false
    }

    private fun makeParsed(confidence: Float, merchant: String = "TestMerchant") =
        ParsedTransaction(10.0, "EUR", merchant, TransactionType.PURCHASE, confidence)

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
        coEvery { userCorrectionDao.hasPreviousApprovals("TestMerchant", "com.test") } returns true
        val result = router.route(makeParsed(0.80f), "com.test")
        assertTrue(result.adjustedConfidence > 0.80f)
    }

    @Test
    fun `high merchant rejection rate reduces confidence`() = runBlocking {
        coEvery { userCorrectionDao.getMerchantTotalCorrections("TestMerchant") } returns 10
        coEvery { userCorrectionDao.getMerchantRejectionCount("TestMerchant") } returns 8

        val result = router.route(makeParsed(0.90f), "com.test")
        assertTrue(result.adjustedConfidence < 0.90f)
    }

    @Test
    fun `spam source dramatically reduces confidence`() = runBlocking {
        coEvery { sourceStatsDao.getByPackage("com.spam") } returns
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
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns true

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
