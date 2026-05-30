package com.yourname.expensetracker.domain.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType

/**
 * Specification for a background worker managed by WorkManager.
 *
 * Each worker has a unique [name], a [version] that is bumped whenever its
 * scheduling parameters change (which triggers cancel + re-enqueue), and
 * optional scheduling policies for backoff and conflict resolution.
 *
 * @property name Unique logical name for the worker (e.g. "data_retention").
 * @property version Spec version; bump to force WorkManager re-enqueue.
 * @property enabled Whether the worker should be active on this device.
 * @property constraints WorkManager constraints (network, battery, etc.).
 * @property repeatIntervalHours How often the periodic worker runs (null for one-shot).
 * @property flexMinutes Flex interval for periodic work (null if not needed).
 * @property existingWorkPolicy How to handle existing periodic work.
 * @property oneShotPolicy How to handle existing one-shot work (used only when
 *   [repeatIntervalHours] is null). Defaults to [ExistingWorkPolicy.KEEP] so a
 *   one-shot is scheduled at most once unless overridden. A spec version bump
 *   always forces REPLACE regardless of this value.
 * @property backoffPolicy Retry backoff policy for transient failures.
 * @property backoffDelaySeconds Initial backoff delay in seconds.
 */
data class WorkerSpec(
    val name: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val constraints: Constraints = Constraints.NONE,
    val repeatIntervalHours: Long? = null,
    val flexMinutes: Long? = null,
    val existingWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    val oneShotPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelaySeconds: Long = 30
) {
    companion object {
        /**
         * Default specs for all 7 background workers in the system.
         *
         * These values reflect the Phase 8 target configuration:
         * - LocationBackfill: 12h interval, UNMETERED network (up from 6h).
         * - DailyBriefing: UNMETERED + battery-not-low + charging constraints.
         * - BillReminder: Disabled by default (user opt-in required).
         * - MerchantKeyBackfill: One-shot (no repeat interval).
         */
        val DEFAULTS: Map<String, WorkerSpec> = mapOf(
            "data_retention" to WorkerSpec(
                name = "data_retention",
                repeatIntervalHours = 24,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            ),
            "location_backfill" to WorkerSpec(
                name = "location_backfill",
                repeatIntervalHours = 12,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
                backoffDelaySeconds = 15
            ),
            "bill_reminder_periodic" to WorkerSpec(
                name = "bill_reminder_periodic",
                version = 2, // bumped: enabled=true (worker was silently disabled)
                enabled = true,
                repeatIntervalHours = 6,
                flexMinutes = 15,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            ),
            "receipt_matching" to WorkerSpec(
                name = "receipt_matching",
                repeatIntervalHours = 2,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
                backoffDelaySeconds = 600
            ),
            "ai_daily_briefing" to WorkerSpec(
                name = "ai_daily_briefing",
                repeatIntervalHours = null, // midnight-aligned one-shot
                // KEEP is explicit here: the midnight self-rescheduling chain relies on
                // KEEP so an already-scheduled briefing is not clobbered/duplicated.
                oneShotPolicy = ExistingWorkPolicy.KEEP,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)
                    .build()
            ),
            "warranty_expiration_check" to WorkerSpec(
                name = "warranty_expiration_check",
                repeatIntervalHours = 24,
                constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
                backoffDelaySeconds = 600
            ),
            // WRK-N5: Use REPLACE (not KEEP) for this one-shot worker via the
            // explicit oneShotPolicy field. KEEP would prevent re-scheduling after
            // completion, meaning if the worker needs to run again (e.g. after new
            // merchants are added), the existing completed work policy would block it.
            // REPLACE allows the one-shot to be re-enqueued each time it is scheduled,
            // matching MerchantKeyBackfillWorker's KDoc intent.
            "merchant_key_backfill" to WorkerSpec(
                name = "merchant_key_backfill",
                repeatIntervalHours = null, // one-shot
                oneShotPolicy = ExistingWorkPolicy.REPLACE,
                constraints = Constraints.NONE,
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelaySeconds = 15
            )
        )
    }
}
