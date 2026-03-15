package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NotificationProcessingPipelineReliabilityTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val rawDao = mockk<RawNotificationDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val confidenceRouter = mockk<ConfidenceRouter>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val budgetMonitor = mockk<BudgetMonitor>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val directionDetector = mockk<TransferDirectionDetector>(relaxed = true)
    private val analytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val locationProvider = mockk<ForegroundLocationProvider>(relaxed = true)

    private val pipeline = NotificationProcessingPipeline(
        database = database,
        dao = rawDao,
        expenseDao = expenseDao,
        pendingReviewDao = pendingReviewDao,
        sourceStatsDao = sourceStatsDao,
        parserRegistry = parserRegistry,
        confidenceRouter = confidenceRouter,
        merchantNormalizer = merchantNormalizer,
        hybridClassifier = hybridClassifier,
        classifier = classifier,
        budgetMonitor = budgetMonitor,
        timeProvider = timeProvider,
        directionDetector = directionDetector,
        analytics = analytics,
        locationProvider = locationProvider
    )

    @Test
    fun `process swallows parser exceptions`() = runBlocking {
        every { parserRegistry.parse(any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        pipeline.process(testNotification("com.test.app"))

        coVerify(exactly = 0) { rawDao.insertOrIgnore(any()) }
    }

    @Test
    fun `processBatch initializes classifier once and continues on per-item failures`() = runBlocking {
        coEvery { classifier.initialize() } returns Unit
        every { parserRegistry.parse(any(), any(), any(), any(), any()) } throws RuntimeException("parse failure")

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
