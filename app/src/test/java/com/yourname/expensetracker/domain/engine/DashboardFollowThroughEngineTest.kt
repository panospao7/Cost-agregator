package com.yourname.expensetracker.domain.engine

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.TransactionFilterSerializer
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for DashboardFollowThroughEngine.
 * Tests recommendation generation logic with deterministic rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFollowThroughEngineTest {

    private lateinit var engine: DashboardFollowThroughEngine
    private lateinit var serializer: TransactionFilterSerializer
    private val testDispatcher = StandardTestDispatcher()
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var thresholdCalculator: SpendingThresholdCalculator

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        serializer = TransactionFilterSerializer()
        thresholdCalculator = SpendingThresholdCalculator(expenseDao, timeProvider, testDispatcher)
        engine = DashboardFollowThroughEngine(serializer, thresholdCalculator, testDispatcher)
    }

    @Test
    fun `generateRecommendations creates recommendation from transaction and AI artifact`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 150.0,
            merchant = "Test Store",
            categoryId = 5L
        )
        val aiArtifact = createAiArtifact(
            summaryText = "Large purchase at Test Store detected"
        )

        val recommendations = engine.generateRecommendations(transaction, aiArtifact, userId)

        assertTrue(recommendations.isNotEmpty())
        val rec = recommendations.find { it.recommendationText == "Large purchase at Test Store detected" }
        assertNotNull(rec)
        assertEquals(userId, rec.userId)
    }

    @Test
    fun `generateRecommendations respects max 5 limit`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 150.0,
            merchant = "Test Store",
            categoryId = 5L
        )
        val aiArtifact = createAiArtifact()

        val recommendations = engine.generateRecommendations(transaction, aiArtifact, userId)

        assertTrue(recommendations.size <= 5)
    }

    @Test
    fun `generateRecommendations uses AI text when artifact provided`() = runTest {
        val userId = "user123"
        val aiText = "Custom AI-generated insight text"
        val transaction = createExpense(amount = 200.0)
        val aiArtifact = createAiArtifact(summaryText = aiText)

        val recommendations = engine.generateRecommendations(transaction, aiArtifact, userId)

        val highAmountRec = recommendations.find { it.priority == RecommendationPriority.HIGH }
        assertNotNull(highAmountRec)
        assertEquals(aiText, highAmountRec.recommendationText)
    }

    @Test
    fun `generateRecommendations generates fallback text when no AI artifact`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 250.0,
            merchant = "Amazon"
        )

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val highAmountRec = recommendations.find { it.priority == RecommendationPriority.HIGH }
        assertNotNull(highAmountRec)
        assertTrue(highAmountRec.recommendationText.contains("Amazon"))
        assertTrue(highAmountRec.recommendationText.contains("250"))
    }

    @Test
    fun `generateRecommendations creates filter criteria as deterministic JSON`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 150.0,
            categoryId = 10L
        )

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        recommendations.forEach { rec ->
            assertNotNull(rec.filterCriteria)
            assertTrue(rec.filterCriteria.isNotEmpty())
            // Should be valid JSON
            val filter = serializer.deserialize(rec.filterCriteria)
            assertNotNull(filter)
        }
    }

    @Test
    fun `generateRecommendations assigns correct priorities`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 150.0, // High amount
            merchant = "Test Merchant",
            categoryId = 5L
        )

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        // High amount should create HIGH priority recommendation
        val highPriorityRecs = recommendations.filter { it.priority == RecommendationPriority.HIGH }
        assertTrue(highPriorityRecs.isNotEmpty())

        // Category and merchant should create MEDIUM priority
        val mediumPriorityRecs = recommendations.filter { it.priority == RecommendationPriority.MEDIUM }
        assertTrue(mediumPriorityRecs.isNotEmpty())

        // Recent transactions should create LOW priority
        val lowPriorityRecs = recommendations.filter { it.priority == RecommendationPriority.LOW }
        assertTrue(lowPriorityRecs.isNotEmpty())
    }

    @Test
    fun `generateRecommendations creates high priority for large transactions above 100`() = runTest {
        val userId = "user123"
        val transaction = createExpense(amount = 150.0)

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val highPriorityRec = recommendations.find { it.priority == RecommendationPriority.HIGH }
        assertNotNull(highPriorityRec)
        assertEquals(DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, highPriorityRec.navigationTarget)
    }

    @Test
    fun `generateRecommendations skips high priority for transactions under 100`() = runTest {
        val userId = "user123"
        val transaction = createExpense(amount = 50.0)

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val highPriorityRecs = recommendations.filter { it.priority == RecommendationPriority.HIGH }
        assertEquals(0, highPriorityRecs.size)
    }

    @Test
    fun `generateRecommendations creates category recommendation when categoryId present`() = runTest {
        val userId = "user123"
        val categoryId = 7L
        val transaction = createExpense(categoryId = categoryId)

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val categoryRec = recommendations.find { it.category == categoryId.toString() }
        assertNotNull(categoryRec)
        assertEquals(DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL, categoryRec.navigationTarget)

        // Filter should include categoryId
        val filter = serializer.deserialize(categoryRec.filterCriteria)
        assertNotNull(filter)
        assertEquals(categoryId, filter.categoryId)
    }

    @Test
    fun `generateRecommendations creates merchant recommendation when merchant present`() = runTest {
        val userId = "user123"
        val merchantName = "Starbucks"
        val transaction = createExpense(merchant = merchantName)

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val merchantRec = recommendations.find { 
            it.recommendationText.contains(merchantName) || 
            it.navigationTarget == DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST 
        }
        assertNotNull(merchantRec)

        // Filter should include merchant name
        val filter = serializer.deserialize(merchantRec.filterCriteria)
        assertNotNull(filter)
    }

    @Test
    fun `generateRecommendations creates recent transactions recommendation`() = runTest {
        val userId = "user123"
        val transaction = createExpense()

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        // Should always create a recent transactions recommendation
        val recentRec = recommendations.find { 
            it.priority == RecommendationPriority.LOW &&
            it.navigationTarget == DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST
        }
        assertNotNull(recentRec)

        // Filter should have 7-day date range
        val filter = serializer.deserialize(recentRec.filterCriteria)
        assertNotNull(filter)
        assertNotNull(filter.dateRange)
    }

    @Test
    fun `generateRecommendations expiration is 7 days from creation`() = runTest {
        val userId = "user123"
        val transaction = createExpense(amount = 150.0)
        val beforeTest = System.currentTimeMillis()

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val afterTest = System.currentTimeMillis()
        val sevenDaysMillis = 7L * 24 * 60 * 60 * 1000

        recommendations.forEach { rec ->
            val expectedMin = beforeTest + sevenDaysMillis
            val expectedMax = afterTest + sevenDaysMillis
            assertTrue(rec.expiresAt in expectedMin..expectedMax)
        }
    }

    @Test
    fun `generateRecommendations sorts by priority and takes top 5`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 200.0, // HIGH priority
            merchant = "Test Store", // MEDIUM priority
            categoryId = 5L // MEDIUM priority
        )
        // This should generate 4 recommendations: 1 HIGH, 2 MEDIUM, 1 LOW

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        // Should be sorted with HIGH first
        assertTrue(recommendations.isNotEmpty())
        assertEquals(RecommendationPriority.HIGH, recommendations[0].priority)
        
        // All should be from HIGH to LOW in order
        for (i in 0 until recommendations.size - 1) {
            val currentRank = when (recommendations[i].priority) {
                RecommendationPriority.HIGH -> 3
                RecommendationPriority.MEDIUM -> 2
                RecommendationPriority.LOW -> 1
            }
            val nextRank = when (recommendations[i + 1].priority) {
                RecommendationPriority.HIGH -> 3
                RecommendationPriority.MEDIUM -> 2
                RecommendationPriority.LOW -> 1
            }
            assertTrue(currentRank >= nextRank)
        }
    }

    @Test
    fun `generateFromInsight creates recommendation with custom parameters`() = runTest {
        val userId = "user123"
        val insightText = "You spent 30% more on dining this month"
        val categoryId = 3L
        val startTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val endTime = System.currentTimeMillis()

        val recommendation = engine.generateFromInsight(
            insightText = insightText,
            categoryId = categoryId,
            dateRangeStart = startTime,
            dateRangeEnd = endTime,
            priority = RecommendationPriority.HIGH,
            userId = userId
        )

        assertEquals(insightText, recommendation.recommendationText)
        assertEquals(userId, recommendation.userId)
        assertEquals(RecommendationPriority.HIGH, recommendation.priority)
        assertEquals(DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, recommendation.navigationTarget)

        val filter = serializer.deserialize(recommendation.filterCriteria)
        assertNotNull(filter)
        assertEquals(categoryId, filter.categoryId)
        assertEquals(Pair(startTime, endTime), filter.dateRange)
    }

    @Test
    fun `generateFromInsight handles null categoryId`() = runTest {
        val userId = "user123"
        val insightText = "Overall spending is up"

        val recommendation = engine.generateFromInsight(
            insightText = insightText,
            categoryId = null,
            dateRangeStart = 1000L,
            dateRangeEnd = 2000L,
            priority = RecommendationPriority.MEDIUM,
            userId = userId
        )

        assertEquals("GENERAL", recommendation.category)
        val filter = serializer.deserialize(recommendation.filterCriteria)
        assertNotNull(filter)
        assertEquals(null, filter.categoryId)
    }

    @Test
    fun `generateRecommendations includes sourceArtifactId when artifact provided`() = runTest {
        val userId = "user123"
        val artifactId = 42L
        val transaction = createExpense(amount = 150.0)
        val aiArtifact = createAiArtifact(id = artifactId)

        val recommendations = engine.generateRecommendations(transaction, aiArtifact, userId)

        recommendations.forEach { rec ->
            assertEquals(artifactId.toString(), rec.sourceArtifactId)
        }
    }

    @Test
    fun `generateRecommendations handles blank merchant name`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            merchant = "",
            categoryId = 5L
        )

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        // Should not create merchant-specific recommendation
        assertTrue(recommendations.all { 
            it.navigationTarget != DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST ||
            it.priority != RecommendationPriority.MEDIUM ||
            !it.recommendationText.contains("merchant", ignoreCase = true)
        })
    }

    @Test
    fun `generateRecommendations filter includes transaction type`() = runTest {
        val userId = "user123"
        val transaction = createExpense(
            amount = 150.0,
            transactionType = TransactionType.PURCHASE
        )

        val recommendations = engine.generateRecommendations(transaction, null, userId)

        val highAmountRec = recommendations.find { it.priority == RecommendationPriority.HIGH }
        assertNotNull(highAmountRec)

        val filter = serializer.deserialize(highAmountRec.filterCriteria)
        assertNotNull(filter)
        assertEquals(TransactionType.PURCHASE, filter.transactionType)
    }

    // Helper functions
    private fun createExpense(
        id: Long = 0,
        amount: Double = 50.0,
        merchant: String = "Test Merchant",
        transactionType: TransactionType = TransactionType.PURCHASE,
        categoryId: Long? = null,
        date: Long = System.currentTimeMillis()
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = "EUR",
            merchant = merchant,
            transactionType = transactionType,
            categoryId = categoryId,
            date = date
        )
    }

    private fun createAiArtifact(
        id: Long = 1,
        summaryText: String = "AI-generated summary"
    ): AiArtifactEntity {
        return AiArtifactEntity(
            id = id,
            targetType = AiTargetType.DASHBOARD,
            targetKey = "test_key",
            capability = AiCapability.DASHBOARD_BRIEFING,
            status = AiArtifactStatus.READY,
            mode = AiMode.ON_DEVICE,
            summaryText = summaryText,
            explanationText = null,
            sourceHash = "hash123",
            promptVersion = "v1",
            payloadJson = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            expiresAt = null
        )
    }
}
