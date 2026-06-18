package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.ai.model.AiSettings
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
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine

import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.ParseOutcome
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.subscription.NotificationSubscriptionDetector
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.subscription.SubscriptionCandidateResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION_ERROR")
class NotificationProcessingPipelineReliabilityTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val rawDao = mockk<RawNotificationDao>(relaxed = true).also {
        // Repair C: Default returns for new fingerprint methods — must return null/false
        // to avoid false duplicate detection (relaxed mock returns 0L for Long?).
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
    private val budgetMonitor = mockk<BudgetMonitor>(relaxed = true)
    private val anomalyAlertOrchestrator = mockk<AnomalyAlertOrchestrator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val directionDetector = mockk<TransferDirectionDetector>(relaxed = true)
    private val analytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
    private val generateTransactionInsightUseCase = mockk<GenerateTransactionInsightUseCase>(relaxed = true)
    private val dashboardFollowThroughEngine = mockk<DashboardFollowThroughEngine>(relaxed = true)
    private val recommendationRepository = mockk<RecommendationRepository>(relaxed = true)
    private val subscriptionDetector = mockk<NotificationSubscriptionDetector>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val transactionEventDao = mockk<TransactionEventDao>(relaxed = true)
    private val pipelineDiagnosticEventDao = mockk<PipelineDiagnosticEventDao>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
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

    @Before
    fun setup() {
        // withTransaction inline mock removed — mockk(relaxed=true) handles underlying RoomDatabase methods
        coEvery { classifier.initialize() } returns Unit
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers { merchantLookupResult(firstArg()) }
    }

