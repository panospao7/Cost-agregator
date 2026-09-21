package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RetentionPurgeFailure
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P9-S4 unit tests for [DataRetentionWorker.doWork].
 *
 * Verifies the worker's guarded execution path: it iterates every registered
 * [RetentionTarget], purges each, and surfaces the total rows purged via
 * [WorkerRunContext.addRowsUpdated] into the run's BackgroundJobRun counters.
 *
 * PR6E additions: tests for transient vs permanent failure classification,
 * partial-failure retry semantics, diagnostic event emission, and cancellation
 * propagation.
 *
 * Mirrors the harness used by the other worker tests (e.g. LocationBackfillWorkerTest):
 * a [TestListenableWorkerBuilder] with a custom [WorkerFactory] injects mocked
 * collaborators, and the execution guard is stubbed to invoke the guarded block
 * with a relaxed [WorkerRunContext] so the counter calls can be verified.
 */
@RunWith(RobolectricTestRunner::class)
class DataRetentionWorkerTest {

    private lateinit var context: Context
    private lateinit var privacySettingsRepository: PrivacySettingsRepository
    private lateinit var appDatabase: AppDatabase
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var retentionRegistry: RetentionRegistry
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var writeBarrier: DatabaseWriteBarrier

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_700_000_000_000L }

    // Relaxed run context so the test can run the guarded block AND coVerify the
    // worker's rowsUpdated counter call.
    private lateinit var ctx: WorkerRunContext

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        privacySettingsRepository = mockk(relaxed = true)
        appDatabase = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        retentionRegistry = mockk()
        diagnosticEventWriter = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        ctx = mockk(relaxed = true)

        // Isolate checkpoint prefs per test (RP-14 14b resume tests read them).
        context.getSharedPreferences(DataRetentionWorker.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        purgeCalls.clear()

        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings()
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: RetryableWorkerException) {
                WorkerGuardResult.Retry(e.message ?: "Retry", e)
            } catch (e: RetentionPurgeFailure) {
                // Mirror the real guard: the worker's typed permanent failure
                // maps to a final failure (RP-14 14b).
                WorkerGuardResult.Failed(e.failureCode, e)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun buildWorker(): DataRetentionWorker =
        TestListenableWorkerBuilder<DataRetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): DataRetentionWorker = DataRetentionWorker(
                    appContext,
                    workerParameters,
                    privacySettingsRepository,
                    appDatabase,
                    timeProvider,
                    executionGuard = executionGuard,
                    retentionRegistry = retentionRegistry,
                    diagnosticEventWriter = diagnosticEventWriter,
                    writeBarrier = writeBarrier
                )
            })
            .build()

    /** Builds a [RetentionTarget] that always purges [rows] rows (records cutoffs). */
    private val purgeCalls = mutableListOf<Pair<String, Long>>()

    private fun target(targetName: String, rows: Int, success: Boolean = true): RetentionTarget =
        object : RetentionTarget {
            override val name = targetName
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                purgeCalls += targetName to cutoffMs
                return RetentionPurgeResult(targetName, rows, success)
            }
        }

    /** Builds a [RetentionTarget] that throws the given exception when purged. */
    private fun throwingTarget(targetName: String, exception: Exception): RetentionTarget =
        object : RetentionTarget {
            override val name = targetName
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = throw exception
        }

    /** Mutable target for multi-run resume tests. */
    private class FlakyTarget(override val name: String) : RetentionTarget {
        var failWith: Exception? = null
        var purgeCount = 0
        override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
            purgeCount++
            failWith?.let { throw it }
            return RetentionPurgeResult(name, 1, true)
        }
    }

    private fun checkpointStore(): RetentionCheckpointStore =
        RetentionCheckpointStore(
            context.getSharedPreferences(DataRetentionWorker.PREFS_NAME, Context.MODE_PRIVATE)
        )

    // ── existing P9-S4 tests ────────────────────────────────────────────────

    @Test
    fun `purges across all targets and surfaces total rowsUpdated`() = runTest {
        // Each registered target purges a known count; rowsUpdated must equal the sum.
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5),
            target("scanned_receipts.rawOcrText", 3),
            target("ai_artifacts", 2),
            target("ai_chat_messages", 4),
            target("email_receipt_sources", 1)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // P9-S4 counts: rowsUpdated == total rows purged/redacted across every target.
        coVerify(exactly = 1) { ctx.addRowsUpdated(5 + 3 + 2 + 4 + 1) }
    }

    @Test
    fun `no purgeable rows still succeeds with zero rowsUpdated`() = runTest {
        // All targets are no-ops (nothing older than the retention cutoff).
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 0),
            target("scanned_receipts.rawOcrText", 0),
            target("ai_artifacts", 0),
            target("ai_chat_messages", 0),
            target("email_receipt_sources", 0)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // P9-S4 zero-count: an empty purge run surfaces rowsUpdated == 0.
        coVerify(exactly = 1) { ctx.addRowsUpdated(0) }
    }

    // ── PR6E: Partial-failure semantics tests ────────────────────────────────

    @Test
    fun `all_targets_success_returns_success`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 2),
            target("scanned_receipts.rawOcrText", 1)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { ctx.addRowsUpdated(3) }
    }

    @Test
    fun `one_target_transient_failure_continues_other_targets_then_retries`() = runTest {
        // target_b throws IOException (transient); other targets succeed
        val transientException = java.io.IOException("disk full")
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_a", 5),
            throwingTarget("target_b", transientException),
            target("target_c", 3)
        )

        val result = buildWorker().doWork()

        // The transient failure should trigger retry
        assertEquals(Result.retry(), result)
        // Both succeeding targets should still have been processed
        coVerify(exactly = 1) { ctx.addRowsUpdated(any()) }
    }

    @Test
    fun `one_target_permanent_failure_returns_final_failure_never_success`() = runTest {
        // RP-14 14b: a permanent target failure is a final worker failure —
        // never success. All targets are still attempted and counters reported.
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_a", 5),
            throwingTarget("target_b", IllegalArgumentException("invalid argument")),
            target("target_c", 3)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 1) { ctx.addRowsUpdated(8) }
        coVerify(exactly = 1) { ctx.setFailedTargetCount(1) }
    }

    // ── RP-14 14b: typed / class-based retry classification ──────────────────

    @Test
    fun `exception message substrings are never used for retry classification`() = runTest {
        // Previously "database is locked" in the message meant transient.
        // RP-14 14b: classification uses exception CLASSES only, so a plain
        // RuntimeException with that message is PERMANENT → failure, not retry.
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", RuntimeException("database is locked (SQLITE_BUSY)"))
        )

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
    }

    @Test
    fun `sqlite exception is transient and retries`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", android.database.sqlite.SQLiteException("interrupted"))
        )

        assertEquals(Result.retry(), buildWorker().doWork())
    }

    @Test
    fun `typed transient purge failure retries and typed permanent fails`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", RetentionPurgeFailure("CODE_A", transient = true))
        )
        assertEquals(Result.retry(), buildWorker().doWork())

        purgeCalls.clear()
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_b", RetentionPurgeFailure("CODE_B", transient = false))
        )
        assertEquals(Result.failure(), buildWorker().doWork())
    }

    @Test
    fun `permanent_failure_keeps_completed_checkpoints_and_failed_record_persists`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_ok", 2),
            throwingTarget("target_bad", IllegalStateException("permanent"))
        )

        assertEquals(Result.failure(), buildWorker().doWork())

        val store = checkpointStore()
        // Checkpoints are NOT cleared after a partial run: completed record kept...
        val completed = store.load("target_ok")
        assertNotNull(completed)
        assertEquals(RetentionCheckpointState.COMPLETED, completed!!.state)
        // ...and the failed record persists so the next run re-attempts it.
        val failed = store.load("target_bad")
        assertNotNull(failed)
        assertEquals(RetentionCheckpointState.FAILED, failed!!.state)
        assertFalse(failed.isTransient)
    }

    // ── RP-14 14b: stage-1/stage-2 partial case + resume ─────────────────────

    @Test
    fun `stage2 transient failure after stage1 success retries and resume skips stage1`() = runTest {
        val snapshots = FlakyTarget("10_transaction_events.snapshots")
        val rows = FlakyTarget("20_transaction_events.rows").apply {
            failWith = java.io.IOException("transient stage-2 failure")
        }
        every { retentionRegistry.allTargets() } returns setOf(snapshots, rows)

        // Run 1: stage 1 succeeds, stage 2 fails transiently → retry.
        assertEquals(Result.retry(), buildWorker().doWork())

        val store = checkpointStore()
        assertEquals(RetentionCheckpointState.COMPLETED, store.load("10_transaction_events.snapshots")?.state)
        val failedRecord = store.load("20_transaction_events.rows")
        assertEquals(RetentionCheckpointState.FAILED, failedRecord?.state)
        assertTrue(failedRecord?.isTransient == true)

        // Run 2 (same durable prefs): stage-1 target is skipped, stage 2 resumes.
        rows.failWith = null
        assertEquals(Result.success(), buildWorker().doWork())

        assertEquals("completed stage-1 target must not be re-purged", 1, snapshots.purgeCount)
        assertEquals("failed stage-2 target must be re-attempted", 2, rows.purgeCount)
        // All targets succeeded → checkpoints cleared.
        assertNull(checkpointStore().load("10_transaction_events.snapshots"))
        assertNull(checkpointStore().load("20_transaction_events.rows"))
    }

    @Test
    fun `cancellation propagates and does not clear checkpoints`() = runTest {
        // Names sort so the successful target completes BEFORE the cancelling one.
        every { retentionRegistry.allTargets() } returns setOf(
            target("aaa_completed_target", 2),
            throwingTarget("zzz_cancelling_target", CancellationException("worker stopped"))
        )

        try {
            buildWorker().doWork()
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("worker stopped", e.message)
        }

        // Cancellation never clears checkpoints: the completed record survives.
        val completed = checkpointStore().load("aaa_completed_target")
        assertNotNull(completed)
        assertEquals(RetentionCheckpointState.COMPLETED, completed!!.state)
    }

    // ── RP-14 14b: audit-write failure is best-effort, never rolls back ──────

    @Test
    fun `audit_write_failure_does_not_rollback_purge_and_defers_audit`() = runTest {
        val auditDao = mockk<PrivacyAuditDao>(relaxed = true)
        every { appDatabase.privacyAuditDao() } returns auditDao
        coEvery { auditDao.insert(any()) } throws RuntimeException("boom")

        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5)
        )

        // The purge itself succeeded; the audit failure only adds ctx errors.
        assertEquals(Result.success(), buildWorker().doWork())
        coVerify(exactly = 1) { auditDao.insert(any()) }

        // Record stays COMPLETED with auditEmitted=false → NOT cleared, so the
        // next run re-emits the audit exactly once (never re-purges).
        val record = checkpointStore().load("raw_notifications")
        assertNotNull(record)
        assertEquals(RetentionCheckpointState.COMPLETED, record!!.state)
        assertFalse(record.auditEmitted)
    }

    // ── RP-14: D14 cutoff routing for the new targets ─────────────────────────

    @Test
    fun `new targets receive their D14 named-constant cutoffs`() = runTest {
        val dayMs = 24L * 60 * 60 * 1000
        every { retentionRegistry.allTargets() } returns setOf(
            target("10_transaction_events.snapshots", 0),
            target("20_transaction_events.rows", 0),
            target("30_operation_runs", 0),
            target("40_receipt_events", 0),
            target("50_privacy_audit_events", 0)
        )

        assertEquals(Result.success(), buildWorker().doWork())

        val now = timeProvider.now()
        val cutoffs = purgeCalls.toMap()
        assertEquals(now - 30 * dayMs, cutoffs["10_transaction_events.snapshots"])
        assertEquals(now - 365 * dayMs, cutoffs["20_transaction_events.rows"])
        assertEquals(now - 90 * dayMs, cutoffs["30_operation_runs"])
        assertEquals(now - 90 * dayMs, cutoffs["40_receipt_events"])
        assertEquals(now - 180 * dayMs, cutoffs["50_privacy_audit_events"])
    }

    @Test
    fun `target_failure_records_sanitized_diagnostic`() = runTest {
        // One target throws a permanent failure → diagnostic event should be emitted
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_a", 5),
            throwingTarget("target_b", IllegalArgumentException("invalid argument")),
            target("target_c", 3)
        )

        buildWorker().doWork()

        coVerify(atLeast = 1) {
            diagnosticEventWriter.emit(match { event ->
                event.pipeline == AppPipeline.PRIVACY &&
                event.stage == "retention_purge" &&
                event.outcome == EventOutcome.FAILED_FINAL &&
                event.entityType == "RetentionTarget"
            })
        }
    }

    @Test
    fun `cancellation_during_target_rethrows`() = runTest {
        // target_b throws CancellationException; must propagate immediately
        val cancelException = CancellationException("worker stopped")
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_a", 5),
            throwingTarget("target_b", cancelException),
            target("target_c", 3)
        )

        try {
            buildWorker().doWork()
            fail("Expected CancellationException to be thrown")
        } catch (e: CancellationException) {
            assertEquals("worker stopped", e.message)
        }
    }

    // ── PR12K-1: DataRetention Privacy Cleanup Semantics ──────────────

    @Test
    fun `data_retention_runs_when_raw_notification_retention_disabled`() = runTest {
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(rawNotificationRetentionDays = 0)
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `data_retention_purges_raw_notification_payload_when_disabled`() = runTest {
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(rawNotificationRetentionDays = 0)
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { ctx.addRowsUpdated(5) }
    }

    @Test
    fun `data_retention_runs_when_raw_ocr_retention_disabled`() = runTest {
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(rawOcrRetentionDays = 0)
        every { retentionRegistry.allTargets() } returns setOf(
            target("scanned_receipts.rawOcrText", 3)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `data_retention_purges_raw_ocr_payload_when_disabled`() = runTest {
        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings(rawOcrRetentionDays = 0)
        every { retentionRegistry.allTargets() } returns setOf(
            target("scanned_receipts.rawOcrText", 3)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { ctx.addRowsUpdated(3) }
    }

    @Test
    fun `data_retention_is_not_gated_by_raw_retention_capabilities`() = runTest {
        every { retentionRegistry.allTargets() } returns emptySet()

        val result = buildWorker().doWork()

        // Worker must succeed (not be blocked by privacy gate)
        assertEquals(Result.success(), result)
        coVerify {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        }
    }

    // ── PR12K-2: DataRetention Sanitized Diagnostics ─────────────────

    @Test
    fun `retention_exception_message_path_not_persisted`() = runTest {
        // A throwing target with sensitive path info in message
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", IllegalArgumentException("C:\\Users\\sensitive\\path"))
        )

        buildWorker().doWork()

        // Diagnostic metadata must not contain the raw exception message
        coVerify(atLeast = 1) {
            diagnosticEventWriter.emit(match { event ->
                event.stage == "retention_purge" &&
                !event.metadata.toJson().contains("sensitive") &&
                !event.metadata.toJson().contains("\"error\":")
            })
        }
    }

    @Test
    fun `retention_diagnostic_uses_failure_code`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", IllegalArgumentException("test"))
        )

        buildWorker().doWork()

        coVerify(atLeast = 1) {
            diagnosticEventWriter.emit(match { event ->
                event.stage == "retention_purge" &&
                event.metadata.toJson().contains(DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name)
            })
        }
    }

    @Test
    fun `retention_diagnostic_uses_error_class`() = runTest {
        every { retentionRegistry.allTargets() } returns setOf(
            throwingTarget("target_a", IllegalArgumentException("test"))
        )

        buildWorker().doWork()

        coVerify(atLeast = 1) {
            diagnosticEventWriter.emit(match { event ->
                event.stage == "retention_purge" &&
                event.metadata.toJson().contains("IllegalArgumentException")
            })
        }
    }

    // ── RP-02 U-004: per-insert barrier ownership for audit writes ───

    @Test
    fun `audit insert is barrier-checked immediately before the write`() = runTest {
        val auditDao = mockk<PrivacyAuditDao>(relaxed = true)
        every { appDatabase.privacyAuditDao() } returns auditDao
        coEvery { auditDao.insert(any()) } returns 1L
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5)
        )

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("privacy.retention.audit")
            auditDao.insert(any())
        }
    }

    @Test
    fun `blocked audit write prevents the insert and surfaces typed barrier exception`() = runTest {
        val auditDao = mockk<PrivacyAuditDao>(relaxed = true)
        every { appDatabase.privacyAuditDao() } returns auditDao
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
                accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
                operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation("privacy.retention.audit"),
                mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_STAGING
            )
        every { retentionRegistry.allTargets() } returns setOf(
            target("raw_notifications", 5)
        )

        try {
            buildWorker().doWork()
            fail("Expected DatabaseAccessBlockedException")
        } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            // In production WorkerExecutionGuard maps this per the worker's
            // blockedPolicy; the invariant under test is that no audit row is
            // written during restore/maintenance.
        }

        coVerify(exactly = 0) { auditDao.insert(any()) }
    }

    // ── PR12M-3: No legacy raw-purge helpers ──────────────────────────

    @Test
    fun `data_retention_worker_has_no_legacy_raw_purge_helpers`() {
        val sourceFile = File("src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt")
        assertTrue("Source file must exist", sourceFile.exists())
        val content = sourceFile.readText()
        assertFalse(
            "purgeRawNotifications must be removed — legacy unsafe helper",
            content.contains("purgeRawNotifications")
        )
        assertFalse(
            "purgeRawOcrText must be removed — legacy unsafe helper",
            content.contains("purgeRawOcrText")
        )
    }
}
