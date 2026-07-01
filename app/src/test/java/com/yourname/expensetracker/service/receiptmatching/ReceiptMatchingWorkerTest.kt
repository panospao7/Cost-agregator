package com.yourname.expensetracker.service.receiptmatching

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptMatchLifecycleService
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION_ERROR")
class ReceiptMatchingWorkerTest {

    private lateinit var context: Context
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var matcher: ReceiptTransactionMatcher
    private lateinit var notificationService: NotificationService
    private lateinit var notificationPermissionChecker: NotificationPermissionChecker
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var matchService: ReceiptMatchLifecycleService
    private lateinit var receiptLinkService: ReceiptLinkService

    // Relaxed run context so behavioral tests can run the guarded block AND
    // coVerify the worker's per-receipt counter calls.
    private lateinit var ctx: WorkerRunContext

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiptRepository = mockk(relaxed = true)
        matcher = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        notificationPermissionChecker = mockk(relaxed = true)
        every { notificationPermissionChecker.areNotificationsEnabled() } returns true
        executionGuard = mockk(relaxed = true)
        matchService = mockk(relaxed = true)
        receiptLinkService = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val transient = listOf("timeout", "interrupted", "deadlock", "SQLITE_BUSY", "database is locked")
                    .any { msg.contains(it, ignoreCase = true) } || e is java.io.IOException
                if (transient) WorkerGuardResult.Retry(msg, e)
                else WorkerGuardResult.Failed(msg, e)
            }
        }
    }

    private fun buildWorker(): ReceiptMatchingWorker {
        return TestListenableWorkerBuilder<ReceiptMatchingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ReceiptMatchingWorker {
                    return ReceiptMatchingWorker(
                        appContext,
                        workerParameters,
                        receiptRepository,
                        matcher,
                        receiptLinkService = receiptLinkService,
                        matchService = matchService,
                        notificationService = notificationService,
                        notificationPermissionChecker = notificationPermissionChecker,
                        executionGuard = executionGuard
                    )
                }
            })
            .build()
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `unmatched receipts matching is attempted`() = runTest {
        val receipt = sampleReceipt(id = 10L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.NoMatch

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // S5 hardening: match on (receipt, any()) so the verify survives a
        // LOOKBACK_DAYS change and fails loudly on real signature drift instead
        // of silently falling through to the relaxed mock.
        coVerify(exactly = 1) { matcher.findBestMatch(receipt, any()) }
        // P9-P1-08: an attempt must always emit a durable MATCH_ATTEMPTED event.
        coVerify(exactly = 1) { matchService.recordMatchAttempted(10L, any()) }
        // P9-P1-08: a NoMatch outcome must emit MATCH_NOT_FOUND (previously silent).
        coVerify(exactly = 1) { matchService.recordMatchNotFound(10L) }
        // P9-S4 counts: the receipt is scanned; a NoMatch is neither updated, skipped,
        // nor notified.
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
    }

    @Test
    fun `all receipts matched no work needed`() = runTest {
        coEvery { receiptRepository.getProcessableReceipts() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any(), any()) }
        coVerify(exactly = 0) { receiptRepository.linkReceiptToExpense(any(), any(), any()) }
        // P9-P1-08: empty receipt list must produce no diagnostics events.
        coVerify(exactly = 0) { matchService.recordMatchAttempted(any(), any()) }
        coVerify(exactly = 0) { matchService.recordMatchNotFound(any()) }
        coVerify(exactly = 0) { matchService.recordMatchSkippedDocumentType(any(), any()) }
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any()) }
        // P9-S4 zero-count: an empty receipt list increments no counters.
        coVerify(exactly = 0) { ctx.addRowsScanned() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
    }

    @Test
    fun `worker returns success`() = runTest {
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(sampleReceipt(id = 11L))
        coEvery { matcher.findBestMatch(any(), any()) } returns MatchResult.NoMatch

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `doc-type skip emits MATCH_SKIPPED_DOCUMENT_TYPE`() = runTest {
        val bankStatement = sampleReceipt(id = 20L, documentType = "BANK_STATEMENT")
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(bankStatement)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // The matcher must never be invoked for a skipped document type.
        coVerify(exactly = 0) { matcher.findBestMatch(any(), any()) }
        // P9-P1-08: the previously-silent skip path must emit a durable event.
        coVerify(exactly = 1) { matchService.recordMatchSkippedDocumentType(20L, "BANK_STATEMENT") }
        coVerify(exactly = 0) { matchService.recordMatchAttempted(any(), any()) }
        // P9-S4 counts: an incompatible document type is scanned then skipped.
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 1) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
    }

    @Test
    fun `link failure emits AUTO_MATCH_LINK_FAILED`() = runTest {
        val receipt = sampleReceipt(id = 30L)
        val expense = sampleExpense(id = 900L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns kotlin.Result.failure(IllegalStateException("link failed"))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // P9-P1-08 regression guard: a link failure must emit a durable event,
        // not merely a Timber.w log. This assertion makes the old "only logged"
        // bug unable to recur silently.
        coVerify(exactly = 1) {
            matchService.recordAutoMatchLinkFailed(30L, 900L, "link failed")
        }
        // A failed link must not be treated as an auto-match success notification.
        coVerify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `link success path sends notification and emits no link-failed event`() = runTest {
        // S5 symmetric regression guard: a SUCCESSFUL auto-match link must send the
        // notification AND must never record AUTO_MATCH_LINK_FAILED. Guards against a
        // false-positive failure event on the happy path.
        val receipt = sampleReceipt(id = 40L)
        val expense = sampleExpense(id = 901L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.99)
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns kotlin.Result.success(sampleLink(receiptId = 40L, expenseId = 901L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any()) }
        // P9-S4 counts: a successful auto-match scans, updates, and notifies.
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
    }

    @Test
    fun `concurrent claim prevents double auto-match`() = runTest {
        // S6 (P9-P1-07 / NEW-07): when a concurrent matching run has already claimed the
        // receipt, the atomic compare-and-set affects 0 rows and the link service returns
        // a ReceiptAlreadyClaimedException. The worker must treat this as a no-op: no
        // notification, and NO false AUTO_MATCH_LINK_FAILED event.
        val receipt = sampleReceipt(id = 50L)
        val expense = sampleExpense(id = 902L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.97)
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns kotlin.Result.failure(ReceiptAlreadyClaimedException(50L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Already-claimed is a benign no-op, not a link failure.
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any()) }
        coVerify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `auto-match requests atomic unmatched claim`() = runTest {
        // S6: the worker must route the auto-link through the lifecycle/link service with
        // requireUnmatchedClaim = true so overlapping runs cannot double-link. This pins
        // the load-bearing overlap-safety contract at the worker→service boundary (not a
        // raw DAO call).
        val receipt = sampleReceipt(id = 60L)
        val expense = sampleExpense(id = 903L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.96)
        coEvery {
            receiptLinkService.linkReceiptToExpense(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns kotlin.Result.success(sampleLink(receiptId = 60L, expenseId = 903L))

        buildWorker().doWork()

        // Pass EVERY named param explicitly: pin the load-bearing ones
        // (receiptId/expenseId/linkType/source/requireUnmatchedClaim) and use any()
        // for the don't-care defaults. This avoids relying on MockK reconstructing
        // omitted-named-arg defaults, which silently breaks on a signature change.
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 60L,
                expenseId = 903L,
                linkType = "AUTO_MATCH",
                source = "MATCHING_WORKER",
                createdBy = any(),
                confidence = any(),
                allowRelink = any(),
                matchStatus = any(),
                writeSourceLink = any(),
                requireUnmatchedClaim = true
            )
        }
    }

    @Test
    fun `worker handles db error gracefully`() = runTest {
        coEvery { receiptRepository.getProcessableReceipts() } throws IllegalStateException("db error")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any(), any()) }
    }

    @Test
    fun `worker stops retrying malformed receipt failures`() = runTest {
        coEvery { receiptRepository.getProcessableReceipts() } throws IllegalArgumentException("malformed receipt data")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any(), any()) }
    }

    @Test
    fun `worker stops retrying logical conflicts`() = runTest {
        coEvery { receiptRepository.getProcessableReceipts() } throws IllegalStateException("receipt matching conflict")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any(), any()) }
    }

    @Test
    fun `receipt_matching_runs_when_notification_permission_denied`() = runTest {
        // The worker must complete successfully even when notification permission is denied.
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns false
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(sampleReceipt(id = 100L))
        coEvery { matcher.findBestMatch(any(), any()) } returns MatchResult.NoMatch

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { matcher.findBestMatch(any(), any()) }
    }

    @Test
    fun `receipt_matching_links_receipt_when_notification_permission_denied`() = runTest {
        // Even when notification permission is denied, auto-match linking must still occur.
        val receipt = sampleReceipt(id = 101L)
        val expense = sampleExpense(id = 910L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns false
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.95)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 101L, expenseId = 910L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 101L, expenseId = 910L, linkType = "AUTO_MATCH", source = "MATCHING_WORKER",
                requireUnmatchedClaim = true, confidence = any(), createdBy = any(),
                allowRelink = any(), matchStatus = any(), writeSourceLink = any()
            )
        }
    }

    @Test
    fun `receipt_matching_saves_suggestion_when_notification_permission_denied`() = runTest {
        // Suggestions must be saved even when notification permission is denied.
        val receipt = sampleReceipt(id = 102L)
        val expense = sampleExpense(id = 911L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns false
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.Suggested(expense, 0.70)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { matchService.saveMatchSuggestion(receiptId = 102L, suggestedExpenseId = 911L, confidence = 0.70) }
    }

    @Test
    fun `receipt_matching_suppresses_notification_when_permission_denied`() = runTest {
        // When permission is denied, no notification is sent and no notifications-sent metric.
        val receipt = sampleReceipt(id = 103L)
        val expense = sampleExpense(id = 912L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns false
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.96)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 103L, expenseId = 912L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        // Link and metric for rows updated must still happen.
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
    }

    @Test
    fun `receipt_matching_permission_revoked_after_check_does_not_fail_worker`() = runTest {
        // If permission is granted at check time but revoked before sending the notification,
        // the SecurityException is caught and the worker does not fail.
        val receipt = sampleReceipt(id = 104L)
        val expense = sampleExpense(id = 913L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.97)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 104L, expenseId = 913L))
        coEvery { notificationService.sendBudgetAlert(any(), any(), any()) } throws SecurityException("notif denied")

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Notification was attempted but threw; metric must NOT be incremented.
        coVerify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        // Link still succeeded.
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
    }

    @Test
    fun `receipt_matching_permission_allowed_sends_notification`() = runTest {
        // When permission is allowed, the notification is sent and the metric is incremented.
        val receipt = sampleReceipt(id = 105L)
        val expense = sampleExpense(id = 914L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 105L, expenseId = 914L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
    }

    @Test
    fun `receipt_matching_notifications_sent_metric_only_when_sent`() = runTest {
        // The notifications-sent metric must only be incremented when a notification is
        // actually sent (not when suppressed, not when the SecurityException is caught).
        val receiptSuppressed = sampleReceipt(id = 106L, parsedMerchant = "SuppressedStore")
        val receiptSent = sampleReceipt(id = 107L, parsedMerchant = "SentStore")
        val expense1 = sampleExpense(id = 915L)
        val expense2 = sampleExpense(id = 916L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returnsMany listOf(false, true)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receiptSuppressed, receiptSent)
        coEvery { matcher.findBestMatch(receiptSuppressed, any()) } returns MatchResult.AutoMatch(expense1, 0.95)
        coEvery { matcher.findBestMatch(receiptSent, any()) } returns MatchResult.AutoMatch(expense2, 0.96)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            kotlin.Result.success(sampleLink(receiptId = 106L, expenseId = 915L)),
            kotlin.Result.success(sampleLink(receiptId = 107L, expenseId = 916L))
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Exactly one notification sent (second receipt allowed), so metric is 1.
        coVerify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
        // Both receipts were updated (linked).
        coVerify(exactly = 2) { ctx.addRowsUpdated() }
    }

    private fun sampleReceipt(
        id: Long,
        documentType: String = "RECEIPT",
        processingStatus: String = "PARSED",
        parsedMerchant: String = "Store"
    ): ScannedReceipt {
        return ScannedReceipt(
            id = id,
            imagePath = null,
            rawOcrText = "sample",
            parsedTotal = 12.34,
            parsedMerchant = parsedMerchant,
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f,
            matchStatus = MatchStatus.UNMATCHED,
            documentType = documentType,
            processingStatus = processingStatus
        )
    }

    private fun sampleExpense(id: Long): Expense {
        return Expense(
            id = id,
            amount = 12.34,
            merchant = "Store",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )
    }

    private fun sampleLink(receiptId: Long, expenseId: Long): ReceiptExpenseLink {
        return ReceiptExpenseLink(
            id = 1L,
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "AUTO_MATCH",
            confidence = 0.99f,
            source = "MATCHING_WORKER",
            createdAt = 1_700_000_000_000L,
            createdBy = "system"
        )
    }
}