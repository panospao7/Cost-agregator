package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkerExecutionGuardTest {

    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var readBarrier: DatabaseReadBarrier
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var workerRunLogger: WorkerRunLogger
    private lateinit var privacyGate: PrivacyGate
    private lateinit var leaseRegistry: WorkerLeaseRegistry
    private lateinit var diagnosticSink: MaintenanceSafeDiagnosticSink
    private lateinit var workerTerminalDiagnosticSink: WorkerTerminalDiagnosticSink
    private lateinit var backgroundJobRunDao: BackgroundJobRunDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var runHandle: WorkerRunHandle
    private lateinit var lease: WorkerLease

    private lateinit var permissionChecker: FakeNotificationPermissionChecker

    private lateinit var guard: WorkerExecutionGuard

    /** Simple fake so we can both control the result and assert (non-)invocation. */
    private class FakeNotificationPermissionChecker(
        var enabled: Boolean
    ) : NotificationPermissionChecker {
        var callCount: Int = 0
        override fun areNotificationsEnabled(): Boolean {
            callCount++
            return enabled
        }
    }

    @Before
    fun setup() {
        writeBarrier = mockk(relaxed = true)
        readBarrier = mockk(relaxed = true)
        restoreMaintenanceMode = mockk(relaxed = true)
        workerRunLogger = mockk()
        privacyGate = mockk(relaxed = true)
        leaseRegistry = mockk()
        diagnosticSink = mockk(relaxed = true)
        workerTerminalDiagnosticSink = mockk(relaxed = true)
        backgroundJobRunDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        runHandle = mockk(relaxed = true)
        lease = mockk(relaxed = true)
        permissionChecker = FakeNotificationPermissionChecker(enabled = true)

        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns lease
        every { leaseRegistry.isStopRequested() } returns false
        coEvery { workerRunLogger.start(any(), any(), any(), any(), any(), any()) } returns runHandle

        // PR12H-3: explicit stubs for terminal methods returning TerminalWriteOutcome
        // (mockk(relaxed=true) cannot create mocks of sealed interfaces)
        coEvery { runHandle.success(any(), any(), any(), any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.skipped(any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.retry(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.failure(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.cancelled(any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.staleAborted() } returns TerminalWriteOutcome.Durable

        guard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMaintenanceMode,
            workerRunLogger = workerRunLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
            workerTerminalDiagnosticSink = workerTerminalDiagnosticSink,
            backgroundJobRunDao = backgroundJobRunDao,
            notificationPermissionChecker = permissionChecker,
            timeProvider = timeProvider
        )
    }

    private fun request() = WorkerGuardRequest(
        workerName = "test_worker",
        requiresNotificationPermission = true
    )

    @Test
    fun `permission denied is skipped with NOTIFICATION_PERMISSION_DENIED reason`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuarded(request()) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `runGuardedWithContext permission denied is skipped with NOTIFICATION_PERMISSION_DENIED reason`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuardedWithContext(request()) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `permission granted runs block`() = runTest {
        permissionChecker.enabled = true
        var blockRan = false

        val result = guard.runGuarded(request()) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Success)
        assertTrue("block must run when permission granted", blockRan)
        coVerify(exactly = 1) { runHandle.success(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { runHandle.skipped(any()) }
    }

    @Test
    fun `permission flag false never calls checker`() = runTest {
        val req = WorkerGuardRequest(
            workerName = "test_worker",
            requiresNotificationPermission = false
        )
        var blockRan = false

        val result = guard.runGuarded(req) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Success)
        assertTrue(blockRan)
        assertEquals("checker must not be invoked when flag is false", 0, permissionChecker.callCount)
    }

    // B1 drift guard: prove the REAL guard EMITS the exact literal the worker's
    // reschedule contract pins on. The literal-pin test in DailyBriefingWorkerTest
    // only proves the CONSTANT equals "Worker disabled by spec"; it does NOT prove
    // the guard still emits that string. Here we drive the real guard against a
    // DISABLED worker spec (injected via WorkerSpec.DEFAULTS, the same source the
    // guard reads) and assert the returned Skipped.reason EQUALS
    // DailyBriefingWorker.DISABLED_BY_SPEC_REASON. Together the two tests pin both
    // ends: a change to the guard's emitted literal — or to the worker's constant —
    // now fails CI instead of passing tautologically.
    @Test
    fun `disabled spec skip reason equals DailyBriefingWorker DISABLED_BY_SPEC_REASON`() = runTest {
        val workerName = "disabled_guard_worker"
        mockkObject(WorkerSpec.Companion)
        try {
            every { WorkerSpec.DEFAULTS } returns mapOf(
                workerName to WorkerSpec(name = workerName, enabled = false)
            )
            var blockRan = false

            val result = guard.runGuarded(
                WorkerGuardRequest(workerName = workerName)
            ) { blockRan = true }

            assertTrue(result is WorkerGuardResult.Skipped)
            assertEquals(
                DailyBriefingWorker.DISABLED_BY_SPEC_REASON,
                (result as WorkerGuardResult.Skipped).reason
            )
            assertFalse("block must not run when worker is disabled by spec", blockRan)
        } finally {
            unmockkAll()
        }
    }

    // -------------------------------------------------------------------------
    // U-WORKER-01: write barrier before run logging
    // -------------------------------------------------------------------------

    @Test
    fun `startRunSafely returns BlockedRetry when write barrier denies before dao insert`() = runTest {
        // Simulate mode transitioning to non-NORMAL between the top-level check and
        // startRunSafely's internal barrier check (TOCTOU race).
        // First call (top-level check) passes; second call (inside startRunSafely) throws.
        var callCount = 0
        every { writeBarrier.checkWritesAllowed(any<String>()) } answers {
            callCount++
            if (callCount > 1) {
                throw com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                    accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                    operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("WorkerRunLogger.start:test_worker"),
                    mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
                )
            }
        }
        var blockRan = false

        val result = guard.runGuarded(
            WorkerGuardRequest(workerName = "test_worker", requiresNotificationPermission = false)
        ) { blockRan = true }

        assertTrue(result is WorkerGuardResult.BlockedRetry)
        assertEquals(
            DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name,
            (result as WorkerGuardResult.BlockedRetry).blockedReasonCode
        )
        assertFalse("block must not run when barrier denies at startRunSafely", blockRan)
        // workerRunLogger.start() must never be called if barrier throws first
        coVerify(exactly = 0) { workerRunLogger.start(any(), any(), any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // PR6A: BlockedPolicy tests
    // -------------------------------------------------------------------------

    @Test
    fun `notification_intake_restore_block_returns_retry`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        var blockRan = false

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "notification_intake",
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { blockRan = true }

        assertTrue("restore block should return BlockedRetry for RETRY policy", result is WorkerGuardResult.BlockedRetry)
        assertEquals(
            DiagnosticReasonCode.RESTORE_BLOCKED.name,
            (result as WorkerGuardResult.BlockedRetry).blockedReasonCode
        )
        assertFalse("block must not run during restore", blockRan)
    }

    @Test
    fun `notification_intake_write_barrier_block_returns_retry`() = runTest {
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("notification_intake"),
                mode = RestoreMaintenanceMode.Mode.NORMAL
            )
        var blockRan = false

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "notification_intake",
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { blockRan = true }

        assertTrue("write barrier block should return BlockedRetry for RETRY policy", result is WorkerGuardResult.BlockedRetry)
        assertEquals(
            DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name,
            (result as WorkerGuardResult.BlockedRetry).blockedReasonCode
        )
        assertFalse("block must not run when write barrier denies", blockRan)
    }

    @Test
    fun `periodic_worker_restore_block_can_skip_success_when_policy_says_so`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
        var blockRan = false

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "ai_daily_briefing",
                requiresDatabaseWrite = false,
                blockedPolicy = BlockedPolicy.SKIP_SUCCESS
            )
        ) { blockRan = true }

        assertTrue("SKIP_SUCCESS policy should return Skipped", result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.RESTORE_BLOCKED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run during restore", blockRan)
    }

    @Test
    fun `start_run_write_barrier_block_uses_worker_policy`() = runTest {
        // Test all three policies for startRunSafely barrier denial
        // Retry
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("WorkerRunLogger.start:wrk"),
                mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
            )
        val resultRetry = guard.runGuarded(
            WorkerGuardRequest(workerName = "wrk", blockedPolicy = BlockedPolicy.RETRY)
        ) { }
        assertTrue(resultRetry is WorkerGuardResult.BlockedRetry)

        // Skip success
        val resultSkip = guard.runGuarded(
            WorkerGuardRequest(workerName = "wrk", blockedPolicy = BlockedPolicy.SKIP_SUCCESS)
        ) { }
        assertTrue(resultSkip is WorkerGuardResult.Skipped)

        // Fail
        val resultFail = guard.runGuarded(
            WorkerGuardRequest(workerName = "wrk", blockedPolicy = BlockedPolicy.FAIL)
        ) { }
        assertTrue(resultFail is WorkerGuardResult.Failed)
    }

    @Test
    fun `acquire_rejected_by_stop_request_returns_retry_for_dynamic_worker`() = runTest {
        // Simulate stop requested => lease acquisition should throw LeaseAcquisitionBlockedException
        // and the guard should map that to BlockedRetry (since default blockedPolicy is RETRY)
        coEvery { leaseRegistry.acquire(any()) } throws
            LeaseAcquisitionBlockedException("test_worker", "stop requested")
        var blockRan = false

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "test_worker",
                requiresNotificationPermission = false,
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { blockRan = true }

        assertTrue("acquire blocked should return BlockedRetry", result is WorkerGuardResult.BlockedRetry)
        assertEquals(
            DiagnosticReasonCode.WORKER_STOP_REQUESTED.name,
            (result as WorkerGuardResult.BlockedRetry).blockedReasonCode
        )
        assertFalse("block must not run when acquire is rejected", blockRan)
    }

    // -------------------------------------------------------------------------
    // P9-NEW-13: typed retry signal + message-based classification precedence
    // -------------------------------------------------------------------------

    @Test
    fun `RetryableWorkerException maps to Retry and finalizes run as retry`() = runTest {
        permissionChecker.enabled = true
        val ex = RetryableWorkerException("explicit retry requested")

        val result = guard.runGuarded(request()) { throw ex }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals("explicit retry requested", (result as WorkerGuardResult.Retry).reason)
        // The run must be finalized as RETRY, never as FAILED — the worker's explicit
        // retry intent must survive even though the message matches no transient keyword.
        coVerify(exactly = 1) { runHandle.retry("explicit retry requested", ex) }
        coVerify(exactly = 0) { runHandle.failure(any(), any()) }
    }

    @Test
    fun `non-transient RuntimeException maps to Failed`() = runTest {
        permissionChecker.enabled = true
        val ex = RuntimeException("some permanent business error")

        val result = guard.runGuarded(request()) { throw ex }

        // Pin the existing message-based behavior: a non-transient message that is not a
        // RetryableWorkerException remains a PERMANENT failure. Proves the additive typed
        // signal did not alter the fallback classification for other workers.
        assertTrue(result is WorkerGuardResult.Failed)
        assertEquals(
            DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
            (result as WorkerGuardResult.Failed).reason
        )
        coVerify(exactly = 1) {
            runHandle.failure(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name, ex)
        }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
    }

    @Test
    fun `transient message still maps to Retry`() = runTest {
        permissionChecker.enabled = true
        val ex = RuntimeException("operation timeout while writing")

        val result = guard.runGuarded(request()) { throw ex }

        // classifyTransient keyword list is unchanged: a "timeout" message still retries,
        // but PR12J-1 now emits the safe structured WORKER_TRANSIENT_ERROR reason code.
        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(
            DiagnosticReasonCode.WORKER_TRANSIENT_ERROR.name,
            (result as WorkerGuardResult.Retry).reason
        )
        coVerify(exactly = 1) {
            runHandle.retry(DiagnosticReasonCode.WORKER_TRANSIENT_ERROR.name, ex)
        }
        coVerify(exactly = 0) { runHandle.failure(any(), any()) }
    }

    @Test
    fun `CancellationException is rethrown with highest precedence`() = runTest {
        permissionChecker.enabled = true
        val ex = kotlinx.coroutines.CancellationException("cancelled")

        try {
            guard.runGuarded(request()) { throw ex }
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected — cancellation must never be classified as retry/failure
        }
        coVerify(exactly = 1) { runHandle.cancelled(DiagnosticReasonCode.WORKER_CANCELLED.name) }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
        coVerify(exactly = 0) { runHandle.failure(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // U-WORKER-03: NO_WORK message for zero-count success runs
    // -------------------------------------------------------------------------

    @Test
    fun `runGuardedWithContext passes NO_WORK message when all counters are zero`() = runTest {
        permissionChecker.enabled = true
        val req = WorkerGuardRequest(workerName = "test_worker", requiresNotificationPermission = false)

        val result = guard.runGuardedWithContext(req) { /* no counter increments */ }

        assertTrue(result is WorkerGuardResult.Success)
        coVerify(exactly = 1) {
            runHandle.success(rowsScanned = 0, rowsUpdated = 0, notificationsSent = 0, message = "NO_WORK", reasonCode = any())
        }
    }

    @Test
    fun `runGuardedWithContext passes null message when counters are non-zero`() = runTest {
        permissionChecker.enabled = true
        val req = WorkerGuardRequest(workerName = "test_worker", requiresNotificationPermission = false)

        val result = guard.runGuardedWithContext(req) { ctx -> ctx.addRowsUpdated(3) }

        assertTrue(result is WorkerGuardResult.Success)
        coVerify(exactly = 1) {
            runHandle.success(rowsScanned = 0, rowsUpdated = 3, notificationsSent = 0, message = null, reasonCode = any())
        }
    }

    // -------------------------------------------------------------------------
    // PR8: Durable Bounded Terminal Diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun `terminal_success_write_survives_worker_cancellation`() = runTest {
        // Verify that the terminal write (runHandle.success()) completes even when
        // the worker coroutine is cancelled, thanks to NonCancellable inside the
        // withBoundedTerminalWrite helper.
        permissionChecker.enabled = true
        var successCalled = false
        val enteredTerminalWrite = CompletableDeferred<Unit>()
        val proceedWithWrite = CompletableDeferred<Unit>()

        coEvery { runHandle.success(any(), any(), any(), any(), any()) } coAnswers {
            enteredTerminalWrite.complete(Unit)
            proceedWithWrite.await() // suspend inside the terminal write
            successCalled = true
            TerminalWriteOutcome.Durable
        }

        val job = launch {
            guard.runGuarded(request()) { /* block succeeds immediately */ }
        }

        // Wait until the coroutine has entered the terminal write and is suspended there
        enteredTerminalWrite.await()
        // Cancel the job while the terminal write is in progress.
        // NonCancellable ensures the write still completes.
        job.cancel()
        // Allow the terminal write to proceed
        proceedWithWrite.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue("Terminal write must complete even after cancellation", successCalled)
    }

    @Test
    fun `terminal_write_timeout_does_not_hang_worker`() = runTest {
        // Mock the DAO-backed handle to take longer than the 5s timeout.
        // The guard should return quickly (within the timeout) rather than
        // hanging on the blocked DB.
        permissionChecker.enabled = true
        coEvery { runHandle.success() } coAnswers {
            delay(10_000L)  // longer than TERMINAL_WRITE_TIMEOUT_MS = 5_000
            TerminalWriteOutcome.Durable
        }

        val result = guard.runGuarded(request()) { /* block succeeds immediately */ }

        // The guard must return Success even though the terminal write timed out
        // (the helper returns null and execution continues).
        assertTrue("Result should be Success despite terminal write timeout", result is WorkerGuardResult.Success)
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12B: CAS-based stale recovery — does NOT overwrite real terminal state
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `stale_recovery_does_not_overwrite_success`() = runTest {
        val staleRun = BackgroundJobRun(
            id = 1L,
            workerName = "stale_worker",
            startedAt = 100L,
            status = "RUNNING"
        )
        coEvery { backgroundJobRunDao.getStaleRunningRuns(any()) } returns listOf(staleRun)
        // staleAbortIfStillRunning returns 0 — row was already completed to SUCCESS by live worker
        coEvery { backgroundJobRunDao.staleAbortIfStillRunning(any(), any(), any(), any(), any(), any()) } returns 0

        guard.recoverStaleRunningJobs(staleThresholdMs = 200L)

        coVerify(exactly = 1) {
            backgroundJobRunDao.staleAbortIfStillRunning(
                id = eq(1L),
                staleThresholdMs = eq(200L),
                finishedAt = any(),
                statusReason = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalReasonCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalDiagnosticCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name)
            )
        }
        // 0 affected means no overwrite occurred — the real SUCCESS state was preserved
    }

    @Test
    fun `stale_recovery_does_not_overwrite_failed`() = runTest {
        val staleRun = BackgroundJobRun(
            id = 2L,
            workerName = "stale_worker",
            startedAt = 100L,
            status = "RUNNING"
        )
        coEvery { backgroundJobRunDao.getStaleRunningRuns(any()) } returns listOf(staleRun)
        // staleAbortIfStillRunning returns 0 — row was already FAILED
        coEvery { backgroundJobRunDao.staleAbortIfStillRunning(any(), any(), any(), any(), any(), any()) } returns 0

        guard.recoverStaleRunningJobs(staleThresholdMs = 300L)

        coVerify(exactly = 1) {
            backgroundJobRunDao.staleAbortIfStillRunning(
                id = eq(2L),
                staleThresholdMs = eq(300L),
                finishedAt = any(),
                statusReason = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalReasonCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalDiagnosticCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name)
            )
        }
    }

    @Test
    fun `stale_recovery_only_updates_running_old_rows`() = runTest {
        val staleRun = BackgroundJobRun(
            id = 3L,
            workerName = "old_worker",
            startedAt = 100L,
            status = "RUNNING"
        )
        coEvery { backgroundJobRunDao.getStaleRunningRuns(any()) } returns listOf(staleRun)
        coEvery { backgroundJobRunDao.staleAbortIfStillRunning(any(), any(), any(), any(), any(), any()) } returns 1

        val threshold = timeProvider.now() - WorkerExecutionGuard.STALE_THRESHOLD_MS
        guard.recoverStaleRunningJobs(staleThresholdMs = threshold)

        coVerify(exactly = 1) {
            backgroundJobRunDao.getStaleRunningRuns(threshold)
        }
        coVerify(exactly = 1) {
            backgroundJobRunDao.staleAbortIfStillRunning(
                id = eq(3L),
                staleThresholdMs = eq(threshold),
                finishedAt = any(),
                statusReason = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalReasonCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name),
                terminalDiagnosticCode = eq(DiagnosticReasonCode.STALE_RUNNING_ABORTED.name)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12C: Privacy & Notification Permission Policy tests — runGuarded
    // ══════════════════════════════════════════════════════════════════════

    private fun privacyRequest(privacyPolicy: PrivacyPolicy = PrivacyPolicy.SKIP_SUCCESS) = WorkerGuardRequest(
        workerName = "test_worker",
        requiredCapabilities = listOf(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST),
        requiresNotificationPermission = false,
        privacyPolicy = privacyPolicy
    )

    private fun notificationRequest(permissionPolicy: PermissionPolicy = PermissionPolicy.SKIP_SUCCESS) = WorkerGuardRequest(
        workerName = "test_worker",
        requiresNotificationPermission = true,
        notificationPermissionPolicy = permissionPolicy
    )

    @Test
    fun `privacy_denied_skip_policy_returns_success`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuarded(privacyRequest(PrivacyPolicy.SKIP_SUCCESS)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Skipped).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.skipped(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name) }
    }

    @Test
    fun `privacy_denied_retry_policy_returns_retry`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuarded(privacyRequest(PrivacyPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Retry).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, null) }
    }

    @Test
    fun `privacy_denied_fail_policy_returns_failure`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuarded(privacyRequest(PrivacyPolicy.FAIL)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Failed)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Failed).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.failure(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, null) }
    }

    @Test
    fun `privacy_fail_closed_honors_policy`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.FailClosed("gate error")
        var blockRan = false

        val result = guard.runGuarded(privacyRequest(PrivacyPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_FAIL_CLOSED.name, (result as WorkerGuardResult.Retry).reason)
        assertFalse("block must not run when privacy fail-closed", blockRan)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_PRIVACY_FAIL_CLOSED.name, null) }
    }

    @Test
    fun `notification_permission_denied_skip_policy_returns_success`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuarded(notificationRequest(PermissionPolicy.SKIP_SUCCESS)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `notification_permission_denied_retry_policy_returns_retry`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuarded(notificationRequest(PermissionPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Retry).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.retry(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name, null)
        }
    }

    @Test
    fun `notification_permission_denied_fail_policy_returns_failure`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuarded(notificationRequest(PermissionPolicy.FAIL)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Failed)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Failed).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.failure(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name, null)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12C: Privacy & Notification Permission Policy tests — runGuardedWithContext
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `runGuardedWithContext privacy_denied_skip_policy_returns_success`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuardedWithContext(privacyRequest(PrivacyPolicy.SKIP_SUCCESS)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Skipped).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.skipped(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name) }
    }

    @Test
    fun `runGuardedWithContext privacy_denied_retry_policy_returns_retry`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuardedWithContext(privacyRequest(PrivacyPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Retry).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, null) }
    }

    @Test
    fun `runGuardedWithContext privacy_denied_fail_policy_returns_failure`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.Denied("privacy opt-out")
        var blockRan = false

        val result = guard.runGuardedWithContext(privacyRequest(PrivacyPolicy.FAIL)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Failed)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, (result as WorkerGuardResult.Failed).reason)
        assertFalse("block must not run when privacy denied", blockRan)
        coVerify(exactly = 1) { runHandle.failure(DiagnosticReasonCode.WORKER_PRIVACY_DENIED.name, null) }
    }

    @Test
    fun `runGuardedWithContext privacy_fail_closed_honors_policy`() = runTest {
        coEvery { privacyGate.check(any<PrivacyCapability>()) } returns
            PrivacyDecision.FailClosed("gate error")
        var blockRan = false

        val result = guard.runGuardedWithContext(privacyRequest(PrivacyPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(DiagnosticReasonCode.WORKER_PRIVACY_FAIL_CLOSED.name, (result as WorkerGuardResult.Retry).reason)
        assertFalse("block must not run when privacy fail-closed", blockRan)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_PRIVACY_FAIL_CLOSED.name, null) }
    }

    @Test
    fun `runGuardedWithContext notification_permission_denied_skip_policy_returns_success`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuardedWithContext(notificationRequest(PermissionPolicy.SKIP_SUCCESS)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `runGuardedWithContext notification_permission_denied_retry_policy_returns_retry`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuardedWithContext(notificationRequest(PermissionPolicy.RETRY)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Retry)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Retry).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.retry(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name, null)
        }
    }

    @Test
    fun `runGuardedWithContext notification_permission_denied_fail_policy_returns_failure`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuardedWithContext(notificationRequest(PermissionPolicy.FAIL)) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Failed)
        assertEquals(
            DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Failed).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.failure(DiagnosticReasonCode.WORKER_NOTIFICATION_PERMISSION_DENIED.name, null)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PR12H-1: TimeoutPolicy + Checkpoint Block Semantics
    // ══════════════════════════════════════════════════════════════════════

    /** Helper: creates a real [TimeoutCancellationException] via [withTimeout]. */
    private fun createTimeoutCancellationException(): TimeoutCancellationException = runBlocking {
        try {
            withTimeout(1) { delay(10) }
            throw IllegalStateException("Expected TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            return@runBlocking e
        }
    }

    @Test
    fun `worker_block_timeout_default_policy_returns_retry`() = runTest {
        permissionChecker.enabled = true
        val timeoutEx = createTimeoutCancellationException()

        val result = guard.runGuarded(
            WorkerGuardRequest(workerName = "test_worker", requiresNotificationPermission = false)
        ) { throw timeoutEx }

        assertTrue("Default RETRY policy should return Retry for TCE", result is WorkerGuardResult.Retry)
        assertEquals(DiagnosticReasonCode.WORKER_TIMEOUT.name, (result as WorkerGuardResult.Retry).reason)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_TIMEOUT.name, timeoutEx) }
        coVerify(exactly = 0) { runHandle.cancelled(any()) }
    }

    @Test
    fun `worker_block_timeout_propagate_policy_rethrows_cancellation`() = runTest {
        permissionChecker.enabled = true
        val timeoutEx = createTimeoutCancellationException()

        try {
            guard.runGuarded(
                WorkerGuardRequest(
                    workerName = "test_worker",
                    requiresNotificationPermission = false,
                    timeoutPolicy = WorkerTimeoutPolicy.PROPAGATE_CANCELLATION
                )
            ) { throw timeoutEx }
            throw AssertionError("Expected TimeoutCancellationException to propagate")
        } catch (e: TimeoutCancellationException) {
            assertEquals(timeoutEx, e)
        }
        coVerify(exactly = 1) { runHandle.cancelled(DiagnosticReasonCode.WORKER_CANCELLED.name) }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
    }

    @Test
    fun `checkpoint_stop_requested_retry_policy_returns_retry`() = runTest {
        permissionChecker.enabled = true
        every { leaseRegistry.isStopRequested() } returns true

        var blockRan = false
        val result = guard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "test_worker",
                requiresNotificationPermission = false,
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { ctx -> blockRan = true; ctx.checkpoint("test_op") }

        assertTrue("Stop requested should return BlockedRetry for RETRY policy", result is WorkerGuardResult.BlockedRetry)
        assertEquals(DiagnosticReasonCode.WORKER_STOP_REQUESTED.name, (result as WorkerGuardResult.BlockedRetry).blockedReasonCode)
        // block started running but checkpoint blocked it
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_STOP_REQUESTED.name, any<WorkerCheckpointBlockedException>()) }
    }

    @Test
    fun `checkpoint_write_barrier_denied_retry_policy_returns_retry`() = runTest {
        permissionChecker.enabled = true
        // Let startRunSafely's barrier check pass, but block the checkpoint's barrier check
        every { writeBarrier.checkWritesAllowed(any<String>()) } answers {
            val op = firstArg<String>()
            if (op.startsWith("WorkerRunLogger.start:")) return@answers
            throw com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation(op),
                mode = RestoreMaintenanceMode.Mode.NORMAL
            )
        }

        var blockRan = false
        val result = guard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "test_worker",
                requiresNotificationPermission = false,
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { ctx -> blockRan = true; ctx.checkpoint("blocked_op") }

        assertTrue("Write barrier denied should return BlockedRetry for RETRY policy", result is WorkerGuardResult.BlockedRetry)
        assertEquals(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, (result as WorkerGuardResult.BlockedRetry).blockedReasonCode)
        coVerify(exactly = 1) { runHandle.retry(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, any<WorkerCheckpointBlockedException>()) }
    }

    @Test
    fun `checkpoint_write_barrier_denied_skip_policy_returns_success`() = runTest {
        permissionChecker.enabled = true
        every { writeBarrier.checkWritesAllowed(any<String>()) } answers {
            val op = firstArg<String>()
            if (op.startsWith("WorkerRunLogger.start:")) return@answers
            throw com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation(op),
                mode = RestoreMaintenanceMode.Mode.NORMAL
            )
        }

        var blockRan = false
        val result = guard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "test_worker",
                requiresNotificationPermission = false,
                blockedPolicy = BlockedPolicy.SKIP_SUCCESS
            )
        ) { ctx -> blockRan = true; ctx.checkpoint("blocked_op") }

        assertTrue("Write barrier denied should return Skipped for SKIP_SUCCESS policy", result is WorkerGuardResult.Skipped)
        assertEquals(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, (result as WorkerGuardResult.Skipped).reason)
        coVerify(exactly = 1) { runHandle.skipped(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name) }
    }

    @Test
    fun `checkpoint_write_barrier_denied_fail_policy_returns_failure`() = runTest {
        permissionChecker.enabled = true
        every { writeBarrier.checkWritesAllowed(any<String>()) } answers {
            val op = firstArg<String>()
            if (op.startsWith("WorkerRunLogger.start:")) return@answers
            throw com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation(op),
                mode = RestoreMaintenanceMode.Mode.NORMAL
            )
        }

        var blockRan = false
        val result = guard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "test_worker",
                requiresNotificationPermission = false,
                blockedPolicy = BlockedPolicy.FAIL
            )
        ) { ctx -> blockRan = true; ctx.checkpoint("blocked_op") }

        assertTrue("Write barrier denied should return Failed for FAIL policy", result is WorkerGuardResult.Failed)
        assertEquals(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, (result as WorkerGuardResult.Failed).reason)
        coVerify(exactly = 1) { runHandle.failure(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, any<WorkerCheckpointBlockedException>()) }
    }

    @Test
    fun `true_external_cancellation_still_rethrows`() = runTest {
        permissionChecker.enabled = true
        val ex = kotlinx.coroutines.CancellationException("system cancel")

        try {
            guard.runGuarded(request()) { throw ex }
            throw AssertionError("Expected CancellationException to propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals(ex, e)
        }
        coVerify(exactly = 1) { runHandle.cancelled(DiagnosticReasonCode.WORKER_CANCELLED.name) }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
        coVerify(exactly = 0) { runHandle.failure(any(), any()) }
    }
}
