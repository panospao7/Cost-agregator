package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
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
        backgroundJobRunDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        runHandle = mockk(relaxed = true)
        lease = mockk(relaxed = true)
        permissionChecker = FakeNotificationPermissionChecker(enabled = true)

        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.acquire(any()) } returns lease
        coEvery { workerRunLogger.start(any()) } returns runHandle

        guard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMaintenanceMode,
            workerRunLogger = workerRunLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
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
            DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `runGuardedWithContext permission denied is skipped with NOTIFICATION_PERMISSION_DENIED reason`() = runTest {
        permissionChecker.enabled = false
        var blockRan = false

        val result = guard.runGuardedWithContext(request()) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Skipped)
        assertEquals(
            DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name,
            (result as WorkerGuardResult.Skipped).reason
        )
        assertFalse("block must not run when permission denied", blockRan)
        coVerify(exactly = 1) {
            runHandle.skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)
        }
    }

    @Test
    fun `permission granted runs block`() = runTest {
        permissionChecker.enabled = true
        var blockRan = false

        val result = guard.runGuarded(request()) { blockRan = true }

        assertTrue(result is WorkerGuardResult.Success)
        assertTrue("block must run when permission granted", blockRan)
        coVerify(exactly = 1) { runHandle.success() }
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
        assertEquals("some permanent business error", (result as WorkerGuardResult.Failed).reason)
        coVerify(exactly = 1) { runHandle.failure("some permanent business error", ex) }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
    }

    @Test
    fun `transient message still maps to Retry`() = runTest {
        permissionChecker.enabled = true
        val ex = RuntimeException("operation timeout while writing")

        val result = guard.runGuarded(request()) { throw ex }

        // classifyTransient keyword list is unchanged: a "timeout" message still retries.
        assertTrue(result is WorkerGuardResult.Retry)
        coVerify(exactly = 1) { runHandle.retry("operation timeout while writing", ex) }
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
        coVerify(exactly = 1) { runHandle.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
        coVerify(exactly = 0) { runHandle.retry(any(), any()) }
        coVerify(exactly = 0) { runHandle.failure(any(), any()) }
    }
}
