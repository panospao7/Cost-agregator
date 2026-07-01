package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
// import kotlinx.coroutines.CancellationException // PR12H-1: checkpoint throws WorkerCheckpointBlockedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the DatabaseWriteBarrier + Worker coordination layer.
 *
 * Tests the interaction between:
 * - [DatabaseWriteBarrier] (write gating)
 * - [DatabaseReadBarrier] (read gating during backup/export)
 * - [WorkerExecutionGuard.runGuarded] (worker lifecycle)
 * - [WorkerExecutionGuard.runGuardedWithContext] (worker lifecycle with context)
 * - [WorkerExecutionGuard.checkpoint] (mid-run barrier check)
 * - [WorkerLeaseRegistryImpl] checkpoint (leases check barrier)
 *
 * These tests verify that when a restore is in progress:
 * 1. The barrier blocks writes and the guard propagates the blocked state.
 * 2. Workers calling checkpoint() during restore get CancellationException.
 * 3. The read-only backup path allows read-only workers.
 * 4. The barrier is idempotent (repeated checks give consistent results).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerBarrierIntegrationTest {

    // ── Mocks ────────────────────────────────────────────────────────

    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var readBarrier: DatabaseReadBarrier
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var workerRunLogger: WorkerRunLogger
    private lateinit var privacyGate: PrivacyGate
    private lateinit var leaseRegistry: WorkerLeaseRegistry
    private lateinit var diagnosticSink: MaintenanceSafeDiagnosticSink
    private lateinit var backgroundJobRunDao: BackgroundJobRunDao
    private lateinit var permissionChecker: FakeNotificationPermissionChecker
    private lateinit var timeProvider: TimeProvider
    private lateinit var runHandle: WorkerRunHandle
    private lateinit var lease: WorkerLease

    private lateinit var guard: WorkerExecutionGuard

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
    fun setUp() {
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
        coEvery { workerRunLogger.start(any(), any(), any(), any(), any(), any()) } returns runHandle

        // PR12H-3: explicit stubs for terminal methods returning TerminalWriteOutcome
        coEvery { runHandle.success(any(), any(), any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.skipped(any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.retry(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.failure(any(), any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.cancelled(any()) } returns TerminalWriteOutcome.Durable
        coEvery { runHandle.staleAborted() } returns TerminalWriteOutcome.Durable

        val workerTerminalDiagnosticSink = mockk<WorkerTerminalDiagnosticSink>(relaxed = true)

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

    private fun request(
        workerName: String = "test_worker",
        requiresDatabaseWrite: Boolean = true,
        allowDuringBackupExport: Boolean = false
    ) = WorkerGuardRequest(
        workerName = workerName,
        requiresDatabaseWrite = requiresDatabaseWrite,
        allowDuringBackupExport = allowDuringBackupExport,
        blockedPolicy = BlockedPolicy.RETRY
    )

    // ─────────────────────────────────────────────────────────────────
    // 1. Barrier blocks writes → WorkerExecutionGuard returns BlockedRetry
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `restore mode blocks DB-writing worker and returns BlockedRetry`() = runTest {
        // Simulate restore in progress
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        var blockRan = false
        val result = guard.runGuarded(request("receipt_matching", requiresDatabaseWrite = true)) {
            blockRan = true
        }

        assertFalse("Worker block must not execute during restore", blockRan)
        assertTrue(
            "Result should be BlockedRetry, was: $result",
            result is WorkerGuardResult.BlockedRetry
        )

        val blocked = result as WorkerGuardResult.BlockedRetry
        assertEquals(DiagnosticReasonCode.RESTORE_BLOCKED.name, blocked.blockedReasonCode)

        // Diagnostic sink should have recorded the blocked operation
        coVerify(atLeast = 1) {
            diagnosticSink.recordBlockedOperation(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `backup exporting mode blocks DB-writing worker`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.BACKUP_EXPORTING

        var blockRan = false
        val result = guard.runGuarded(request("data_retention", requiresDatabaseWrite = true)) {
            blockRan = true
        }

        assertFalse("DB-writing worker must be blocked during backup export", blockRan)
        assertTrue(result is WorkerGuardResult.BlockedRetry)
    }

    @Test
    fun `restore staging mode blocks DB-writing worker`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_STAGING

        var blockRan = false
        val result = guard.runGuarded(request("warranty_expiration_check")) {
            blockRan = true
        }

        assertFalse("Worker must be blocked during restore staging", blockRan)
        assertTrue(result is WorkerGuardResult.BlockedRetry)
    }

    @Test
    fun `restore swapping mode blocks DB-writing worker`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_SWAPPING

        var blockRan = false
        val result = guard.runGuarded(request("location_backfill")) {
            blockRan = true
        }

        assertFalse("Worker must be blocked during DB swap", blockRan)
        assertTrue(result is WorkerGuardResult.BlockedRetry)
    }

    @Test
    fun `reset database mode blocks DB-writing worker`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESETTING_DATABASE

        var blockRan = false
        val result = guard.runGuarded(request("merchant_key_backfill")) {
            blockRan = true
        }

        assertFalse("Worker must be blocked during DB reset", blockRan)
        assertTrue(result is WorkerGuardResult.BlockedRetry)
    }

    @Test
    fun `NORMAL mode allows DB-writing worker to execute`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL

        var blockRan = false
        val result = guard.runGuarded(request("bill_reminder_periodic")) {
            blockRan = true
        }

        assertTrue("Worker must run in NORMAL mode", blockRan)
        assertTrue("Result should be Success, was: $result", result is WorkerGuardResult.Success)
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. Barrier blocks mid-run checkpoint
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `checkpoint throws CancellationException when write barrier blocks`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { leaseRegistry.isStopRequested() } returns false

        // Simulate write barrier throwing mid-run
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                DatabaseAccessType.WRITE,
                DatabaseAccessOperation("mid_run_checkpoint"),
                RestoreMaintenanceMode.Mode.RESTORE_PREPARING
            )

        val ex = assertThrows(WorkerCheckpointBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { guard.checkpoint("process_item") }
        }

        assertTrue(
            "Exception message should mention blocked writes: ${ex.message}",
            ex.message?.contains("blocked", ignoreCase = true) == true ||
                ex.message?.contains("Writes blocked", ignoreCase = true) == true
        )
        assertEquals(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, ex.reasonCode)
    }

    @Test
    fun `checkpoint throws CancellationException when stop is requested`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { leaseRegistry.isStopRequested() } returns true

        val ex = assertThrows(WorkerCheckpointBlockedException::class.java) {
            kotlinx.coroutines.runBlocking { guard.checkpoint("save_results") }
        }

        assertTrue(
            "Exception should mention stop: ${ex.message}",
            ex.message?.contains("stop", ignoreCase = true) == true
        )
        assertEquals(DiagnosticReasonCode.WORKER_STOP_REQUESTED.name, ex.reasonCode)
    }

    @Test
    fun `checkpoint passes in normal mode with writes allowed`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { writeBarrier.checkWritesAllowed(any<String>()) } returns Unit
        every { leaseRegistry.isStopRequested() } returns false

        // Should not throw
        guard.checkpoint("normal_operation")
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Read-only workers during backup export
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `read_only worker allowed during backup exporting`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        every { readBarrier.checkReadAllowed(any(), any<DatabaseReadPolicy>()) } returns Unit

        var blockRan = false
        val result = guard.runGuarded(
            request("readonly_exporter", requiresDatabaseWrite = false, allowDuringBackupExport = true)
        ) {
            blockRan = true
        }

        assertTrue("Read-only worker should run during backup export", blockRan)
        assertTrue("Result should be Success, was: $result", result is WorkerGuardResult.Success)
    }

    @Test
    fun `read_only worker blocked during restore (not backup export)`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        var blockRan = false
        val result = guard.runGuarded(
            request("readonly_exporter", requiresDatabaseWrite = false, allowDuringBackupExport = true)
        ) {
            blockRan = true
        }

        assertFalse("Read-only worker should be blocked during restore", blockRan)
        assertTrue("Result should be BlockedRetry, was: $result", result is WorkerGuardResult.BlockedRetry)
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. BlockedPolicy.RETRY vs SKIP_SUCCESS vs FAIL
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `blockedPolicy RETRY returns BlockedRetry`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "test",
                blockedPolicy = BlockedPolicy.RETRY
            )
        ) { }

        assertTrue(result is WorkerGuardResult.BlockedRetry)
    }

    @Test
    fun `blockedPolicy SKIP_SUCCESS returns Skipped`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "test",
                blockedPolicy = BlockedPolicy.SKIP_SUCCESS
            )
        ) { }

        assertTrue(result is WorkerGuardResult.Skipped)
    }

    @Test
    fun `blockedPolicy FAIL returns Failed`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "test",
                blockedPolicy = BlockedPolicy.FAIL
            )
        ) { }

        assertTrue(result is WorkerGuardResult.Failed)
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. Write barrier denies in NORMAL mode after explicit block
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `explicit write barrier deny in NORMAL mode blocks worker via guard`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL

        // Even though mode is NORMAL, an explicit write barrier deny should block
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                DatabaseAccessType.WRITE,
                DatabaseAccessOperation("test_worker"),
                RestoreMaintenanceMode.Mode.NORMAL // unusual but handled
            )

        var blockRan = false
        val result = guard.runGuarded(request("test_worker")) {
            blockRan = true
        }

        assertFalse("Worker block must not execute when barrier denies", blockRan)
        assertTrue(result is WorkerGuardResult.BlockedRetry)
        val blocked = result as WorkerGuardResult.BlockedRetry
        assertEquals(DiagnosticReasonCode.WORKER_WRITE_BARRIER_DENIED.name, blocked.blockedReasonCode)
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. Barrier idempotency — repeated checks produce same result
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `write barrier is idempotent — repeated blocked checks are consistent`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result1 = guard.runGuarded(request("worker1")) { "ok" }
        val result2 = guard.runGuarded(request("worker2")) { "ok" }

        assertTrue("First worker should be blocked", result1 is WorkerGuardResult.BlockedRetry)
        assertTrue("Second worker should also be blocked", result2 is WorkerGuardResult.BlockedRetry)
        assertEquals(
            (result1 as WorkerGuardResult.BlockedRetry).blockedReasonCode,
            (result2 as WorkerGuardResult.BlockedRetry).blockedReasonCode
        )
    }

    @Test
    fun `write barrier allows writes consistently after mode change to NORMAL`() = runTest {
        // First: blocked during restore
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        val blocked = guard.runGuarded(request("worker")) { "ok" }
        assertTrue(blocked is WorkerGuardResult.BlockedRetry)

        // Then: unblocked after restore completes
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val allowed = guard.runGuarded(request("worker")) { "ok" }
        assertTrue(allowed is WorkerGuardResult.Success)
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. WorkerGuardResult.toWorkerResult() mapping
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `BlockedRetry maps to WorkManager Result_retry`() = runTest {
        val blocked = WorkerGuardResult.BlockedRetry("RESTORE_BLOCKED", "RESTORE_BLOCKED")
        val result = blocked.toWorkerResult()
        assertTrue("BlockedRetry should produce retry()", result is androidx.work.ListenableWorker.Result.Retry)
    }

    @Test
    fun `Success maps to WorkManager Result_success`() = runTest {
        val success = WorkerGuardResult.Success("done")
        val result = success.toWorkerResult()
        assertTrue("Success should produce success()", result is androidx.work.ListenableWorker.Result.Success)
    }

    @Test
    fun `Skipped maps to WorkManager Result_success`() = runTest {
        val skipped = WorkerGuardResult.Skipped("disabled")
        val result = skipped.toWorkerResult()
        assertTrue("Skipped should produce success() (non-retry)", result is androidx.work.ListenableWorker.Result.Success)
    }

    @Test
    fun `Retry maps to WorkManager Result_retry`() = runTest {
        val retry = WorkerGuardResult.Retry("Transient error")
        val result = retry.toWorkerResult()
        assertTrue("Retry should produce retry()", result is androidx.work.ListenableWorker.Result.Retry)
    }

    @Test
    fun `Failed maps to WorkManager Result_failure`() = runTest {
        val failed = WorkerGuardResult.Failed("Permanent error")
        val result = failed.toWorkerResult()
        assertTrue("Failed should produce failure()", result is androidx.work.ListenableWorker.Result.Failure)
    }

    // ─────────────────────────────────────────────────────────────────
    // 8. BlockedPolicy FAIL is terminal (not retryable)
    // ─────────────────────────────────────────────────────────────────

    @Test
    fun `FAIL blocked policy terminal result maps to failure not retry`() = runTest {
        every { restoreMaintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = guard.runGuarded(
            WorkerGuardRequest(
                workerName = "critical_worker",
                blockedPolicy = BlockedPolicy.FAIL
            )
        ) { "ok" }

        assertTrue(result is WorkerGuardResult.Failed)
        val wmResult = result.toWorkerResult()
        assertTrue("FAIL policy must produce failure(), not retry()",
            wmResult is androidx.work.ListenableWorker.Result.Failure)
    }
}
