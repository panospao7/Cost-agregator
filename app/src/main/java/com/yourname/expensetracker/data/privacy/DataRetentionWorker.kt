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
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
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
    private val executionGuard: WorkerExecutionGuard,
    private val retentionRegistry: RetentionRegistry
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
            val emailCutoff = now - TimeUnit.DAYS.toMillis(30)

            // PRIV-441-12: Use injectable RetentionRegistry instead of inline list
            val allTargets = retentionRegistry.allTargets()
            val results = mutableListOf<RetentionPurgeResult>()

            // Notification target uses its own cutoff
            executionGuard.checkpoint("data_retention_notifications")
            allTargets.firstOrNull { it.name == "raw_notifications" }?.let {
                results += it.purge(notificationCutoff)
            }

            // OCR target uses its own cutoff
            executionGuard.checkpoint("data_retention_ocr")
            allTargets.firstOrNull { it.name == "scanned_receipts.rawOcrText" }?.let {
                results += it.purge(ocrCutoff)
            }

            // All other targets use now as their TTL-based cutoff, except email which uses emailCutoff
            for (target in allTargets) {
                if (target.name != "raw_notifications" && target.name != "scanned_receipts.rawOcrText") {
                    // PRIV-43B-12: Email target uses emailCutoff (30 days), not now
                    val cutoff = if (target.name == "email_receipt_sources") emailCutoff else now
                    results += target.purge(cutoff)
                }
            }

            val auditDao = appDatabase.privacyAuditDao()

            // Log per-target counts and audit successes
            for (result in results) {
                if (result.rowsPurged > 0 || !result.success) {
                    Log.d(TAG, "RetentionTarget[${result.targetName}]: purged=${result.rowsPurged} success=${result.success} error=${result.errorMessage}")
                }
            }

            val notifCount = results.firstOrNull { it.targetName == "raw_notifications" }?.rowsPurged ?: 0
            val ocrCount = results.firstOrNull { it.targetName == "scanned_receipts.rawOcrText" }?.rowsPurged ?: 0

            if (notifCount > 0) {
                auditDao.insert(PrivacyAuditEvent(
                    capability = PrivacyCapability.RAW_NOTIFICATION_RETENTION.name,
                    decision = "ALLOWED",
                    reason = "Purged $notifCount raw notifications older than ${settings.rawNotificationRetentionDays} days",
                    context = "{\"purgedCount\": $notifCount, \"retentionDays\": ${settings.rawNotificationRetentionDays}}",
                    timestampMs = now,
                    caller = "DataRetentionWorker"
                ))
            }
            if (ocrCount > 0) {
                auditDao.insert(PrivacyAuditEvent(
                    capability = PrivacyCapability.RAW_OCR_RETENTION.name,
                    decision = "ALLOWED",
                    reason = "Purged raw OCR text from $ocrCount receipts older than ${settings.rawOcrRetentionDays} days",
                    context = "{\"purgedCount\": $ocrCount, \"retentionDays\": ${settings.rawOcrRetentionDays}}",
                    timestampMs = now,
                    caller = "DataRetentionWorker"
                ))
            }

            Log.d(TAG, "Data retention worker completed: notifications=$notifCount ocr=$ocrCount")
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
