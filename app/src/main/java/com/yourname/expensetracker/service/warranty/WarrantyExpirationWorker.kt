package com.yourname.expensetracker.service.warranty

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.WarrantyReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.WarrantyReminderDelivery
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.NotificationIdGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
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
 * ## Durable, claim-before-notify sent-state (S9 / P9-P1-09, PR6)
 * Sent-state is tracked in the [WarrantyReminderDelivery] Room entity, one row per
 * (warrantyId, windowDays, expiryDate). This replaces the previous in-memory set +
 * SharedPreferences (`warranty_expiration_worker_prefs`) approach, which only deduped
 * within a single run and an in-app-storage cooldown that did not survive backup/restore.
 *
 * For every expiring warranty + window the worker:
 *   1. **Seeds** a SCHEDULED delivery row idempotently via the unique key
 *      ([WarrantyReminderDeliveryDao.insertOrIgnore]).
 *   2. **Claims** it atomically ([WarrantyReminderDeliveryDao.claim]); the claim only
 *      succeeds from SCHEDULED/FAILED, so a row already SENT in a prior run (durable
 *      cross-reboot dedup) is skipped, and two concurrent runs can never both claim it.
 *   3. Only if the claim was acquired does it send the notification and inspect the
 *      [NotificationService.DeliveryResult]:
 *        - DELIVERED → [WarrantyReminderDeliveryDao.markSentFromClaimed] (persists the
 *          notificationId; this is the ONLY transition into SENT and only from CLAIMED).
 *        - NOT_DELIVERED → [WarrantyReminderDeliveryDao.markFailed] (records the reason;
 *          the row stays re-claimable on a later run).
 *
 * Crash recovery: any CLAIMED row whose claim is older than [STALE_CLAIM_MS] is reset to
 * SCHEDULED at the start of each run ([WarrantyReminderDeliveryDao.recoverStaleClaimed]),
 * so a worker that crashed mid-send retries instead of being stuck.
 *
 * Because the state lives in the Room DB (which is snapshotted whole during backup), the
 * sent-state survives backup/restore.
 *
 * ## Notification permission gate
 * Notification permission is enforced by [WorkerExecutionGuard] via
 * [WorkerGuardRequest.requiresNotificationPermission]. When notifications are
 * disabled the guard records a durable SKIPPED run
 * ([com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED])
 * and the block below never runs.
 */
