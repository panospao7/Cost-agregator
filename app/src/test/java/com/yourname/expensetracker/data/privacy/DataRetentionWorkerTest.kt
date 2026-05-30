package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
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

/**
 * P9-S4 unit tests for [DataRetentionWorker.doWork].
 *
 * Verifies the worker's guarded execution path: it iterates every registered
 * [RetentionTarget], purges each, and surfaces the total rows purged via
 * [WorkerRunContext.addRowsUpdated] into the run's BackgroundJobRun counters.
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
        ctx = mockk(relaxed = true)

        coEvery { privacySettingsRepository.getSettings() } returns PrivacySettings()
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            WorkerGuardResult.Success(block.invoke(ctx))
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
                    retentionRegistry = retentionRegistry
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
}
