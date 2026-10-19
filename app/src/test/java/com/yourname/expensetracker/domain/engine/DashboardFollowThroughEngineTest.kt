package com.yourname.expensetracker.domain.engine

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.TransactionFilterSerializer
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for DashboardFollowThroughEngine.
 * Tests recommendation generation logic with deterministic rules.
 *
 * Production facts used for expected values:
 * - Adaptive HIGH threshold = max(P90 of last 90 days, 50.0); with fewer than 10 samples
 *   (including empty history) it is the 50.0 minimum, and HIGH requires amount strictly greater.
 * - Rules emit in order HIGH (threshold), category MEDIUM, merchant MEDIUM, recent LOW;
 *   the stable priority sort keeps that order within equal priority.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFollowThroughEngineTest {

    private lateinit var engine: DashboardFollowThroughEngine
    private lateinit var serializer: TransactionFilterSerializer
    private val testDispatcher = StandardTestDispatcher()
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var thresholdCalculator: SpendingThresholdCalculator
    private lateinit var originalLocale: Locale

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Fallback text uses String.format("%.2f") with the default locale; pin it.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        serializer = TransactionFilterSerializer()
        every { timeProvider.now() } returns FIXED_NOW
        thresholdCalculator = SpendingThresholdCalculator(expenseDao, timeProvider, testDispatcher)
        engine = DashboardFollowThroughEngine(serializer, thresholdCalculator, timeProvider, testDispatcher)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Dispatchers.resetMain()
    }

    /** Fresh engine + fresh threshold calculator (its 24h cache is per instance). */
    private fun newEngine(): DashboardFollowThroughEngine = DashboardFollowThroughEngine(
        serializer,
        SpendingThresholdCalculator(expenseDao, timeProvider, testDispatcher),
        timeProvider,
        testDispatcher
    )

    // ── Text: AI artifact vs fallback ─────────────────────────────────────────

    @Test
    fun `generateRecommendations uses AI text and userId on every recommendation when artifact provided`() = runTest {
        val userId = "user123"
        val aiText = "Large purchase at Test Store detected"
        val transaction = createExpense(amount = 150.0, merchant = "Test Store", categoryId = 5L)

        val recommendations = engine.generateRecommendations(transaction, createAiArtifact(summaryText = aiText), userId)

        // HIGH + category + merchant + recent
        assertEquals(4, recommendations.size, "recommendation count")
        recommendations.forEachIndexed { i, rec ->
            assertEquals(aiText, rec.recommendationText, "[#$i ${rec.priority}] AI summary text")
            assertEquals(userId, rec.userId, "[#$i ${rec.priority}] userId")
        }
        val highAmountRec = assertNotNull(recommendations.find { it.priority == RecommendationPriority.HIGH })
        assertEquals(aiText, highAmountRec.recommendationText, "HIGH rec AI text")
    }

    private data class FallbackRow(
        val label: String,
        val index: Int,
        val priority: RecommendationPriority,
        val navigationTarget: String,
        val expectedText: String
    )

    @Test
    fun `generateRecommendations exact fallback text per rule when no AI artifact`() = runTest {
        val transaction = createExpense(amount = 250.0, merchant = "Amazon", categoryId = 5L)

        val recommendations = engine.generateRecommendations(transaction, null, "user123")

        val rows = listOf(
            FallbackRow(
                "high amount", 0, RecommendationPriority.HIGH,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                "Large transaction detected: Amazon - €250.00"
            ),
            FallbackRow(
                "category", 1, RecommendationPriority.MEDIUM,
                DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL,
                "Review all transactions in this category"
            ),
            FallbackRow(
                "merchant", 2, RecommendationPriority.MEDIUM,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                "Review all transactions from Amazon"
            ),
            FallbackRow(
                "recent", 3, RecommendationPriority.LOW,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                "Review your recent spending this week"
            )
        )
        assertEquals(4, rows.size, "fallback table size guard")
        assertEquals(4, recommendations.size, "recommendation count")
        for (row in rows) {
            val rec = recommendations[row.index]
            assertEquals(row.priority, rec.priority, "[${row.label}] priority")
            assertEquals(row.navigationTarget, rec.navigationTarget, "[${row.label}] navigation target")
            assertEquals(row.expectedText, rec.recommendationText, "[${row.label}] fallback text")
            assertEquals("", rec.sourceArtifactId, "[${row.label}] sourceArtifactId without artifact")
        }
    }

    // ── High-amount threshold (min 50.0, strictly greater) ────────────────────

    private data class ThresholdRow(
        val label: String,
        val amount: Double,
        val expectHigh: Boolean
    )

    @Test
    fun `high amount threshold table - empty history uses 50 euro minimum with strict greater than`() = runTest {
        val rows = listOf(
            ThresholdRow("150.0 above the 50.0 minimum (empty history)", 150.0, true),
            ThresholdRow("50.01 just above the 50.0 minimum", 50.01, true),
            ThresholdRow("50.0 equals the minimum, not strictly greater", 50.0, false),
            ThresholdRow("49.99 below the minimum (empty percentile history)", 49.99, false)
        )
        assertEquals(4, rows.size, "threshold table size guard")

        for (row in rows) {
            coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns emptyList()

            val recommendations = newEngine().generateRecommendations(createExpense(amount = row.amount), null, "u1")

            val highRecs = recommendations.filter { it.priority == RecommendationPriority.HIGH }
            assertEquals(if (row.expectHigh) 1 else 0, highRecs.size, "[${row.label}] HIGH recommendation count")
            if (row.expectHigh) {
                assertEquals(
                    DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                    highRecs.single().navigationTarget,
                    "[${row.label}] HIGH navigation target"
                )
            }
        }
    }

    @Test
    fun `very large transaction is detected as high amount with transaction list navigation`() = runTest {
        // 5 samples < 10 minimum sample size -> threshold stays at the 50.0 minimum.
        coEvery { expenseDao.getAmountsForPercentileCalc(any(), any()) } returns listOf(10.0, 20.0, 30.0, 40.0, 50.0)

        val recommendations = engine.generateRecommendations(createExpense(amount = 5000.0, categoryId = 2L), null, "u1")

        val high = recommendations.first { it.priority == RecommendationPriority.HIGH }
        assertEquals(DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, high.navigationTarget, "HIGH nav target")
        val filter = assertNotNull(serializer.deserialize(high.filterCriteria), "HIGH filter deserializes")
        assertEquals(5000.0, filter.minAmount, "HIGH filter minAmount")
    }

    // ── Priorities / ordering / limits ────────────────────────────────────────

    @Test
    fun `generateRecommendations assigns correct priorities`() = runTest {
        val transaction = createExpense(amount = 150.0, merchant = "Test Merchant", categoryId = 5L)

        val recommendations = engine.generateRecommendations(transaction, null, "user123")

        // High amount -> 1 HIGH; category + merchant -> 2 MEDIUM; recent transactions -> 1 LOW.
        assertEquals(1, recommendations.count { it.priority == RecommendationPriority.HIGH }, "HIGH count")
        assertEquals(2, recommendations.count { it.priority == RecommendationPriority.MEDIUM }, "MEDIUM count")
        assertEquals(1, recommendations.count { it.priority == RecommendationPriority.LOW }, "LOW count")
    }

    @Test
    fun `generateRecommendations sorts by priority and returns all four rules within the max 5 limit`() = runTest {
        val transaction = createExpense(
            amount = 200.0, // HIGH priority
            merchant = "Test Store", // MEDIUM priority
            categoryId = 5L // MEDIUM priority
        )

        val recommendations = engine.generateRecommendations(transaction, null, "user123")

        // 1 HIGH, 2 MEDIUM, 1 LOW: exactly 4, which also satisfies the max-5 limit.
        assertEquals(4, recommendations.size, "recommendation count (<= 5)")
        assertEquals(
            listOf(
                RecommendationPriority.HIGH,
                RecommendationPriority.MEDIUM,
                RecommendationPriority.MEDIUM,
                RecommendationPriority.LOW
            ),
            recommendations.map { it.priority },
            "priority order"
        )
        // Stable sort keeps rule order inside MEDIUM: category first, then merchant.
        assertEquals(
            listOf(
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST
            ),
            recommendations.map { it.navigationTarget },
            "navigation target order"
        )
    }

    // ── Filter content ────────────────────────────────────────────────────────

    private data class FilterRow(
        val label: String,
        val index: Int,
        val priority: RecommendationPriority,
        val navigationTarget: String,
        val category: String,
        /** Expected filter with the (clock-derived) dateRange stripped. */
        val expectedFilterWithoutRange: DomainTransactionFilter,
        /** Expected calendar-day span of dateRange, or null when no dateRange is expected. */
        val rangeDays: Int?
    )

    @Test
    fun `generateRecommendations filter content table - category merchant type and date range per rule`() = runTest {
        val transaction = createExpense(
            amount = 150.0,
            merchant = "Test Merchant",
            categoryId = 7L,
            transactionType = TransactionType.PURCHASE
        )

        val recommendations = engine.generateRecommendations(transaction, null, "user123")

        val rows = listOf(
            FilterRow(
                "high amount", 0, RecommendationPriority.HIGH,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, "7",
                DomainTransactionFilter(categoryId = 7L, transactionType = DomainTransactionType.PURCHASE, minAmount = 150.0),
                null
            ),
            FilterRow(
                "category", 1, RecommendationPriority.MEDIUM,
                DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL, "7",
                DomainTransactionFilter(categoryId = 7L, transactionType = DomainTransactionType.PURCHASE),
                30
            ),
            FilterRow(
                "merchant", 2, RecommendationPriority.MEDIUM,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, "7",
                DomainTransactionFilter(merchantName = "Test Merchant"),
                null
            ),
            FilterRow(
                "recent", 3, RecommendationPriority.LOW,
                DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, "GENERAL",
                DomainTransactionFilter(transactionType = DomainTransactionType.PURCHASE),
                7
            )
        )
        assertEquals(4, rows.size, "filter table size guard")
        assertEquals(4, recommendations.size, "recommendation count")

        for (row in rows) {
            val rec = recommendations[row.index]
            assertEquals(row.priority, rec.priority, "[${row.label}] priority")
            assertEquals(row.navigationTarget, rec.navigationTarget, "[${row.label}] navigation target")
            assertEquals(row.category, rec.category, "[${row.label}] category")
            val filter = assertNotNull(serializer.deserialize(rec.filterCriteria), "[${row.label}] filter deserializes")
            assertEquals(row.expectedFilterWithoutRange, filter.copy(dateRange = null), "[${row.label}] filter content")
            if (row.rangeDays == null) {
                assertNull(filter.dateRange, "[${row.label}] no dateRange")
            } else {
                assertCalendarRange(row.label, filter.dateRange, row.rangeDays)
            }
        }
    }

    @Test
    fun `generateRecommendations filter criteria is valid JSON and identical across two calls`() = runTest {
        val transaction = createExpense(amount = 150.0, categoryId = 10L)

        val first = engine.generateRecommendations(transaction, null, "user123")
        val second = engine.generateRecommendations(transaction, null, "user123")

        // createExpense defaults merchant = "Test Merchant", so with amount 150.0 and a category:
        // HIGH (150 > 50 minimum) + category MEDIUM + merchant MEDIUM + recent LOW.
        assertEquals(4, first.size, "first call recommendation count")
        assertEquals(
            listOf(
                RecommendationPriority.HIGH,
                RecommendationPriority.MEDIUM,
                RecommendationPriority.MEDIUM,
                RecommendationPriority.LOW
            ),
            first.map { it.priority },
            "first call priority order"
        )
        first.forEachIndexed { i, rec ->
            assertTrue(rec.filterCriteria.isNotEmpty(), "[#$i] filterCriteria not empty")
            assertTrue(serializer.isValid(rec.filterCriteria), "[#$i] filterCriteria is valid JSON")
        }
        assertEquals(
            first.map { it.filterCriteria },
            second.map { it.filterCriteria },
            "filterCriteria identical for identical input and clock"
        )
    }

    @Test
    fun `generateRecommendations creates merchant recommendation when merchant present`() = runTest {
        val merchantName = "Starbucks"
        val transaction = createExpense(merchant = merchantName)

        val recommendations = engine.generateRecommendations(transaction, null, "user123")

        // Default amount 50.0 is not HIGH and there is no category: merchant (MEDIUM) then recent (LOW).
        assertEquals(2, recommendations.size, "recommendation count")
        val merchantRec = recommendations.single { it.priority == RecommendationPriority.MEDIUM }
        assertEquals(DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST, merchantRec.navigationTarget, "nav target")
        assertEquals("Review all transactions from Starbucks", merchantRec.recommendationText, "text")
        assertEquals("GENERAL", merchantRec.category, "category without categoryId")
        val filter = assertNotNull(serializer.deserialize(merchantRec.filterCriteria), "filter deserializes")
        assertEquals(DomainTransactionFilter(merchantName = merchantName), filter, "merchant-only filter")
    }

    @Test
    fun `generateRecommendations creates recent transactions recommendation`() = runTest {
        val recommendations = engine.generateRecommendations(createExpense(), null, "user123")

        // Should always create a recent transactions recommendation
        val recentRec = assertNotNull(
            recommendations.find {
                it.priority == RecommendationPriority.LOW &&
                    it.navigationTarget == DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST
            }
        )
        assertEquals("Review your recent spending this week", recentRec.recommendationText, "text")
        val filter = assertNotNull(serializer.deserialize(recentRec.filterCriteria), "filter deserializes")
        assertEquals(DomainTransactionType.PURCHASE, filter.transactionType, "recent filter type")
        assertCalendarRange("recent", filter.dateRange, 7)
    }

    // ── Expiry / metadata ─────────────────────────────────────────────────────

    @Test
    fun `generateRecommendations expiration is 7 days from creation`() = runTest {
        val recommendations = engine.generateRecommendations(createExpense(amount = 150.0), null, "user123")

        // HIGH + merchant + recent (no categoryId)
        assertEquals(3, recommendations.size, "recommendation count")
        recommendations.forEachIndexed { i, rec ->
            assertEquals(FIXED_NOW, rec.createdAt, "[#$i ${rec.priority}] createdAt from pinned clock")
            assertEquals(604_800_000L, rec.expiresAt - rec.createdAt, "[#$i ${rec.priority}] expiry delta (7 days in ms)")
        }
    }

    @Test
    fun `generateRecommendations includes sourceArtifactId when artifact provided`() = runTest {
        val aiArtifact = createAiArtifact(id = 42L)

        val recommendations = engine.generateRecommendations(createExpense(amount = 150.0), aiArtifact, "user123")

        assertEquals(3, recommendations.size, "recommendation count")
        recommendations.forEachIndexed { i, rec ->
            assertEquals("42", rec.sourceArtifactId, "[#$i ${rec.priority}] sourceArtifactId")
        }
    }

    private data class BlankMerchantRow(val label: String, val merchant: String)

    @Test
    fun `generateRecommendations blank merchant creates no merchant recommendation`() = runTest {
        val rows = listOf(
            BlankMerchantRow("empty merchant", ""),
            BlankMerchantRow("whitespace-only merchant", "   ")
        )
        assertEquals(2, rows.size, "blank merchant table size guard")

        for (row in rows) {
            val recommendations = engine.generateRecommendations(
                createExpense(merchant = row.merchant, categoryId = 5L), null, "user123"
            )

            // Default amount 50.0 is not HIGH: only category (MEDIUM) and recent (LOW) remain.
            assertEquals(
                listOf(RecommendationPriority.MEDIUM, RecommendationPriority.LOW),
                recommendations.map { it.priority },
                "[${row.label}] priorities"
            )
            assertEquals(
                listOf(
                    DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL,
                    DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST
                ),
                recommendations.map { it.navigationTarget },
                "[${row.label}] navigation targets"
            )
            recommendations.forEachIndexed { i, rec ->
                val filter = assertNotNull(serializer.deserialize(rec.filterCriteria), "[${row.label} #$i] filter deserializes")
                assertNull(filter.merchantName, "[${row.label} #$i] no merchant-based filter")
            }
        }
    }

    // ── generateFromInsight ───────────────────────────────────────────────────

    @Test
    fun `generateFromInsight creates recommendation with custom parameters`() = runTest {
        val userId = "user123"
        val insightText = "You spent 30% more on dining this month"
        val categoryId = 3L
        val startTime = 1_697_408_000_000L
        val endTime = 1_700_000_000_000L

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
        assertEquals("3", recommendation.category, "category from categoryId")
        assertEquals(FIXED_NOW, recommendation.createdAt, "createdAt from pinned clock")
        assertEquals(1_700_604_800_000L, recommendation.expiresAt, "expiresAt = now + 7 days")

        val filter = assertNotNull(serializer.deserialize(recommendation.filterCriteria))
        assertEquals(categoryId, filter.categoryId)
        assertEquals(Pair(startTime, endTime), filter.dateRange)
    }

    @Test
    fun `generateFromInsight handles null categoryId`() = runTest {
        val recommendation = engine.generateFromInsight(
            insightText = "Overall spending is up",
            categoryId = null,
            dateRangeStart = 1000L,
            dateRangeEnd = 2000L,
            priority = RecommendationPriority.MEDIUM,
            userId = "user123"
        )

        assertEquals("GENERAL", recommendation.category)
        val filter = assertNotNull(serializer.deserialize(recommendation.filterCriteria))
        assertNull(filter.categoryId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Asserts the range contains the pinned clock and spans [days] calendar days within a
     * 2 hour tolerance (DST shifts inside a 30-day window change the span by at most 1 hour).
     */
    private fun assertCalendarRange(label: String, range: Pair<Long, Long>?, days: Int) {
        val (start, end) = assertNotNull(range, "[$label] dateRange present")
        val expectedSpan = days * DAY_MS
        assertTrue(start <= FIXED_NOW, "[$label] range start $start must be <= now")
        assertTrue(FIXED_NOW < end, "[$label] range end $end must be > now")
        val span = end - start
        assertTrue(
            span >= expectedSpan - TWO_HOURS_MS && span <= expectedSpan + TWO_HOURS_MS,
            "[$label] range span $span ms must be within 2h of $days days ($expectedSpan ms)"
        )
    }

    private fun createExpense(
        id: Long = 0,
        amount: Double = 50.0,
        merchant: String = "Test Merchant",
        transactionType: TransactionType = TransactionType.PURCHASE,
        categoryId: Long? = null,
        date: Long = FIXED_NOW
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = "EUR",
            merchant = merchant,
            transactionType = transactionType,
            categoryId = categoryId,
            date = date,
            createdAt = FIXED_NOW
        )
    }

    private fun createAiArtifact(
        id: Long = 1,
        summaryText: String = "AI-generated summary"
    ): AiArtifactRecord {
        return AiArtifactRecord(
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
            createdAt = FIXED_NOW,
            updatedAt = FIXED_NOW,
            expiresAt = null
        )
    }

    private companion object {
        /** 2023-11-14T22:13:20Z; windows of 7 and 30 days around it contain no DST change for >1h. */
        const val FIXED_NOW = 1_700_000_000_000L
        const val DAY_MS = 86_400_000L
        const val TWO_HOURS_MS = 7_200_000L
    }
}