@HiltWorker
class WarrantyExpirationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val warrantyRepository: WarrantyTrackerRepository,
    private val notificationService: NotificationService,
    private val deliveryDao: WarrantyReminderDeliveryDao,
    private val executionGuard: WorkerExecutionGuard,
    private val timeProvider: TimeProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "warranty_expiration_check",
                requiresNotificationPermission = true,
                allowDuringBackupExport = false
            )
        ) {
            try {
                Timber.d("Checking for expiring warranties...")
                // WRK-16/N1: observe maintenance drain + write barrier before the DB
                // reconcile so a backup/restore can stop this worker promptly.
                executionGuard.checkpoint("warranty_reconcile")
                val reconciliationResult = warrantyRepository.reconcileExpiredItems(timeProvider.now())

                val now = timeProvider.now()
                // DAY_IN_MILLIS constant — acceptable TTL usage (not calendar math)
                val oneDayMs = 86_400_000L

                // Recover deliveries that were claimed but never completed (e.g. the
                // worker crashed after claiming). Only genuinely-old claims are reset.
                deliveryDao.recoverStaleClaimed(
                    staleClaimThreshold = now - STALE_CLAIM_MS,
                    now = now
                )

                // Check warranties expiring in 7 days
                val expiringIn7Days = warrantyRepository.getWarrantiesExpiringSoon(7)
                expiringIn7Days.forEach { warranty ->
                    executionGuard.checkpoint("warranty_notify_7d")
                    deliverReminder(
                        warranty = warranty,
                        windowDays = 7,
                        now = now,
                        title = applicationContext.getString(R.string.warranty_expiring_soon_title),
                        message = applicationContext.getString(
                            R.string.warranty_expires_in_7_days_format,
                            warranty.productName,
                            warranty.merchantName
                        )
                    )
                }

                // Check warranties expiring in 30 days (less urgent).
                // Use ID-based filtering to correctly exclude warranties already
                // covered by the 7-day window (fixes fragile object-equality check).
                val sevenDayIds = expiringIn7Days.map { it.id }.toSet()
                val expiringIn30Days = warrantyRepository.getWarrantiesExpiringSoon(30)
                    .filter { it.id !in sevenDayIds }
                expiringIn30Days.forEach { warranty ->
                    executionGuard.checkpoint("warranty_notify_30d")
                    deliverReminder(
                        warranty = warranty,
                        windowDays = 30,
                        now = now,
                        title = applicationContext.getString(R.string.warranty_expiration_reminder_title),
                        message = applicationContext.getString(
                            R.string.warranty_expires_in_30_days_format,
                            warranty.productName
                        )
                    )
                }

                // ── Prune deliveries whose expiry is older than 90 days ───────
                deliveryDao.deleteOlderThan(now - 90L * oneDayMs)

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

    /**
     * Seeds (idempotently), atomically claims, and — only if the claim was acquired —
     * sends the reminder notification, then records the durable outcome.
     *
     * - A row already SENT in a previous run is skipped (claim returns 0) — durable dedup.
     * - DELIVERED → SENT (persists notificationId). NOT_DELIVERED → FAILED (re-claimable).
     */
    private suspend fun deliverReminder(
        warranty: com.yourname.expensetracker.data.database.entity.Warranty,
        windowDays: Int,
        now: Long,
        title: String,
        message: String
    ) {
        val expiryDate = warranty.warrantyEndDate

        // 1. Seed a SCHEDULED row idempotently (no-op if it already exists).
        deliveryDao.insertOrIgnore(
            WarrantyReminderDelivery(
                warrantyId = warranty.id,
                windowDays = windowDays,
                expiryDate = expiryDate,
                status = "SCHEDULED",
                createdAt = now,
                updatedAt = now
            )
        )

        // 2. Atomically claim. Only SCHEDULED/FAILED rows can be claimed, so an
        //    already-SENT delivery (durable dedup) or one claimed by a concurrent
        //    run yields 0 and is skipped.
        val claimed = deliveryDao.claim(
            warrantyId = warranty.id,
            windowDays = windowDays,
            expiryDate = expiryDate,
            now = now
        )
        if (claimed != 1) return

        val row = deliveryDao.getByKey(warranty.id, windowDays, expiryDate) ?: return

        // 3. Send and record the outcome. Mark SENT only when delivery actually succeeds.
        val notificationId = NotificationIdGenerator.forWarranty(warranty.id, windowDays)
        val deliveryResult = notificationService.sendBudgetAlert(
            notificationId = notificationId,
            title = title,
            message = message
        )

        if (deliveryResult == NotificationService.DeliveryResult.DELIVERED) {
            deliveryDao.markSentFromClaimed(row.id, notificationId, now)
        } else {
            deliveryDao.markFailed(row.id, reason = "notification_not_delivered", now = now)
        }
    }

    companion object {
        private const val WORK_NAME = "warranty_expiration_check"

        /**
         * A CLAIMED delivery whose claim is older than this is considered stale (the
         * worker likely crashed mid-send) and is reset to SCHEDULED for retry.
         */
        private const val STALE_CLAIM_MS = 60L * 60L * 1000L // 1 hour

        /**
         * Schedules the warranty expiration worker.
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         *
         * ## Durable sent-state (S9)
         * Sent-state is tracked in the [WarrantyReminderDelivery] Room table using a
         * claim-before-notify protocol, preventing re-sending the same notification across
         * device reboots, worker reschedules, and backup/restore. Deliveries whose expiry is
         * older than 90 days are pruned at the end of each run.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, WarrantyExpirationWorker::class.java)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
