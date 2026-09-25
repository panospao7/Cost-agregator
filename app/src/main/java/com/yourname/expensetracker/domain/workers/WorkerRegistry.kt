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
import kotlinx.coroutines.CancellationException
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
        val schedule: (Context, TimeProvider) -> ScheduleResult
    )

    data class ScheduleAllResult(
        val results: List<ScheduleResult>
    ) {
        val failedWorkerNames: List<String>
            get() = results.filterNot { it.scheduled }.map { it.workerName }

        fun confirms(expectedWorkerNames: Set<String>): Boolean {
            val resultNames = results.map { it.workerName }
            return results.size == expectedWorkerNames.size &&
                resultNames.size == resultNames.toSet().size &&
                resultNames.toSet() == expectedWorkerNames &&
                results.all { it.scheduled }
        }
    }

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
        Entry("location_backfill") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "location_backfill", LocationBackfillWorker::class.java)
        },
        Entry("merchant_key_backfill") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "merchant_key_backfill", MerchantKeyBackfillWorker::class.java)
        },
        Entry("warranty_expiration_check") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "warranty_expiration_check", WarrantyExpirationWorker::class.java)
        },
        Entry("data_retention") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "data_retention", DataRetentionWorker::class.java)
        },
        Entry("bill_reminder_periodic") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "bill_reminder_periodic", BillReminderWorker::class.java)
        },
        Entry("receipt_matching") { context, _ ->
            WorkerSpecScheduler.scheduleFromSpec(context, "receipt_matching", ReceiptMatchingWorker::class.java)
        },
        Entry("ai_daily_briefing") { context, timeProvider ->
            WorkerSpecScheduler.scheduleAtMidnight(context, "ai_daily_briefing", DailyBriefingWorker::class.java, timeProvider)
        }
    )

    /**
     * Schedules all registered workers.
     *
     * Ordinary failures are recorded so one worker does not prevent later workers
     * from being scheduled. Cancellation is always propagated to the caller.
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
    fun scheduleAll(
        context: Context,
        timeProvider: TimeProvider,
        diagnosticEventWriter: DiagnosticEventWriter? = null
    ): ScheduleAllResult = scheduleEntries(
        context = context,
        timeProvider = timeProvider,
        entriesToSchedule = entries,
        diagnosticEventWriter = diagnosticEventWriter
    )

    internal fun scheduleEntries(
        context: Context,
        timeProvider: TimeProvider,
        entriesToSchedule: List<Entry>,
        diagnosticEventWriter: DiagnosticEventWriter? = null
    ): ScheduleAllResult {
        val results = mutableListOf<ScheduleResult>()

        for (entry in entriesToSchedule) {
            val result = try {
                entry.schedule(context, timeProvider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failureCode = "WORKER_SCHEDULE_EXCEPTION"
                Timber.w(
                    "WorkerRegistry: failed to schedule %s (%s)",
                    entry.specName,
                    e::class.java.simpleName
                )
                ScheduleResult(
                    workerName = entry.specName,
                    scheduled = false,
                    policyUsed = "",
                    versionChanged = false,
                    error = failureCode
                )
            }
            results += result
        }

        val aggregate = ScheduleAllResult(results.toList())
        val failedWorkers = aggregate.failedWorkerNames
        val writer = diagnosticEventWriter
        if (writer == null || failedWorkers.isEmpty()) return aggregate

        val metadata = SafeEventMetadata.builder()
            .put("totalWorkers", entriesToSchedule.size)
            .put("successCount", results.count { it.scheduled })
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Diagnostics are best-effort; suppress emit failures.
            }
        }
        return aggregate
    }
}
