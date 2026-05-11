package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
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
     * Check whether writes should be allowed.
     *
     * P7-P1-02/P1-03: Writes are only allowed in NORMAL mode. All other modes
     * (including BACKUP_EXPORTING during snapshot acquisition, and all restore
     * stages) block writes to guarantee data consistency.
     */
    fun isWritesAllowed(): Boolean {
        return readMode() == Mode.NORMAL
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
     * P7-P1-07: Uses [WorkerRegistry.scheduleAll] — the single source of truth
     * for which workers exist and how they are scheduled. Previously this was a
     * hardcoded list that could diverge from [WorkerSpec.DEFAULTS].
     */
    private fun scheduleAllWorkers() {
        val application = context.applicationContext
        WorkerRegistry.scheduleAll(application)
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
