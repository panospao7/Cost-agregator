package com.yourname.expensetracker.data.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that geocodes existing expenses that have no latitude/longitude.
 *
 * Design:
 *  - Runs on Wi-Fi only (network type = UNMETERED) to avoid mobile-data charges.
 *  - Survives app restarts ([PeriodicWorkRequest]).
 *  - Processes at most [BATCH_SIZE] expenses per run so it doesn't time out.
 *  - Uses [LocationResolver] which already enforces the 1 req/sec Nominatim rate limit.
 *  - Requires the app to be in the foreground *once* so [ForegroundLocationProvider]
 *    can optionally supply a GPS bias — but the worker succeeds even without GPS.
 *
 * Scheduling (called from [AppStartupCoordinator]):
 * ```
 * LocationBackfillWorker.schedule(context)
 * ```
 */
@HiltWorker
class LocationBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val expenseRepository: ExpenseRepository,
    private val locationResolver: LocationResolver,
    private val merchantLocationRepository: MerchantLocationRepository,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Backfill worker started")

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "location_backfill",
                requiredCapabilities = listOf(PrivacyCapability.BACKGROUND_LOCATION_BACKFILL),
                allowDuringBackupExport = false,
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount
            )
        ) { ctx ->
            // Evict stale merchant-location cache entries before geocoding new ones.
            // This prevents the resolver from returning outdated cached coordinates.
            try {
                merchantLocationRepository.evictStaleCache()
                Log.d(TAG, "Stale cache eviction complete")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Cache eviction failed (non-fatal)", e)
            }

            // Bug #23 fix: only fetch expenses that haven't exceeded MAX_ATTEMPTS so
            // unresolvable merchants are not retried indefinitely.
            val unlocated = try {
                expenseRepository.getUnlocatedExpensesForBackfill(limit = BATCH_SIZE)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to fetch unlocated expenses", e)
                throw e
            }

            if (unlocated.isEmpty()) {
                Log.d(TAG, "No unlocated expenses — nothing to do")
                return@runGuardedWithContext
            }

            Log.d(TAG, "Processing ${unlocated.size} unlocated expenses")
            var resolved = 0
            var failed = 0
            var skipped = 0
            var shouldRetry = false

            for (expense in unlocated) {
                if (isStopped) break  // Worker was cancelled
                ctx.checkpoint("location_backfill")
                ctx.addRowsScanned()

                val merchantToken = merchantLocationRepository
                    .normalizeKey(expense.merchant)
                    .anonymizeForLog()

                val result = try {
                    locationResolver.resolve(
                        rawMerchantName = expense.merchant,
                        transactionDateMs = expense.date
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Resolver threw for expenseId=${expense.id} merchantToken=$merchantToken", e)
                    // Transient thrown failure: trigger a retry without consuming the permanent
                    // attempt budget, consistent with the structured Retryable result path. Unexpected
                    // exceptions are presumed transient (e.g. geocoder/network outage), so we must not
                    // permanently abandon the row via incrementBackfillAttempts — only the structured
                    // Unresolved/NeedsUserSelection paths consume the budget for genuinely ungeocodable rows.
                    shouldRetry = true
                    failed++
                    continue
                }

                when (result) {
                    is LocationResolutionResult.Resolved -> {
                        // Use conditional update to avoid overwriting user-set locations
                        // (race condition: user may have set location between fetch and write).
                        val affected = expenseRepository.conditionallySetLocation(
                            expenseId = expense.id,
                            latitude = result.latitude,
                            longitude = result.longitude,
                            source = result.source,
                            placeId = result.osmId,
                            address = result.displayAddress
                        )
                        if (affected > 0) {
                            resolved++
                            ctx.addRowsUpdated()
                        } else {
                            Log.d(TAG, "Expense ${expense.id} was already located — skipped (user-set location preserved)")
                            skipped++
                            ctx.addRowsSkipped()
                        }
                    }
                    is LocationResolutionResult.Retryable -> {
                        Log.w(
                            TAG,
                            "Retryable backfill failure for expenseId=${expense.id} merchantToken=$merchantToken error=${result.error}"
                        )
                        shouldRetry = true
                        failed++
                    }
                    is LocationResolutionResult.NeedsUserSelection -> {
                        // Cannot auto-resolve; leave for user interaction in Map screen.
                        // Count as a failed attempt so we don't spam Overpass on each run.
                        Log.d(TAG, "NeedsUserSelection for expenseId=${expense.id} merchantToken=$merchantToken — skipping backfill")
                        expenseRepository.incrementBackfillAttempts(expense.id)
                    }
                    is LocationResolutionResult.Unresolved -> {
                        Log.d(TAG, "Unresolved for expenseId=${expense.id} merchantToken=$merchantToken")
                        expenseRepository.incrementBackfillAttempts(expense.id)
                        failed++
                    }
                }
            }

            Log.d(TAG, "Backfill run complete: resolved=$resolved skipped=$skipped failed=$failed shouldRetry=$shouldRetry")
            // P9-PR2 (NEW-P9-009): If stopped mid-loop, signal retry instead of misleading SUCCESS
            if (isStopped) {
                throw RetryableWorkerException("Worker stopped mid-backfill, will retry remaining")
            }
            if (shouldRetry) {
                throw RetryableWorkerException("Some backfill resolutions failed, will retry")
            }
        }

        return guardResult.toWorkerResult()
    }

    companion object {
        const val TAG = "LocationBackfillWorker"
        const val WORK_NAME = "location_backfill"

        /** Expenses to geocode per run. Keeps the worker well under the 10-min Android limit. */
        private const val BATCH_SIZE = 50

        /**
         * Enqueue a periodic backfill job.
         * Safe to call multiple times — uses [ExistingPeriodicWorkPolicy.KEEP].
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, LocationBackfillWorker::class.java)
        }
    }
}
