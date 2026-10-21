package com.yourname.expensetracker.service.recurring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
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
import io.mockk.mockk
import io.mockk.slot
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
import kotlin.test.assertFailsWith

/**
 * R21 S3b: [RecurringHorizonWorker] tops up every ACTIVE rule through
 * [RecurringRuleLifecycleCoordinator.topUpHorizon] inside [WorkerExecutionGuard],
 * isolates per-rule failures, and ends the run with a bounded retryable failure.
 *
 * Real top-up semantics (idempotency, inactive/IRREGULAR no-ops, bounded catch-up)
 * are covered at coordinator level against real Room in RecurringHorizonAndLinkR21Test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringHorizonWorkerTest {

    private lateinit var context: Context
    private lateinit var ruleRepository: ManualRecurringExpenseRepository
    private lateinit var ruleCoordinator: RecurringRuleLifecycleCoordinator
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var ctxMock: WorkerRunContext
    private lateinit var timeProvider: FakeTimeProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ruleRepository = mockk()
        ruleCoordinator = mockk()
        executionGuard = mockk()
        ctxMock = mockk(relaxed = true)
        timeProvider = FakeTimeProvider(NOW)

        // Default guard fake: runs the block; maps RetryableWorkerException to Retry
        // exactly like the real guard (CancellationException is never swallowed).
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
            }
        }
    }

    private fun rule(id: Long) = ManualRecurringExpense(
        id = id,
        merchant = "Merchant $id",
        amount = 10.0,
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = NOW
    )

    private fun buildWorker(): RecurringHorizonWorker =
        TestListenableWorkerBuilder<RecurringHorizonWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = RecurringHorizonWorker(
                    appContext,
                    workerParameters,
                    ruleRepository,
                    ruleCoordinator,
                    executionGuard,
                    timeProvider
                )
            })
            .build()

    @Test
    fun `tops up every active rule with the injected clock and succeeds`() = runTest {
        coEvery { ruleRepository.getAll() } returns listOf(rule(1L), rule(2L), rule(3L))
        coEvery { ruleCoordinator.topUpHorizon(any(), any()) } returns 5

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(1L, NOW) }
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(2L, NOW) }
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(3L, NOW) }
        coVerify(exactly = 3) { ctxMock.checkpoint("recurring_horizon:rule") }
    }

    @Test
    fun `rules the coordinator treats as no-op do not fail the run`() = runTest {
        // Inactive / IRREGULAR rules return 0 from the coordinator (no rows inserted).
        coEvery { ruleRepository.getAll() } returns listOf(rule(7L))
        coEvery { ruleCoordinator.topUpHorizon(7L, NOW) } returns 0

        assertEquals(Result.success(), buildWorker().doWork())

        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(7L, NOW) }
    }

    @Test
    fun `one failing rule still processes the others then retries with bounded code`() = runTest {
        coEvery { ruleRepository.getAll() } returns listOf(rule(1L), rule(2L), rule(3L))
        coEvery { ruleCoordinator.topUpHorizon(1L, NOW) } returns 1
        coEvery { ruleCoordinator.topUpHorizon(2L, NOW) } throws IllegalStateException("/private/receipt/1234.56")
        coEvery { ruleCoordinator.topUpHorizon(3L, NOW) } returns 1

        // Fake guard maps RetryableWorkerException -> Retry -> Result.retry().
        assertEquals(Result.retry(), buildWorker().doWork())

        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(1L, NOW) }
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(2L, NOW) }
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(3L, NOW) }
    }

    @Test
    fun `partial failure raises RetryableWorkerException with bounded reason code`() = runTest {
        coEvery { ruleRepository.getAll() } returns listOf(rule(1L), rule(2L))
        coEvery { ruleCoordinator.topUpHorizon(1L, NOW) } throws IllegalStateException("secret text")
        coEvery { ruleCoordinator.topUpHorizon(2L, NOW) } returns 2
        // Pass-through guard so the raw exception surfaces to the test.
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend (WorkerRunContext) -> Any>().invoke(ctxMock))
        }

        val ex = assertFailsWith<RetryableWorkerException> { buildWorker().doWork() }

        assertEquals("RECURRING_HORIZON_PARTIAL_FAILURE", ex.reasonCode)
        assertTrue(ex.message?.contains("secret text") != true)
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(2L, NOW) }
    }

    @Test
    fun `stopped worker breaks the loop before processing further rules`() = runTest {
        coEvery { ruleRepository.getAll() } returns listOf(rule(1L), rule(2L), rule(3L))
        coEvery { ruleCoordinator.topUpHorizon(any(), any()) } returns 0
        val worker = buildWorker()
        // Stop the worker while the first rule is being processed.
        coEvery { ctxMock.checkpoint(any()) } coAnswers {
            worker.stop(WorkInfo.STOP_REASON_UNKNOWN)
        }

        worker.doWork()

        // Rule 1 is already inside the loop body; rules 2 and 3 must never be touched.
        coVerify(exactly = 1) { ruleCoordinator.topUpHorizon(1L, NOW) }
        coVerify(exactly = 0) { ruleCoordinator.topUpHorizon(2L, NOW) }
        coVerify(exactly = 0) { ruleCoordinator.topUpHorizon(3L, NOW) }
    }

    @Test
    fun `cancellation from top-up propagates and is not counted as a rule failure`() = runTest {
        coEvery { ruleRepository.getAll() } returns listOf(rule(1L), rule(2L))
        coEvery { ruleCoordinator.topUpHorizon(1L, NOW) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { buildWorker().doWork() }

        coVerify(exactly = 0) { ruleCoordinator.topUpHorizon(2L, NOW) }
    }

    @Test
    fun `guard request uses recurring_horizon with RETRY blocked policy and DB write`() = runTest {
        coEvery { ruleRepository.getAll() } returns emptyList()
        val requestSlot = slot<WorkerGuardRequest>()
        coEvery {
            executionGuard.runGuardedWithContext(capture(requestSlot), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend (WorkerRunContext) -> Any>().invoke(ctxMock))
        }

        assertEquals(Result.success(), buildWorker().doWork())

        val request = requestSlot.captured
        assertEquals("recurring_horizon", request.workerName)
        assertEquals(BlockedPolicy.RETRY, request.blockedPolicy)
        assertTrue(request.requiresDatabaseWrite)
        assertTrue(!request.requiresNotificationPermission)
        assertTrue(!request.allowDuringBackupExport)
        assertEquals(WorkerSpec.DEFAULTS["recurring_horizon"]?.version, request.specVersion)
        assertNotNull(WorkerSpec.DEFAULTS["recurring_horizon"])
    }

    @Test
    fun `guard blocked by restore or export retries without touching rules`() = runTest {
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } returns WorkerGuardResult.BlockedRetry("blocked", "RESTORE_IN_PROGRESS")

        assertEquals(Result.retry(), buildWorker().doWork())

        coVerify(exactly = 0) { ruleRepository.getAll() }
        coVerify(exactly = 0) { ruleCoordinator.topUpHorizon(any(), any()) }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
