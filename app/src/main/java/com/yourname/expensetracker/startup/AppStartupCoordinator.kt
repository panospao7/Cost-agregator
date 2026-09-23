package com.yourname.expensetracker.startup

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreInternalWriteScope
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartupCoordinator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val backgroundLifecycleObserver: AppBackgroundLifecycleObserver,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase,
    private val restoreJournal: RestoreJournal,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val restoreDatabaseOpener: RestoreDatabaseOpener,
    private val restoreInternalWriteScope: RestoreInternalWriteScope,
    private val workerExecutionGuard: com.yourname.expensetracker.domain.workers.WorkerExecutionGuard,
    private val restoreJournalImporter: com.yourname.expensetracker.data.backup.RestoreJournalImporter,
    private val intakeRecoveryScheduler: com.yourname.expensetracker.domain.notification.capture.NotificationIntakeRecoveryScheduler,
    private val bankSyncStartupRecovery: com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery,
    private val timeProvider: TimeProvider,
    @ApplicationScope private val applicationScope: CoroutineScope
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
            recoverStaleBankRuns()
            importRestoreJournals()
            recoverPendingIntakeRows()
        }
    }

    /**
     * RP-17 17-E: independent startup recovery for stale bank sync bookkeeping —
     * operation_runs rows of bank operations and bank_statement_import_runs rows
     * left RUNNING by process death. Mirrors the stale worker-run recovery
     * pattern; each family is finalized separately with controlled reason codes
     * inside [com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery]
     * (which additionally guards every write with the restore write barrier).
     */
    private fun recoverStaleBankRuns() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            try {
                bankSyncStartupRecovery.recoverStaleRuns(
                    staleThresholdMs = startupStaleThresholdMs()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Startup: stale bank-run recovery failed")
            }
        }
    }

    /**
     * RP-10 10b (P1-003): app-start hook for pending intake recovery (the
     * scheduler KDoc promised app-start + restore-complete; both are served by
     * this central hook — a completed restore forces a restart, so the next
     * launch reaches here with writes allowed and the barrier guards the rest).
     */
    private fun recoverPendingIntakeRows() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            try {
                intakeRecoveryScheduler.recoverPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Startup: pending intake recovery failed")
            }
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
                // P7-002 (RP-03B): the DB is already swapped and was verified once — a
                // crash/cancellation during the asset loop must NOT re-lock every
                // launch forever. Re-verify the swapped DB, resume the journal's
                // asset-task ledger idempotently, and finalize the journal. Only a
                // genuinely unsafe state (swapped DB unhealthy AND safety recovery
                // failed) keeps the fail-closed CRITICAL lock.
                handleAssetsIncompleteRecovery(recovery.entry)
                return
            }

            is RestoreJournal.RecoveryResult.CleanedNonDestructive -> {
                val state = recovery.entry.state
                Timber.w("Startup: cleaned up from non-destructive restore state: %s", state)
            }

            is RestoreJournal.RecoveryResult.RecoveredFromSwap -> {
                val entry = recovery.entry
                Timber.e("Startup: detected incomplete restore from state: %s", entry.state)

                // P7-P0-02 / P7-CURRENT-003: fail-closed crash recovery.
                // RP-03B recovery ordering: journal-recorded safety backup first, then
                // the verified `.pre_restore` snapshot saved just before the swap
                // (P7-004 recovery hook). If neither yields a verified DB we must NOT
                // delete the journal, NOT reset maintenance mode, and NOT allow normal
                // startup — the DB may be corrupt.
                if (!recoverLiveDbFromSafetySources(entry)) {
                    // P7-CURRENT-003: Fail-closed across restarts.
                    // Preserve journal as failure record and block all writes.
                    // Use CRITICAL_RECOVERY_REQUIRED (NOT RESTORE_COMPLETE_RESTART_REQUIRED):
                    // failJournal() renames the active journal away, so on the next restart
                    // checkAndRecover() returns NoAction. Only CRITICAL_RECOVERY_REQUIRED is
                    // exempt from the startup auto-reset below, so it is the only mode that
                    // keeps writes blocked across repeated restarts until manual recovery.
                    try {
                        restoreJournal.failJournal(
                            entry,
                            "STARTUP_CRASH_RECOVERY_FAILED"
                        )
                    } finally {
                        restoreMaintenanceMode.enterCriticalRecoveryRequired(
                            "STARTUP_CRASH_RECOVERY_FAILED"
                        )
                    }
                    Timber.e(
                        "Startup: CRITICAL — crash recovery failed; " +
                            "maintenance mode blocks all writes across restarts until manual intervention"
                    )
                    return
                }

                // Recovery succeeded — clean up staging files and journal.
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
            CancellationSafe.runCatchingCancellable { db.close() }
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
     * Attempts fail-safe recovery of the live DB from on-device safety sources.
     *
     * RP-03B recovery ordering: the journal-recorded safety backup first, then the
     * verified `.pre_restore` snapshot saved just before the swap (P7-004 recovery
     * hook). Every candidate copy is fsync'd and re-verified (integrity + FK + Room
     * open) before it is trusted; the caller enters CRITICAL_RECOVERY_REQUIRED when
     * no candidate yields a healthy DB.
     *
     * @return true if the live DB files now hold a verified database.
     */
    private fun recoverLiveDbFromSafetySources(entry: RestoreJournal.JournalEntry): Boolean {
        val liveDbPath = entry.liveDbPath ?: run {
            Timber.e("Startup: journal has no live DB path; cannot recover from incomplete restore")
            return false
        }
        val liveDbFile = File(liveDbPath)
        val candidates = buildList {
            entry.safetyBackupPath?.let { add(it to RecoverySourceKind.SAFETY_BACKUP) }
            val preRestoreFile = File("$liveDbPath.pre_restore")
            if (preRestoreFile.exists() && preRestoreFile.canRead()) {
                add(preRestoreFile.absolutePath to RecoverySourceKind.PRE_RESTORE_SNAPSHOT)
            }
        }
        for ((sourcePath, sourceKind) in candidates) {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists() || !sourceFile.canRead()) continue
            val restored = runCatching {
                restoreDbFilesFrom(sourceFile, liveDbFile)
                verifySafetyRestoredDb(liveDbFile)
            }.getOrElse { e ->
                Timber.e("Startup: recovery from %s failed: %s", sourceKind.name, e.javaClass.simpleName)
                false
            }
            if (restored) {
                Timber.w("Startup: live DB recovered from %s after incomplete restore", sourceKind.name)
                if (sourceKind == RecoverySourceKind.PRE_RESTORE_SNAPSHOT) {
                    // P7-004: the .pre_restore snapshot has been successfully consumed.
                    runCatching { sourceFile.delete() }
                } else {
                    // RP-03 fix: recovery consumed the SAFETY BACKUP, so a stale
                    // .pre_restore snapshot (left over from this restore's pre-swap
                    // step) is residue - remove it now instead of letting it linger
                    // in filesDir forever. It is only kept on CRITICAL/unsafe
                    // outcomes below, where it remains the last recovery source.
                    runCatching { File("$liveDbPath.pre_restore").delete() }
                }
                return true
            }
        }
        Timber.e("Startup: no recovery source yielded a verified live DB")
        return false
    }

    /** On-device sources a failed restore can be rolled back from (RP-03B ordering). */
    private enum class RecoverySourceKind { SAFETY_BACKUP, PRE_RESTORE_SNAPSHOT }

    /**
     * Copies [sourceDb] (with `-wal`/`-shm` sidecars when present) over the live DB
     * trio. Potentially corrupt live files are removed first; copies are fsync'd.
     */
    private fun restoreDbFilesFrom(sourceDb: File, liveDb: File) {
        File(liveDb.path + "-wal").delete()
        File(liveDb.path + "-shm").delete()
        liveDb.delete()
        copyFileSynced(sourceDb, liveDb)
        val sourceWal = File(sourceDb.path + "-wal")
        val sourceShm = File(sourceDb.path + "-shm")
        if (sourceWal.exists()) copyFileSynced(sourceWal, File(liveDb.path + "-wal"))
        if (sourceShm.exists()) copyFileSynced(sourceShm, File(liveDb.path + "-shm"))
    }

    /**
     * RP-03B: stream-copy [sourceFile] to [destinationFile], then flush + fsync so
     * the recovered bytes survive a crash/power loss before the next step trusts
     * the copy (same pattern as RestoreJournal.writeTextSynced).
     */
    private fun copyFileSynced(sourceFile: File, destinationFile: File) {
        destinationFile.parentFile?.mkdirs()
        java.io.FileOutputStream(destinationFile).use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
            runCatching { output.fd.sync() }
        }
    }

    /**
     * P7-002 (RP-03B): startup recovery for a journal left in ASSETS_RESTORING.
     *
     * 1. Re-verify the swapped DB (integrity + FK + Room open). If it fails, run the
     *    existing fail-closed recovery (safety backup → verified `.pre_restore` →
     *    CRITICAL_RECOVERY_REQUIRED).
     * 2. If the DB is healthy, resume the journal's asset-task ledger: sources come
     *    from the recorded extraction temp dir; missing sources mark the task FAILED
     *    and keep the verified DB (best-effort image loss must never roll it back).
     * 3. Commit the journal and enter RESTORE_COMPLETE_RESTART_REQUIRED — the forced
     *    restart contract stays, but it is now reachable-to-completion: the journal
     *    is consumed, so the next startup auto-resets the mode to NORMAL instead of
     *    re-locking forever.
     */
    private fun handleAssetsIncompleteRecovery(entry: RestoreJournal.JournalEntry) {
        Timber.w("Startup: journal in ASSETS_RESTORING — resuming receipt asset recovery")
        // Ensure the write barrier is active and restore-internal writes are permitted
        // even if the persisted mode drifted while the journal survived. This
        // maintenance-mode decision stays SYNCHRONOUS on the main thread: writes are
        // blocked the moment startup observes the journal (fail-closed), regardless
        // of the async resume below.
        restoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.ASSETS_RESTORING)

        // RP-03 fix: the resume is N x (copy + fsync + rename + Room write) - running
        // it with runBlocking on the main thread during onCreate is an ANR risk. Run
        // it on the application-scoped IO scope. The journal remains the source of
        // truth and the resume is idempotent, so a crash or cancellation mid-resume
        // leaves the journal in ASSETS_RESTORING (writes stay blocked) and the next
        // launch simply retries.
        applicationScope.launch {
            resumeAssetsIncompleteRecovery(entry)
        }
    }

    private suspend fun resumeAssetsIncompleteRecovery(entry: RestoreJournal.JournalEntry) {
        // 1. Re-verify the swapped DB before touching anything.
        val dbHealthy = entry.liveDbPath?.let { verifySafetyRestoredDb(File(it)) } ?: false
        if (!dbHealthy) {
            Timber.e("Startup: swapped DB failed verification during ASSETS_RESTORING recovery — attempting safety rollback")
            if (recoverLiveDbFromSafetySources(entry)) {
                // Rolled back to a verified pre-restore DB inside a fresh process;
                // safe to resume normal operation (same contract as the swap-crash
                // recovery path above).
                restoreJournal.cleanStagingFiles(entry)
                restoreJournal.deleteJournal()
                restoreMaintenanceMode.exit(forceRestartRequired = false)
                Timber.w("Startup: rolled back to a verified pre-restore DB; restore marked failed")
                return
            }
            restoreJournal.failJournal(
                entry,
                "Startup asset recovery: swapped DB failed verification and safety recovery failed"
            )
            restoreMaintenanceMode.enterCriticalRecoveryRequired("startup crash recovery failed")
            Timber.e("Startup: CRITICAL — ASSETS_RESTORING recovery failed; writes stay blocked across restarts")
            return
        }

        // 2. Resume the asset ledger idempotently (best-effort image restore).
        var finalEntry = entry
        try {
            finalEntry = resumePendingAssetTasks(entry)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // RP-01 contract: caller cancellation propagates - never swallowed.
            // ASSETS_RESTORING stays active with the journal intact, so the next
            // launch simply retries the idempotent resume instead of marking
            // unfinished tasks FAILED.
            throw e
        } catch (e: Exception) {
            Timber.e("Startup: asset resume failed (%s) — marking remaining tasks FAILED", e.javaClass.simpleName)
            // Base the finalization on the latest journaled ledger so tasks already
            // completed before the failure are not clobbered.
            val latest = restoreJournal.readJournal() ?: finalEntry
            try {
                finalEntry = markUnfinishedAssetTasksFailed(latest, "ASSET_RESUME_FAILED")
            } catch (durability: RestoreJournal.JournalDurabilityException) {
                restoreMaintenanceMode.enterCriticalRecoveryRequired("STARTUP_ASSET_JOURNAL_DURABILITY_FAILED")
                return
            }
        }

        // 3. Finalize the journal forward — per-task failures are recorded in the
        //    ledger; a verified DB is never rolled back for best-effort image loss.
        try {
            restoreJournal.commitJournal(finalEntry)
            restoreMaintenanceMode.exit(forceRestartRequired = true)
        } catch (e: RestoreJournal.JournalDurabilityException) {
            restoreMaintenanceMode.enterCriticalRecoveryRequired("STARTUP_ASSET_JOURNAL_FINALIZATION_FAILED")
            return
        } catch (e: RestoreMaintenanceMode.PersistenceException) {
            restoreMaintenanceMode.enterCriticalRecoveryRequired("STARTUP_ASSET_MODE_PERSISTENCE_FAILED")
            return
        }
        Timber.w("Startup: ASSETS_RESTORING recovery finished — restart required (journal consumed)")
    }

    /**
     * P7-002: idempotent resume of the receipt-asset ledger recorded in the restore
     * journal (RP-03B asset recovery). Only tasks that are not COMPLETED are retried.
     * Each task: copy the bundle asset from the recorded extraction temp dir to a
     * durable final file (fsync'd, deterministic per-asset name), then move the DB
     * pointer inside [RestoreInternalWriteScope]. Missing sources mark the task
     * FAILED(SOURCE_MISSING) and never touch the verified DB.
     */
    private suspend fun resumePendingAssetTasks(entry: RestoreJournal.JournalEntry): RestoreJournal.JournalEntry {
        val pending = entry.assetTasks.filter { it.status != RestoreJournal.AssetRestoreStatus.COMPLETED }
        if (pending.isEmpty()) return entry

        // RP-03 fix (fail-closed duplicate rejection): the plan contract rejects
        // duplicate asset IDs / duplicate receipt+kind keys. With identity-derived
        // final names, two tasks for one receiptId resolve to the SAME final file -
        // ALL conflicting tasks fail with ASSET_REASON_DUPLICATE_TASK (no silent
        // overwrite, no arbitrary first-wins preference). Duplicates are detected
        // across the whole ledger: a COMPLETED duplicate still owns the derived
        // name, so its pending twin must not run either.
        val duplicateIds = entry.assetTasks
            .groupBy { it.receiptId }
            .filterValues { it.size > 1 }
            .keys

        val sourceDir = entry.extractTempDirPath?.let { File(it, "receipts") }
        val receiptsDir = File(appContext.filesDir, "receipts").apply { mkdirs() }
        var current = entry
        val db = restoreDatabaseOpener.openFreshDatabase()
        try {
            val dao = db.scannedReceiptDao()
            for (task in pending) {
                current = if (duplicateIds.contains(task.receiptId)) {
                    updateAssetTask(
                        current, task,
                        RestoreJournal.AssetRestoreStatus.FAILED,
                        RestoreJournal.ASSET_REASON_DUPLICATE_TASK
                    )
                } else {
                    resumeSingleAssetTask(current, task, sourceDir, receiptsDir, dao)
                }
            }
        } finally {
            CancellationSafe.runCatchingCancellable { db.close() }
        }
        return current
    }

    private suspend fun resumeSingleAssetTask(
        entry: RestoreJournal.JournalEntry,
        task: RestoreJournal.AssetRestoreTask,
        sourceDir: File?,
        receiptsDir: File,
        dao: ScannedReceiptDao
    ): RestoreJournal.JournalEntry {
        return try {
            val sourceFile = sourceDir?.let { File(it, task.sourceRelativePath) }
            if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile) {
                // RP-03B: missing source → keep the verified DB, record the loss.
                Timber.w("Startup: asset source missing for receiptId=%d — marking FAILED(SOURCE_MISSING)", task.receiptId)
                return updateAssetTask(entry, task, RestoreJournal.AssetRestoreStatus.FAILED, "SOURCE_MISSING")
            }

            // P7-009 + RP-03 fix: the final file name is DERIVED from the task's own
            // identity (receiptId + allowlisted source extension) and is deterministic
            // across retries. The journal-recorded target name is a cross-check only -
            // never the source of the name - so a tampered or stale journal cannot
            // steer the write target.
            val finalName = RestoreJournal.deriveAssetTargetName(task.receiptId, sourceFile.extension)
                ?: return updateAssetTask(
                    entry, task,
                    RestoreJournal.AssetRestoreStatus.FAILED,
                    RestoreJournal.ASSET_REASON_INVALID_TARGET
                )
            val journalName = task.targetPath?.let { File(it).name }
            if (journalName != null && journalName != finalName) {
                Timber.w(
                    "Startup: journal target name mismatch for receiptId=%d - marking FAILED(%s)",
                    task.receiptId, RestoreJournal.ASSET_REASON_INVALID_TARGET
                )
                return updateAssetTask(
                    entry, task,
                    RestoreJournal.AssetRestoreStatus.FAILED,
                    RestoreJournal.ASSET_REASON_INVALID_TARGET
                )
            }
            val finalFile = File(receiptsDir, finalName)
            val tempFile = File(receiptsDir, "$finalName.tmp")

            // RP-03 fix (fail-closed collision rejection): an existing final file may
            // belong to a foreign/earlier asset - never overwrite it (a plain rename
            // would silently replace it). Both checks below guard the rename.
            if (finalFile.exists()) {
                Timber.w(
                    "Startup: asset target collision for receiptId=%d - marking FAILED(%s)",
                    task.receiptId, RestoreJournal.ASSET_REASON_TARGET_COLLISION
                )
                return updateAssetTask(
                    entry, task,
                    RestoreJournal.AssetRestoreStatus.FAILED,
                    RestoreJournal.ASSET_REASON_TARGET_COLLISION
                )
            }

            // FINAL_DURABLE: copy + fsync, then atomic rename, BEFORE any DB write.
            sourceFile.inputStream().use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    CancellationSafe.runCatchingCancellable { output.fd.sync() }
                }
            }
            if (!tempFile.renameTo(finalFile)) {
                if (finalFile.exists()) {
                    // A foreign file appeared between the pre-check and the rename -
                    // fail closed instead of overwriting it via the copy fallback.
                    CancellationSafe.runCatchingCancellable { tempFile.delete() }
                    return updateAssetTask(
                        entry, task,
                        RestoreJournal.AssetRestoreStatus.FAILED,
                        RestoreJournal.ASSET_REASON_TARGET_COLLISION
                    )
                }
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            if (!finalFile.exists() || !finalFile.isFile) {
                throw java.io.IOException("ASSET_FINAL_DURABLE_FAILED")
            }

            // DB_UPDATED: only after the final file is durable; conditional on receipt ID.
            val receipt = dao.getById(task.receiptId)
            if (receipt == null) {
                Timber.w("Startup: receipt row missing for restored asset receiptId=%d", task.receiptId)
                CancellationSafe.runCatchingCancellable { tempFile.delete() }
                CancellationSafe.runCatchingCancellable { finalFile.delete() }
                return updateAssetTask(entry, task, RestoreJournal.AssetRestoreStatus.FAILED, "RECEIPT_ROW_MISSING")
            }
            restoreInternalWriteScope.run("startupAssetResume.updateImagePath") {
                dao.update(receipt.copy(imagePath = finalFile.absolutePath))
            }
            Timber.d("Startup: resumed receipt asset for receiptId=%d", task.receiptId)
            updateAssetTask(
                entry,
                task,
                RestoreJournal.AssetRestoreStatus.COMPLETED,
                error = null,
                targetPath = finalFile.absolutePath
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e("Startup: asset task failed for receiptId=%d (%s)", task.receiptId, e.javaClass.simpleName)
            updateAssetTask(entry, task, RestoreJournal.AssetRestoreStatus.FAILED, "ASSET_RESTORE_FAILED")
        }
    }

    /** Merges a single asset-task update into the journal ledger (never replaces it). */
    private fun updateAssetTask(
        entry: RestoreJournal.JournalEntry,
        task: RestoreJournal.AssetRestoreTask,
        status: RestoreJournal.AssetRestoreStatus,
        error: String?,
        targetPath: String? = task.targetPath
    ): RestoreJournal.JournalEntry {
        val updatedTasks = entry.assetTasks.map { t ->
            if (t.receiptId == task.receiptId && t.sourceRelativePath == task.sourceRelativePath) {
                t.copy(status = status, error = error, targetPath = targetPath)
            } else {
                t
            }
        }
        val updated = entry.copy(assetTasks = updatedTasks)
        restoreJournal.writeJournal(updated)
        return updated
    }

    /** Marks every non-COMPLETED asset task FAILED so the journal can be finalized. */
    private fun markUnfinishedAssetTasksFailed(
        entry: RestoreJournal.JournalEntry,
        reasonCode: String
    ): RestoreJournal.JournalEntry {
        if (entry.assetTasks.none { it.status != RestoreJournal.AssetRestoreStatus.COMPLETED }) return entry
        val updated = entry.copy(
            assetTasks = entry.assetTasks.map { t ->
                if (t.status == RestoreJournal.AssetRestoreStatus.COMPLETED) {
                    t
                } else {
                    t.copy(status = RestoreJournal.AssetRestoreStatus.FAILED, error = reasonCode)
                }
            }
        )
        restoreJournal.writeJournal(updated)
        return updated
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
            try {
                workerExecutionGuard.recoverStaleRunningJobs(
                    staleThresholdMs = startupStaleThresholdMs()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Startup: stale worker-run recovery failed")
            }
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
            try {
                restoreJournalImporter.importLastSuccessJournalIfPresent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Startup: restore success-journal import failed")
            }
            try {
                restoreJournalImporter.importLastFailureJournalIfPresent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Startup: restore failure-journal import failed")
            }
        }
    }
}
