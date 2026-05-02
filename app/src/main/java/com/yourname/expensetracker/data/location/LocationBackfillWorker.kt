package com.yourname.expensetracker.data.location

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.workers.WorkerSpec
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
    private val merchantLocationRepository: MerchantLocationRepository,
    private val privacyGate: PrivacyGate,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Backfill worker started")

        // Defense-in-depth: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Log.w(TAG, "Writes blocked during restore mode, skipping")
            return@withContext Result.success()
        }

        // WorkerSpec gate: check if this worker is enabled
        val spec = WorkerSpec.DEFAULTS[WORK_NAME] ?: return@withContext Result.success()
        if (!spec.enabled) {
            Log.w(TAG, "Worker $WORK_NAME disabled by spec, skipping")
            return@withContext Result.success()
        }

        // Privacy gate check: background location backfill must be enabled
        val gateCheck = privacyGate.check(PrivacyCapability.BACKGROUND_LOCATION_BACKFILL)
        if (gateCheck is PrivacyDecision.Denied) {
            Log.w(TAG, "Backfill blocked by privacy gate: ${gateCheck.reason}")
            return@withContext Result.success()
        }

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
        var skipped = 0
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
                expenseRepository.incrementBackfillAttempts(expense.id)
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
                    } else {
                        Log.d(TAG, "Expense ${expense.id} was already located — skipped (user-set location preserved)")
                        skipped++
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
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         */
        fun schedule(context: Context) {
            val spec = WorkerSpec.DEFAULTS[WORK_NAME] ?: return
            if (!spec.enabled) {
                Log.w(TAG, "Worker $WORK_NAME disabled by spec, skipping schedule")
                return
            }
            val intervalHours = spec.repeatIntervalHours ?: run {
                Log.w(TAG, "Worker $WORK_NAME has no repeat interval, skipping periodic schedule")
                return
            }

            val request = PeriodicWorkRequestBuilder<LocationBackfillWorker>(
                repeatInterval = intervalHours,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(spec.constraints)
                .setBackoffCriteria(
                    spec.backoffPolicy,
                    spec.backoffDelaySeconds,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                spec.existingWorkPolicy,
                request
            )
            Log.d(TAG, "Backfill worker scheduled (interval=${intervalHours}h)")
        }
    }
}
