package com.yourname.expensetracker.domain.workers

import android.content.Context
import com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.service.reminder.BillReminderWorker
import com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker

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
     */
    fun scheduleAll(context: Context) {
        for (entry in entries) {
            runCatching { entry.schedule(context) }
        }
    }
}
