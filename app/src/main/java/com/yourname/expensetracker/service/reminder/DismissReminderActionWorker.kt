package com.yourname.expensetracker.service.reminder

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * One-shot worker that dismisses a reminder delivery under the
 * lease/barrier/run-ledger guard provided by [WorkerExecutionGuard].
 *
 * Enqueued by [DismissReminderReceiver] instead of calling
 * [RecurringLifecycleCoordinator] directly, so every DB-affecting
 * background operation goes through the guard system.
 */
@HiltWorker
class DismissReminderActionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: RecurringLifecycleCoordinator,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deliveryId = inputData.getLong("deliveryId", -1L)
        if (deliveryId == -1L) {
            Timber.w("DismissReminderActionWorker: missing deliveryId")
            return Result.failure()
        }

        val result = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = WORK_NAME,
                requiresDatabaseWrite = true,
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount
            )
        ) { ctx ->
            ctx.checkpoint("reminderAction:dismiss:beforeLoad")
            coordinator.dismissReminderDelivery(deliveryId)
            ctx.checkpoint("reminderAction:dismiss:afterWrite")
        }

        return result.toWorkerResult()
    }

    companion object {
        const val WORK_NAME = "reminder_action_dismiss"
    }
}
