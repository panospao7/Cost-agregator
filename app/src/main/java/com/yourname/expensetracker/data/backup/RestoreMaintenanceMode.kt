package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext private val context: Context,
    private val workerLeaseRegistry: dagger.Lazy<WorkerLeaseRegistry>,
    private val timeProvider: TimeProvider
) {
    /** Test-only constructor — uses a no-op WorkerLeaseRegistry. */
    constructor(context: Context, timeProvider: TimeProvider) : this(
        context,
        dagger.Lazy { com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController().let {
            object : WorkerLeaseRegistry {
                override suspend fun acquire(workerName: String) = object : com.yourname.expensetracker.domain.workers.WorkerLease {
                    override val leaseId: String = "restore-mode-noop"
                    override suspend fun checkpoint(operation: String) {}
                    override fun close() {}
                }
                override suspend fun requestStopAll(reason: String) {}
                override suspend fun awaitNoActiveWorkers(timeoutMs: Long) = true
                override fun isStopRequested() = false
                override fun resetStopFlag() {}
            }
        }},
        timeProvider
    )

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
        ASSETS_RESTORING("assets_restoring"),
        RESETTING_DATABASE("resetting_database"),
        RESTORE_COMPLETE_RESTART_REQUIRED("restore_complete_restart_required"),
        CRITICAL_RECOVERY_REQUIRED("critical_recovery_required")
    }

    // ── Observable mode flow ──────────────────────────────────────

    private val _modeFlow = MutableStateFlow(readMode())
    val modeFlow: StateFlow<Mode> = _modeFlow.asStateFlow()

    /** Derived operational state for the app shell to observe. */
    val operationalStateFlow: StateFlow<AppOperationalState> get() = _operationalStateFlow
    private val _operationalStateFlow = kotlinx.coroutines.flow.MutableStateFlow(toOperationalState(readMode()))

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
     * Enters [Mode.CRITICAL_RECOVERY_REQUIRED] for unrecoverable states
     * (rollback failure, corrupt safety backup, etc.).
     * Writes remain blocked until manual intervention and process restart.
     */
    fun enterCriticalRecoveryRequired(reason: String) {
        Timber.e("Maintenance mode: entering CRITICAL_RECOVERY_REQUIRED — %s", reason)
        // P7-PR4 (NEW-P7-003): Single atomic commit for mode + reason + timestamp.
        // Previously two separate commits; crash between them left inconsistent state.
        prefs.edit()
            .putString(KEY_CRITICAL_REASON, reason)
            .putLong(KEY_CRITICAL_TIMESTAMP, timeProvider.now())
            .putString(KEY_MAINTENANCE_MODE, Mode.CRITICAL_RECOVERY_REQUIRED.name)
            .commit()
        _modeFlow.value = Mode.CRITICAL_RECOVERY_REQUIRED
        _operationalStateFlow.value = toOperationalState(Mode.CRITICAL_RECOVERY_REQUIRED)
        pauseAllWorkers()
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
            // Reset the worker stop flag so future workers can run normally
            workerLeaseRegistry.get().resetStopFlag()
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
        WorkerRegistry.scheduleAll(application, timeProvider)
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
        // Use commit() (synchronous) instead of apply() (async) to ensure
        // mode persists to disk before any subsequent restore operations.
        // This prevents the mode from reverting on process death.
        prefs.edit().putString(KEY_MAINTENANCE_MODE, mode.name).commit()
        _modeFlow.value = mode
        _operationalStateFlow.value = toOperationalState(mode)
    }

    private fun toOperationalState(mode: Mode): AppOperationalState = when (mode) {
        Mode.NORMAL -> AppOperationalState.Normal
        Mode.BACKUP_EXPORTING -> AppOperationalState.BackupExporting
        Mode.RESTORE_COMPLETE_RESTART_REQUIRED -> AppOperationalState.RestartRequiredAfterRestore
        Mode.CRITICAL_RECOVERY_REQUIRED -> AppOperationalState.CriticalRecoveryRequired(
            reason = prefs.getString(KEY_CRITICAL_REASON, null),
            timestamp = prefs.getLong(KEY_CRITICAL_TIMESTAMP, 0L).takeIf { it > 0L }
        )
        else -> AppOperationalState.RestoreInProgress(mode)
    }

    companion object {
        private const val PREFS_NAME = "restore_maintenance_mode"
        private const val KEY_MAINTENANCE_MODE = "current_mode"
        private const val KEY_CRITICAL_REASON = "critical_recovery_reason"
        private const val KEY_CRITICAL_TIMESTAMP = "critical_recovery_timestamp"
    }
}
