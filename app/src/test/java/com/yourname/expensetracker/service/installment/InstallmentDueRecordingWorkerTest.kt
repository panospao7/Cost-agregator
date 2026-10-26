package com.yourname.expensetracker.service.installment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentDueRunReport
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentReconcileReport
import com.yourname.expensetracker.domain.installment.model.InstallmentDeferralReason
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import com.yourname.expensetracker.domain.workers.WorkerSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFailsWith

/**
 * Loan-installments S7a-2: [InstallmentDueRecordingWorker] makes exactly one call,
 * [InstallmentPlanLifecycleCoordinator.recordDueEntries], inside [WorkerExecutionGuard], dispatches
 * the returned post-commit batches and maps the outcome to a bounded Result. Recording semantics
 * (INV-12/13/14/34, the latch, the name check) are covered at coordinator level in
 * InstallmentRecordDueEntriesTest against real Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentDueRecordingWorkerTest {

    private lateinit var context: Context
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator
    private lateinit var runner: PostCommitActionRunner
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var ctxMock: WorkerRunContext
    private lateinit var timeProvider: FakeTimeProvider

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: Long = Instant.ofEpochMilli(NOW).atZone(zone).toLocalDate().toEpochDay()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        coordinator = mockk()
        runner = mockk()
        executionGuard = mockk()
        ctxMock = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(NOW)

        coEvery { runner.run(any()) } returns SideEffectBatchResult("c", 1, 0, 0, 0, 0, emptyList())

        // Default guard fake mirrors the real guard: runs the block, maps RetryableWorkerException
        // to Retry and any other exception to Failed; CancellationException is never swallowed.
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctxMock))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryableWorkerException) {
                WorkerGuardResult.Retry(e.reasonCode, e)
            } catch (e: Exception) {
                WorkerGuardResult.Failed("WORKER_UNHANDLED_EXCEPTION", e)
            }
        }
    }

    private fun batch(correlationId: String): PostCommitActionBatch =
        PostCommitActionBatch(correlationId, listOf(mockk<PostCommitAction>(relaxed = true)))

    private fun done(
        report: InstallmentDueRunReport = InstallmentDueRunReport(recorded = 1),
        batches: List<PostCommitActionBatch> = emptyList()
    ): InstallmentOpResult<InstallmentDueRunReport> = InstallmentOpResult.Done(report, batches)

    private fun buildWorker(): InstallmentDueRecordingWorker =
        TestListenableWorkerBuilder<InstallmentDueRecordingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = InstallmentDueRecordingWorker(
                    appContext,
                    workerParameters,
                    coordinator,
                    runner,
                    executionGuard,
                    timeProvider
                )
            })
            .build()

    @Test
    fun `guard denied retries without calling the coordinator`() = runTest {
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } returns WorkerGuardResult.BlockedRetry("blocked", "RESTORE_IN_PROGRESS")

        assertEquals(Result.retry(), buildWorker().doWork())

        coVerify(exactly = 0) { coordinator.recordDueEntries(any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `success makes exactly one coordinator call with today and zone and dispatches the batches in order`() = runTest {
        val first = batch("b1")
        val second = batch("b2")
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            done(InstallmentDueRunReport(recorded = 2), listOf(first, PostCommitActionBatch.empty("empty"), second))

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { coordinator.recordDueEntries(any(), any()) }
        coVerify(exactly = 1) { coordinator.recordDueEntries(today, zone) }
        coVerifyOrder {
            runner.run(first)
            runner.run(second)
        }
        coVerify(exactly = 2) { runner.run(any()) }
        coVerify(exactly = 1) { ctxMock.checkpoint("installment_due:run") }
    }

    @Test
    fun `deferred and refused entries are normal outcomes and succeed`() = runTest {
        val report = InstallmentDueRunReport(
            deferred = mapOf(InstallmentDeferralReason.entries.first() to 2)
        )
        coEvery { coordinator.recordDueEntries(any(), any()) } returns done(report)

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { coordinator.recordDueEntries(any(), any()) }
    }

    @Test
    fun `stoppedByBarrier dispatches the batches then retries`() = runTest {
        val committed = batch("committed")
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            done(InstallmentDueRunReport(recorded = 1, stoppedByBarrier = true), listOf(committed))

        assertEquals(Result.retry(), buildWorker().doWork())

        coVerify(exactly = 1) { runner.run(committed) }
    }

    @Test
    fun `stoppedByBarrier raises RetryableWorkerException with bounded reason code`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            done(InstallmentDueRunReport(stoppedByBarrier = true))
        passThroughGuard()

        val ex = assertFailsWith<RetryableWorkerException> { buildWorker().doWork() }

        assertEquals("INSTALLMENT_DUE_STOPPED_BY_BARRIER", ex.reasonCode)
    }

    @Test
    fun `closed barrier at reconcile start retries without dispatching`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } throws DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("installment_due_recording"),
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        )

        assertEquals(Result.retry(), buildWorker().doWork())

        coVerify(exactly = 1) { coordinator.recordDueEntries(any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `closed barrier raises RetryableWorkerException with bounded reason code`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } throws DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("installment_due_recording"),
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
        )
        passThroughGuard()

        val ex = assertFailsWith<RetryableWorkerException> { buildWorker().doWork() }

        assertEquals("INSTALLMENT_DUE_BARRIER_CLOSED", ex.reasonCode)
    }

    @Test
    fun `cancellation from the coordinator propagates`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `cancellation while dispatching a batch propagates`() = runTest {
        val first = batch("b1")
        val second = batch("b2")
        coEvery { coordinator.recordDueEntries(any(), any()) } returns done(batches = listOf(first, second))
        coEvery { runner.run(first) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 0) { runner.run(second) }
    }

    @Test
    fun `a failing batch is contained and the remaining batches are still dispatched`() = runTest {
        val first = batch("b1")
        val second = batch("b2")
        coEvery { coordinator.recordDueEntries(any(), any()) } returns done(batches = listOf(first, second))
        coEvery { runner.run(first) } throws IllegalStateException("/private/receipt/1234.56")

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { runner.run(first) }
        coVerify(exactly = 1) { runner.run(second) }
        // The failure is logged by class name only: no message text, path or amount.
        val logged = ShadowLog.getLogsForTag(InstallmentDueRecordingWorker.TAG).map { it.msg.orEmpty() }
        assertTrue(logged.isNotEmpty())
        assertTrue(logged.none { it.contains("/private") || it.contains("1234.56") })
    }

    @Test
    fun `reconcile failures with nothing due are recorded as errors and the Result is unchanged`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            done(
                InstallmentDueRunReport(
                    reconcile = InstallmentReconcileReport(plansFailed = 2, orphanFlagsFailed = 1)
                )
            )

        assertEquals(Result.success(), buildWorker().doWork())

        verify(exactly = 1) { ctxMock.addErrors(3) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `per-entry failures dispatch the batches then retry with bounded reason code`() = runTest {
        val committed = batch("committed")
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            done(
                InstallmentDueRunReport(recorded = 1, failed = mapOf(InstallmentOpFailure.CONFLICT to 1)),
                listOf(committed)
            )
        passThroughGuard()

        val ex = assertFailsWith<RetryableWorkerException> { buildWorker().doWork() }

        assertEquals("INSTALLMENT_DUE_PARTIAL_FAILURE", ex.reasonCode)
        coVerify(exactly = 1) { runner.run(committed) }
    }

    @Test
    fun `a result that is not Done maps to a bounded failure`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } returns
            InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT)

        assertEquals(Result.failure(), buildWorker().doWork())

        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `run-level read failure is left to the guard and maps to failure`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } throws
            IllegalStateException("/private/receipt/1234.56 plan name")

        assertEquals(Result.failure(), buildWorker().doWork())

        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `guard request uses installment_due_recording with RETRY blocked policy and DB write`() = runTest {
        coEvery { coordinator.recordDueEntries(any(), any()) } returns done(InstallmentDueRunReport())
        val requestSlot = slot<WorkerGuardRequest>()
        coEvery {
            executionGuard.runGuardedWithContext(capture(requestSlot), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend (WorkerRunContext) -> Any>().invoke(ctxMock))
        }

        assertEquals(Result.success(), buildWorker().doWork())

        val request = requestSlot.captured
        assertEquals("installment_due_recording", request.workerName)
        assertEquals(BlockedPolicy.RETRY, request.blockedPolicy)
        assertTrue(request.requiresDatabaseWrite)
        assertTrue(!request.requiresNotificationPermission)
        assertTrue(!request.allowDuringBackupExport)
        assertEquals(WorkerSpec.DEFAULTS["installment_due_recording"]?.version, request.specVersion)
        assertNotNull(WorkerSpec.DEFAULTS["installment_due_recording"])
    }

    /** Pass-through guard so the raw worker-block exception surfaces to the test. */
    private fun passThroughGuard() {
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend (WorkerRunContext) -> Any>().invoke(ctxMock))
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
