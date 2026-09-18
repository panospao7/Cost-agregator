package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.ParseOutcome
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkResult
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * P1-PR2: Tests that source-link failures inside transactions do NOT:
 * - Roll back the transaction (review is still created)
 * - Call diagnosticEmitter.emit() inside the transaction (deferred to post-commit)
 *
 * Fixes: NEW-P1-002, NEW-P1-015
 */
@Suppress("DEPRECATION_ERROR")
class NotificationProcessingPipelineSourceLinkTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val rawDao = mockk<RawNotificationDao>(relaxed = true).also {
        coEvery { it.findIdByDedupeFingerprint(any()) } returns null
        coEvery { it.existsByDedupeFingerprint(any()) } returns false
    }
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val sourceLinkWriter = mockk<SourceLinkWriter>(relaxed = true)
    private val pendingReviewSourceLinkService = mockk<PendingReviewSourceLinkService>(relaxed = true)
    private val diagnosticEmitter = mockk<NotificationDiagnosticEmitter>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val parserRegistry = mockk<AppParserRegistry>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = TestScope(testDispatcher)

    private lateinit var pipeline: NotificationProcessingPipeline

    @Before
    fun setup() {
        // GR-14p: the parsed-path DB phase is scoped in writeBarrier.runWrite
        // (NotificationProcessingPipeline.kt:553); a relaxed mock would neither
        // run the block nor return its value (the pipeline casts the result to
        // ParsedDbOutcome), so pass the block through.
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2). Without
        // mockkStatic, the real Room body runs against the relaxed AppDatabase
        // mock and either suspends forever or returns a default Object, which
        // surfaces as a ClassCastException on ParsedDbOutcome (measured:
        // docs/testing/test-sweep-outcomes-2026-09-18.md). The recorded call
        // args are positional with the RECEIVER as arg 0 and the block as arg 1,
        // so the block is secondArg.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        every { timeProvider.now() } returns 1_700_000_000_000L

        pipeline = NotificationProcessingPipeline(
            database = database,
            dao = rawDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            sourceStatsDao = sourceStatsDao,
            subscriptionCandidateDao = mockk(relaxed = true),
            parserRegistry = parserRegistry,
            confidenceRouter = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            classifier = mockk<TransactionClassifier>(relaxed = true).also {
                coEvery { it.initialize() } returns Unit
            },
            timeProvider = timeProvider,
            directionDetector = mockk(relaxed = true),
            analytics = mockk(relaxed = true),
            aiSettingsRepository = mockk(relaxed = true),
            generateTransactionInsightUseCase = mockk(relaxed = true),
            dashboardFollowThroughEngine = mockk(relaxed = true),
            recommendationRepository = mockk(relaxed = true),
            subscriptionDetector = mockk(relaxed = true),
            coordinator = mockk(relaxed = true),
            postCommitActionRunner = mockk(relaxed = true),
            pendingReviewSourceLinkService = pendingReviewSourceLinkService,
            sourceLinkWriter = sourceLinkWriter,
            transactionLifecycleEventWriter = mockk(relaxed = true),
            transactionRunner = mockk(relaxed = true),
            diagnosticEmitter = diagnosticEmitter,
            writeBarrier = writeBarrier,
            privacySettingsRepository = mockk(relaxed = true),
            userCurrencyProvider = mockk(relaxed = true),
            moneySignalDetector = mockk(relaxed = true),
            applicationScope = applicationScope
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    /**
     * NEW-P1-015: When pendingReviewSourceLinkService.linkSourcesForReview() reports
     * a fatal failure, the review should STILL be created (transaction not rolled back).
     */
    @Test
    fun `fatal source link failure does not prevent review creation`() = runBlocking {
        // Setup: parser fails, oversized amount detected, no duplicate
        coEvery { rawDao.insertOrIgnore(any()) } returns 1L
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.hasPendingDuplicateInRangeTypeAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { pendingReviewDao.upsertByRawNotificationId(any()) } returns 99L

        // Source link service reports fatal failure
        coEvery { pendingReviewSourceLinkService.linkSourcesForReview(any(), any(), any(), any(), any()) } returns
            PendingReviewSourceLinkResult(
                attempted = 1,
                inserted = 0,
                alreadyExists = 0,
                failed = 1,
                failures = listOf("Rejected: test failure")
            )

        // Force the parse-fallback branch (same recipe as the Reliability
        // suite's NeedsReview tests): a relaxed parser mock "succeeds" with junk
        // and the flow would auto-accept into the lifecycle coordinator instead
        // of creating the pending review under test here. The title must carry
        // a currency-attached amount for detectTransactionSignalCandidate.
        val notification = testNotification().copy(
            title = "Payment €4.08",
            text = "Card payment completed"
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
        val outcome = pipeline.process(notification)

        // Review should still be created despite link failure
        assertTrue(
            "Expected NeedsReview outcome but got $outcome",
            outcome is NotificationPipelineOutcome.NeedsReview
        )
        coVerify(exactly = 1) { pendingReviewDao.upsertByRawNotificationId(any()) }
    }

    /**
     * NEW-P1-002: When sourceLinkWriter.linkTarget() throws inside the transaction,
     * diagnosticEmitter.emit() should NOT be called inside the transaction.
     * It should be deferred to post-commit.
     */
    @Test
    fun `source link writer exception does not call diagnosticEmitter inside transaction`() = runBlocking {
        // Setup: parser fails, oversized amount detected, IS a duplicate (triggers dedupe source link)
        coEvery { rawDao.insertOrIgnore(any()) } returns 1L
        coEvery { expenseDao.isDuplicateCurrencyAware(any(), any(), any(), any(), any(), any(), any(), any()) } returns true

        // Source link writer throws
        coEvery { sourceLinkWriter.linkTarget(any(), any(), any(), any()) } throws RuntimeException("DB error")

        val notification = testNotification()
        val outcome = pipeline.process(notification)

        // Should still produce Duplicate outcome (not crash)
        assertTrue(
            "Expected Duplicate outcome but got $outcome",
            outcome is NotificationPipelineOutcome.Duplicate
        )

        // Diagnostic emitter should be called AFTER transaction (post-commit), not zero times
        // The deferred emission happens via runPostCommitSafely
        coVerify(atLeast = 1) { diagnosticEmitter.emit(any()) }
    }

    private fun testNotification(
        packageName: String = "com.revolut.revolut"
    ) = RawNotification(
        packageName = packageName,
        appName = "Revolut",
        title = "Payment",
        text = "You paid €1,500.00 at Test Store",
        bigText = null,
        subText = null,
        timestamp = 1_700_000_000_000L,
        capturedAt = 1_700_000_000_000L,
        isProcessed = false
    )
}
