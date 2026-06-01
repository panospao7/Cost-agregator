package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.RoutingResult
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.ParseOutcome
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.subscription.NotificationSubscriptionDetector
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
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
import org.junit.Assert.assertTrue

/**
 * P1-SLICE-D: Verifies that [NotificationProcessingPipeline] calls
 * [RawNotificationDao.markProcessed] atomically inside [database.withTransaction]
 * blocks — never after the transaction completes.
 *
 * Fixes NEW-P1-010 (atomic markProcessed).
 */
@Suppress("DEPRECATION_ERROR")
class NotificationProcessingPipelineAtomicityTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val rawDao = mockk<RawNotificationDao>(relaxed = true).also {
        coEvery { it.findIdByDedupeFingerprint(any()) } returns null
        coEvery { it.existsByDedupeFingerprint(any()) } returns false
    }
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val subscriptionCandidateDao = mockk<SubscriptionCandidateDao>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val confidenceRouter = mockk<ConfidenceRouter>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val directionDetector = mockk<TransferDirectionDetector>(relaxed = true)
    private val analytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
    private val generateTransactionInsightUseCase = mockk<GenerateTransactionInsightUseCase>(relaxed = true)
    private val dashboardFollowThroughEngine = mockk<DashboardFollowThroughEngine>(relaxed = true)
    private val recommendationRepository = mockk<RecommendationRepository>(relaxed = true)
    private val subscriptionDetector = mockk<NotificationSubscriptionDetector>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = TestScope(testDispatcher)

    private lateinit var pipeline: NotificationProcessingPipeline

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val dbBlock = slot<suspend () -> Any>()
        coEvery { database.withTransaction(capture(dbBlock)) } coAnswers {
            dbBlock.captured.invoke()
        }
        coEvery { classifier.initialize() } returns Unit
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers { merchantLookupResult(firstArg()) }

        pipeline = NotificationProcessingPipeline(
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
            timeProvider = timeProvider,
            directionDetector = directionDetector,
            analytics = analytics,
            aiSettingsRepository = aiSettingsRepository,
            generateTransactionInsightUseCase = generateTransactionInsightUseCase,
            dashboardFollowThroughEngine = dashboardFollowThroughEngine,
            recommendationRepository = recommendationRepository,
            subscriptionDetector = subscriptionDetector,
            coordinator = coordinator,
            postCommitActionRunner = mockk(relaxed = true),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionLifecycleEventWriter = mockk(relaxed = true),
            diagnosticEmitter = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            privacySettingsRepository = mockk(relaxed = true),
            userCurrencyProvider = mockk(relaxed = true),
            moneySignalDetector = mockk(relaxed = true),
            applicationScope = applicationScope
        )
    }

    @Test
    fun `pipeline marks raw as processed inside transaction for auto-accepted notification`() = runBlocking {
        val notification = testNotification()
        val parsed = ParsedTransaction(
            amount = 50.0,
            currency = "EUR",
            merchant = "Shop",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.95f,
            date = notification.timestamp
        )
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, any(), any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
        coEvery {
            hybridClassifier.classify(any(), any(), any(), any(), any())
        } returns ClassificationResult(0L, "Unknown", 0f, matchType = MatchType.FALLBACK)
        coEvery { rawDao.insertOrIgnore(any()) } returns 123L
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.hasPendingDuplicateInRangeTypeAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
            com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created(456L),
            PostCommitActionBatch.empty("test")
        )

        val result = pipeline.process(notification)

        assertTrue("Expected AutoAccepted, got $result", result is NotificationPipelineOutcome.AutoAccepted)
        // markProcessed must be called exactly once, atomically inside the transaction
        coVerify(exactly = 1) { rawDao.markProcessed(123L) }
    }

    @Test
    fun `pipeline marks raw as processed inside transaction for needs-review notification`() = runBlocking {
        val notification = testNotification()
        val parsed = ParsedTransaction(
            amount = 25.0,
            currency = "EUR",
            merchant = "Shop",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.6f,
            date = notification.timestamp
        )
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, any(), any())
        } returns RoutingResult(
            decision = RoutingDecision.NEEDS_REVIEW,
            adjustedConfidence = 0.6f,
            reason = "medium confidence"
        )
        coEvery {
            hybridClassifier.classify(any(), any(), any(), any(), any())
        } returns ClassificationResult(0L, "Unknown", 0f, matchType = MatchType.FALLBACK)
        coEvery { rawDao.insertOrIgnore(any()) } returns 124L
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.hasPendingDuplicateInRangeTypeAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.upsertByRawNotificationId(any()) } returns 789L

        val result = pipeline.process(notification)

        assertTrue("Expected NeedsReview, got $result", result is NotificationPipelineOutcome.NeedsReview)
        coVerify(exactly = 1) { rawDao.markProcessed(124L) }
    }

    @Test
    fun `fingerprint duplicate does not mark processed`() = runBlocking {
        val notification = testNotification()
        // Fingerprint pre-check finds an existing notification — no new rawId
        coEvery { rawDao.existsByDedupeFingerprint(any()) } returns true

        val result = pipeline.process(notification)

        assertTrue("Expected Duplicate, got $result", result is NotificationPipelineOutcome.Duplicate)
        coVerify(exactly = 0) { rawDao.markProcessed(any()) }
    }

    @Test
    fun `insert raw duplicate in parsed path does not mark processed`() = runBlocking {
        val notification = testNotification()
        val parsed = ParsedTransaction(
            amount = 10.0,
            currency = "EUR",
            merchant = "Shop",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.95f,
            date = notification.timestamp
        )
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, any(), any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
        // Simulate insertRawNotificationIfNotDuplicate returning Duplicate
        coEvery { rawDao.findIdByDedupeFingerprint(any()) } returns 999L

        val result = pipeline.process(notification)

        assertTrue("Expected Duplicate, got $result", result is NotificationPipelineOutcome.Duplicate)
        coVerify(exactly = 0) { rawDao.markProcessed(any()) }
    }

    @Test
    fun `insert raw duplicate in parse-failed path does not mark processed`() = runBlocking {
        val notification = testNotification()
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        // Simulate insertRawNotificationIfNotDuplicate returning Duplicate
        coEvery { rawDao.findIdByDedupeFingerprint(any()) } returns 888L

        val result = pipeline.process(notification)

        assertTrue("Expected Duplicate, got $result", result is NotificationPipelineOutcome.Duplicate)
        coVerify(exactly = 0) { rawDao.markProcessed(any()) }
    }

    @Test
    fun `auto-rejected notification is marked processed inside transaction`() = runBlocking {
        // Parse-failed path: no oversized/transaction signal — auto-rejected.
        // A new raw notification IS inserted, so markProcessed MUST be called.
        val notification = testNotification("com.test.nonfinancial").copy(
            title = "Hello",
            text = "world"
        )
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        coEvery { rawDao.insertOrIgnore(any()) } returns 55L

        val result = pipeline.process(notification)

        assertTrue("Expected AutoRejected, got $result", result is NotificationPipelineOutcome.AutoRejected)
        coVerify(exactly = 1) { rawDao.markProcessed(55L) }
    }

    @Test
    fun `crash after commit does not reprocess`() = runBlocking {
        // When an exception is thrown inside the transaction (e.g. coordinator error),
        // the transaction rolls back and markProcessed must NOT be called because
        // the raw notification insert is also rolled back.
        val notification = testNotification()
        val parsed = ParsedTransaction(
            amount = 50.0,
            currency = "EUR",
            merchant = "Shop",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.95f,
            date = notification.timestamp
        )
        coEvery {
            parserRegistry.parseWithProvenance(any(), any(), any(), any(), any())
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, any(), any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
        coEvery {
            hybridClassifier.classify(any(), any(), any(), any(), any())
        } returns ClassificationResult(0L, "Unknown", 0f, matchType = MatchType.FALLBACK)
        coEvery { rawDao.insertOrIgnore(any()) } returns 123L
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.hasPendingDuplicateInRangeTypeAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        // Coordinator throws — triggers exception inside transaction, rolling it back
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
            com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Error(
                RuntimeException("DB constraint violation simulating crash")
            ),
            PostCommitActionBatch.empty("test")
        )

        val result = pipeline.process(notification)

        assertTrue("Expected Error outcome, got $result", result is NotificationPipelineOutcome.Error)
        // markProcessed must NOT be called when the transaction rolls back
        coVerify(exactly = 0) { rawDao.markProcessed(any()) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun testNotification(
        packageName: String = "com.test.app"
    ) = RawNotification(
        packageName = packageName,
        appName = "Test",
        title = "Paid EUR 10.00",
        text = "Card transaction at Test Store",
        timestamp = 1_700_000_000_000L,
        capturedAt = 1_700_000_000_000L
    )

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
}
