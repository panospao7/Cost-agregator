// P8-P1-06: Retention scope expanded to cover AI artifacts and email receipt sources.
// Remaining gaps: chat messages (AiChatMessageDao), debug diagnostics (ServiceDiagnostics).
// RP-16 16-C: boolean `completed_<target>` checkpoint replaced by versioned per-target
// records (PENDING / COMPLETED(rowsPurged, auditEmitted) / FAILED(controlledErrorCode,
// transient, attemptMetadata)) with exactly-once audit identity and per-record cleanup.

package com.yourname.expensetracker.data.privacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
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
 * Raw OCR data on [ScannedReceipt.rawOcrText] is nulled out for receipts
 * whose `createdAt` is older than the configured retention period AND that
 * have not already been purged. This ensures that raw OCR data is not
 * retained indefinitely and respects the user's privacy retention preferences.
 *
 * Runs daily via [PeriodicWorkRequest] and is safe to call multiple times:
 * - Only rows whose `rawContentPurgedAt` / `rawOcrTextPurgedAt` IS NULL are candidates.
 * - After purging, the column is set to the current timestamp so the row is not
 *   processed again on subsequent runs.
 * - Audit events are written for the count of purged rows per category.
 *
 * ## RP-16 16-C: Checkpoint state machine
 * Per-target progress is tracked in [RetentionCheckpointStore] (v2 records):
 * - PENDING → COMPLETED(rowsPurged, auditEmitted) | FAILED(controlledErrorCode, transient, attempts).
 * - Legacy boolean `completed_<target>` checkpoints are read compatibly (as
 *   booleans, never as counts) when no v2 record exists.
 * - Audits for audit-required targets are emitted exactly once per
 *   (target, cutoff day bucket) via [PrivacyAuditDao.countAuditEventsByKey].
 * - Cleanup is per record, only after the target's desired durable states
 *   (COMPLETED + emitted audit) are reached. There is NO blanket
 *   preferences clear.
 * - Permanent (non-transient) target failures do not abort the run: the run
 *   reports them via ctx counters (failedTargetCount / partialFailureCount)
 *   and the FAILED record persists so the NEXT scheduled run re-attempts the
 *   target. Transient failures abort the run as RETRY; records persist and a
 *   retry resumes (completed targets skipped, pending audits emitted).
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
    private val diagnosticEventWriter: DiagnosticEventWriter,
    // RP-02 U-004: barrier ownership for the post-purge audit writes. The guard's
    // earlier entry/checkpoint check is NOT ownership of these later writes.
    private val writeBarrier: DatabaseWriteBarrier
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Data retention worker started")

        // P8-PR1 (NEW-P8-002): Initialise checkpoint prefs — if we crashed mid-run
        // on a previous attempt, resume from the last durable per-target record.
        val prefs = checkpointPrefs()

        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "data_retention",
                requiredCapabilities = emptyList(),
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

            val store = RetentionCheckpointStore(prefs)
            val auditDao = appDatabase.privacyAuditDao()

            fun cutoffFor(targetName: String): Long = when (targetName) {
                "raw_notifications" -> notificationCutoff
                "scanned_receipts.rawOcrText" -> ocrCutoff
                "email_receipt_sources" -> emailCutoff
                "ai_chat_messages" -> aiChatCutoff
                "notification_intake" -> notificationCutoff
                "pipeline_diagnostic_events" -> diagnosticsCutoff
                "ai_artifacts" -> now
                else -> now - TimeUnit.DAYS.toMillis(30)
            }

            fun retentionDaysFor(targetName: String): Int = when (targetName) {
                "raw_notifications", "notification_intake" -> settings.rawNotificationRetentionDays
                "scanned_receipts.rawOcrText" -> settings.rawOcrRetentionDays
                else -> 30
            }

            // PRIV-441-12: Use injectable RetentionRegistry instead of inline list
            val allTargets = retentionRegistry.allTargets().toList()
            // P8-PR1 (NEW-P8-002): Order targets deterministically so checkpoint works.
            // GR-14u49: explicit element-type annotation so the static callgraph
            // resolves the loop variable's receiver (GR-08k1 precedent).
            val orderedTargets: List<RetentionTarget> = allTargets.sortedBy { it.name }
            val results = mutableListOf<RetentionPurgeResult>()

            // RP-16 16-C: v2 records win; legacy boolean checkpoints are consulted
            // only when no v2 record exists (booleans read as booleans, never counts).
            val legacyResumeFrom: String? = if (!store.hasAnyRecord()) {
                store.legacyResumePoint(orderedTargets.map { it.name })
            } else {
                null
            }
            if (legacyResumeFrom != null) {
                Log.w(TAG, "Resuming from legacy checkpoint target: $legacyResumeFrom")
            }
            var legacySkip = legacyResumeFrom != null

            for (target in orderedTargets) {
                val record = store.load(target.name)
                val cutoff = cutoffFor(target.name)

                // v2 skip: durable COMPLETED with durable audit (or no audit required).
                if (record != null &&
                    record.state == RetentionCheckpointState.COMPLETED &&
                    (record.auditEmitted || !AUDIT_REQUIRED_TARGETS.contains(target.name))
                ) {
                    Log.d(TAG, "Skipping already-completed target: ${target.name}")
                    continue
                }

                // v2 resume audit: purge durable, audit not durable yet → emit exactly-once
                // audit and finish the record. The purge itself is NOT repeated.
                if (record != null &&
                    record.state == RetentionCheckpointState.COMPLETED &&
                    AUDIT_REQUIRED_TARGETS.contains(target.name) &&
                    !record.auditEmitted
                ) {
                    emitRetentionAudit(store, record, auditDao, ctx)
                    continue
                }

                // P8-PR1 (NEW-P8-002): legacy prefix-resume — skip targets before the
                // first incomplete one (booleans never reinterpreted as counts).
                if (legacySkip) {
                    if (target.name == legacyResumeFrom) {
                        legacySkip = false // This target was incomplete, process it now
                    } else {
                        Log.d(TAG, "Skipping already-completed (legacy) target: ${target.name}")
                        continue
                    }
                }

                ctx.checkpoint("retention_${target.name}")

                val attempts = (record?.attempts ?: 0) + 1

                // PENDING before the purge: a crash mid-purge leaves a record that a
                // resume re-processes (purges are idempotent).
                val pendingSaved = store.save(
                    RetentionCheckpointRecord(
                        targetName = target.name,
                        state = RetentionCheckpointState.PENDING,
                        attempts = attempts,
                        cutoffMs = cutoff,
                        retentionDays = retentionDaysFor(target.name),
                        updatedAtMs = timeProvider.now()
                    )
                )
                if (!pendingSaved) {
                    // Checkpoint write failure is a safe non-durable state: the purge is
                    // idempotent, so re-processing on a later run never double-reports.
                    Log.w(TAG, "Checkpoint PENDING save failed for target ${target.name}")
                    ctx.addErrors()
                }

                // P8-PR1 (NEW-P8-006): Catch per-target purge failures so a single
                // failing target does not prevent other targets from being processed.
                val result = try {
                    target.purge(cutoff)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "RetentionTarget[${target.name}] purge threw — continuing", e)
                    val isTransient = isTransientFailure(e)
                    val failureCode = if (isTransient)
                        DiagnosticReasonCode.WORKER_TRANSIENT_ERROR.name
                        else DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name
                    RetentionPurgeResult(
                        targetName = target.name,
                        rowsPurged = 0,
                        success = false,
                        errorCode = failureCode,
                        errorClass = e.javaClass.simpleName,
                        isTransient = isTransient
                    )
                }
                results += result

                if (result.success) {
                    val completedRecord = RetentionCheckpointRecord(
                        targetName = target.name,
                        state = RetentionCheckpointState.COMPLETED,
                        rowsPurged = result.rowsPurged,
                        auditEmitted = false,
                        attempts = attempts,
                        cutoffMs = cutoff,
                        retentionDays = retentionDaysFor(target.name),
                        updatedAtMs = timeProvider.now()
                    )
                    if (!store.save(completedRecord)) {
                        Log.w(TAG, "Checkpoint COMPLETED save failed for target ${target.name}")
                        ctx.addErrors()
                    } else if (AUDIT_REQUIRED_TARGETS.contains(target.name)) {
                        emitRetentionAudit(store, completedRecord, auditDao, ctx)
                    }
                } else {
                    val failedRecord = RetentionCheckpointRecord(
                        targetName = target.name,
                        state = RetentionCheckpointState.FAILED,
                        errorCode = result.errorCode ?: DiagnosticReasonCode.WORKER_UNHANDLED_EXCEPTION.name,
                        isTransient = result.isTransient,
                        attempts = attempts,
                        cutoffMs = cutoff,
                        retentionDays = retentionDaysFor(target.name),
                        updatedAtMs = timeProvider.now()
                    )
                    if (!store.save(failedRecord)) {
                        Log.w(TAG, "Checkpoint FAILED save failed for target ${target.name}")
                        ctx.addErrors()
                    }
                    Log.w(TAG, "RetentionTarget[${target.name}] purge reported failure: ${result.errorCode}/${result.errorClass}")

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
                                .put("failureCode", result.errorCode)
                                .put("errorClass", result.errorClass)
                                .build()
                        ))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Failed to write retention diagnostic", e)
                    }
                }
            }

            // Log per-target counts
            for (result in results) {
                if (result.rowsPurged > 0 || !result.success) {
                    Log.d(TAG, "RetentionTarget[${result.targetName}]: purged=${result.rowsPurged} success=${result.success} errorCode=${result.errorCode} errorClass=${result.errorClass}")
                }
            }

            val failedTargets = results.filter { !it.success }
            val anyFailure = failedTargets.isNotEmpty()
            val anyTransient = failedTargets.any { it.isTransient }

            if (anyFailure) {
                val failedNames = failedTargets.map { it.targetName }
                Log.w(TAG, "Data retention worker completed with PARTIAL failures: $failedNames")
            } else {
                Log.d(TAG, "Data retention worker completed")
            }

            // Report partial/failed target counts to the durable run counters (D9).
            ctx.addRowsUpdated(results.sumOf { it.rowsPurged })
            if (anyFailure) {
                ctx.setFailedTargetCount(failedTargets.size)
                ctx.setPartialFailureCount(failedTargets.count { it.isTransient })
            }

            // RP-16 16-C: per-record cleanup — only when the run completed without a
            // transient abort, and only for targets whose desired durable states
            // (COMPLETED + emitted audit) are reached. FAILED records persist so the
            // next scheduled run re-attempts those targets; PENDING records persist
            // so a resume re-processes them. No blanket preferences clear.
            if (!anyTransient) {
                for (name in store.allRecordedTargetNames()) {
                    val rec = store.load(name) ?: continue
                    if (rec.state == RetentionCheckpointState.COMPLETED &&
                        (rec.auditEmitted || !AUDIT_REQUIRED_TARGETS.contains(name))
                    ) {
                        store.remove(name)
                    }
                }
            }

            // If any transient failure occurred, trigger retry (records persist so a
            // retry resumes instead of re-purging completed targets).
            if (anyTransient) {
                val transientNames = failedTargets.filter { it.isTransient }.map { it.targetName }
                Log.w(TAG, "Transient failures detected in targets: $transientNames — requesting retry")
                throw RetryableWorkerException(DiagnosticReasonCode.WORKER_RETRYABLE_ERROR.name, message = "RETENTION_PARTIAL_FAILURE: $transientNames")
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * RP-16 16-C: Emit the privacy audit for an audit-required COMPLETED record
     * exactly once, then mark the record's audit as durable.
     *
     * Exactly-once identity: "[retention-audit:<target>:<cutoff day bucket>]" is
     * appended to the audit reason and checked via
     * [PrivacyAuditDao.countAuditEventsByKey] before insert, so a crash between
     * the audit insert and the checkpoint update can never duplicate the audit.
     *
     * Returns true only when the record's audit state is durable (or the target
     * needs no audit); a failure leaves the record un-updated (safe non-durable
     * state + error counter), never a false "audit done".
     */
    private suspend fun emitRetentionAudit(
        store: RetentionCheckpointStore,
        record: RetentionCheckpointRecord,
        auditDao: PrivacyAuditDao,
        ctx: com.yourname.expensetracker.domain.workers.WorkerRunContext
    ): Boolean {
        // Zero-row purges carry no audit-worthy information and the legacy worker
        // never audited them; the audit state is durable by vacuity.
        if (record.rowsPurged <= 0) {
            return store.save(record.copy(auditEmitted = true, updatedAtMs = timeProvider.now()))
        }
        return try {
            // RP-02 U-004: per-insert barrier check — a mode flip after the purge
            // must still block this audit write.
            writeBarrier.checkWritesAllowed("privacy.retention.audit")
            val auditKey = auditIdentity(record.targetName, record.cutoffMs)
            val alreadyEmitted = auditDao.countAuditEventsByKey(AUDIT_CALLER, auditKey) > 0
            if (!alreadyEmitted) {
                val (capability, reason, contextJson) = auditContentFor(record)
                auditDao.insert(PrivacyAuditEvent(
                    capability = capability,
                    decision = "ALLOWED",
                    reason = "$reason [$auditKey]",
                    context = contextJson,
                    timestampMs = timeProvider.now(),
                    caller = AUDIT_CALLER
                ))
            }
            store.save(record.copy(auditEmitted = true, updatedAtMs = timeProvider.now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            // Barrier denial during maintenance must follow the guard's blockedPolicy
            // contract (RP-02 U-004) — never swallowed. The record stays
            // COMPLETED/auditEmitted=false so a later resume re-emits the audit.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Retention audit emission failed for target ${record.targetName}", e)
            ctx.addErrors()
            false
        }
    }

    /** Controlled audit payload (counts + retention days only — never raw data). */
    private fun auditContentFor(record: RetentionCheckpointRecord): Triple<String, String, String> {
        return when (record.targetName) {
            "raw_notifications" -> {
                val days = record.retentionDays ?: 0
                Triple(
                    PrivacyCapability.RAW_NOTIFICATION_RETENTION.name,
                    "Purged ${record.rowsPurged} raw notifications older than $days days",
                    "{\"purgedCount\": ${record.rowsPurged}, \"retentionDays\": $days}"
                )
            }
            else -> {
                val days = record.retentionDays ?: 0
                Triple(
                    PrivacyCapability.RAW_OCR_RETENTION.name,
                    "Purged raw OCR text from ${record.rowsPurged} receipts older than $days days",
                    "{\"purgedCount\": ${record.rowsPurged}, \"retentionDays\": $days}"
                )
            }
        }
    }

    private fun auditIdentity(targetName: String, cutoffMs: Long?): String {
        val dayBucket = (cutoffMs ?: 0L) / DAY_MS
        return "retention-audit:$targetName:$dayBucket"
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

    private fun checkpointPrefs(): SharedPreferences =
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val TAG = "DataRetentionWorker"
        const val WORK_NAME = "data_retention"
        private const val PREFS_NAME = "data_retention_checkpoint"

        /** Targets whose purge requires a durable privacy audit. */
        internal val AUDIT_REQUIRED_TARGETS = setOf("raw_notifications", "scanned_receipts.rawOcrText")

        internal const val AUDIT_CALLER = "DataRetentionWorker"
        private const val DAY_MS = 24L * 60L * 60L * 1000L

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
