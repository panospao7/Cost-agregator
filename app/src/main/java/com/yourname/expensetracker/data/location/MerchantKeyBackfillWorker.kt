package com.yourname.expensetracker.data.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.workers.WorkerSpec
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * One-shot WorkManager worker that backfills [Expense.merchantKey] for every
 * existing row that was created before schema version 32.
 *
 * Design:
 *  - One-time work (not periodic). Enqueued at startup with [ExistingWorkPolicy.KEEP]
 *    so it is only scheduled once per device lifetime.
 *  - Processes all rows in batches of [BATCH_SIZE] until none remain.
 *  - Runs on any network (no network needed — purely local Kotlin computation).
 *  - Safe to interrupt: relies on `isStopped` check and will be re-enqueued on
 *    the next app start if not completed (via [ExistingWorkPolicy.KEEP]).
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
    private val expenseRepository: ExpenseRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Merchant-key backfill started")

        // WorkerSpec gate: check if this worker is enabled
        val spec = WorkerSpec.DEFAULTS[WORK_NAME] ?: return@withContext Result.success()
        if (!spec.enabled) {
            Log.w(TAG, "Worker $WORK_NAME disabled by spec, skipping")
            return@withContext Result.success()
        }

        var totalUpdated = 0
        val failedExpenseIdsThisRun = mutableSetOf<Long>()

        while (!isStopped) {
            val batch = try {
                expenseRepository.getExpensesWithNullMerchantKey(limit = BATCH_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch batch", e)
                return@withContext Result.retry()
            }

            if (batch.isEmpty()) break

            val pendingBatch = batch.filterNot { expense -> expense.id in failedExpenseIdsThisRun }
            if (pendingBatch.isEmpty()) {
                Log.w(
                    TAG,
                    "Merchant-key backfill made no progress; retrying after repeated failures for ${batch.size} rows"
                )
                return@withContext Result.retry()
            }

            var batchUpdated = 0

            for (expense in pendingBatch) {
                if (isStopped) break
                val key = MerchantKeyGenerator.generate(expense.merchant)
                try {
                    expenseRepository.updateMerchantKey(expense.id, key)
                    totalUpdated++
                    batchUpdated++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedExpenseIdsThisRun += expense.id
                    // Non-fatal: row will be retried in the next batch on the next run.
                    Log.w(TAG, "Failed to update merchantKey for expense ${expense.id}", e)
                }
            }

            if (!isStopped && batchUpdated == 0) {
                Log.w(TAG, "Merchant-key backfill made no progress for current batch; retrying")
                return@withContext Result.retry()
            }
        }

        Log.d(TAG, "Merchant-key backfill complete: updated $totalUpdated rows")
        Result.success()
    }

    companion object {
        const val TAG = "MerchantKeyBackfillWorker"
        const val WORK_NAME = "merchant_key_backfill"

        private const val BATCH_SIZE = 200

        /**
         * Enqueue a one-time backfill job.
         * Uses [ExistingWorkPolicy.KEEP] so the job is only scheduled once; if the
         * work has already completed (or is queued/running) nothing changes.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<MerchantKeyBackfillWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Merchant-key backfill scheduled")
        }
    }
}
