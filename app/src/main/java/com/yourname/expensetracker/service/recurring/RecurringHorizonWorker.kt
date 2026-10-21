package com.yourname.expensetracker.service.recurring

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * R21 S3b: daily worker that keeps every ACTIVE recurring rule's occurrence horizon
 * topped up (today + 12 months) so rules never run dry between app launches.
 *
 * All mutation goes through [RecurringRuleLifecycleCoordinator.topUpHorizon]
 * (single legal recurring-rule write path); this worker never touches a DAO.
 * Inactive and IRREGULAR rules are no-ops inside the coordinator. A failure on one
 * rule never prevents the remaining rules from being processed; if any rule failed
 * the run ends with a bounded [RetryableWorkerException] so WorkManager retries
 * (top-up is idempotent). This worker is local-data only and is not privacy-gated.
 */
@HiltWorker
class RecurringHorizonWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val ruleRepository: ManualRecurringExpenseRepository,
    private val ruleCoordinator: RecurringRuleLifecycleCoordinator,
    private val executionGuard: WorkerExecutionGuard,
    private val timeProvider: TimeProvider
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = WORK_NAME,
                requiresNotificationPermission = false,
                requiresDatabaseWrite = true,
                allowDuringBackupExport = false,
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                specVersion = WorkerSpec.DEFAULTS[WORK_NAME]?.version
            )
        ) { ctx ->
            // getAll() is the active-only read contract of the rule repository.
            val rules = ruleRepository.getAll()
            var failedRules = 0

            for (rule in rules) {
                if (isStopped) break

                ctx.addRowsScanned()
                ctx.checkpoint("recurring_horizon:rule")

                try {
                    val inserted = ruleCoordinator.topUpHorizon(rule.id, timeProvider.now())
                    if (inserted > 0) ctx.addRowsUpdated(inserted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Sibling catch above already rethrows; the explicit idiom is kept
                    // because SourceScanningArchitectureGuardTest requires it per worker file.
                    if (e is CancellationException) throw e
                    failedRules++
                    ctx.addRowsSkipped()
                    Log.w(TAG, "RECURRING_HORIZON_RULE_FAILED class=${e::class.java.simpleName}")
                }
            }

            if (failedRules > 0) {
                throw RetryableWorkerException("RECURRING_HORIZON_PARTIAL_FAILURE")
            }
        }

        return guardResult.toWorkerResult()
    }

    companion object {
        const val TAG = "RecurringHorizonWorker"
        const val WORK_NAME = "recurring_horizon"

        /**
         * Schedules the periodic horizon worker from [WorkerSpec.DEFAULTS]
         * (24h, [androidx.work.ExistingPeriodicWorkPolicy.KEEP]).
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, RecurringHorizonWorker::class.java)
        }
    }
}
