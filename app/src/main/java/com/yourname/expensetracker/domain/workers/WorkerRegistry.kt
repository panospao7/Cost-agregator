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
import com.yourname.expensetracker.domain.util.TimeProvider
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
        /**
         * Scheduling function called at startup and after restore exit.
         *
         * @param timeProvider The single source of "now" (G-TIME-01), forwarded to
         *   entries that need it (e.g. midnight-aligned scheduling). Entries that
         *   don't need time ignore it.
         */
        val schedule: (Context, TimeProvider) -> Unit
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
        Entry("location_backfill") { context, _ -> LocationBackfillWorker.schedule(context) },
        Entry("merchant_key_backfill") { context, _ -> MerchantKeyBackfillWorker.schedule(context) },
        Entry("warranty_expiration_check") { context, _ -> WarrantyExpirationWorker.schedule(context) },
        Entry("data_retention") { context, _ -> DataRetentionWorker.schedule(context) },
        Entry("bill_reminder_periodic") { context, _ -> BillReminderWorker.schedule(context) },
        Entry("receipt_matching") { context, _ -> ReceiptMatchingWorker.schedule(context) },
        Entry("ai_daily_briefing") { context, timeProvider ->
            WorkerSpecScheduler.scheduleAtMidnight(context, "ai_daily_briefing", DailyBriefingWorker::class.java, timeProvider)
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
     * @param timeProvider The single source of "now" (G-TIME-01), forwarded to
     *   each [Entry.schedule] so midnight-aligned entries compute delays from the
     *   same injected clock as the rest of the app.
     * @param diagnosticEventWriter Optional writer for emitting summary diagnostic events.
     */
    fun scheduleAll(context: Context, timeProvider: TimeProvider, diagnosticEventWriter: DiagnosticEventWriter? = null) {
        val failedWorkers = mutableListOf<String>()
        var successCount = 0

        for (entry in entries) {
            val caught = runCatching { entry.schedule(context, timeProvider) }
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