    @Test
    fun `process swallows parser exceptions`() = runBlocking {
        coEvery { parserRegistry.parseWithProvenance(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        pipeline.process(testNotification("com.test.app"))

        coVerify(exactly = 0) { rawDao.insertOrIgnore(any()) }
    }

    @Test
    fun `processBatch initializes classifier once and continues on per-item failures`() = runBlocking {
        coEvery { parserRegistry.parseWithProvenance(any(), any(), any(), any(), any()) } throws RuntimeException("parse failure")

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
    fun `process suppresses exact raw duplicate before insert when schema has no unique index`() = runBlocking {
        val notification = testNotification("com.test.app")

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        coEvery { rawDao.findIdByDedupeFingerprint(any()) } returns 123L

        val result = pipeline.process(notification)

        assertTrue("Expected Duplicate outcome, got $result", result is NotificationPipelineOutcome.Duplicate)
        coVerify(exactly = 1) { rawDao.findIdByDedupeFingerprint(any()) }
        coVerify(exactly = 0) { rawDao.insertOrIgnore(any()) }
        coVerify(exactly = 0) { sourceStatsDao.insertIfNotExists(any()) }
        coVerify(exactly = 0) { sourceStatsDao.incrementTotalAndAutoRejected(any(), any()) }
    }

    @Test
    fun `process inserts raw notification when exact duplicate does not exist`() = runBlocking {
        val notification = testNotification("com.test.unique").copy(
            title = "Hello",
            text = "world"
        )

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        coEvery { rawDao.insertOrIgnore(any()) } returns 42L

        val result = pipeline.process(notification)

        assertTrue("Expected AutoRejected outcome, got $result", result is NotificationPipelineOutcome.AutoRejected)
        coVerifyOrder {
            rawDao.findIdByDedupeFingerprint(any())
            rawDao.insertOrIgnore(any())
        }
        coVerify(exactly = 1) { sourceStatsDao.insertIfNotExists(any()) }
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
                notificationText = "Paid at coffee island",
                createdAt = System.currentTimeMillis()
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
                status = PendingReviewStatus.APPROVED,
                createdAt = System.currentTimeMillis()
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
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, notification.packageName, any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
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
        coEvery { rawDao.insertOrIgnore(any()) } returns 123L
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
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
            com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created(456L),
            PostCommitActionBatch.empty("test")
        )

        val result = pipeline.process(notification)

        assertTrue("Expected AutoAccepted outcome, got $result", result is NotificationPipelineOutcome.AutoAccepted)
        coVerify {
            coordinator.createExpenseDbOnlyV2(match {
                it.amount == 50.0 &&
                    it.currency == "EUR" &&
                    it.merchant == "Shop" &&
                    it.transactionType == TransactionType.PURCHASE
            })
        }
        coVerify(exactly = 0) { sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, any()) }
    }

    @Test
    fun `process remains stable when concurrent subscription detections insert same pending candidate`() = runTest(testDispatcher) {
        val merchant = "Netflix"
        val notification1 = testNotification("com.wallet").copy(timestamp = 1_700_000_000_000L)
        val notification2 = testNotification("com.wallet").copy(timestamp = 1_700_000_060_000L, capturedAt = 1_700_000_060_000L)
        val parsed1 = recurringParsedTransaction(merchant, notification1.timestamp)
        val parsed2 = recurringParsedTransaction(merchant, notification2.timestamp)
        val recentExpenses = recurringExpenses(merchant)
        val detectedCandidate = SubscriptionCandidateResult(
            merchant = merchant,
            canonicalMerchant = merchant,
            averageAmount = 9.99,
            currency = "EUR",
            detectedInterval = "monthly",
            confidence = 0.92,
            transactionCount = 3,
            firstSeen = recentExpenses.first().expense.date,
            lastSeen = recentExpenses.last().expense.date,
            estimatedAnnualCost = 119.88
        )
        val candidateEntity = SubscriptionCandidate(
            merchant = merchant,
            canonicalMerchant = merchant,
            averageAmount = 9.99,
            currency = "EUR",
            detectedInterval = "monthly",
            confidence = 0.92,
            transactionCount = 3,
            firstSeen = recentExpenses.first().expense.date,
            lastSeen = recentExpenses.last().expense.date,
            estimatedAnnualCost = 119.88,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        val pendingQueryCount = AtomicInteger(0)
        val initialCheckBarrier = CompletableDeferred<Unit>()
        val finalCheckBarrier = CompletableDeferred<Unit>()

        coEvery {
            parserRegistry.parseWithProvenance(
                notification1.title,
                notification1.text,
                notification1.bigText,
                notification1.subText,
                notification1.packageName
            )
        } returns ParseOutcome.Parsed(parsed1, mockk(relaxed = true))
        coEvery {
            parserRegistry.parseWithProvenance(
                notification2.title,
                notification2.text,
                notification2.bigText,
                notification2.subText,
                notification2.packageName
            )
        } returns ParseOutcome.Parsed(parsed2, mockk(relaxed = true))
        coEvery { confidenceRouter.route(any(), any(), any()) } returns RoutingResult(
            decision = RoutingDecision.AUTO_ACCEPT,
            adjustedConfidence = 0.95f,
            reason = "high confidence"
        )
        // normalize is already stubbed in setup() — no need for per-test override
        coEvery {
            hybridClassifier.classify(
                merchantName = merchant,
                amount = 9.99,
                notificationTitle = any(),
                notificationText = any(),
                packageName = any()
            )
        } returns ClassificationResult(
            categoryId = 0L,
            categoryName = "Unknown",
            confidence = 0f,
            matchType = MatchType.FALLBACK
        )
        coEvery { rawDao.insertOrIgnore(any()) } returnsMany listOf(1L, 2L)
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 9.99,
                merchant = merchant,
                date = any(),
                currency = "EUR",
                transactionType = TransactionType.PURCHASE.name,
                windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returnsMany listOf(
            MutationResult(
                com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created(101L),
                PostCommitActionBatch.empty("test")
            ),
            MutationResult(
                com.yourname.expensetracker.domain.transaction.CreateExpenseResult.Created(102L),
                PostCommitActionBatch.empty("test")
            )
        )
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = false))
        coEvery { expenseDao.getRecentExpensesWithCategoryForMerchant(merchant, any()) } returns recentExpenses
        coEvery { subscriptionDetector.detectSubscriptions(recentExpenses) } returns listOf(detectedCandidate)
        every { subscriptionDetector.toEntity(detectedCandidate) } returns candidateEntity
        coEvery { subscriptionCandidateDao.getPendingCanonicalMerchants(listOf(merchant)) } coAnswers {
            when (pendingQueryCount.incrementAndGet()) {
                1 -> {
                    initialCheckBarrier.await()
                    emptyList<String>()
                }
                2 -> {
                    initialCheckBarrier.complete(Unit)
                    emptyList<String>()
                }
                3 -> {
                    finalCheckBarrier.await()
                    emptyList<String>()
                }
                4 -> {
                    finalCheckBarrier.complete(Unit)
                    emptyList<String>()
                }
                else -> emptyList<String>()
            }
        }

        val result1 = pipeline.process(notification1)
        val result2 = pipeline.process(notification2)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Expected AutoAccepted for notification1, got $result1", result1 is NotificationPipelineOutcome.AutoAccepted)
        assertTrue("Expected AutoAccepted for notification2, got $result2", result2 is NotificationPipelineOutcome.AutoAccepted)
        coVerify(exactly = 4) { subscriptionCandidateDao.getPendingCanonicalMerchants(listOf(merchant)) }
        coVerify(exactly = 2) { subscriptionCandidateDao.insert(candidateEntity) }
    }

