package com.yourname.expensetracker.service.reminder

import android.content.Context
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [DismissReminderActionWorker] — the worker that performs
 * the actual dismiss under [WorkerExecutionGuard], replacing direct
 * receiver-side coordinator calls.
 */
class DismissReminderActionWorkerTest {

    @Test
    fun `dismiss_action_worker_runs_under_guard_and_calls_coordinator`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        val executionGuard = mockk<WorkerExecutionGuard>(relaxed = true)

        every { params.inputData.getLong("deliveryId", -1L) } returns 42L
        coEvery {
            executionGuard.runGuardedWithContext<Unit>(any(), any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = secondArg<suspend (WorkerRunContext) -> Unit>()
            block(mockk<WorkerRunContext>(relaxed = true))
            WorkerGuardResult.Success(Unit)
        }

        val worker = DismissReminderActionWorker(
            context = context, params = params,
            coordinator = coordinator, executionGuard = executionGuard
        )

        val result = worker.doWork()

        assertEquals(WorkResult.success(), result)
        coVerify { coordinator.dismissReminderDelivery(42L) }
    }

    @Test
    fun `dismiss_action_worker_missing_delivery_id_returns_failure`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        val executionGuard = mockk<WorkerExecutionGuard>(relaxed = true)

        every { params.inputData.getLong("deliveryId", -1L) } returns -1L

        val worker = DismissReminderActionWorker(
            context = context, params = params,
            coordinator = coordinator, executionGuard = executionGuard
        )

        val result = worker.doWork()

        assertEquals(WorkResult.failure(), result)
    }
}
