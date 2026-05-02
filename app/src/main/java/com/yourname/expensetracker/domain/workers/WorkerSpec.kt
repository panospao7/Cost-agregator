package com.yourname.expensetracker.domain.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
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
                enabled = false,
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
                repeatIntervalHours = 24,
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
            "merchant_key_backfill" to WorkerSpec(
                name = "merchant_key_backfill",
                repeatIntervalHours = null, // one-shot
                constraints = Constraints.NONE,
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelaySeconds = 15
            )
        )
    }
}
