package com.yourname.expensetracker.service.installment

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentDueRunReport
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.ZoneId

/**
 * Loan-installments S7a-2: daily worker that records the due entries of every ACTIVE AUTO_RECORD
 * installment plan (domain spec 5.2; INV-12, INV-13, INV-14, INV-31, INV-34).
 *
 * All work is one call to [InstallmentPlanLifecycleCoordinator.recordDueEntries]; the coordinator
 * runs `reconcile()` first (D-18), handles each entry in its own transaction and returns the
 * post-commit batches of everything it committed. This worker never touches a data-access object,
 * a repository or the transaction lifecycle coordinator directly, and it has no startup hook and
 * no persisted marker (D-19). It is local-data only and is not privacy-gated.
 *
 * Result mapping (every reason code is a bounded constant, no name, amount, date or id):
 * - guard denied, restore or maintenance mode: the guard's own mapping (retry), no coordinator call;
 * - `Done`: the post-commit batches are dispatched first through [InstallmentPostCommitDispatcher]
 *   (the single owner: a failing batch is logged by class name and does not stop the rest);
 *   - `stoppedByBarrier`: [RetryableWorkerException] `INSTALLMENT_DUE_STOPPED_BY_BARRIER` (retry);
 *   - any per-entry failure count: [RetryableWorkerException] `INSTALLMENT_DUE_PARTIAL_FAILURE`
 *     (retry; recording is idempotent, the latch and the compare-and-set make a re-run safe);
 *   - otherwise success (deferred and refused entries are normal outcomes, not failures);
 * - [DatabaseAccessBlockedException] before the first entry (closed barrier at reconcile start):
 *   [RetryableWorkerException] `INSTALLMENT_DUE_BARRIER_CLOSED` (retry);
 * - any other result variant: bounded failure `INSTALLMENT_DUE_NOT_DONE`;
 * - any other exception from the run-level reads: left to [WorkerExecutionGuard] (transient retry
 *   or bounded failure), exactly like the recurring horizon worker;
 * - cancellation is never caught here and always propagates.
 */
@HiltWorker
class InstallmentDueRecordingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val installmentCoordinator: InstallmentPlanLifecycleCoordinator,
    private val postCommitDispatcher: InstallmentPostCommitDispatcher,
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
            ctx.checkpoint("installment_due:run")
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate().toEpochDay()
            val result = try {
                installmentCoordinator.recordDueEntries(today, zone)
            } catch (e: DatabaseAccessBlockedException) {
                // A closed write barrier at reconcile start (restore, reset, maintenance, backup).
                throw RetryableWorkerException(REASON_BARRIER_CLOSED)
            }
            finishRun(ctx, result)
        }

        return guardResult.toWorkerResult()
    }

    private suspend fun finishRun(
        ctx: WorkerRunContext,
        result: InstallmentOpResult<InstallmentDueRunReport>
    ) {
        if (result !is InstallmentOpResult.Done) {
            Log.w(TAG, "INSTALLMENT_DUE_RUN_NOT_DONE class=${result.javaClass.simpleName}")
            throw IllegalStateException(REASON_NOT_DONE)
        }
        // Dispatch first: the batches belong to transactions that already committed.
        postCommitDispatcher.dispatch(result.postCommit)

        val report = result.value
        val skipped = report.deferred.values.sum() + report.refused.values.sum()
        val failed = report.failed.values.sum()
        val reconcile = report.reconcile
        val repaired = reconcile.r1Reflagged + reconcile.r2Reopened + reconcile.r3Reflagged +
            reconcile.r4Unlinked + reconcile.r5Detached + reconcile.r6Completed +
            reconcile.orphanFlagsCleared
        val reconcileFailed = reconcile.plansFailed + reconcile.orphanFlagsFailed
        val updated = report.recorded + repaired
        if (updated > 0) ctx.addRowsUpdated(updated)
        if (skipped > 0) ctx.addRowsSkipped(skipped)
        // Reconcile failures are counted for diagnostics only; the Result mapping below is unchanged.
        if (failed + reconcileFailed > 0) ctx.addErrors(failed + reconcileFailed)

        if (report.stoppedByBarrier) {
            throw RetryableWorkerException(REASON_STOPPED_BY_BARRIER)
        }
        if (failed > 0) {
            throw RetryableWorkerException(REASON_PARTIAL_FAILURE)
        }
    }

    companion object {
        const val TAG = "InstallmentDueRecordingWorker"
        const val WORK_NAME = "installment_due_recording"

        const val REASON_BARRIER_CLOSED = "INSTALLMENT_DUE_BARRIER_CLOSED"
        const val REASON_STOPPED_BY_BARRIER = "INSTALLMENT_DUE_STOPPED_BY_BARRIER"
        const val REASON_PARTIAL_FAILURE = "INSTALLMENT_DUE_PARTIAL_FAILURE"
        const val REASON_NOT_DONE = "INSTALLMENT_DUE_NOT_DONE"

        /**
         * Schedules the periodic worker from [WorkerSpec.DEFAULTS]
         * (24h, [androidx.work.ExistingPeriodicWorkPolicy.KEEP], no network).
         *
         * Kept for worker-template parity only. It routes to the same `scheduleFromSpec` call, unique
         * work name and KEEP policy as `WorkerRegistry`, and `WorkerRegistry` is the production
         * scheduling path.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, InstallmentDueRecordingWorker::class.java)
        }
    }
}
