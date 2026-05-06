package com.yourname.expensetracker.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-safe restore journal.
 *
 * Writes a journal file to [filesDir]/restore_journal.json before each
 * critical step of a restore operation. On next app start, [checkAndRecover]
 * reads the journal and either completes the restore or rolls back.
 *
 * ## State machine
 *
 *   PREPARING
 *     → STAGED                      (after staging DB extracted & validated)
 *       → SAFETY_BACKUP_CREATED     (after safety backup created)
 *         → SWAPPING                (moving staged → live)
 *           → VERIFYING             (reopening live, checking integrity)
 *             → COMPLETE            (delete journal)
 *             → ROLLING_BACK        (verification failed → restore safety)
 *           → ROLLING_BACK
 *         → ROLLING_BACK
 *       → ROLLING_BACK
 *     → FAILED                      (clean up, clear journal)
 */
@Singleton
class RestoreJournal @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class JournalEntry(
        val operationId: String = UUID.randomUUID().toString(),
        val state: JournalState = JournalState.PREPARING,
        val startedAt: Long = System.currentTimeMillis(),
        val sourceBackupPath: String? = null,
        val stagedDbPath: String? = null,
        val safetyBackupPath: String? = null,
        val liveDbPath: String? = null,
        val error: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("operationId", operationId)
            put("state", state.name)
            put("startedAt", startedAt)
            put("sourceBackupPath", sourceBackupPath ?: JSONObject.NULL)
            put("stagedDbPath", stagedDbPath ?: JSONObject.NULL)
            put("safetyBackupPath", safetyBackupPath ?: JSONObject.NULL)
            put("liveDbPath", liveDbPath ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject): JournalEntry = JournalEntry(
                operationId = json.optString("operationId", UUID.randomUUID().toString()),
                state = json.optString("state", "PREPARING").let { stateName ->
                    try {
                        JournalState.valueOf(stateName)
                    } catch (e: IllegalArgumentException) {
                        JournalState.PREPARING
                    }
                },
                startedAt = json.optLong("startedAt", System.currentTimeMillis()),
                sourceBackupPath = json.optString("sourceBackupPath", null)
                    ?.takeIf { it != "null" },
                stagedDbPath = json.optString("stagedDbPath", null)
                    ?.takeIf { it != "null" },
                safetyBackupPath = json.optString("safetyBackupPath", null)
                    ?.takeIf { it != "null" },
                liveDbPath = json.optString("liveDbPath", null)
                    ?.takeIf { it != "null" },
                error = json.optString("error", null)
                    ?.takeIf { it != "null" }
            )
        }
    }

    enum class JournalState {
        PREPARING,
        STAGED,
        SAFETY_BACKUP_CREATED,
        SWAPPING,
        VERIFYING,
        ROLLING_BACK,
        COMPLETE,
        FAILED
    }

    private val journalFile: File
        get() = File(context.filesDir, JOURNAL_FILENAME)

    // ── Read / Write ──────────────────────────────────────────────

    /**
     * Reads the current journal entry, or null if no journal exists.
     */
    fun readJournal(): JournalEntry? {
        return try {
            if (!journalFile.exists()) return null
            val text = journalFile.readText()
            if (text.isBlank()) return null
            JournalEntry.fromJson(JSONObject(text))
        } catch (e: Exception) {
            Timber.e(e, "Failed to read restore journal")
            null
        }
    }

    /**
     * Writes (overwrites) the journal entry.
     */
    fun writeJournal(entry: JournalEntry) {
        try {
            journalFile.parentFile?.mkdirs()
            val text = entry.toJson().toString(2)
            journalFile.writeText(text)
            Timber.d("Restore journal: state=%s operationId=%s", entry.state, entry.operationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write restore journal")
        }
    }

    /**
     * Creates a new journal with PREPARING state.
     */
    fun beginJournal(
        sourceBackupPath: String,
        stagedDbPath: String,
        liveDbPath: String
    ): JournalEntry {
        val entry = JournalEntry(
            state = JournalState.PREPARING,
            sourceBackupPath = sourceBackupPath,
            stagedDbPath = stagedDbPath,
            liveDbPath = liveDbPath
        )
        writeJournal(entry)
        return entry
    }

    /**
     * Transitions the journal to a new state.
     *
     * @param entry the current journal entry
     * @param newState the target state
     * @param error optional error message
     * @param safetyBackupPath optional path to the safety backup (used for crash recovery)
     */
    fun transitionTo(
        entry: JournalEntry,
        newState: JournalState,
        error: String? = null,
        safetyBackupPath: String? = null
    ): JournalEntry {
        val updated = entry.copy(
            state = newState,
            error = error ?: entry.error,
            safetyBackupPath = safetyBackupPath ?: entry.safetyBackupPath
        )
        writeJournal(updated)
        return updated
    }

    /**
     * BAK-ND-FIXED: Skip terminal-state write before deletion.
     *
     * Writing a terminal state (COMPLETE/FAILED) and then immediately deleting
     * the journal file is redundant — the delete makes the write invisible.
     * Since no crash recovery path reads these terminal states (COMPLETE is
     * handled by [checkAndRecover] via `deleteJournal()` and FAILED is treated
     * the same as PREPARING/STAGED), we skip the write entirely and go straight
     * to deletion. This eliminates a pointless I/O cycle.
     *
     * The terminal-state copy is still returned for API consistency (callers may
     * use it for in-memory logging).
     */
    fun commitJournal(entry: JournalEntry): JournalEntry {
        val updated = entry.copy(state = JournalState.COMPLETE)
        deleteJournal()
        return updated
    }

    /**
     * BAK-ND-FIXED: Skip terminal-state write before deletion (see [commitJournal]).
     */
    fun failJournal(entry: JournalEntry, errorMessage: String): JournalEntry {
        val updated = entry.copy(state = JournalState.FAILED, error = errorMessage)
        deleteJournal()
        return updated
    }

    /**
     * Deletes the journal file.
     */
    fun deleteJournal() {
        if (journalFile.exists()) {
            journalFile.delete()
            Timber.d("Restore journal deleted")
        }
    }

    /**
     * Returns true if a journal file exists.
     */
    fun hasJournal(): Boolean = journalFile.exists()

    // ── Crash recovery ────────────────────────────────────────────

    /**
     * Result of crash recovery.
     */
    sealed class RecoveryResult {
        /** No journal found — normal startup. */
        object NoAction : RecoveryResult()

        /** Journal was COMPLETE — journal deleted, normal startup. */
        object CompleteClean : RecoveryResult()

        /** Non-destructive state (PREPARING, STAGED, FAILED) — staging cleaned, normal startup. */
        data class CleanedNonDestructive(val entry: JournalEntry) : RecoveryResult()

        /** Destructive state during swap — recovery attempted. */
        data class RecoveredFromSwap(val entry: JournalEntry, val success: Boolean) : RecoveryResult()

        /** Critical failure — both live DB and safety backup are corrupt. */
        object CriticalRecoveryRequired : RecoveryResult()
    }

    /**
     * Checks the journal file and returns a [RecoveryResult] indicating what
     * action to take. Does NOT perform the recovery — the caller is responsible
     * for executing the appropriate recovery steps.
     */
    fun checkAndRecover(): RecoveryResult {
        val entry = readJournal() ?: return RecoveryResult.NoAction

        return when (entry.state) {
            JournalState.COMPLETE -> {
                // Already complete; clean up and proceed.
                deleteJournal()
                RecoveryResult.CompleteClean
            }

            JournalState.PREPARING,
            JournalState.STAGED,
            JournalState.FAILED -> {
                // Non-destructive states. Clean up staging files and journal.
                cleanStagingFiles(entry)
                deleteJournal()
                RecoveryResult.CleanedNonDestructive(entry)
            }

            JournalState.SAFETY_BACKUP_CREATED -> {
                // Safety backup was created but swap didn't start.
                // Clean up staging, keep safety backup, delete journal.
                cleanStagingFiles(entry)
                deleteJournal()
                RecoveryResult.CleanedNonDestructive(entry)
            }

            JournalState.SWAPPING,
            JournalState.VERIFYING -> {
                // Destructive — swap may be partial. Attempt recovery.
                RecoveryResult.RecoveredFromSwap(entry, success = false)
            }

            JournalState.ROLLING_BACK -> {
                // Was already rolling back when crash occurred.
                RecoveryResult.RecoveredFromSwap(entry, success = false)
            }
        }
    }

    /**
     * Cleans up staging database files.
     */
    fun cleanStagingFiles(entry: JournalEntry) {
        if (entry.stagedDbPath != null) {
            val stagedFile = File(entry.stagedDbPath)
            if (stagedFile.exists()) {
                stagedFile.delete()
                Timber.d("Cleaned staging DB: %s", entry.stagedDbPath)
            }
            // Also clean WAL/SHM
            File(entry.stagedDbPath + "-wal").delete()
            File(entry.stagedDbPath + "-shm").delete()
        }
    }

    companion object {
        private const val JOURNAL_FILENAME = "restore_journal.json"
    }
}
