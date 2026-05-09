package com.yourname.expensetracker.service.warranty

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.NotificationIdGenerator
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Periodic WorkManager worker that checks for expiring warranties and sends
 * reminder notifications.
 *
 * ## Idempotency
 * Uses an in-memory set of already-notified `warrantyId:window` keys within
 * each run to prevent duplicate notifications. The 30-day filtered list uses
 * ID-based exclusion (not object equality) to correctly separate 7-day and
 * 30-day windows.
 *
 * ## Next step: persistent reminder state
 * The in-memory dedup only prevents duplicates within a single run.
 * A per-warranty last-sent timestamp in persistent storage (e.g. room or
 * DataStore) would prevent re-sending the same reminder across device
 * reboots or worker reschedules. The [notifiedThisRun] set should be
 * replaced or supplemented with persistent tracking.
 *
 * ## Settings gate
 * At the start of [doWork], checks that notifications are enabled. If denied,
 * the worker exits early with [Result.success] (skipped, not retried).
 */
@HiltWorker
class WarrantyExpirationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val warrantyRepository: WarrantyTrackerRepository,
    private val notificationService: NotificationService,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "warranty_expiration_check",
                allowDuringBackupExport = false
            )
        ) {
            // ── Settings gate: notification permission check ──────────────────
            if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
                Timber.w("Warranty notifications disabled by permission — skipping run")
                return@runGuarded
            }

            try {
                Timber.d("Checking for expiring warranties...")
                val reconciliationResult = warrantyRepository.reconcileExpiredItems()

                // ── Persistent reminder state via SharedPreferences ───────────────
                // Tracks last-notified timestamps per (warrantyId:window) key.
                // If already notified within the current window, skip to avoid
                // re-sending the same notification across device reboots.
                val prefs: SharedPreferences = applicationContext.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE
                )
                // TODO: Use TimeProvider instead of System.currentTimeMillis()
                val now = System.currentTimeMillis()
                // DAY_IN_MILLIS constant for notification cooldown — acceptable TTL usage (not calendar math)
                val oneDayMs = 86_400_000L

                // Track already-notified (warrantyId:window) keys within this run
                // to prevent duplicate notifications in the same invocation.
                val notifiedThisRun = mutableSetOf<String>()

                // Helper: check persistent state before sending
                fun shouldSend(key: String, lastNotifiedAt: Long?): Boolean {
                    if (key in notifiedThisRun) return false
                    if (lastNotifiedAt != null && (now - lastNotifiedAt) < oneDayMs) return false
                    return true
                }

                // Check warranties expiring in 7 days
                val expiringIn7Days = warrantyRepository.getWarrantiesExpiringSoon(7)
                expiringIn7Days.forEach { warranty ->
                    val key = "${warranty.id}:7"
                    val lastNotified = prefs.getLong(key, -1L).takeIf { it >= 0L }
                    if (shouldSend(key, lastNotified)) {
                        notificationService.sendBudgetAlert(
                            notificationId = NotificationIdGenerator.forWarranty(warranty.id, 7),
                            title = applicationContext.getString(R.string.warranty_expiring_soon_title),
                            message = applicationContext.getString(
                                R.string.warranty_expires_in_7_days_format,
                                warranty.productName,
                                warranty.merchantName
                            )
                        )
                        notifiedThisRun += key
                        prefs.edit().putLong(key, now).apply()
                    }
                }

                // Check warranties expiring in 30 days (less urgent).
                // Use ID-based filtering to correctly exclude warranties already
                // covered by the 7-day window (fixes fragile object-equality check).
                val sevenDayIds = expiringIn7Days.map { it.id }.toSet()
                val expiringIn30Days = warrantyRepository.getWarrantiesExpiringSoon(30)
                    .filter { it.id !in sevenDayIds }
                expiringIn30Days.forEach { warranty ->
                    val key = "${warranty.id}:30"
                    val lastNotified = prefs.getLong(key, -1L).takeIf { it >= 0L }
                    if (shouldSend(key, lastNotified)) {
                        notificationService.sendBudgetAlert(
                            notificationId = NotificationIdGenerator.forWarranty(warranty.id, 30),
                            title = applicationContext.getString(R.string.warranty_expiration_reminder_title),
                            message = applicationContext.getString(
                                R.string.warranty_expires_in_30_days_format,
                                warranty.productName
                            )
                        )
                        notifiedThisRun += key
                        prefs.edit().putLong(key, now).apply()
                    }
                }

                // ── Clean up stale entries older than 90 days ────────────────
                val cutoff = now - 90L * oneDayMs
                prefs.all.keys.forEach { k ->
                    val v = prefs.getLong(k, -1L)
                    if (v >= 0L && v < cutoff) {
                        prefs.edit().remove(k).apply()
                    }
                }

                Timber.d(
                    "Warranty check complete. Expired ${reconciliationResult.expiredWarrantyCount} warranties, " +
                        "${reconciliationResult.expiredReturnWindowCount} return windows; found ${expiringIn7Days.size} expiring in 7 days, " +
                        "${expiringIn30Days.size} in 30 days"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error checking warranty expirations")
                throw e
            }
        }

        return guardResult.toWorkerResult()
    }

    companion object {
        private const val WORK_NAME = "warranty_expiration_check"
        private const val PREFS_NAME = "warranty_expiration_worker_prefs"

        /**
         * Schedules the warranty expiration worker.
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         *
         * ## Persistent sent-state (WKR-3)
         * Uses SharedPreferences ([PREFS_NAME]) to track last-notified timestamps per
         * (warrantyId:window) key, preventing re-sending the same notification across
         * device reboots or worker reschedules. Stale entries older than 90 days are
         * cleaned up at the end of each run.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, WarrantyExpirationWorker::class.java)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
