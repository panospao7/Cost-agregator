package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class ConfidenceRouterEdgeCaseTest {
    
    private lateinit var router: ConfidenceRouter
    private val sourceStatsRepository = mockk<SourceStatsRepository>(relaxed = true)
    private val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
        router = ConfidenceRouter(sourceStatsRepository, userCorrectionRepository, classifier, timeProvider)
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
        coEvery { userCorrectionRepository.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionRepository.getMerchantRejectionCount(any()) } returns 0
        coEvery { userCorrectionRepository.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns false
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

    @Test(expected = IllegalArgumentException::class)
    fun `invalid confidence NaN is rejected at construction`() {
        makeParsed(Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank merchant name is rejected at construction`() {
        makeParsed(0.90f, "")
    }

    @Test
    fun `sourceStats with zero totalNotifications does not crash`() = runBlocking {
        coEvery { sourceStatsRepository.getByPackage("com.test") } returns
            SourceStats(
                packageName = "com.test",
                totalNotifications = 0,
                acceptedAsExpense = 0
            )
        
        val result = router.route(makeParsed(0.90f), "com.test")
        assertNotNull("Zero notifications should not cause division by zero", result)
    }
}
