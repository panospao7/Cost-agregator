package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.yourname.expensetracker.data.ai.worker.DailyBriefingWorker
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages maintenance mode for database restore operations.
 *
 * When activated, this pauses all 7 background workers and blocks notification
 * ingestion to ensure no writes occur during the restore process.
 *
 * State is persisted in [SharedPreferences] so it survives process death.
 */
@Singleton
class RestoreMaintenanceMode @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Mode enum ─────────────────────────────────────────────────

    enum class Mode(val label: String) {
        NORMAL("normal"),
        BACKUP_EXPORTING("backup_exporting"),
        RESTORE_PREPARING("restore_preparing"),
        RESTORE_STAGING("restore_staging"),
        RESTORE_SWAPPING("restore_swapping"),
        RESTORE_VERIFYING("restore_verifying"),
        RESTORE_ROLLING_BACK("restore_rolling_back"),
        RESTORE_COMPLETE_RESTART_REQUIRED("restore_complete_restart_required")
    }

    // ── State tracking ────────────────────────────────────────────

    /**
     * Check whether writes should be allowed. If mode != NORMAL, writes
     * should be blocked (except during BACKUP_EXPORTING where policy may vary).
     */
    fun isWritesAllowed(): Boolean {
        val current = readMode()
        return current == Mode.NORMAL || current == Mode.BACKUP_EXPORTING
    }

    /**
     * Returns the current maintenance mode.
     */
    fun currentMode(): Mode = readMode()

    // ── Enter / Exit ──────────────────────────────────────────────

    /**
     * Enters the specified maintenance mode, pausing workers and blocking
     * notification ingestion.
     */
    fun enter(mode: Mode) {
        Timber.w("Maintenance mode: entering %s", mode.label)
        writeMode(mode)
        pauseAllWorkers()
        Timber.d("Maintenance mode: entered %s", mode.label)
    }

    /**
     * Exits maintenance mode, re-enabling workers and notification ingestion.
     *
     * If [forceRestartRequired] is true, the mode transitions to
     * [Mode.RESTORE_COMPLETE_RESTART_REQUIRED] instead of [Mode.NORMAL],
     * keeping writes blocked until the app restarts.
     */
    fun exit(forceRestartRequired: Boolean = false) {
        val targetMode = if (forceRestartRequired) {
            Mode.RESTORE_COMPLETE_RESTART_REQUIRED
        } else {
            Mode.NORMAL
        }
        Timber.w("Maintenance mode: exiting to %s", targetMode.label)
        writeMode(targetMode)
        if (targetMode == Mode.NORMAL) {
            // BAK-NE: Reschedule background workers immediately instead of
            // waiting for next app start, so that critical jobs (data retention,
            // receipt matching, etc.) resume without delay after restore.
            scheduleAllWorkers()
            Timber.d("Maintenance mode: workers rescheduled")
        } else {
            Timber.d("Maintenance mode: writes remain blocked until app restart")
        }
    }

    /**
     * Resets maintenance mode to NORMAL (used after app restart).
     */
    fun reset() {
        Timber.w("Maintenance mode: resetting to NORMAL")
        writeMode(Mode.NORMAL)
    }

    // ── Worker control ────────────────────────────────────────────

    /**
     * Cancels all 7 background workers by their unique work names.
     * All workers are enqueued via [enqueueUniquePeriodicWork] or [enqueueUniqueWork]
     * with their [WorkerSpec.name] as the unique name, so [cancelUniqueWork] is the
     * correct API to pause them.
     */
    private fun pauseAllWorkers() {
        val workManager = WorkManager.getInstance(context)
        val workerNames = WorkerSpec.DEFAULTS.keys
        for (name in workerNames) {
            workManager.cancelUniqueWork(name)
            Timber.d("Cancelled unique worker: %s", name)
        }
    }

    /**
     * Reschedules all background workers after exiting maintenance mode.
     *
     * BAK-NE: This ensures critical jobs resume without waiting for the next
     * app start. Each worker's schedule() companion method reads its interval
     * and constraints from [WorkerSpec.DEFAULTS].
     */
    private fun scheduleAllWorkers() {
        // Workers that provide a companion schedule() method are called here.
        // Each schedule() is wrapped in runCatching to isolate failures.
        val application = context
        runCatching {
            com.yourname.expensetracker.data.location.LocationBackfillWorker.schedule(application)
        }
        runCatching {
            com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker.schedule(application)
        }
        runCatching {
            com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker.schedule(application)
        }
        runCatching {
            com.yourname.expensetracker.data.privacy.DataRetentionWorker.schedule(application)
        }
        runCatching {
            com.yourname.expensetracker.service.reminder.BillReminderWorker.schedule(application)
        }
        runCatching {
            com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker.schedule(application)
        }
        runCatching {
            // ai_daily_briefing uses WorkerSpecScheduler.scheduleAtMidnight (not companion schedule())
            WorkerSpecScheduler.scheduleAtMidnight(
                context, "ai_daily_briefing",
                DailyBriefingWorker::class.java
            )
        }
    }

    // ── Persistence ───────────────────────────────────────────────

    private fun readMode(): Mode {
        val name = prefs.getString(KEY_MAINTENANCE_MODE, Mode.NORMAL.name)
            ?: Mode.NORMAL.name
        return try {
            Mode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            Mode.NORMAL
        }
    }

    private fun writeMode(mode: Mode) {
        prefs.edit().putString(KEY_MAINTENANCE_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "restore_maintenance_mode"
        private const val KEY_MAINTENANCE_MODE = "current_mode"
    }
}
