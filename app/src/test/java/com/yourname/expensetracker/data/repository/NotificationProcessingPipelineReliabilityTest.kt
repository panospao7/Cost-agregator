package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.RoutingResult
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.subscription.NotificationSubscriptionDetector
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NotificationProcessingPipelineReliabilityTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val rawDao = mockk<RawNotificationDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val subscriptionCandidateDao = mockk<SubscriptionCandidateDao>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val confidenceRouter = mockk<ConfidenceRouter>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val budgetMonitor = mockk<BudgetMonitor>(relaxed = true)
    private val anomalyAlertOrchestrator = mockk<AnomalyAlertOrchestrator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val directionDetector = mockk<TransferDirectionDetector>(relaxed = true)
    private val analytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val locationProvider = mockk<ForegroundLocationProvider>(relaxed = true)
    private val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
    private val generateTransactionInsightUseCase = mockk<GenerateTransactionInsightUseCase>(relaxed = true)
    private val dashboardFollowThroughEngine = mockk<DashboardFollowThroughEngine>(relaxed = true)
    private val recommendationRepository = mockk<RecommendationRepository>(relaxed = true)
    private val subscriptionDetector = mockk<NotificationSubscriptionDetector>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = TestScope(testDispatcher)

    private val pipeline = NotificationProcessingPipeline(
        database = database,
        dao = rawDao,
        expenseDao = expenseDao,
        pendingReviewDao = pendingReviewDao,
        sourceStatsDao = sourceStatsDao,
        subscriptionCandidateDao = subscriptionCandidateDao,
        parserRegistry = parserRegistry,
        confidenceRouter = confidenceRouter,
        merchantNormalizer = merchantNormalizer,
        hybridClassifier = hybridClassifier,
        classifier = classifier,
        budgetMonitor = budgetMonitor,
        anomalyAlertOrchestrator = anomalyAlertOrchestrator,
        timeProvider = timeProvider,
        directionDetector = directionDetector,
        analytics = analytics,
        locationProvider = locationProvider,
        aiSettingsRepository = aiSettingsRepository,
        generateTransactionInsightUseCase = generateTransactionInsightUseCase,
        dashboardFollowThroughEngine = dashboardFollowThroughEngine,
        recommendationRepository = recommendationRepository,
        subscriptionDetector = subscriptionDetector,
        applicationScope = applicationScope
    )

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val dbBlock = slot<suspend () -> Any>()
        coEvery { database.withTransaction(capture(dbBlock)) } coAnswers {
            dbBlock.captured.invoke()
        }
        coEvery { classifier.initialize() } returns Unit
        every { timeProvider.now() } returns 1_700_000_000_000L
    }

    @Test
    fun `process swallows parser exceptions`() = runBlocking {
        coEvery { parserRegistry.parseWithAiFallback(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        pipeline.process(testNotification("com.test.app"))

        coVerify(exactly = 0) { rawDao.insertOrIgnore(any()) }
    }

    @Test
    fun `processBatch initializes classifier once and continues on per-item failures`() = runBlocking {
        coEvery { parserRegistry.parseWithAiFallback(any(), any(), any(), any(), any()) } throws RuntimeException("parse failure")

        val notifications = listOf(
            testNotification("com.test.a"),
            testNotification("com.test.b"),
            testNotification("com.test.c")
        )

        pipeline.processBatch(notifications)

        coVerify(exactly = 1) { classifier.initialize() }
        coVerify(exactly = 0) { rawDao.insertOrIgnore(any()) }
    }

    @Test
    fun `pending review duplicate matcher matches same amount and currency`() {
        val existing = listOf(
            PendingReview(
                rawNotificationId = 10L,
                suggestedAmount = 22.35,
                suggestedCurrency = "EUR",
                suggestedMerchant = "coffee island",
                suggestedType = "PURCHASE",
                suggestedCategoryId = null,
                confidence = 0.72f,
                packageName = "com.test.app",
                notificationTitle = "Paid 22.35",
                notificationText = "Paid at coffee island"
            )
        )

        val duplicate = NotificationProcessingPipeline.hasNearDuplicatePendingReview(
            existing = existing,
            amount = 22.35,
            currency = "eur"
        )
        assertTrue(duplicate)
    }

    @Test
    fun `pending review duplicate matcher ignores non-pending or amount mismatch`() {
        val existing = listOf(
            PendingReview(
                rawNotificationId = 11L,
                suggestedAmount = 22.35,
                suggestedCurrency = "EUR",
                suggestedMerchant = "coffee island",
                suggestedType = "PURCHASE",
                suggestedCategoryId = null,
                confidence = 0.72f,
                packageName = "com.test.app",
                notificationTitle = "Paid 22.35",
                notificationText = "Paid at coffee island",
                status = PendingReviewStatus.APPROVED
            )
        )

        val duplicate = NotificationProcessingPipeline.hasNearDuplicatePendingReview(
            existing = existing,
            amount = 22.36,
            currency = "EUR"
        )
        assertFalse(duplicate)
    }

    // ── ISSUE-4: boundary-convention tests ───────────────────────────────────────

    /**
     * The pending-review endDate passed to [PendingReviewDao.hasPendingDuplicateInRangeTypeAware]
     * must equal [DuplicateDetectionPolicy.windowEndExclusive] so that both expense and
     * pending-review duplicate windows share the same inclusive range
     * [date - windowMs, date + windowMs] under the DAO's exclusive-end SQL convention.
     */
    @Test
    fun `windowEndExclusive is date plus window plus 1 - matching ExpenseDao convention`() {
        val date = 1_000_000L
        val windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS

        // ExpenseDao.isDuplicateCurrencyAware computes: endDate = date + windowMs + 1
        val expenseDaoEndDate = date + windowMs + 1L

        // The shared helper must produce the same value.
        val pendingReviewEndDate = DuplicateDetectionPolicy.windowEndExclusive(date, windowMs)

        assertEquals(
            "windowEndExclusive must match ExpenseDao's date + windowMs + 1 convention",
            expenseDaoEndDate,
            pendingReviewEndDate
        )
    }

    @Test
    fun `windowEndExclusive includes exact boundary timestamp under exclusive SQL`() {
        val date = 1_000_000L
        val windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS

        // A pending-review at exactly date + windowMs is at the inclusive edge.
        val boundaryTimestamp = date + windowMs

        // Under the exclusive-end SQL convention (suggestedDate < :endDate):
        // endDate = date + windowMs     → boundaryTimestamp < endDate is FALSE (misses it)
        // endDate = date + windowMs + 1 → boundaryTimestamp < endDate is TRUE  (catches it)
        val endDateWithoutFix = date + windowMs
        val endDateWithFix = DuplicateDetectionPolicy.windowEndExclusive(date, windowMs)

        assertFalse(
            "Without the fix the exact boundary timestamp is excluded by the SQL < predicate",
            boundaryTimestamp < endDateWithoutFix
        )
        assertTrue(
            "With windowEndExclusive the exact boundary timestamp is included by the SQL < predicate",
            boundaryTimestamp < endDateWithFix
        )
    }

    @Test
    fun `windowEndExclusive with default windowMs uses DUPLICATE_WINDOW_MS`() {
        val date = 5_000_000L
        val expected = date + DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS + 1L
        assertEquals(expected, DuplicateDetectionPolicy.windowEndExclusive(date))
    }

    @Test
    fun `process auto-accept does not treat same merchant-date-amount with different currency as duplicate`() = runBlocking {
        val notification = testNotification("com.wallet")
        val parsed = ParsedTransaction(
            amount = 50.0,
            currency = "EUR",
            merchant = "Shop",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.95f,
            date = notification.timestamp
        )

        coEvery {
            parserRegistry.parseWithAiFallback(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns parsed
        coEvery {
            confidenceRouter.route(parsed, notification.packageName, any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
        coEvery { merchantNormalizer.normalize("Shop", any(), any()) } returns merchantLookupResult("Shop")
        coEvery {
            hybridClassifier.classify(
                merchantName = "Shop",
                amount = 50.0,
                notificationTitle = notification.title,
                notificationText = notification.text,
                packageName = notification.packageName
            )
        } returns ClassificationResult(
            categoryId = 0L,
            categoryName = "Unknown",
            confidence = 0f,
            matchType = MatchType.FALLBACK
        )
        coEvery { rawDao.insertOrIgnore(notification) } returns 123L
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 50.0,
                merchant = "Shop",
                date = notification.timestamp,
                currency = "EUR",
                transactionType = "PURCHASE",
                windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery { expenseDao.insertAtomic(any()) } returns 456L

        val result = pipeline.process(notification)

        assertEquals(NotificationProcessingPipeline.ProcessingResult.Success(notification.packageName), result)
        coVerify {
            expenseDao.insertAtomic(match {
                it.amount == 50.0 &&
                    it.currency == "EUR" &&
                    it.merchant == "Shop" &&
                    it.transactionType == TransactionType.PURCHASE
            })
        }
        coVerify(exactly = 0) { sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, any()) }
    }

    private fun merchantLookupResult(normalizedName: String): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(
                id = 1L,
                normalizedName = normalizedName,
                searchKey = normalizedName.lowercase()
            ),
            alias = null,
            confidence = 1.0f,
            matchType = MatchType.EXACT_MATCH
        )
    }

    private fun testNotification(pkg: String): RawNotification =
        RawNotification(
            packageName = pkg,
            appName = "Test",
            title = "Paid EUR 10.00",
            text = "Card transaction",
            timestamp = 1_700_000_000_000,
            capturedAt = 1_700_000_000_000
        )
}
