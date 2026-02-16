package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class ConfidenceRouterEdgeCaseTest {
    
    private lateinit var router: ConfidenceRouter
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)

    @Before
    fun setup() {
        router = ConfidenceRouter(sourceStatsDao, userCorrectionDao, classifier)
        coEvery { sourceStatsDao.getByPackage(any()) } returns null
        coEvery { userCorrectionDao.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.getMerchantRejectionCount(any()) } returns 0
        coEvery { userCorrectionDao.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
    }

    private fun makeParsed(confidence: Float, merchant: String = "TestMerchant") =
        ParsedTransaction(10.0, "EUR", merchant, TransactionType.PURCHASE, confidence)

    @Test
    fun `exact threshold boundary - auto accept at exactly 0_85`() = runBlocking {
        val result = router.route(makeParsed(0.85f), "com.test")
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }

    @Test
    fun `just below auto accept threshold - needs review`() = runBlocking {
        val result = router.route(makeParsed(0.849f), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `exact threshold boundary - review at exactly 0_50`() = runBlocking {
        val result = router.route(makeParsed(0.50f), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `just below review threshold - auto reject`() = runBlocking {
        val result = router.route(makeParsed(0.499f), "com.test")
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }

    @Test
    fun `invalid confidence NaN is handled gracefully`() = runBlocking {
        val result = router.route(makeParsed(Float.NaN), "com.test")
        assertTrue("NaN should result in AUTO_REJECT or NEEDS_REVIEW",
            result.decision == RoutingDecision.AUTO_REJECT || 
            result.decision == RoutingDecision.NEEDS_REVIEW
        )
    }

    @Test
    fun `null merchant name applies penalty`() = runBlocking {
        val result = router.route(makeParsed(0.90f, ""), "com.test")
        assertTrue("Empty merchant should reduce confidence", 
            result.adjustedConfidence < 0.90f
        )
    }

    @Test
    fun `sourceStats with zero totalNotifications does not crash`() = runBlocking {
        coEvery { sourceStatsDao.getByPackage("com.test") } returns
            SourceStats(
                packageName = "com.test",
                totalNotifications = 0,
                acceptedAsExpense = 0
            )
        
        val result = router.route(makeParsed(0.90f), "com.test")
        assertNotNull("Zero notifications should not cause division by zero", result)
    }
}
