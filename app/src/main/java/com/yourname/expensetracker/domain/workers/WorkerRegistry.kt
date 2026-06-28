package com.yourname.expensetracker.domain.workers

import android.content.Context
import com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.service.reminder.BillReminderWorker
import com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Single source-of-truth registry for all background workers.
 *
 * P7-P1-07: Replaces the hardcoded worker lists in
 * [com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.scheduleAllWorkers]
 * and [com.yourname.expensetracker.startup.AppStartupCoordinator.scheduleStartupWork]
 * with a single registry that both pause (via [WorkerSpec.DEFAULTS.keys]) and
 * resume/schedule can derive from.
 *
 * ## Adding a new worker
 * 1. Add its spec to [WorkerSpec.DEFAULTS].
 * 2. Add an [Entry] to [entries] with the same name.
 * 3. The worker is automatically paused by [RestoreMaintenanceMode.pauseAllWorkers]
 *    and scheduled by [scheduleAll].
 *
 * ## Privacy gating
 * Privacy-setting changes do NOT cancel/reschedule workers by hardcoded name.
 * The mapping from privacy toggles to gated workers lives in
 * [PrivacyRuntimeWorkerPolicy]; [com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImpl]
 * reschedules re-enabled workers by looking up their [Entry.schedule] here, so a
 * disabled [WorkerSpec] is still honoured on reschedule.
 */
object WorkerRegistry {

    /** A registered worker with its scheduling function. */
    data class Entry(
        /** Must match a key in [WorkerSpec.DEFAULTS]. */
        val specName: String,
        /** Scheduling function called at startup and after restore exit. */
        val schedule: (Context) -> Unit
    )

    /**
     * Fire-and-forget scope for summary diagnostic emission. Diagnostics are best-effort
     * and must not block scheduling — failed emits are silently discarded.
     */
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * All registered workers in startup / restore-resume order.
     *
     * Every key here must also exist in [WorkerSpec.DEFAULTS] for the pause
     * side to match. The `ai_daily_briefing` worker uses
     * [WorkerSpecScheduler.scheduleAtMidnight] instead of a companion
     * `schedule()` method because it is midnight-aligned, not periodic.
     */
    val entries: List<Entry> = listOf(
        Entry("location_backfill") { LocationBackfillWorker.schedule(it) },
        Entry("merchant_key_backfill") { MerchantKeyBackfillWorker.schedule(it) },
        Entry("warranty_expiration_check") { WarrantyExpirationWorker.schedule(it) },
        Entry("data_retention") { DataRetentionWorker.schedule(it) },
        Entry("bill_reminder_periodic") { BillReminderWorker.schedule(it) },
        Entry("receipt_matching") { ReceiptMatchingWorker.schedule(it) },
        Entry("ai_daily_briefing") {
            WorkerSpecScheduler.scheduleAtMidnight(it, "ai_daily_briefing", DailyBriefingWorker::class.java)
        }
    )

    /**
     * Schedules all registered workers.
     *
     * Each [Entry.schedule] call is wrapped in a [runCatching] so one failure
     * does not prevent other workers from being scheduled.
     *
     * If [diagnosticEventWriter] is provided, a summary diagnostic event is
     * emitted after all entries have been scheduled, recording how many
     * succeeded and how many threw exceptions.
     *
     * @param context Application or activity context.
     * @param diagnosticEventWriter Optional writer for emitting summary diagnostic events.
     */
    fun scheduleAll(context: Context, diagnosticEventWriter: DiagnosticEventWriter? = null) {
        val failedWorkers = mutableListOf<String>()
        var successCount = 0

        for (entry in entries) {
            val caught = runCatching { entry.schedule(context) }
            if (caught.isSuccess) {
                successCount++
            } else {
                failedWorkers.add(entry.specName)
                Timber.w(caught.exceptionOrNull(), "WorkerRegistry: failed to schedule ${entry.specName}")
            }
        }

        val writer = diagnosticEventWriter ?: return
        if (failedWorkers.isEmpty()) return

        val metadata = SafeEventMetadata.builder()
            .put("totalWorkers", entries.size)
            .put("successCount", successCount)
            .put("failedCount", failedWorkers.size)
            .put("failedWorkers", failedWorkers.joinToString(","))
            .build()

        diagnosticScope.launch {
            try {
                writer.emit(
                    DiagnosticEvent(
                        pipeline = AppPipeline.WORKER,
                        stage = "schedule_all",
                        outcome = EventOutcome.FAILED_FINAL,
                        entityType = "WorkerRegistry",
                        entityId = null,
                        metadata = metadata
                    )
                )
            } catch (_: Exception) {
                // Diagnostics are best-effort; suppress emit failures.
            }
        }
    }
}
