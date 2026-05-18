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
    private val restoreDatabaseOpener: RestoreDatabaseOpener
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
        }
    }

    companion object {
        // Kept for reference; SharedPreferences restart flag removed — operationalStateFlow drives the lock.
    }

    /**
     * Checks for a pending restore journal on startup and handles crash recovery.
     *
     * For destructive states (SWAPPING, VERIFYING), this actually restores the
     * safety backup to recover the live database rather than just logging.
     */
    private fun checkRestoreJournal() {
        Timber.i("Startup: checking restore journal")
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
                    // Fail-closed: preserve journal as failure record and block all writes.
                    // Do NOT clean staging, do NOT delete journal, do NOT reset maintenance mode.
                    // The app is in an unknown state — operator must intervene before use.
                    restoreJournal.failJournal(
                        entry,
                        "Startup crash recovery failed: safety backup copy did not complete"
                    )
                    restoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
                    Timber.e(
                        "Startup: CRITICAL — crash recovery failed; " +
                            "maintenance mode blocks all writes until manual intervention and app restart"
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

        if (restoreMaintenanceMode.currentMode() != RestoreMaintenanceMode.Mode.NORMAL) {
            Timber.w("Startup: resetting maintenance mode from %s to NORMAL", restoreMaintenanceMode.currentMode())
            restoreMaintenanceMode.reset()
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
        WorkerRegistry.scheduleAll(application)
    }

    private fun syncProactiveBriefingWork() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            syncProactiveBriefingWorkUseCase()
        }
    }
}
