package com.yourname.expensetracker.startup

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
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
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val restoreDatabaseOpener: RestoreDatabaseOpener,
    private val workerExecutionGuard: com.yourname.expensetracker.domain.workers.WorkerExecutionGuard,
    private val restoreJournalImporter: com.yourname.expensetracker.data.backup.RestoreJournalImporter,
    private val timeProvider: TimeProvider
) {

    fun initialize(application: Application) {
        configureDebugTools()

        checkRestoreJournal()
        registerLifecycleObserver()

        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.w("Startup: maintenance mode active, skipping worker scheduling")
        } else {
            scheduleStartupWork(application)
            syncProactiveBriefingWork()
            recoverStaleWorkerRuns()
            importRestoreJournals()
        }
    }

    // SharedPreferences restart flag removed — operationalStateFlow drives the lock.

    /**
     * Checks for a pending restore journal on startup and handles crash recovery.
     *
     * For destructive states (SWAPPING, VERIFYING), this actually restores the
     * safety backup to recover the live database rather than just logging.
     *
     * Visible for testing so the fail-closed crash-recovery contract
     * (P7-CURRENT-003) can be exercised without bootstrapping the full app.
     */
    @androidx.annotation.VisibleForTesting
    internal fun checkRestoreJournal() {
        Timber.i("Startup: checking restore journal")
        when (val recovery = restoreJournal.checkAndRecover()) {
            is RestoreJournal.RecoveryResult.NoAction -> {
                // Normal startup — no journal found
            }

            is RestoreJournal.RecoveryResult.CompleteClean -> {
                Timber.w("Startup: found completed restore journal, cleaning up")
            }

            is RestoreJournal.RecoveryResult.AssetsIncomplete -> {
                Timber.w("Restore journal in ASSETS_RESTORING state — DB has been swapped but assets may be incomplete")
                // Best-effort: keep maintenance mode active, log warning
                // Asset recovery will be handled by periodic cleanup or user-initiated restore
                restoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
                Timber.w("Restore incomplete — user should verify receipt attachments")
                return
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
                // P7-P0-02: Fail-closed crash recovery.
                // Track whether the safety-backup copy actually succeeded.
                // If it fails we must NOT delete the journal, NOT reset maintenance mode,
                // and NOT allow normal startup — the DB may be corrupt.
                var recovered = false

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

                                recovered = true
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

                if (!recovered) {
                    // P7-CURRENT-003: Fail-closed across restarts.
                    // Preserve journal as failure record and block all writes.
                    // Do NOT clean staging, do NOT delete journal, do NOT reset maintenance mode.
                    // The app is in an unknown state — operator must intervene before use.
                    //
                    // Use CRITICAL_RECOVERY_REQUIRED (NOT RESTORE_COMPLETE_RESTART_REQUIRED):
                    // failJournal() renames the active journal away, so on the next restart
                    // checkAndRecover() returns NoAction. Only CRITICAL_RECOVERY_REQUIRED is
                    // exempt from the startup auto-reset below, so it is the only mode that
                    // keeps writes blocked across repeated restarts until manual recovery.
                    restoreJournal.failJournal(
                        entry,
                        "Startup crash recovery failed: safety backup copy did not complete"
                    )
                    restoreMaintenanceMode.enterCriticalRecoveryRequired(
                        "Startup crash recovery failed: safety backup copy did not complete"
                    )
                    Timber.e(
                        "Startup: CRITICAL — crash recovery failed; " +
                            "maintenance mode blocks all writes across restarts until manual intervention"
                    )
                    return
                }

                // Recovery succeeded — verify the restored DB before returning to NORMAL
                val verificationPassed = entry.liveDbPath?.let { verifySafetyRestoredDb(File(it)) } ?: false
                if (!verificationPassed) {
                    restoreJournal.failJournal(
                        entry,
                        "Startup crash recovery: safety backup copy succeeded but DB verification failed"
                    )
                    restoreMaintenanceMode.enterCriticalRecoveryRequired("startup crash recovery failed")
                    Timber.e("Startup: CRITICAL — safety-restored DB failed verification; blocking app")
                    return
                }

                // Verification passed — clean up staging files and journal.
                restoreJournal.cleanStagingFiles(entry)
                restoreJournal.deleteJournal()
            }

            is RestoreJournal.RecoveryResult.CriticalRecoveryRequired -> {
                Timber.e("Startup: CRITICAL — safety backup and live DB are both corrupt")
                restoreMaintenanceMode.enterCriticalRecoveryRequired("startup crash recovery failed")
                Timber.e("Startup: maintenance mode blocks writes until manual recovery and app restart")
                return
            }
        }

        // P7-CURRENT-003: Auto-reset to NORMAL on clean startup is intended for transient
        // modes (e.g. RESTORE_COMPLETE_RESTART_REQUIRED after a successful restore + restart,
        // or a stale in-progress mode whose journal was already resolved above).
        //
        // CRITICAL_RECOVERY_REQUIRED must NEVER be auto-reset: it is the fail-closed mode set
        // when a rollback or crash recovery failed and the DB may be corrupt. Its journal has
        // been renamed to the failure file, so checkAndRecover() returns NoAction on subsequent
        // restarts; if we reset here, writes would silently resume against an unknown DB. It
        // stays blocked across restarts until manual intervention clears it.
        val mode = restoreMaintenanceMode.currentMode()
        if (mode != RestoreMaintenanceMode.Mode.NORMAL &&
            mode != RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
        ) {
            Timber.w("Startup: resetting maintenance mode from %s to NORMAL", mode)
            restoreMaintenanceMode.reset()
        } else if (mode == RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED) {
            Timber.e("Startup: CRITICAL_RECOVERY_REQUIRED persists across restart; writes remain blocked")
        }
    }

    /**
     * Verifies the safety-restored DB with PRAGMA integrity_check and a Room open attempt.
     * @return true if the DB is healthy, false if it should be treated as corrupt.
     */
    private fun verifySafetyRestoredDb(liveDbFile: File): Boolean {
        if (!liveDbFile.exists()) {
            Timber.e("Startup: verifySafetyRestoredDb — live DB file missing")
            return false
        }
        // 1. SQLite integrity_check + foreign_key_check
        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                liveDbFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) {
            Timber.e(e, "Startup: safety-restored DB could not be opened")
            return false
        }
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
            if (!integrity.equals("ok", ignoreCase = true)) {
                Timber.e("Startup: safety-restored DB integrity_check failed: %s", integrity)
                return false
            }
            val fkViolations = db.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
            if (fkViolations > 0) {
                Timber.e("Startup: safety-restored DB has %d foreign key violation(s)", fkViolations)
                return false
            }
            Timber.d("Startup: safety-restored DB integrity + FK checks passed")
        } catch (e: Exception) {
            Timber.e(e, "Startup: safety-restored DB PRAGMA check threw exception")
            return false
        } finally {
            runCatching { db.close() }
        }
        // 2. Room open attempt (triggers migration validation)
        try {
            val freshDb = restoreDatabaseOpener.openFreshDatabase()
            freshDb.openHelper.writableDatabase
            runCatching { freshDb.close() }
            Timber.d("Startup: safety-restored DB Room open passed")
        } catch (e: Exception) {
            Timber.e(e, "Startup: safety-restored DB Room open failed")
            return false
        }
        return true
    }

    /**
     * Enables debug-only tooling: Timber logging and StrictMode.
     *
     * ## PII audit note
     * [Timber] (via [Timber.DebugTree]) may log personally identifiable
     * information (PII) including merchant names, transaction amounts,
     * and free-form notes entered by the user. In debug builds this is
     * intentional for development diagnostics. **Release builds must not
     * plant DebugTree.** If a production logging tree is added, ensure it
     * redacts or anonymizes PII before writing to logcat or persistent
     * storage. See also [com.yourname.expensetracker.data.privacy.ExportAnonymizer]
     * for the redaction utility.
     */
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
     * P7-P1-07: Uses [WorkerRegistry.scheduleAll] — the single source of truth
     * for worker scheduling at both startup and post-restore resume. Previously
     * this was a hardcoded list of 7 workers.
     */
    private fun scheduleStartupWork(application: Application) {
        Timber.i("Startup: scheduling workers via WorkerRegistry")
        WorkerRegistry.scheduleAll(application, timeProvider)
    }

    private fun syncProactiveBriefingWork() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            syncProactiveBriefingWorkUseCase()
        }
    }

    /**
     * P9-CURRENT-003 / N3: Reconcile background_job_runs rows left in RUNNING by
     * process death (no CancellationException is thrown when the OS kills the
     * process, so the guard's in-run finalizer never executes). Marks runs older
     * than the stale threshold as STALE_ABORTED so the run ledger is accurate.
     *
     * U-WORKER-02: Uses a 15-minute threshold at startup instead of the default
     * 4 hours. Any RUNNING row with startedAt before (now - 15 min) is definitely
     * stale because the previous process is dead. The shorter window ensures recent
     * crash-orphaned rows are recovered immediately rather than lingering for hours.
     */
    /**
     * T3A / G-TIME-01: Startup stale-run recovery cutoff, computed from the
     * injected [TimeProvider] (never the wall clock). Visible for testing so the
     * threshold computation can be asserted deterministically without bootstrapping
     * [ProcessLifecycleOwner].
     */
    @androidx.annotation.VisibleForTesting
    internal fun startupStaleThresholdMs(): Long =
        timeProvider.now() - STARTUP_STALE_THRESHOLD_MS

    private fun recoverStaleWorkerRuns() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            runCatching {
                workerExecutionGuard.recoverStaleRunningJobs(
                    staleThresholdMs = startupStaleThresholdMs()
                )
            }.onFailure { Timber.w(it, "Startup: stale worker-run recovery failed") }
        }
    }

    companion object {
        /** 15-minute threshold for startup stale-run recovery (U-WORKER-02). */
        private const val STARTUP_STALE_THRESHOLD_MS = 15 * 60 * 1000L
    }

    /**
     * P7-CURRENT-016: Import the last restore/reset journal trails (success + failure)
     * into the queryable OperationRun ledger.
     *
     * The restore/reset path bans Room after the DB swap (P7-CURRENT-005), so the
     * operation trail — including terminal FAILURE outcomes (wrong password, verification
     * failure, rollback failure, reset failure) — survives only in the on-disk journal
     * until ingested here on the next healthy startup. Runs only when writes are allowed
     * (DB healthy) and is idempotent per event, so repeated startups never duplicate rows.
     */
    private fun importRestoreJournals() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            runCatching { restoreJournalImporter.importLastSuccessJournalIfPresent() }
                .onFailure { Timber.w(it, "Startup: restore success-journal import failed") }
            runCatching { restoreJournalImporter.importLastFailureJournalIfPresent() }
                .onFailure { Timber.w(it, "Startup: restore failure-journal import failed") }
        }
    }
}
