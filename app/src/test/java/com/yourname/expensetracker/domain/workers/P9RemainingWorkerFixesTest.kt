package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural unit tests covering the P9 remaining worker fixes.
 *
 * - NEW-P9-014: Battery constraint for merchant_key_backfill
 * - NEW-P9-015: WorkerRunLogger.Handle idempotency
 * - NEW-P9-011: scheduleAtMidnight minimum delay guard (now covered by WorkerSpecSchedulerTest)
 * - NEW-P9-013: WorkerExecutionGuard checkpoint exception handling
 */
class P9RemainingWorkerFixesTest {

    // ──────────────────────────────────────────────────────────────
    // NEW-P9-014: merchant_key_backfill battery constraint
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `merchant_key_backfill spec has battery not low constraint`() {
        val spec = WorkerSpec.DEFAULTS["merchant_key_backfill"]
        assertNotNull("merchant_key_backfill spec must exist", spec)

        assertNotNull("Constraints must not be null", spec!!.constraints)
        assertTrue(
            "merchant_key_backfill must have requireBatteryNotLow",
            spec.constraints.requiresBatteryNotLow()
        )
    }

    // ──────────────────────────────────────────────────────────────
    // NEW-P9-015: WorkerRunLogger.Handle idempotency
    //
    // These rows run against a relaxed DAO (completeTerminal returns the relaxed
    // default rather than a stubbed affected-row count), which is a different DAO
    // outcome path from WorkerRunLoggerTest's duplicate_terminal_call_is_noop_table
    // (stubbed affected=1). Every terminal kind therefore stays a row here.
    // ──────────────────────────────────────────────────────────────

    private data class IdempotencyRow(
        val name: String,
        val first: suspend (WorkerRunHandle) -> TerminalWriteOutcome,
        val second: suspend (WorkerRunHandle) -> TerminalWriteOutcome
    )

    @Test
    fun `handle terminal calls are idempotent - second call is no-op`() = runTest {
        val rows = listOf(
            IdempotencyRow("success then success", { it.success() }, { it.success() }),
            // Second call is a no-op even with a different terminal status
            IdempotencyRow("retry then success", { it.retry("TRANSIENT_ERROR") }, { it.success() }),
            IdempotencyRow(
                "failure then failure",
                { it.failure("PERMANENT_ERROR") }, { it.failure("IGNORED_DUPLICATE") }
            ),
            IdempotencyRow(
                "cancelled then cancelled",
                { it.cancelled("SYSTEM_CANCEL") }, { it.cancelled("IGNORED_DUPLICATE") }
            ),
            IdempotencyRow("staleAborted then staleAborted", { it.staleAborted() }, { it.staleAborted() }),
            IdempotencyRow(
                "skipped then skipped",
                { it.skipped("WORK_ALREADY_IN_PROGRESS") }, { it.skipped("IGNORED_DUPLICATE") }
            )
        )
        assertEquals("every terminal kind must stay a row", 6, rows.size)

        for (row in rows) {
            val dao = mockk<BackgroundJobRunDao>(relaxed = true) // fresh mock per row
            val timeProvider = mockk<TimeProvider>()
            val sanitizer = mockk<EventMetadataSanitizer>(relaxed = true)
            every { timeProvider.now() } returns 1000L
            every { sanitizer.sanitizeExceptionMessage(any()) } answers { firstArg() }

            val logger = WorkerRunLoggerImpl(dao, sanitizer, timeProvider)
            val handle = logger.start("test_worker")

            // First call: should write to DAO
            row.first(handle)
            try {
                coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            } catch (e: AssertionError) {
                throw AssertionError("[${row.name}] first call must write exactly once: ${e.message}", e)
            }

            // Second call: must be idempotent — no additional DAO write
            row.second(handle)
            try {
                coVerify(exactly = 1) { dao.completeTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            } catch (e: AssertionError) {
                throw AssertionError("[${row.name}] second call must be a no-op: ${e.message}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // NEW-P9-013: WorkerExecutionGuard checkpoint exception handling
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `checkpoint write barrier exception is caught and converted to WorkerCheckpointBlockedException`() = runTest {
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val readBarrier = mockk<com.yourname.expensetracker.data.backup.DatabaseReadBarrier>(relaxed = true)
        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        val workerRunLogger = mockk<WorkerRunLogger>(relaxed = true)
        val privacyGate = mockk<com.yourname.expensetracker.domain.privacy.PrivacyGate>(relaxed = true)
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val diagnosticSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)
        val backgroundJobRunDao = mockk<BackgroundJobRunDao>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val permissionChecker = mockk<NotificationPermissionChecker>(relaxed = true)

        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        coEvery { leaseRegistry.isStopRequested() } returns false

        // Simulate write barrier throwing an unexpected exception at checkpoint
        coEvery { writeBarrier.checkWritesAllowed("test_op") } throws
            RuntimeException("Unexpected barrier failure")

        every { timeProvider.now() } returns 1000L

        val workerTerminalDiagnosticSink = mockk<WorkerTerminalDiagnosticSink>(relaxed = true)

        val guard = WorkerExecutionGuard(
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            restoreMaintenanceMode = restoreMode,
            workerRunLogger = workerRunLogger,
            privacyGate = privacyGate,
            leaseRegistry = leaseRegistry,
            diagnosticSink = diagnosticSink,
            workerTerminalDiagnosticSink = workerTerminalDiagnosticSink,
            backgroundJobRunDao = backgroundJobRunDao,
            notificationPermissionChecker = permissionChecker,
            timeProvider = timeProvider,
            appContext = mockk<android.content.Context>(relaxed = true)
        )

        // The checkpoint should throw WorkerCheckpointBlockedException (fail-safe) rather
        // than propagating the raw RuntimeException.
        var threwBlocked = false
        try {
            guard.checkpoint("test_op")
        } catch (e: WorkerCheckpointBlockedException) {
            threwBlocked = true
        } catch (e: Exception) {
            throw AssertionError("Expected WorkerCheckpointBlockedException but got ${e::class.simpleName}: ${e.message}")
        }

        assertTrue(
            "checkpoint must throw WorkerCheckpointBlockedException on write barrier failure",
            threwBlocked
        )
        coVerify(exactly = 1) { diagnosticSink.recordBlockedOperation(any(), any(), any()) }
    }

    // NEW-P9-011 (scheduleAtMidnight 60 s floor) is covered against production code by
    // WorkerSpecSchedulerTest `near-midnight schedule floors initial delay at 60 seconds`
    // and `midnight worker initial delay equals next-midnight delay from fixed time`.
}
