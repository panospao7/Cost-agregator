package com.yourname.expensetracker.data.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * One-shot WorkManager worker that backfills [Expense.merchantKey] for every
 * existing row that was created before schema version 32.
 *
 * Design:
 *  - One-time work (not periodic). Enqueued at startup with [ExistingWorkPolicy.REPLACE]
 *    (via [WorkerSpec.oneShotPolicy]) so it can be re-scheduled when needed (e.g. after
 *    new merchants are imported).
 *  - Processes all rows in batches of [BATCH_SIZE] until none remain.
 *  - Runs on any network (no network needed — purely local Kotlin computation).
 *  - Safe to interrupt: relies on `isStopped` check and will be re-enqueued on
 *    the next app start if not completed (via [ExistingWorkPolicy.REPLACE]).
 *
 * Why a worker rather than inline SQL in the migration?
 *  SQLite cannot replicate the diphthong-aware Greek→Latin transliteration in
 *  [MerchantKeyGenerator] (e.g. "μπ"→"b", "ου"→"ou"). The migration therefore
 *  only adds the NULL column; this worker populates it with the correct values.
 */
@HiltWorker
class MerchantKeyBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val expenseRepository: ExpenseRepository,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Merchant-key backfill started")

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "merchant_key_backfill",
                allowDuringBackupExport = false
            )
        ) { ctx ->
            var totalUpdated = 0
            var batchesProcessed = 0
            val maxBatches = 25 // WRK-11: Per-run budget — max 25 batches (5000 rows)
            val failedExpenseIdsThisRun = mutableSetOf<Long>()

            while (!isStopped && batchesProcessed < maxBatches) {
                batchesProcessed++
                // WRK-15/N1: observe maintenance drain + write barrier between batches so a
                // backup/restore can stop this worker promptly instead of timing out the drain.
                ctx.checkpoint("merchant_key_backfill_batch")
                val batch = try {
                    expenseRepository.getExpensesWithNullMerchantKey(limit = BATCH_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch batch", e)
                    throw e
                }

                if (batch.isEmpty()) break

                val pendingBatch = batch.filterNot { expense -> expense.id in failedExpenseIdsThisRun }
                if (pendingBatch.isEmpty()) {
                    Log.w(
                        TAG,
                        "Merchant-key backfill made no progress; retrying after repeated failures for ${batch.size} rows"
                    )
                    throw RetryableWorkerException("No progress made")
                }

                var batchUpdated = 0

                for (expense in pendingBatch) {
                    if (isStopped) break
                    // WRK-15/N1: stop before mutating if a maintenance drain/restore began.
                    ctx.checkpoint("merchant_key_backfill_update")
                    ctx.addRowsScanned()
                    val key = MerchantKeyGenerator.generate(expense.merchant)
                    try {
                        expenseRepository.updateMerchantKey(expense.id, key)
                        totalUpdated++
                        batchUpdated++
                        ctx.addRowsUpdated()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedExpenseIdsThisRun += expense.id
                        ctx.addErrors()
                        // Non-fatal: row will be retried in the next batch on the next run.
                        Log.w(TAG, "Failed to update merchantKey for expense ${expense.id}", e)
                    }
                }

                if (!isStopped && batchUpdated == 0) {
                    Log.w(TAG, "Merchant-key backfill made no progress for current batch; retrying")
                    throw RetryableWorkerException("No progress in current batch")
                }
            }

            Log.d(TAG, "Merchant-key backfill complete: updated $totalUpdated rows")
            // P9-PR2 (NEW-P9-010): If stopped mid-loop, signal retry instead of misleading SUCCESS
            if (isStopped) {
                throw RetryableWorkerException("Worker stopped mid-backfill, will retry remaining")
            }
        }

        return guardResult.toWorkerResult()
    }

    companion object {
        const val TAG = "MerchantKeyBackfillWorker"
        const val WORK_NAME = "merchant_key_backfill"

        private const val BATCH_SIZE = 200

        /**
         * Enqueue a one-time backfill job.
         * WRK-N5: Uses [ExistingWorkPolicy.REPLACE] instead of KEEP so the worker
         * CAN be re-scheduled when needed (e.g., after new merchants are imported).
         * KEEP would silently ignore subsequent scheduling requests after the first
         * completion, making it impossible to trigger re-backfill without an app
         * restart or version bump.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, MerchantKeyBackfillWorker::class.java)
        }
    }
}
