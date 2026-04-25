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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
    private val merchantLocationRepository: MerchantLocationRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Backfill worker started")

        // Evict stale merchant-location cache entries before geocoding new ones.
        // This prevents the resolver from returning outdated cached coordinates.
        try {
            merchantLocationRepository.evictStaleCache()
            Log.d(TAG, "Stale cache eviction complete")
        } catch (e: Exception) {
            Log.w(TAG, "Cache eviction failed (non-fatal)", e)
        }

        // Bug #23 fix: only fetch expenses that haven't exceeded MAX_ATTEMPTS so
        // unresolvable merchants are not retried indefinitely.
        val unlocated = try {
            expenseRepository.getUnlocatedExpensesForBackfill(limit = BATCH_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch unlocated expenses", e)
            return@withContext Result.retry()
        }

        if (unlocated.isEmpty()) {
            Log.d(TAG, "No unlocated expenses — nothing to do")
            return@withContext Result.success()
        }

        Log.d(TAG, "Processing ${unlocated.size} unlocated expenses")
        var resolved = 0
        var failed = 0
        var shouldRetry = false

        for (expense in unlocated) {
            if (isStopped) break  // Worker was cancelled

            val merchantToken = merchantLocationRepository
                .normalizeKey(expense.merchant)
                .anonymizeForLog()

            val result = try {
                locationResolver.resolve(
                    rawMerchantName = expense.merchant,
                    transactionDateMs = expense.date
                )
            } catch (e: Exception) {
                Log.w(TAG, "Resolver threw for expenseId=${expense.id} merchantToken=$merchantToken", e)
                shouldRetry = true
                failed++
                continue
            }

            when (result) {
                is LocationResolutionResult.Resolved -> {
                    expenseRepository.updateExpenseLocation(
                        expenseId = expense.id,
                        latitude = result.latitude,
                        longitude = result.longitude,
                        source = result.source,
                        placeId = result.osmId,
                        address = result.displayAddress
                    )
                    resolved++
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

        Log.d(TAG, "Backfill run complete: resolved=$resolved failed=$failed shouldRetry=$shouldRetry")
        if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        const val TAG = "LocationBackfillWorker"
        const val WORK_NAME = "location_backfill"

        /** Expenses to geocode per run. Keeps the worker well under the 10-min Android limit. */
        private const val BATCH_SIZE = 50

        /**
         * Enqueue a periodic backfill job.
         * Safe to call multiple times — uses [ExistingPeriodicWorkPolicy.KEEP].
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)  // Wi-Fi only
                .build()

            val request = PeriodicWorkRequestBuilder<LocationBackfillWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Backfill worker scheduled")
        }
    }
}
