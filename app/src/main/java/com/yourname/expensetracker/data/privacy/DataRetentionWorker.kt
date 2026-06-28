// P8-P1-06: Retention scope expanded to cover AI artifacts and email receipt sources.
// Remaining gaps: chat messages (AiChatMessageDao), debug diagnostics (ServiceDiagnostics).

package com.yourname.expensetracker.data.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.BlockedPolicy
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
    private val retentionRegistry: RetentionRegistry,
    private val diagnosticEventWriter: DiagnosticEventWriter
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Data retention worker started")

        // P8-PR1 (NEW-P8-002): Initialise checkpoint prefs — if we crashed mid-run
        // on a previous attempt, resume from the last incomplete target.
        val prefs = checkpointPrefs()
        val resumeFrom = getLastIncompleteTarget(prefs)
        if (resumeFrom != null) {
            Log.w(TAG, "Resuming from last incomplete target: $resumeFrom")
        }

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "data_retention",
                allowDuringBackupExport = false,
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount,
                specVersion = WorkerSpec.DEFAULTS["data_retention"]?.version
            )
        ) { ctx ->
            val settings = privacySettingsRepository.getSettings()
            val now = timeProvider.now()

            val notificationCutoff = now - TimeUnit.DAYS.toMillis(settings.rawNotificationRetentionDays.toLong())
            val ocrCutoff = now - TimeUnit.DAYS.toMillis(settings.rawOcrRetentionDays.toLong())
            val emailCutoff = now - TimeUnit.DAYS.toMillis(30)
            val aiChatCutoff = now - TimeUnit.DAYS.toMillis(30)
            val diagnosticsCutoff = now - TimeUnit.DAYS.toMillis(30)

            // PRIV-441-12: Use injectable RetentionRegistry instead of inline list
            val allTargets = retentionRegistry.allTargets().toList()
            // P8-PR1 (NEW-P8-002): Order targets deterministically so checkpoint works.
            val orderedTargets = allTargets.sortedBy { it.name }
            val results = mutableListOf<RetentionPurgeResult>()

            // Determine which targets to skip based on checkpoint
            var skipTargets = resumeFrom != null

            for (target in orderedTargets) {
                // P8-PR1 (NEW-P8-002): If resuming, skip targets that were already completed
                if (skipTargets) {
                    if (target.name == resumeFrom) {
                        skipTargets = false // This target was incomplete, process it now
                    } else {
                        Log.d(TAG, "Skipping already-completed target: ${target.name}")
                        continue
                    }
                }

                val cutoff = when (target.name) {
                    "raw_notifications" -> notificationCutoff
                    "scanned_receipts.rawOcrText" -> ocrCutoff
                    "email_receipt_sources" -> emailCutoff
                    "ai_chat_messages" -> aiChatCutoff
                    "notification_intake" -> notificationCutoff
                    "pipeline_diagnostic_events" -> diagnosticsCutoff
                    "ai_artifacts" -> now
                    else -> now - TimeUnit.DAYS.toMillis(30)
                }

                ctx.checkpoint("retention_${target.name}")

                // P8-PR1 (NEW-P8-006): Catch per-target purge failures so a single
                // failing target does not prevent other targets from being processed.
                val result = try {
                    target.purge(cutoff)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "RetentionTarget[${target.name}] purge threw — continuing", e)
                    val isTransient = isTransientFailure(e)
                    RetentionPurgeResult(
                        targetName = target.name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "${if (isTransient) "TRANSIENT" else "PERMANENT"}: ${e.message}",
                        isTransient = isTransient
                    )
                }
                results += result

                if (result.success) {
                    markTargetComplete(prefs, target.name)
                } else {
                    markTargetFailed(prefs, target.name)
                    Log.w(TAG, "RetentionTarget[${target.name}] purge reported failure: ${result.errorMessage}")

                    // Emit diagnostic for failure
                    try {
                        diagnosticEventWriter.emit(DiagnosticEvent(
                            pipeline = AppPipeline.PRIVACY,
                            stage = "retention_purge",
                            outcome = if (result.isTransient) EventOutcome.FAILED_RETRYABLE else EventOutcome.FAILED_FINAL,
                            entityType = "RetentionTarget",
                            entityId = null,
                            metadata = SafeEventMetadata.builder()
                                .put("target", target.name)
                                .put("transient", result.isTransient)
                                .put("error", result.errorMessage)
                                .build()
                        ))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed to write retention diagnostic", e)
                    }
                }
            }

            // P8-PR1 (NEW-P8-002): Clear checkpoint once all targets are done
            clearCheckpoint(prefs)

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

            val failedTargets = results.filter { !it.success }
            val anyFailure = failedTargets.isNotEmpty()
            val anyTransient = failedTargets.any { it.isTransient }

            if (anyFailure) {
                val failedNames = failedTargets.map { it.targetName }
                Log.w(TAG, "Data retention worker completed with PARTIAL failures: $failedNames")
            } else {
                Log.d(TAG, "Data retention worker completed: notifications=$notifCount ocr=$ocrCount")
            }

            // Report partial failure counts to run context
            ctx.addRowsUpdated(results.sumOf { it.rowsPurged })

            // If any transient failure occurred, trigger retry
            if (anyTransient) {
                val transientNames = failedTargets.filter { it.isTransient }.map { it.targetName }
                Log.w(TAG, "Transient failures detected in targets: $transientNames — requesting retry")
                throw RetryableWorkerException("RETENTION_PARTIAL_FAILURE: $transientNames")
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * Classifies whether a given exception represents a transient (retryable) failure
     * or a permanent one. Transient failures include I/O problems, SQLite locking
     * issues, and timeouts.
     */
    private fun isTransientFailure(e: Exception): Boolean = when {
        e is java.io.IOException -> true
        e.message?.contains("database is locked", ignoreCase = true) == true -> true
        e.message?.contains("SQLITE_BUSY", ignoreCase = true) == true -> true
        e.message?.contains("timeout", ignoreCase = true) == true -> true
        else -> false
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

    // ── P8-PR1 (NEW-P8-002): Checkpoint helpers ──────────────────────

    private fun checkpointPrefs(): SharedPreferences =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun markTargetComplete(prefs: SharedPreferences, targetName: String) {
        prefs.edit().putBoolean("${CHECKPOINT_PREFIX}$targetName", true).commit()
    }

    private fun markTargetFailed(prefs: SharedPreferences, targetName: String) {
        prefs.edit().putBoolean("${CHECKPOINT_PREFIX}${targetName}_failed", true).commit()
    }

    /**
     * Returns the name of the first target that is not marked complete,
     * or `null` if all targets are complete (no checkpoint needed).
     */
    private fun getLastIncompleteTarget(prefs: SharedPreferences): String? {
        val allTargets = retentionRegistry.allTargets().sortedBy { it.name }
        // A checkpoint is active only if at least one target is marked complete
        // and the overall run is not cleared (no CLEARED sentinel).
        if (prefs.getBoolean(PREFS_CLEARED_KEY, false)) return null

        var foundComplete = false
        for (target in allTargets) {
            val key = "${CHECKPOINT_PREFIX}${target.name}"
            val isComplete = prefs.getBoolean(key, false)
            if (!isComplete) {
                // If we've seen at least one completed target, this is the resume point
                if (foundComplete) return target.name
                // Otherwise no checkpoint is active yet
                return null
            }
            foundComplete = true
        }

        // All targets complete — no resume needed
        return null
    }

    /** Clears the checkpoint once all targets have been processed successfully. */
    private fun clearCheckpoint(prefs: SharedPreferences) {
        prefs.edit().clear().commit()
    }

    companion object {
        const val TAG = "DataRetentionWorker"
        const val WORK_NAME = "data_retention"
        private const val PAGE_SIZE = 100

        private const val PREFS_NAME = "data_retention_checkpoint"
        private const val CHECKPOINT_PREFIX = "completed_"
        private const val PREFS_CLEARED_KEY = "_cleared"

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
