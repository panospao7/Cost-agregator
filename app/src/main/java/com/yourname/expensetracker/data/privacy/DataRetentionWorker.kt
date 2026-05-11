// P8-P1-06: Retention scope expanded to cover AI artifacts and email receipt sources.
// Remaining gaps: chat messages (AiChatMessageDao), debug diagnostics (ServiceDiagnostics).

package com.yourname.expensetracker.data.privacy

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that purges raw data (notification content, OCR text)
 * after the retention period configured in [PrivacySettings].
 *
 * ## RCP-10: Raw OCR purge respects retention
 * The [purgeRawOcrText] method nulls out [ScannedReceipt.rawOcrText] and sets
 * [ScannedReceipt.rawOcrTextPurgedAt] for receipts whose `createdAt` is older
 * than the configured retention period AND that have not already been purged.
 * This ensures that raw OCR data is not retained indefinitely and respects
 * the user's privacy retention preferences.
 *
 * Runs daily via [PeriodicWorkRequest] and is safe to call multiple times:
 * - Only rows whose `rawContentPurgedAt` / `rawOcrTextPurgedAt` IS NULL are candidates.
 * - After purging, the column is set to the current timestamp so the row is not
 *   processed again on subsequent runs.
 * - Audit events are written for the count of purged rows per category.
 */
@HiltWorker
class DataRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val appDatabase: AppDatabase,
    private val timeProvider: TimeProvider,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Data retention worker started")

        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "data_retention",
                allowDuringBackupExport = false
            )
        ) {
            val settings = privacySettingsRepository.getSettings()
            val now = timeProvider.now()

            val notificationCutoff = now - TimeUnit.DAYS.toMillis(settings.rawNotificationRetentionDays.toLong())
            val ocrCutoff = now - TimeUnit.DAYS.toMillis(settings.rawOcrRetentionDays.toLong())

            val notificationDao = appDatabase.rawNotificationDao()
            val receiptDao = appDatabase.scannedReceiptDao()
            val auditDao = appDatabase.privacyAuditDao()

            // 1. Purge raw notification content (title, text, bigText, extrasJson)
            val notificationPurgeCount = purgeRawNotifications(notificationDao, notificationCutoff, now)
            if (notificationPurgeCount > 0) {
                Log.d(TAG, "Purged $notificationPurgeCount raw notifications")
                auditDao.insert(
                    PrivacyAuditEvent(
                        capability = PrivacyCapability.RAW_NOTIFICATION_RETENTION.name,
                        decision = "ALLOWED",
                        reason = "Purged $notificationPurgeCount raw notifications older than ${settings.rawNotificationRetentionDays} days",
                        context = "{\"purgedCount\": $notificationPurgeCount, \"retentionDays\": ${settings.rawNotificationRetentionDays}}",
                        timestampMs = now,
                        caller = "DataRetentionWorker"
                    )
                )
            }

            // 2. Purge raw OCR text from scanned_receipts
            val ocrPurgeCount = purgeRawOcrText(receiptDao, ocrCutoff, now)
            if (ocrPurgeCount > 0) {
                Log.d(TAG, "Purged raw OCR text from $ocrPurgeCount scanned receipts")
                auditDao.insert(
                    PrivacyAuditEvent(
                        capability = PrivacyCapability.RAW_OCR_RETENTION.name,
                        decision = "ALLOWED",
                        reason = "Purged raw OCR text from $ocrPurgeCount receipts older than ${settings.rawOcrRetentionDays} days",
                        context = "{\"purgedCount\": $ocrPurgeCount, \"retentionDays\": ${settings.rawOcrRetentionDays}}",
                        timestampMs = now,
                        caller = "DataRetentionWorker"
                    )
                )
            }

            // 3. Purge expired AI artifacts (TTL-based, typically 90 days)
            val aiArtifactDao = appDatabase.aiArtifactDao()
            aiArtifactDao.deleteExpired(now)

            // 4. Purge email receipt source records older than 30 days
            val emailRetentionCutoff = now - TimeUnit.DAYS.toMillis(30)
            val emailReceiptDao = appDatabase.emailReceiptDao()
            emailReceiptDao.deleteOlderThan(emailRetentionCutoff)

            Log.d(TAG, "Data retention worker completed: notifications=$notificationPurgeCount ocr=$ocrPurgeCount")
        }

        return guardResult.toWorkerResult()
    }

    /**
     * Nulls out raw content fields of [RawNotification] rows whose
     * [RawNotification.capturedAt] is older than [cutoff] and that have not
     * already been purged ([RawNotification.rawContentPurgedAt] IS NULL).
     *
     * P2-28: Uses LIMIT-based pagination to avoid loading all candidates into memory.
     *
     * @return number of rows updated
     */
    private suspend fun purgeRawNotifications(
        dao: com.yourname.expensetracker.data.database.dao.RawNotificationDao,
        cutoff: Long,
        now: Long
    ): Int {
        var totalPurged = 0
        while (true) {
            val candidates = dao.getUnpurgedRawNotificationsOlderThan(cutoff, PAGE_SIZE)
            if (candidates.isEmpty()) break

            for (notification in candidates) {
                executionGuard.checkpoint("data_retention_notifications")
                dao.updateRawContentPurged(
                    id = notification.id,
                    rawContentPurgedAt = now,
                    title = null,
                    text = null,
                    bigText = null,
                    subText = null,
                    extrasJson = null,
                    parseResult = null
                )
            }
            totalPurged += candidates.size
        }
        return totalPurged
    }

    /**
     * Nulls out [ScannedReceipt.rawOcrText] for rows whose [ScannedReceipt.createdAt]
     * is older than [cutoff] and that have not already been purged
     * ([ScannedReceipt.rawOcrTextPurgedAt] IS NULL).
     *
     * P2-28: Uses LIMIT-based pagination to avoid loading all candidates into memory.
     *
     * @return number of rows updated
     */
    private suspend fun purgeRawOcrText(
        dao: com.yourname.expensetracker.data.database.dao.ScannedReceiptDao,
        cutoff: Long,
        now: Long
    ): Int {
        var totalPurged = 0
        while (true) {
            val candidates = dao.getUnpurgedScannedReceiptsOlderThan(cutoff, PAGE_SIZE)
            if (candidates.isEmpty()) break

            for (receipt in candidates) {
                executionGuard.checkpoint("data_retention_ocr")
                dao.updateRawOcrTextPurged(
                    id = receipt.id,
                    rawOcrTextPurgedAt = now
                )
            }
            totalPurged += candidates.size
        }
        return totalPurged
    }

    companion object {
        const val TAG = "DataRetentionWorker"
        const val WORK_NAME = "data_retention"
        private const val PAGE_SIZE = 100

        /**
         * Enqueue a daily data-retention job.
         * Safe to call multiple times — uses [ExistingPeriodicWorkPolicy.KEEP].
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, DataRetentionWorker::class.java)
        }
    }
}
