package com.yourname.expensetracker.startup

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.service.reminder.BillReminderWorker
import com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val backgroundLifecycleObserver: AppBackgroundLifecycleObserver,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase,
    private val restoreJournal: RestoreJournal,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {

    fun initialize(application: Application) {
        configureDebugTools()

        // Check if a restore completed and the app needs a restart.
        // Must run BEFORE checkRestoreJournal() which resets the mode to NORMAL.
        if (restoreMaintenanceMode.currentMode() == RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED) {
            val prefs = application.getSharedPreferences(PREFS_RESTART_CHECK, Application.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_RESTART_REQUIRED, true).apply()
            Timber.w("Restore complete — restart required flag set")
        }

        checkRestoreJournal()
        registerLifecycleObserver()
        scheduleStartupWork(application)
        syncProactiveBriefingWork()
    }

    companion object {
        private const val PREFS_RESTART_CHECK = "app_restart_check"
        private const val KEY_RESTART_REQUIRED = "restore_complete_restart_required"
    }

    /**
     * Checks for a pending restore journal on startup and handles crash recovery.
     *
     * For destructive states (SWAPPING, VERIFYING), this actually restores the
     * safety backup to recover the live database rather than just logging.
     */
    private fun checkRestoreJournal() {
        when (val recovery = restoreJournal.checkAndRecover()) {
            is RestoreJournal.RecoveryResult.NoAction -> {
                // Normal startup — no journal found
            }

            is RestoreJournal.RecoveryResult.CompleteClean -> {
                Timber.w("Startup: found completed restore journal, cleaning up")
            }

            is RestoreJournal.RecoveryResult.CleanedNonDestructive -> {
                val state = recovery.entry.state
                Timber.w("Startup: cleaned up from non-destructive restore state: %s", state)
            }

            is RestoreJournal.RecoveryResult.RecoveredFromSwap -> {
                val entry = recovery.entry
                Timber.e("Startup: detected incomplete restore from state: %s", entry.state)

                // Attempt recovery from safety backup
                val safetyBackupPath = entry.safetyBackupPath
                if (safetyBackupPath != null) {
                    val safetyBackupFile = File(safetyBackupPath)
                    if (safetyBackupFile.exists() && safetyBackupFile.canRead()) {
                        val liveDbPath = entry.liveDbPath
                        if (liveDbPath != null) {
                            try {
                                val liveDbFile = File(liveDbPath)
                                val liveDbWalFile = File(liveDbPath + "-wal")
                                val liveDbShmFile = File(liveDbPath + "-shm")
                                val safetyWalFile = File(safetyBackupFile.parentFile, "${safetyBackupFile.name}-wal")
                                val safetyShmFile = File(safetyBackupFile.parentFile, "${safetyBackupFile.name}-shm")

                                // Remove potentially corrupt live DB files
                                liveDbFile.delete()
                                liveDbWalFile.delete()
                                liveDbShmFile.delete()

                                // Restore from safety backup
                                safetyBackupFile.inputStream().use { input ->
                                    liveDbFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (safetyWalFile.exists()) {
                                    safetyWalFile.inputStream().use { input ->
                                        liveDbWalFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                                if (safetyShmFile.exists()) {
                                    safetyShmFile.inputStream().use { input ->
                                        liveDbShmFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }

                                Timber.w("Startup: successfully recovered live DB from safety backup after incomplete restore")
                            } catch (e: Exception) {
                                Timber.e(e, "Startup: failed to recover from safety backup during crash recovery")
                            }
                        } else {
                            Timber.e("Startup: journal has safety backup path but no live DB path; cannot recover")
                        }
                    } else {
                        Timber.e("Startup: safety backup file not found or unreadable: %s", safetyBackupPath)
                    }
                } else {
                    Timber.e("Startup: journal has no safety backup path; cannot recover from incomplete restore")
                }

                // Clean up staging files and delete journal regardless of recovery outcome
                restoreJournal.cleanStagingFiles(entry)
                restoreJournal.deleteJournal()
            }

            is RestoreJournal.RecoveryResult.CriticalRecoveryRequired -> {
                Timber.e("Startup: CRITICAL — safety backup and live DB are both corrupt")
            }
        }

        // Reset maintenance mode to NORMAL on startup if it was left in a non-restart state
        if (restoreMaintenanceMode.currentMode() != RestoreMaintenanceMode.Mode.NORMAL) {
            Timber.w("Startup: resetting maintenance mode from %s to NORMAL", restoreMaintenanceMode.currentMode())
            restoreMaintenanceMode.reset()
        }
    }

    private fun configureDebugTools() {
        if (!BuildConfig.DEBUG) return

        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }

    private fun registerLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycleObserver)
    }

    /**
     * Schedules all periodic background workers at app startup.
     *
     * ## E7: WorkerSpec.DEFAULTS not wired
     *
     * Each worker's `schedule()` companion method currently uses its own
     * hardcoded constraints and repeat intervals.  These values happen to
     * match the [com.yourname.expensetracker.domain.workers.WorkerSpec.DEFAULTS]
     * map, but **WorkerSpec.DEFAULTS is not the runtime source of truth**.
     *
     * Consequence: changing a spec in `WorkerSpec.DEFAULTS` will NOT
     * propagate to the actual scheduling without also updating each
     * worker's `schedule()` method.  This is a maintenance trap.
     *
     * Recommended fix: refactor each worker to read from
     * `WorkerSpec.DEFAULTS[workerName]` for its constraints, interval,
     * backoff policy, and enabled flag.  A shared scheduling utility
     * function would eliminate this duplication entirely.
     */
    private fun scheduleStartupWork(application: Application) {
        LocationBackfillWorker.schedule(application)
        MerchantKeyBackfillWorker.schedule(application)
        WarrantyExpirationWorker.schedule(application)
        DataRetentionWorker.schedule(application)
        BillReminderWorker.schedule(application)
        ReceiptMatchingWorker.schedule(application)
    }

    private fun syncProactiveBriefingWork() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            syncProactiveBriefingWorkUseCase()
        }
    }
}
