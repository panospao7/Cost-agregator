package com.yourname.expensetracker.service.receiptmatching

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import com.yourname.expensetracker.data.service.AndroidNotificationService
import com.yourname.expensetracker.data.service.NotificationPresentationApplier
import com.yourname.expensetracker.data.service.NotificationPresentationTexts
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.service.PresentationFixtures.ScriptedSettingsRepository
import com.yourname.expensetracker.data.service.PresentationFixtures.everyString
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.ui.MainActivity
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.workers.NotificationPermissionChecker
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import timber.log.Timber

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

    private var diagnosticsScope: CoroutineScope? = null

    @After
    fun tearDownDiagnosticsScope() {
        diagnosticsScope?.cancel()
    }

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
        coEvery { matchService.saveMatchSuggestion(any(), any(), any()) } returns true
        receiptLinkService = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: CancellationException) {
                // Mirror the real guard: a plain CancellationException is rethrown ahead of the Exception catch.
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val transient = listOf("timeout", "interrupted", "deadlock", "SQLITE_BUSY", "database is locked")
                    .any { msg.contains(it, ignoreCase = true) } || e is java.io.IOException
                if (transient) WorkerGuardResult.Retry(msg, e)
                else WorkerGuardResult.Failed(msg, e)
            }
        }
    }

    @Test
    fun lostSuggestionCasDoesNotCountAnUpdate() = runTest {
        val receipt = sampleReceipt(id = 501L)
        val expense = sampleExpense(id = 601L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.Suggested(expense, 0.7)
        coEvery { matchService.saveMatchSuggestion(receipt.id, expense.id, 0.7) } returns false

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { matchService.saveMatchSuggestion(receipt.id, expense.id, 0.7) }
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
    }

    @Test
    fun successfulSuggestionCasCountsOneUpdate() = runTest {
        val receipt = sampleReceipt(id = 502L)
        val expense = sampleExpense(id = 602L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.Suggested(expense, 0.7)
        coEvery { matchService.saveMatchSuggestion(receipt.id, expense.id, 0.7) } returns true

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { ctx.addRowsUpdated() }
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
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any(), any()) }
        // P9-S4 zero-count: an empty receipt list increments no counters.
        coVerify(exactly = 0) { ctx.addRowsScanned() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
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
        // PR12L-2: uses structured reason code + error class instead of raw message
        coVerify(exactly = 1) {
            matchService.recordAutoMatchLinkFailed(30L, 900L, "RECEIPT_LINK_INVALID_STATE", "IllegalStateException")
        }
        // A failed link must not be treated as an auto-match success notification.
        coVerify(exactly = 0) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
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
        // RP-16 16-D: worker counts only DELIVERED results as notificationsSent.
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
    }

    // ── PR12L-2: structured link-failure diagnostics ─────────────────────────

    @Test
    fun `link_failure_uses_structured_reason_code`() = runTest {
        // An IllegalArgumentException must be mapped to RECEIPT_LINK_INVALID_ARGUMENT,
        // not the raw exception message.
        val receipt = sampleReceipt(id = 70L)
        val expense = sampleExpense(id = 920L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.failure(IllegalArgumentException("bad input data"))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            matchService.recordAutoMatchLinkFailed(
                receiptId = 70L,
                expenseId = 920L,
                reason = "RECEIPT_LINK_INVALID_ARGUMENT",
                errorClass = "IllegalArgumentException"
            )
        }
    }

    @Test
    fun `link_failure_error_class_not_message_persisted`() = runTest {
        // The raw exception message must NOT be persisted as the reason;
        // the reason must be the structured code and the errorClass must be
        // the Java simple name.
        val receipt = sampleReceipt(id = 71L)
        val expense = sampleExpense(id = 921L)
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        val rawMessage = "some sensitive internal detail"
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.failure(IllegalStateException(rawMessage))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // The reason must NOT be the raw message
        coVerify(exactly = 1) {
            matchService.recordAutoMatchLinkFailed(
                receiptId = 71L,
                expenseId = 921L,
                reason = "RECEIPT_LINK_INVALID_STATE",
                errorClass = "IllegalStateException"
            )
        }
    }

    // ── PR12L-3: durable notification-suppression diagnostics ───────────────

    @Test
    fun `permission_denied_records_suppressed_notification_diagnostic`() = runTest {
        // When permission is denied, the worker must record a durable
        // NOTIFICATION_SUPPRESSED event with the appropriate reason code.
        val receipt = sampleReceipt(id = 80L)
        val expense = sampleExpense(id = 930L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns false
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.96)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 80L, expenseId = 930L))

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 80L,
                expenseId = 930L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_DENIED",
                errorClass = null
            )
        }
    }

    @Test
    fun `permission_revoked_after_check_records_suppressed_diagnostic`() = runTest {
        // When permission is granted at check time but revoked before sending,
        // the SecurityException is caught and a diagnostic is recorded.
        val receipt = sampleReceipt(id = 81L)
        val expense = sampleExpense(id = 931L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.97)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 81L, expenseId = 931L))
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } throws SecurityException("notif denied")

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 81L,
                expenseId = 931L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED",
                errorClass = "SecurityException"
            )
        }
    }

    // ── PR12M-2: suppress all optional notification failures durably ─────────

    @Test
    fun `notification_service_exception_records_suppression_and_worker_succeeds`() = runTest {
        // When postBudgetAlertForKind throws a non-cancellation, non-SecurityException,
        // the worker must catch it, record a SERVICE_FAILURE suppression diagnostic,
        // and still succeed.
        val receipt = sampleReceipt(id = 82L)
        val expense = sampleExpense(id = 932L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 82L, expenseId = 932L))
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } throws RuntimeException("notification service unavailable")

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 82L,
                expenseId = 932L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_SERVICE_FAILURE",
                errorClass = "RuntimeException"
            )
        }
        // The notification-sent metric must NOT be incremented on failure.
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        // The link still succeeded.
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
    }

    @Test
    fun `notification_service_cancellation_rethrows`() = runTest {
        // When postBudgetAlertForKind throws CancellationException, it must propagate
        // through the notification catch, the worker's outer catch and the guard
        // (which rethrows cancellation), so doWork() throws instead of returning a Result.
        val receipt = sampleReceipt(id = 83L)
        val expense = sampleExpense(id = 933L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 83L, expenseId = 933L))
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } throws CancellationException("job cancelled")

        // CancellationException must propagate out of doWork(), not be mapped to a Result.
        // try/fail pattern: an AssertionError (not a CancellationException) is raised if
        // doWork() returns normally, and any other exception type is not swallowed.
        try {
            val result = buildWorker().doWork()
            throw AssertionError("Expected CancellationException to propagate, got result=$result")
        } catch (e: CancellationException) {
            // expected - cancellation must not be swallowed or mapped to a Result
            assertEquals("job cancelled", e.message)
        }
        // The notification call was actually reached (the CE originated from it).
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        // No suppression diagnostic should be recorded for cancellation.
        coVerify(exactly = 0) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }
        // No notification-sent metric.
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
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
        coVerify(exactly = 0) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
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
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } throws SecurityException("notif denied")

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Notification was attempted but threw; metric must NOT be incremented.
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
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
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
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
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Exactly one notification sent (second receipt allowed), so metric is 1.
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
        // Both receipts were updated (linked).
        coVerify(exactly = 2) { ctx.addRowsUpdated() }
    }

    @Test
    fun `receipt_matching_counts_only_DELIVERED_results_and_records_suppression`() = runTest {
        // RP-16 16-D: a NOT_DELIVERED result is NOT counted as notificationsSent.
        // It is recorded as a controlled suppression (constant reason code, error
        // class null — no payload leakage) and counted as a skipped row.
        val receipt = sampleReceipt(id = 108L, parsedMerchant = "NotDeliveredStore")
        val expense = sampleExpense(id = 917L)
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.94)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = 108L, expenseId = 917L))
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.NOT_DELIVERED

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        coVerify(exactly = 1) { ctx.addRowsSkipped() }
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 108L,
                expenseId = 917L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_NOT_DELIVERED",
                errorClass = null
            )
        }
        // Link still counted as updated work.
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
    }

    // ── S7 (AE-11, AE-13, AE-111, TECH-9 / owner decision C6): kind RECEIPT_MATCH + explicit PERMISSION_DENIED ──

    private fun stubAutoMatchLinkSuccess(receipt: ScannedReceipt, expense: Expense) {
        coEvery { notificationPermissionChecker.areNotificationsEnabled() } returns true
        coEvery { receiptRepository.getProcessableReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt, any()) } returns MatchResult.AutoMatch(expense, 0.98)
        coEvery {
            receiptLinkService.linkReceiptToExpense(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns kotlin.Result.success(sampleLink(receiptId = receipt.id, expenseId = expense.id))
    }

    @Test
    fun `AE-13 worker declares kind RECEIPT_MATCH with the unchanged receipt id and never BUDGET or the other ForKind form`() = runTest {
        val receipt = sampleReceipt(id = 120L, parsedMerchant = "SentinelMerchant")
        val expense = sampleExpense(id = 940L)
        stubAutoMatchLinkSuccess(receipt, expense)
        val kind = slot<NotificationKind>()
        val id = slot<NotificationId>()
        val title = slot<String>()
        val message = slot<String>()
        coEvery {
            notificationService.postBudgetAlertForKind(capture(kind), capture(id), capture(title), capture(message))
        } returns NotificationService.DeliveryResult.DELIVERED

        assertEquals(Result.success(), buildWorker().doWork())

        assertEquals(NotificationKind.RECEIPT_MATCH, kind.captured)
        // NotificationId has no equals: compare the value.
        assertEquals(NotificationId.forReceipt(120L).value, id.captured.value)
        assertEquals(context.getString(com.yourname.expensetracker.R.string.receipt_matching_auto_matched_title), title.captured)
        assertEquals(
            context.getString(com.yourname.expensetracker.R.string.receipt_matching_auto_matched_message_format, "SentinelMerchant"),
            message.captured
        )
        coVerify(exactly = 0) {
            notificationService.postBudgetAlertForKind(NotificationKind.BUDGET, any(), any(), any())
        }
        // S9a: the legacy kind-less methods no longer exist; the raw-id kind-aware form is the other path.
        coVerify(exactly = 0) { notificationService.sendBudgetAlertForKind(any(), any(), any(), any()) }
    }

    @Test
    fun `AE-11 DELIVERED keeps parity - one notificationsSent, no skipped row, no suppression, link called once`() = runTest {
        val receipt = sampleReceipt(id = 121L)
        val expense = sampleExpense(id = 941L)
        stubAutoMatchLinkSuccess(receipt, expense)
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            receiptLinkService.linkReceiptToExpense(
                receiptId = 121L, expenseId = 941L, linkType = "AUTO_MATCH", source = "MATCHING_WORKER",
                requireUnmatchedClaim = true, confidence = any(), createdBy = any(),
                allowRelink = any(), matchStatus = any(), writeSourceLink = any()
            )
        }
    }

    @Test
    fun `AE-11 TECH-9 PERMISSION_DENIED result records PERMISSION_REVOKED with constant error class and no skipped row`() = runTest {
        val receipt = sampleReceipt(id = 122L, parsedMerchant = "SentinelMerchant")
        val expense = sampleExpense(id = 942L)
        stubAutoMatchLinkSuccess(receipt, expense)
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } returns
            NotificationService.DeliveryResult.PERMISSION_DENIED

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 122L,
                expenseId = 942L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED",
                errorClass = "SecurityException"
            )
        }
        coVerify(exactly = 1) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { matchService.recordAutoMatchLinkFailed(any(), any(), any(), any()) }
    }

    @Test
    fun `AE-11 a service that still throws SecurityException keeps the defensive catch with the same diagnostic`() = runTest {
        val receipt = sampleReceipt(id = 123L)
        val expense = sampleExpense(id = 943L)
        stubAutoMatchLinkSuccess(receipt, expense)
        coEvery { notificationService.postBudgetAlertForKind(any(), any(), any(), any()) } throws
            SecurityException("notif denied")

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 123L,
                expenseId = 943L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED",
                errorClass = "SecurityException"
            )
        }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
    }

    @Test
    fun `AE-11 parity - real service with a throwing NotificationManager yields the same counters and diagnostic as a PERMISSION_DENIED result`() = runTest {
        val receipt = sampleReceipt(id = 124L, parsedMerchant = "SentinelMerchant")
        val expense = sampleExpense(id = 944L)
        stubAutoMatchLinkSuccess(receipt, expense)
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.areNotificationsEnabled() } returns true
        every { manager.notify(any<Int>(), any<Notification>()) } throws SecurityException("denied for SentinelMerchant")
        notificationService = realService(contextWith(manager), discreet = true)

        // Capture both android.util.Log (ShadowLog, used by the service) and Timber (used by the worker).
        ShadowLog.clear()
        val timberLines = mutableListOf<String>()
        val timberTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                timberLines += "$tag $message ${t?.toString().orEmpty()}"
            }
        }
        Timber.plant(timberTree)
        try {
            assertEquals(Result.success(), buildWorker().doWork())
        } finally {
            Timber.uproot(timberTree)
        }

        val logLines = ShadowLog.getLogs().map { "${it.tag} ${it.msg} ${it.throwable?.toString().orEmpty()}" } + timberLines
        assertTrue("the denied path logged at least one line", logLines.isNotEmpty())
        logLines.forEach { assertFalse("sentinel merchant leaked into log '$it'", it.contains("SentinelMerchant")) }

        verify(exactly = 1) { manager.notify(NotificationId.forReceipt(124L).value, any<Notification>()) }
        coVerify(exactly = 1) {
            matchService.recordNotificationSuppressed(
                receiptId = 124L,
                expenseId = 944L,
                reasonCode = "RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_PERMISSION_REVOKED",
                errorClass = "SecurityException"
            )
        }
        coVerify(exactly = 1) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addNotificationsSent() }
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
    }

    @Test
    fun `AE-111 real service DISCREET - sentinel merchant absent, channel budget_alerts, receipt id, tap target home with CLEAR_TASK`() = runTest {
        val sentinel = "SentinelMerchant4242"
        val receipt = sampleReceipt(id = 125L, parsedMerchant = sentinel)
        val expense = sampleExpense(id = 945L)
        stubAutoMatchLinkSuccess(receipt, expense)
        notificationService = realService(context, discreet = true)

        assertEquals(Result.success(), buildWorker().doWork())

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val expectedId = NotificationId.forReceipt(125L).value
        val matches = manager.activeNotifications.filter { it.id == expectedId }
        assertEquals("exactly one active notification with the receipt id", 1, matches.size)
        val n = matches.single().notification
        assertEquals("budget_alerts", n.channelId)
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
        coVerify(exactly = 0) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }

        assertDiscreetReceiptNotification(n, sentinel)

        val intent = shadowOf(n.contentIntent).savedIntent
        assertNotNull(intent)
        assertEquals("expensetracker://home", intent.data.toString())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `AE-111 INV-6 real service with an unreadable settings store posts DISCREET`() = runTest {
        val sentinel = "SentinelMerchant4242"
        val receipt = sampleReceipt(id = 127L, parsedMerchant = sentinel)
        val expense = sampleExpense(id = 947L)
        stubAutoMatchLinkSuccess(receipt, expense)
        notificationService = realService(context, discreet = false, settingsUnreadable = true)

        assertEquals(Result.success(), buildWorker().doWork())

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val expectedId = NotificationId.forReceipt(127L).value
        val matches = manager.activeNotifications.filter { it.id == expectedId }
        assertEquals("exactly one active notification with the receipt id", 1, matches.size)
        val n = matches.single().notification
        assertEquals("budget_alerts", n.channelId)
        assertDiscreetReceiptNotification(n, sentinel)
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
        coVerify(exactly = 0) { matchService.recordNotificationSuppressed(any(), any(), any(), any()) }
    }

    @Test
    fun `AE-111 real service FULL - the detailed merchant text is shown on the same channel and id`() = runTest {
        val merchant = "FullModeMerchant"
        val receipt = sampleReceipt(id = 126L, parsedMerchant = merchant)
        val expense = sampleExpense(id = 946L)
        stubAutoMatchLinkSuccess(receipt, expense)
        notificationService = realService(context, discreet = false)

        assertEquals(Result.success(), buildWorker().doWork())

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = manager.activeNotifications.single { it.id == NotificationId.forReceipt(126L).value }.notification
        assertEquals("budget_alerts", n.channelId)
        assertTrue(everyString(n).any { it.contains(merchant) })
        coVerify(exactly = 1) { ctx.addNotificationsSent() }
    }

    private fun contextWith(manager: NotificationManager): Context =
        object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) manager else super.getSystemService(name)
        }

    private fun realService(
        serviceContext: Context,
        discreet: Boolean,
        settingsUnreadable: Boolean = false
    ): AndroidNotificationService {
        val scope = CoroutineScope(Job())
        diagnosticsScope = scope
        val repo = ScriptedSettingsRepository {
            if (settingsUnreadable) {
                AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
            } else {
                AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = discreet))
            }
        }
        val resolver = NotificationPresentationResolver(repo, mockk<PrivacyDiagnosticsEmitter>(relaxed = true), scope)
        val applier = NotificationPresentationApplier(context, NotificationPresentationTexts(context))
        return AndroidNotificationService(serviceContext, resolver, applier, mockk<PrivacyDiagnosticsEmitter>(relaxed = true))
    }

    /**
     * The DISCREET contract for a receipt-match notification: the RECEIPT_MATCH constants (never the BUDGET
     * ones), no merchant and no "Receipt from" text anywhere, an explicit MainActivity immutable tap target,
     * and the generic public version. The recursive string scan is [PresentationFixtures.everyString].
     */
    private fun assertDiscreetReceiptNotification(n: Notification, sentinel: String) {
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals(context.getString(R.string.notif_discreet_receipt_match_title), title)
        assertEquals(context.getString(R.string.notif_discreet_receipt_match_text), text)
        val budget = NotificationPresentationTexts(context).discreet(NotificationKind.BUDGET)
        assertNotEquals(budget.title, title)
        assertNotEquals(budget.text, text)

        val everything = everyString(n)
        assertTrue("the notification carries some text", everything.isNotEmpty())
        everything.forEach {
            assertFalse("sentinel merchant leaked: $it", it.contains(sentinel))
            assertFalse("'Receipt from' leaked: $it", it.contains("Receipt from"))
        }

        val shadow = shadowOf(n.contentIntent)
        val intent = shadow.savedIntent
        assertNotNull(intent)
        assertNotNull("explicit intent component", intent.component)
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertTrue("content PendingIntent is immutable", (shadow.flags and PendingIntent.FLAG_IMMUTABLE) != 0)

        val pub = n.publicVersion
        assertNotNull("public version present", pub)
        assertEquals(context.getString(R.string.notif_public_title), pub!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(context.getString(R.string.notif_public_text), pub.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
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