    @Test
    fun `parser-null notification with currency and transaction signals routes to pending review`() = runBlocking {
        val notification = testNotification("com.test.signal").copy(
            title = "Payment €4.08",
            text = "Card payment completed"
        )
        val reviewSlot = slot<PendingReview>()

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        coEvery {
            rawDao.exists(
                packageName = notification.packageName,
                timestamp = notification.timestamp,
                title = notification.title,
                text = notification.text,
                bigText = null,
            )
        } returns false
        coEvery { rawDao.insertOrIgnore(any()) } returns 50L
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 4.08,
                merchant = "Unknown",
                date = notification.timestamp,
                currency = "EUR",
                transactionType = TransactionType.UNKNOWN.name,
                windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery {
            pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
                merchantKey = any(),
                merchantName = "Unknown",
                startDate = notification.timestamp - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                endDate = DuplicateDetectionPolicy.windowEndExclusive(notification.timestamp),
                minAmount = 4.08 - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                maxAmount = 4.08 + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                currency = "EUR",
                transactionType = TransactionType.UNKNOWN.name
            )
        } returns false

        val result = pipeline.process(notification)

        assertTrue("Expected NeedsReview outcome, got $result", result is NotificationPipelineOutcome.NeedsReview)
        coVerify(exactly = 1) { pendingReviewDao.upsertByRawNotificationId(capture(reviewSlot)) }
        assertEquals(4.08, reviewSlot.captured.suggestedAmount!!, 0.0001)
        coVerify(exactly = 1) { sourceStatsDao.incrementTotalAndPending(notification.packageName, any()) }
        coVerify(exactly = 1) { rawDao.markRelevance(any(), true) }
        coVerify(exactly = 0) { sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, any()) }
    }

    @Test
    fun `parser-null notification without transaction signals is still auto-rejected`() = runBlocking {
        val notification = testNotification("com.test.nonfinancial").copy(
            title = "Hello",
            text = "world"
        )

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.NoParse(mockk(relaxed = true))
        coEvery {
            rawDao.exists(
                packageName = notification.packageName,
                timestamp = notification.timestamp,
                title = notification.title,
                text = notification.text,
                bigText = null,
            )
        } returns false
        coEvery { rawDao.insertOrIgnore(notification) } returns 51L

        val result = pipeline.process(notification)

        assertTrue("Expected AutoRejected outcome, got $result", result is NotificationPipelineOutcome.AutoRejected)
        coVerify(exactly = 1) { sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, any()) }
        coVerify(exactly = 0) { pendingReviewDao.upsertByRawNotificationId(any()) }
    }

    @Test
    fun `AUTO_REJECT from financial package is salvaged to NEEDS_REVIEW`() = runBlocking {
        val notification = testNotification("com.google.android.apps.walletnfcrel")
        val parsed = ParsedTransaction(
            amount = 4.08,
            currency = "EUR",
            merchant = "Unknown",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.90f,
            date = notification.timestamp
        )

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, notification.packageName, any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_REJECT,
            adjustedConfidence = 0.45f,
            reason = "Unknown merchant"
        )
        coEvery { merchantNormalizer.normalize("Unknown", any(), any()) } returns merchantLookupResult("unknown")
        coEvery {
            hybridClassifier.classify(
                merchantName = "unknown",
                amount = 4.08,
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
        coEvery {
            rawDao.exists(
                packageName = notification.packageName,
                timestamp = notification.timestamp,
                title = notification.title,
                text = notification.text,
                bigText = null,
            )
        } returns false
        coEvery { rawDao.insertOrIgnore(notification) } returns 55L
        coEvery {
            expenseDao.isDuplicateCurrencyAware(
                amount = 4.08,
                merchant = "unknown",
                date = notification.timestamp,
                currency = "EUR",
                transactionType = TransactionType.PURCHASE.name,
                windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                merchantKey = any(),
                dedupeKey = any()
            )
        } returns false
        coEvery {
            pendingReviewDao.hasPendingDuplicateInRangeTypeAware(
                merchantKey = any(),
                merchantName = "unknown",
                startDate = notification.timestamp - DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
                endDate = DuplicateDetectionPolicy.windowEndExclusive(notification.timestamp),
                minAmount = 4.08 - DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                maxAmount = 4.08 + DuplicateDetectionPolicy.AMOUNT_TOLERANCE,
                currency = "EUR",
                transactionType = TransactionType.PURCHASE.name
            )
        } returns false

        val result = pipeline.process(notification)

        assertTrue("Expected NeedsReview outcome (salvaged from AUTO_REJECT for financial package), got $result", result is NotificationPipelineOutcome.NeedsReview)
        coVerify(exactly = 1) { pendingReviewDao.upsertByRawNotificationId(any()) }
        coVerify(exactly = 1) { sourceStatsDao.incrementTotalAndPending(notification.packageName, any()) }
        coVerify(exactly = 0) { sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, any()) }
    }

    @Test
    fun `AUTO_REJECT from non-financial package is NOT salvaged`() = runBlocking {
        val notification = testNotification("com.some.random.app")
        val parsed = ParsedTransaction(
            amount = 4.08,
            currency = "EUR",
            merchant = "Unknown",
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.90f,
            date = notification.timestamp
        )

        coEvery {
            parserRegistry.parseWithProvenance(
                notification.title,
                notification.text,
                notification.bigText,
                notification.subText,
                notification.packageName
            )
        } returns ParseOutcome.Parsed(parsed, mockk(relaxed = true))
        coEvery {
            confidenceRouter.route(parsed, notification.packageName, any())
        } returns RoutingResult(
            decision = RoutingDecision.AUTO_REJECT,
            adjustedConfidence = 0.45f,
            reason = "Unknown merchant"
        )
        coEvery { merchantNormalizer.normalize("Unknown", any(), any()) } returns merchantLookupResult("unknown")
        coEvery { rawDao.insertOrIgnore(any()) } returns 56L

        val result = pipeline.process(notification)

        assertTrue("Expected AutoRejected outcome, got $result", result is NotificationPipelineOutcome.AutoRejected)
        coVerify(exactly = 1) { rawDao.markRelevance(any(), false) }
        coVerify(exactly = 1) { sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, any()) }
        coVerify(exactly = 0) { pendingReviewDao.upsertByRawNotificationId(any()) }
    }

    @Test
    fun `detectTransactionSignalCandidate returns candidate for normal transaction-like text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Payment €4.08",
            text = "Transaction completed",
            bigText = null
        )

        assertNotNull(candidate)
        assertEquals(4.08, candidate!!.amount, 0.0001)
        assertEquals("EUR", candidate.currency)
    }

    @Test
    fun `detectTransactionSignalCandidate returns null for non-transaction text`() {
        val candidate = NotificationProcessingPipeline.detectTransactionSignalCandidate(
            title = "Hello",
            text = "World",
            bigText = null
        )

        assertNull(candidate)
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

    private fun recurringParsedTransaction(merchant: String, timestamp: Long): ParsedTransaction {
        return ParsedTransaction(
            amount = 9.99,
            currency = "EUR",
            merchant = merchant,
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.97f,
            date = timestamp
        )
    }

    private fun recurringExpenses(merchant: String): List<ExpenseWithCategory> {
        return listOf(
            1_699_000_000_000L,
            1_699_200_000_000L,
            1_699_400_000_000L
        ).mapIndexed { index, date ->
            ExpenseWithCategory(
                expense = Expense(
                    id = (index + 1).toLong(),
                    amount = 9.99,
                    currency = "EUR",
                    merchant = merchant,
                    transactionType = TransactionType.PURCHASE,
                    date = date,
                    createdAt = System.currentTimeMillis()
                ),
                category = null
            )
        }
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