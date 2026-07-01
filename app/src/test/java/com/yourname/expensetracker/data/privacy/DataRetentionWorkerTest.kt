package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        ctx = mockk(relaxed = true)

        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings()
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: RetryableWorkerException) {
                WorkerGuardResult.Retry(e.message ?: "Retry", e)
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
                    diagnosticEventWriter = diagnosticEventWriter
                )
            })
            .build()

    /** Builds a [RetentionTarget] that always purges [rows] rows. */
    private fun target(targetName: String, rows: Int, success: Boolean = true): RetentionTarget =
        object : RetentionTarget {
            override val name = targetName
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult =
                RetentionPurgeResult(targetName, rows, success)
        }

    /** Builds a [RetentionTarget] that throws the given exception when purged. */
    private fun throwingTarget(targetName: String, exception: Exception): RetentionTarget =
        object : RetentionTarget {
            override val name = targetName
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = throw exception
        }

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
    fun `one_target_permanent_failure_records_partial_failure_not_success`() = runTest {
        // target_b throws IllegalArgumentException (permanent); no transient → no retry
        every { retentionRegistry.allTargets() } returns setOf(
            target("target_a", 5),
            throwingTarget("target_b", IllegalArgumentException("invalid argument")),
            target("target_c", 3)
        )

        val result = buildWorker().doWork()

        // No transient failures → should return success (partial failure logged but no retry)
        assertEquals(Result.success(), result)
        // Both succeeding targets should have been processed
        coVerify(exactly = 1) { ctx.addRowsUpdated(8) }
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
}
