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
 *             → ASSETS_RESTORING    (P7-P1-04: DB verified, assets in progress)
 *               → COMPLETE          (assets restored, delete journal)
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

    data class AssetRestoreTask(
        val receiptId: Long,
        val sourceRelativePath: String,
        val status: AssetRestoreStatus,
        val targetPath: String? = null,
        val error: String? = null
    )

    enum class AssetRestoreStatus { PENDING, COMPLETED, FAILED }

    data class JournalEntry(
        val operationId: String = UUID.randomUUID().toString(),
        val operationCorrelationId: String = UUID.randomUUID().toString(),
        val state: JournalState = JournalState.PREPARING,
        val startedAt: Long = System.currentTimeMillis(),
        val sourceBackupPath: String? = null,
        val stagedDbPath: String? = null,
        val safetyBackupPath: String? = null,
        val liveDbPath: String? = null,
        val error: String? = null,
        val assetTasks: List<AssetRestoreTask> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("operationId", operationId)
            put("operationCorrelationId", operationCorrelationId)
            put("state", state.name)
            put("startedAt", startedAt)
            // DDL-81-12: store only basenames/hashes — no full local paths in diagnostics journal
            put("sourceBackupName", sourceBackupPath?.let { java.io.File(it).name } ?: JSONObject.NULL)
            put("stagedDbName", stagedDbPath?.let { java.io.File(it).name } ?: JSONObject.NULL)
            put("safetyBackupName", safetyBackupPath?.let { java.io.File(it).name } ?: JSONObject.NULL)
            put("liveDbName", liveDbPath?.let { java.io.File(it).name } ?: JSONObject.NULL)
            // Internal paths kept in separate fields for crash recovery only (not exported)
            put("_sourceBackupPath", sourceBackupPath ?: JSONObject.NULL)
            put("_stagedDbPath", stagedDbPath ?: JSONObject.NULL)
            put("_safetyBackupPath", safetyBackupPath ?: JSONObject.NULL)
            put("_liveDbPath", liveDbPath ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
            put("assetTasks", org.json.JSONArray().also { arr ->
                assetTasks.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("receiptId", t.receiptId)
                        put("src", t.sourceRelativePath)
                        put("status", t.status.name)
                        // Store only basename for asset target path
                        if (t.targetPath != null) put("targetName", java.io.File(t.targetPath).name)
                        if (t.error != null) put("error", t.error)
                    })
                }
            })
        }

        /** DDL-A8-06: privacy-safe version — strips internal path fields before debug/export. */
        fun toDiagnosticsJson(): JSONObject {
            val json = toJson()
            listOf("_sourceBackupPath", "_stagedDbPath", "_safetyBackupPath", "_liveDbPath").forEach { json.remove(it) }
            return json
        }

        companion object {
            fun fromJson(json: JSONObject): JournalEntry = JournalEntry(
                operationId = json.optString("operationId", UUID.randomUUID().toString()),
                operationCorrelationId = json.optString("operationCorrelationId", UUID.randomUUID().toString()),
                state = json.optString("state", "PREPARING").let { stateName ->
                    try {
                        JournalState.valueOf(stateName)
                    } catch (e: IllegalArgumentException) {
                        JournalState.PREPARING
                    }
                },
                startedAt = json.optLong("startedAt", System.currentTimeMillis()),
                // DDL-016-05: read both old name and new _prefixed name for recovery paths
                sourceBackupPath = (json.optString("_sourceBackupPath").takeIf { it.isNotEmpty() && it != "null" }
                    ?: json.optString("sourceBackupPath", null)?.takeIf { it != "null" }),
                stagedDbPath = (json.optString("_stagedDbPath").takeIf { it.isNotEmpty() && it != "null" }
                    ?: json.optString("stagedDbPath", null)?.takeIf { it != "null" }),
                safetyBackupPath = (json.optString("_safetyBackupPath").takeIf { it.isNotEmpty() && it != "null" }
                    ?: json.optString("safetyBackupPath", null)?.takeIf { it != "null" }),
                liveDbPath = (json.optString("_liveDbPath").takeIf { it.isNotEmpty() && it != "null" }
                    ?: json.optString("liveDbPath", null)?.takeIf { it != "null" }),
                error = json.optString("error", null)
                    ?.takeIf { it != "null" },
                assetTasks = json.optJSONArray("assetTasks")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val o = arr.getJSONObject(i)
                            AssetRestoreTask(
                                receiptId = o.getLong("receiptId"),
                                sourceRelativePath = o.getString("src"),
                                status = AssetRestoreStatus.valueOf(o.getString("status")),
                                // DDL-016-05: read both "target" (old) and "targetName" (new basenames-only)
                                targetPath = (o.optString("target").takeIf { it.isNotEmpty() && it != "null" }
                                    ?: o.optString("targetName").takeIf { it.isNotEmpty() && it != "null" }),
                                error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" }
                            )
                        }.getOrNull()
                    }
                } ?: emptyList()
            )
        }
    }

    enum class JournalState {
        PREPARING,
        STAGED,
        SAFETY_BACKUP_CREATED,
        SWAPPING,
        VERIFYING,
        /** P7-P1-04: DB verified, receipt asset restoration in progress — crash-safe. */
        ASSETS_RESTORING,
        ROLLING_BACK,
        COMPLETE,
        FAILED
    }

    private val journalFile: File
        get() = File(context.filesDir, JOURNAL_FILENAME)

    // ── RestoreJournalEvent (append-only stage trail) ─────────────

    /** Privacy-safe append-only event record for a restore stage. */
    data class RestoreJournalEvent(
        val eventId: String = UUID.randomUUID().toString(),
        val correlationId: String,
        val stage: String,
        val outcome: String,
        val severity: String,
        val reasonCode: String?,
        val occurredAt: Long,
        val metadataJson: String?,
        val exceptionClass: String?,
        val exceptionMessageSafe: String?,
        val isTerminal: Boolean
    )

    /** Append a stage event to the current journal's event history. */
    fun appendEvent(
        correlationId: String,
        stage: String,
        outcome: String,
        severity: String = "INFO",
        reasonCode: String? = null,
        metadataJson: String? = null,
        exceptionClass: String? = null,
        exceptionMessageSafe: String? = null,
        isTerminal: Boolean = false
    ) {
        appendEventToFile(
            targetFile = journalFile,
            correlationId = correlationId, stage = stage, outcome = outcome,
            severity = severity, reasonCode = reasonCode, metadataJson = metadataJson,
            exceptionClass = exceptionClass, exceptionMessageSafe = exceptionMessageSafe,
            isTerminal = isTerminal
        )
    }

    /**
     * DDL-512-01: Append a stage event to the failure journal.
     * Use this when emitting a terminal event AFTER [failJournal] has already
     * renamed the active journal to the failure file.
     */
    fun appendEventToFailureJournal(
        correlationId: String,
        stage: String,
        outcome: String,
        severity: String = "ERROR",
        reasonCode: String? = null,
        metadataJson: String? = null,
        exceptionClass: String? = null,
        exceptionMessageSafe: String? = null,
        isTerminal: Boolean = false
    ) {
        val failureFile = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
        appendEventToFile(
            targetFile = failureFile,
            correlationId = correlationId, stage = stage, outcome = outcome,
            severity = severity, reasonCode = reasonCode, metadataJson = metadataJson,
            exceptionClass = exceptionClass, exceptionMessageSafe = exceptionMessageSafe,
            isTerminal = isTerminal
        )
    }

    private val journalLock = Any()

    private fun appendEventToFile(
        targetFile: File,
        correlationId: String,
        stage: String,
        outcome: String,
        severity: String,
        reasonCode: String?,
        metadataJson: String?,
        exceptionClass: String?,
        exceptionMessageSafe: String?,
        isTerminal: Boolean
    ) {
        // P7-PR4 (NEW-P7-004): Synchronized to prevent concurrent read-modify-write race.
        synchronized(journalLock) {
        try {
            if (!targetFile.exists()) return
            val json = runCatching { JSONObject(targetFile.readText()) }.getOrNull() ?: return
            val existingEvents = parseEvents(json)
            val newEvent = RestoreJournalEvent(
                correlationId = correlationId,
                stage = stage, outcome = outcome, severity = severity,
                reasonCode = reasonCode, occurredAt = System.currentTimeMillis(),
                metadataJson = metadataJson,
                exceptionClass = exceptionClass, exceptionMessageSafe = exceptionMessageSafe,
                isTerminal = isTerminal
            )
            json.put("events", serializeEvents(existingEvents + newEvent))
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            // P7-CURRENT-022: fsync temp file before rename so the event is crash-durable.
            writeTextSynced(tmpFile, json.toString(2))
            // DDL-C67-07: check rename result; fallback to copy+delete
            if (!tmpFile.renameTo(targetFile)) {
                writeTextSynced(targetFile, tmpFile.readText())
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "RestoreJournal: failed to append event to ${targetFile.name} stage=$stage")
        }
        } // synchronized
    }

    /** Read all events from the diagnostics journal at [correlationId]. */
    fun getEventsByCorrelationId(correlationId: String): List<RestoreJournalEvent> {
        return try {
            val json = readJournalJson() ?: return emptyList()
            parseEvents(json).filter { it.correlationId == correlationId }
        } catch (_: Exception) { emptyList() }
    }

    /** Read all events from the success journal. */
    fun getSuccessJournalEvents(): List<RestoreJournalEvent> {
        return try {
            val file = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (!file.exists()) return emptyList()
            val json = JSONObject(file.readText())
            parseEvents(json)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * DDL-512-10: Read events from all three journal files (active, success, failure).
     * Returns a deduplicated list suitable for getRecentFailures().
     */
    fun getAllDiagnosticEvents(): List<RestoreJournalEvent> {
        val all = mutableListOf<RestoreJournalEvent>()
        // active journal
        runCatching {
            val json = readJournalJson()
            if (json != null) all += parseEvents(json)
        }
        // success journal
        runCatching {
            val file = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (file.exists()) all += parseEvents(JSONObject(file.readText()))
        }
        // failure journal
        runCatching {
            val file = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (file.exists()) all += parseEvents(JSONObject(file.readText()))
        }
        return all.distinctBy { it.eventId }
    }

    private fun readJournalJson(): JSONObject? = try {
        if (!journalFile.exists()) null else JSONObject(journalFile.readText())
    } catch (_: Exception) { null }

    private fun serializeEvents(events: List<RestoreJournalEvent>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("eventId", e.eventId); put("corrId", e.correlationId)
                put("stage", e.stage); put("outcome", e.outcome); put("severity", e.severity)
                if (e.reasonCode != null) put("reasonCode", e.reasonCode)
                put("occurredAt", e.occurredAt)
                // DDL-512-03: persist metadataJson
                if (e.metadataJson != null) put("metadataJson", e.metadataJson)
                if (e.exceptionClass != null) put("excClass", e.exceptionClass)
                if (e.exceptionMessageSafe != null) put("excMsg", e.exceptionMessageSafe)
                put("terminal", e.isTerminal)
            })
        }
        return arr
    }

    private fun parseEvents(json: JSONObject): List<RestoreJournalEvent> {
        val arr = json.optJSONArray("events") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                RestoreJournalEvent(
                    eventId = o.optString("eventId", UUID.randomUUID().toString()),
                    correlationId = o.optString("corrId", ""),
                    stage = o.getString("stage"), outcome = o.getString("outcome"),
                    severity = o.optString("severity", "INFO"),
                    reasonCode = o.optString("reasonCode").takeIf { it.isNotEmpty() },
                    occurredAt = o.optLong("occurredAt", 0L),
                    // DDL-512-03: restore metadataJson
                    metadataJson = o.optString("metadataJson").takeIf { it.isNotEmpty() },
                    exceptionClass = o.optString("excClass").takeIf { it.isNotEmpty() },
                    exceptionMessageSafe = o.optString("excMsg").takeIf { it.isNotEmpty() },
                    isTerminal = o.optBoolean("terminal", false)
                )
            }.getOrNull()
        }
    }

    // ── Read / Write ──────────────────────────────────────────────

    /** Read the last-success journal (written after a successful restore + restart). */
    fun readSuccessJournal(): JournalEntry? {
        return try {
            val file = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) return null
            JournalEntry.fromJson(JSONObject(text))
        } catch (e: Exception) {
            Timber.w(e, "Failed to read success journal")
            null
        }
    }

    /** DDL-A8-08: mark success journal as imported by adding importedAt timestamp. */
    fun markSuccessJournalImported(correlationId: String) {
        try {
            val file = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.put("importedAt", System.currentTimeMillis())
            json.put("importedCorrelationId", correlationId)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark success journal imported")
        }
    }

    /** DDL-A8-08: true if success journal has been fully imported. */
    fun isSuccessJournalImported(correlationId: String): Boolean {
        return try {
            val file = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (!file.exists()) return false
            val json = JSONObject(file.readText())
            json.has("importedAt") && json.optString("importedCorrelationId") == correlationId
        } catch (_: Exception) { false }
    }

    // ── Failure-journal import APIs (P7-CURRENT-016) ──────────────
    // Symmetric to the success-journal APIs above. The restore/reset path bans
    // Room after the DB swap (P7-CURRENT-005), so terminal FAILURE diagnostics
    // live only in the on-disk failure journal until a startup importer ingests
    // them into the queryable OperationRun ledger.

    /** Read the last-failure journal (written by [failJournal]/[preserveJournal]). */
    fun readFailureJournal(): JournalEntry? {
        return try {
            val file = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) return null
            JournalEntry.fromJson(JSONObject(text))
        } catch (e: Exception) {
            Timber.w(e, "Failed to read failure journal")
            null
        }
    }

    /** Read all events from the failure journal. */
    fun getFailureJournalEvents(): List<RestoreJournalEvent> {
        return try {
            val file = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (!file.exists()) return emptyList()
            val json = JSONObject(file.readText())
            parseEvents(json)
        } catch (_: Exception) { emptyList() }
    }

    /** Mark failure journal as imported by adding importedAt timestamp. */
    fun markFailureJournalImported(correlationId: String) {
        try {
            val file = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.put("importedAt", System.currentTimeMillis())
            json.put("importedCorrelationId", correlationId)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark failure journal imported")
        }
    }

    /** True if the failure journal has been fully imported. */
    fun isFailureJournalImported(correlationId: String): Boolean {
        return try {
            val file = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (!file.exists()) return false
            val json = JSONObject(file.readText())
            json.has("importedAt") && json.optString("importedCorrelationId") == correlationId
        } catch (_: Exception) { false }
    }

    /** DDL-A8-19: read events from active, success, and failure journals. */
    fun getAllDiagnosticEventsByCorrelationId(correlationId: String): List<RestoreJournalEvent> {
        val all = mutableListOf<RestoreJournalEvent>()
        runCatching { all += getEventsByCorrelationId(correlationId) }
        runCatching {
            val successFile = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            if (successFile.exists()) {
                val json = JSONObject(successFile.readText())
                all += parseEvents(json).filter { it.correlationId == correlationId }
            }
        }
        runCatching {
            val failureFile = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
            if (failureFile.exists()) {
                val json = JSONObject(failureFile.readText())
                all += parseEvents(json).filter { it.correlationId == correlationId }
            }
        }
        return all.distinctBy { it.eventId }
    }

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
            // DDL-A8-02: preserve existing events when overwriting journal state
            val oldJson = readJournalJson()
            val newJson = entry.toJson()
            val existingEvents = oldJson?.optJSONArray("events")
            if (existingEvents != null && existingEvents.length() > 0) {
                newJson.put("events", existingEvents)
            }
            val tmpFile = File(journalFile.parentFile, "${journalFile.name}.tmp")
            // P7-CURRENT-022: fsync temp file before rename so the journal state
            // (incl. safety backup path needed for crash recovery) is crash-durable.
            writeTextSynced(tmpFile, newJson.toString(2))
            // DDL-C67-07: check rename result
            if (!tmpFile.renameTo(journalFile)) {
                writeTextSynced(journalFile, tmpFile.readText())
                tmpFile.delete()
            }
            Timber.d("Restore journal: state=%s operationId=%s", entry.state, entry.operationId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write restore journal")
        }
    }

    /**
     * P7-CURRENT-022: Writes [text] to [file] and forces it to stable storage via
     * [java.io.FileDescriptor.sync] before returning. Without the fsync, a power
     * loss / crash immediately after [renameTo] can leave the (renamed) file with
     * unflushed or partial contents — losing a journal transition or safety-backup
     * path at the exact moment it is needed for recovery.
     */
    private fun writeTextSynced(file: File, text: String) {
        java.io.FileOutputStream(file).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            // fd.sync() flushes OS buffers to disk; guard against the rare device
            // that throws SyncFailedException so a sync limitation never aborts the
            // operation (the bytes are still written + flushed above).
            runCatching { fos.fd.sync() }
        }
    }

    /**
     * Creates a new journal with PREPARING state.
     *
     * Cleans up any previous failure journal from the last failed restore
     * so diagnostics always reflect the most recent failure.
     */
    fun beginJournal(
        sourceBackupPath: String,
        stagedDbPath: String,
        liveDbPath: String
    ): JournalEntry {
        // Clean up previous failure journal
        val failureFile = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
        if (failureFile.exists()) {
            failureFile.delete()
            Timber.d("Cleaned up previous failure journal: %s", FAILURE_JOURNAL_FILENAME)
        }
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
     * DDL-81-11: Preserve successful restore trail after DB swap.
     * Renames journal to [SUCCESS_JOURNAL_FILENAME] so the operation trail
     * survives restart and can be imported into the restored DB.
     */
    fun commitJournal(entry: JournalEntry): JournalEntry {
        val updated = entry.copy(state = JournalState.COMPLETE)
        writeJournal(updated)
        try {
            val successFile = File(context.filesDir, SUCCESS_JOURNAL_FILENAME)
            successFile.delete()
            // DDL-C67-07: check rename; fallback to copy+delete
            if (!journalFile.renameTo(successFile)) {
                journalFile.copyTo(successFile, overwrite = true)
                journalFile.delete()
            }
            Timber.d("Restore journal preserved as %s", SUCCESS_JOURNAL_FILENAME)
        } catch (e: Exception) {
            Timber.w(e, "Failed to preserve success journal; deleting instead")
            deleteJournal()
        }
        return updated
    }

    /**
     * P7-P1-8: Preserve failed restore journal for diagnostics.
     *
     * Instead of deleting the journal on failure, we write the terminal FAILED
     * state and rename the file to [FAILURE_JOURNAL_FILENAME] so that
     * diagnostics / crash-recovery analysis can inspect the cause.
     */
    fun failJournal(entry: JournalEntry, errorMessage: String): JournalEntry {
        val updated = entry.copy(state = JournalState.FAILED, error = errorMessage)
        writeJournal(updated)
        preserveJournal()
        return updated
    }

    /**
     * Renames the current journal file to [FAILURE_JOURNAL_FILENAME] so the
     * failure record is not lost and can be inspected for diagnostics.
     */
    private fun preserveJournal() {
        if (!journalFile.exists()) return
        val failureFile = File(context.filesDir, FAILURE_JOURNAL_FILENAME)
        try {
            // DDL-C67-07: check rename; fallback to copy+delete
            if (!journalFile.renameTo(failureFile)) {
                journalFile.copyTo(failureFile, overwrite = true)
                journalFile.delete()
            }
            Timber.d("Restore journal preserved as %s", FAILURE_JOURNAL_FILENAME)
        } catch (e: Exception) {
            Timber.e(e, "Failed to preserve restore journal as %s", FAILURE_JOURNAL_FILENAME)
        }
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

            JournalState.SAFETY_BACKUP_CREATED,
            JournalState.ASSETS_RESTORING -> {
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
        const val FAILURE_JOURNAL_FILENAME = "restore_journal_last_failure.json"
        const val SUCCESS_JOURNAL_FILENAME = "restore_journal_last_success.json"
    }
}
