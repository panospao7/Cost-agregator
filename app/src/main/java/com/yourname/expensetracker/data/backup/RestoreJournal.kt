package com.yourname.expensetracker.data.backup

import android.content.Context
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) {

    class JournalDurabilityException : IllegalStateException("RESTORE_JOURNAL_DURABILITY_FAILED")

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
        val startedAt: Long = 0L, // Must be set explicitly by beginJournal() using timeProvider
        val sourceBackupPath: String? = null,
        val stagedDbPath: String? = null,
        val safetyBackupPath: String? = null,
        val liveDbPath: String? = null,
        val error: String? = null,
        val assetTasks: List<AssetRestoreTask> = emptyList(),
        /** P7-P1-04: absolute path to the temp directory where the costbackup bundle was extracted. */
        val extractTempDirPath: String? = null
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
            put("_extractTempDirPath", extractTempDirPath ?: JSONObject.NULL)
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
            listOf("_sourceBackupPath", "_stagedDbPath", "_safetyBackupPath", "_liveDbPath", "_extractTempDirPath").forEach { json.remove(it) }
            return json
        }

        companion object {
            /**
             * Parses a journal entry. Legacy journals without a `startedAt` field fall
             * back to the caller-supplied [nowEpochMs] — no hidden wall-clock read.
             */
            private fun requiredString(json: JSONObject, key: String): String =
                (json.opt(key) as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")

            private fun optionalString(json: JSONObject, key: String): String? {
                if (!json.has(key) || json.isNull(key)) return null
                return json.opt(key) as? String
                    ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")
            }

            private fun requiredLong(json: JSONObject, key: String): Long = when (val value = json.opt(key)) {
                is Long -> value
                is Int -> value.toLong()
                else -> throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")
            }

            private fun recoveryPath(json: JSONObject, current: String, legacy: String): String? {
                val currentValue = optionalString(json, current)
                val legacyValue = optionalString(json, legacy)
                return currentValue?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: legacyValue?.takeIf { it != "null" }
            }

            fun fromJson(
                json: JSONObject,
                nowEpochMs: Long,
                validateRecoveryIdentity: Boolean = false
            ): JournalEntry = JournalEntry(
                operationId = requiredString(json, "operationId"),
                operationCorrelationId = requiredString(json, "operationCorrelationId"),
                state = JournalState.values().firstOrNull { it.name == requiredString(json, "state") }
                    ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT"),
                startedAt = if (json.has("startedAt")) requiredLong(json, "startedAt") else nowEpochMs,
                sourceBackupPath = recoveryPath(json, "_sourceBackupPath", "sourceBackupPath"),
                stagedDbPath = recoveryPath(json, "_stagedDbPath", "stagedDbPath"),
                safetyBackupPath = recoveryPath(json, "_safetyBackupPath", "safetyBackupPath"),
                liveDbPath = recoveryPath(json, "_liveDbPath", "liveDbPath"),
                error = optionalString(json, "error")?.takeIf { it != "null" },
                extractTempDirPath = optionalString(json, "_extractTempDirPath")
                    ?.takeIf { it.isNotEmpty() && it != "null" },
                assetTasks = if (!json.has("assetTasks")) {
                    emptyList()
                } else {
                    val tasks = json.optJSONArray("assetTasks")
                        ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")
                    (0 until tasks.length()).map { index ->
                        val task = tasks.optJSONObject(index)
                            ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")
                        AssetRestoreTask(
                            receiptId = requiredLong(task, "receiptId"),
                            sourceRelativePath = requiredString(task, "src"),
                            status = AssetRestoreStatus.values().firstOrNull {
                                it.name == requiredString(task, "status")
                            } ?: throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT"),
                            targetPath = recoveryPath(task, "target", "targetName"),
                            error = optionalString(task, "error")?.takeIf { it != "null" }
                        )
                    }
                }
            ).also {
                if (validateRecoveryIdentity && it.liveDbPath.isNullOrBlank()) {
                    throw IllegalArgumentException("RESTORE_JOURNAL_CORRUPT_INPUT")
                }
            }
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

    /** Narrow deterministic seam for fsync/rename failure tests; production uses the real APIs. */
    internal var testWriteTextSynced: ((File, String) -> Unit)? = null
    internal var testRenameTo: ((File, File) -> Boolean)? = null
    internal enum class IoStage { INSPECT, READ, OPEN, WRITE, SYNC, RENAME, DELETE }
    internal var beforeIo: ((File, IoStage) -> Unit)? = null
    internal var testDelete: ((File) -> Boolean)? = null

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
                reasonCode = reasonCode, occurredAt = timeProvider.now(),
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
            JournalEntry.fromJson(JSONObject(text), timeProvider.now())
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
            json.put("importedAt", timeProvider.now())
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
            JournalEntry.fromJson(JSONObject(text), timeProvider.now())
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
            json.put("importedAt", timeProvider.now())
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
    sealed class JournalReadResult {
        data object Absent : JournalReadResult()
        data class Valid(val entry: JournalEntry) : JournalReadResult()
        data object Corrupt : JournalReadResult()
    }

    /** Distinguishes an absent active journal from bytes that cannot be trusted. */
    fun readJournalResult(): JournalReadResult {
        return try {
            beforeIo?.invoke(journalFile, IoStage.INSPECT)
            if (!journalFile.exists()) return JournalReadResult.Absent
            beforeIo?.invoke(journalFile, IoStage.READ)
            val text = journalFile.readText()
            if (text.isBlank()) return JournalReadResult.Corrupt
            val json = JSONObject(text)
            if (!json.has("operationId") || !json.has("operationCorrelationId") || !json.has("state")) {
                return JournalReadResult.Corrupt
            }
            JournalReadResult.Valid(JournalEntry.fromJson(json, timeProvider.now(), validateRecoveryIdentity = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to read restore journal")
            JournalReadResult.Corrupt
        }
    }

    /** Compatibility reader; corruption is never treated as an actionable entry. */
    fun readJournal(): JournalEntry? = when (val result = readJournalResult()) {
        JournalReadResult.Absent, JournalReadResult.Corrupt -> null
        is JournalReadResult.Valid -> result.entry
    }

    /**
     * Writes (overwrites) the journal entry.
     */
    fun writeJournal(entry: JournalEntry) {
        synchronized(journalLock) {
            try {
                journalFile.parentFile?.mkdirs()
                // DDL-A8-02: preserve existing events when overwriting journal state.
                val oldJson = when (readJournalResult()) {
                    JournalReadResult.Absent -> null
                    is JournalReadResult.Valid -> readJournalJson()
                    JournalReadResult.Corrupt -> throw JournalDurabilityException()
                }
                val newJson = entry.toJson()
                val existingEvents = oldJson?.optJSONArray("events")
                if (existingEvents != null && existingEvents.length() > 0) newJson.put("events", existingEvents)
                val tmpFile = File(journalFile.parentFile, "${journalFile.name}.tmp")
                writeTextSynced(tmpFile, newJson.toString(2))
                if (!renameTo(tmpFile, journalFile)) {
                    writeTextSynced(journalFile, tmpFile.readText())
                    if (!journalFile.exists()) throw JournalDurabilityException()
                    deleteChecked(tmpFile)
                }
                if (!journalFile.exists()) throw JournalDurabilityException()
                Timber.d("Restore journal: state=%s operationId=%s", entry.state, entry.operationId)
            } catch (e: JournalDurabilityException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw JournalDurabilityException()
            }
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
        beforeIo?.invoke(file, IoStage.OPEN)
        java.io.FileOutputStream(file).use { fos ->
            beforeIo?.invoke(file, IoStage.WRITE)
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            beforeIo?.invoke(file, IoStage.SYNC)
            testWriteTextSynced?.invoke(file, text)
            fos.fd.sync()
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
        val entry = JournalEntry(
            state = JournalState.PREPARING,
            startedAt = timeProvider.now(),
            sourceBackupPath = sourceBackupPath,
            stagedDbPath = stagedDbPath,
            liveDbPath = liveDbPath
        )
        writeJournal(entry)
        // Retain the previous failure record until the new active record is durable.
        deleteChecked(File(context.filesDir, FAILURE_JOURNAL_FILENAME))
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
        preserveJournalAs(SUCCESS_JOURNAL_FILENAME)
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
        preserveJournalAs(FAILURE_JOURNAL_FILENAME)
    }

    private fun preserveJournalAs(fileName: String) {
        try {
            beforeIo?.invoke(journalFile, IoStage.INSPECT)
            if (!journalFile.exists()) throw JournalDurabilityException()
            val target = File(context.filesDir, fileName)
            val tmp = File(context.filesDir, "$fileName.tmp")
            beforeIo?.invoke(journalFile, IoStage.READ)
            writeTextSynced(tmp, journalFile.readText())
            if (!renameTo(tmp, target)) {
                writeTextSynced(target, tmp.readText())
                deleteChecked(tmp)
            }
            if (!target.exists()) throw JournalDurabilityException()
            deleteChecked(journalFile)
            Timber.d("Restore journal preserved as %s", fileName)
        } catch (e: JournalDurabilityException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JournalDurabilityException()
        }
    }

    private fun renameTo(source: File, target: File): Boolean {
        beforeIo?.invoke(target, IoStage.RENAME)
        return testRenameTo?.invoke(source, target) ?: source.renameTo(target)
    }

    private fun deleteChecked(file: File) {
        try {
            beforeIo?.invoke(file, IoStage.INSPECT)
            if (!file.exists()) return
            beforeIo?.invoke(file, IoStage.DELETE)
            if (!(testDelete?.invoke(file) ?: file.delete())) throw JournalDurabilityException()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JournalDurabilityException) {
            throw e
        } catch (_: Exception) {
            throw JournalDurabilityException()
        }
    }

    /**
     * Deletes the journal file.
     */
    fun deleteJournal() {
        deleteChecked(journalFile)
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

        /** P7-P1-04: ASSETS_RESTORING state — DB has been swapped but assets are incomplete. Journal must be preserved for recovery. */
        data class AssetsIncomplete(val entry: JournalEntry) : RecoveryResult()

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
        val entry = when (val read = readJournalResult()) {
            JournalReadResult.Absent -> return RecoveryResult.NoAction
            JournalReadResult.Corrupt -> return RecoveryResult.CriticalRecoveryRequired
            is JournalReadResult.Valid -> read.entry
        }

        return when (entry.state) {
            JournalState.COMPLETE -> {
                // A crash may have interrupted archival after the terminal write.
                preserveJournalAs(SUCCESS_JOURNAL_FILENAME)
                RecoveryResult.CompleteClean
            }

            JournalState.PREPARING,
            JournalState.STAGED,
            JournalState.SAFETY_BACKUP_CREATED -> {
                failJournal(entry, "RESTORE_ABORTED_BEFORE_SWAP")
                cleanStagingFiles(entry)
                RecoveryResult.CleanedNonDestructive(entry)
            }

            JournalState.FAILED -> {
                preserveJournalAs(FAILURE_JOURNAL_FILENAME)
                cleanStagingFiles(entry)
                RecoveryResult.CleanedNonDestructive(entry)
            }

            JournalState.ASSETS_RESTORING -> {
                // DB has already been swapped — journal entry is incomplete.
                // Preserve journal for crash recovery; do NOT clean staging or delete journal.
                RecoveryResult.AssetsIncomplete(entry)
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

        // -- RP-03B asset-restore controlled reason codes ------------
        // Controlled constants only - never payload-, path-, or exception-derived.

        /** Source extension outside the allowlist, or journal-recorded target name mismatch (identity-derived name contract). */
        const val ASSET_REASON_INVALID_TARGET = "INVALID_ASSET_TARGET"

        /** Deterministic final file already exists - refusing to overwrite a foreign file. */
        const val ASSET_REASON_TARGET_COLLISION = "ASSET_TARGET_COLLISION"

        /** Duplicate receipt-id asset tasks in one journal - all conflicting tasks fail closed. */
        const val ASSET_REASON_DUPLICATE_TASK = "DUPLICATE_ASSET_TASK"

        /**
         * File extensions a restored receipt asset may use. The backup write side
         * (ReceiptAssetStore / ReceiptOcrService) produces jpg; jpeg/png/webp are
         * legacy-tolerated (same set ReceiptAssistInputBuilder accepts).
         */
        private val ASSET_EXTENSION_ALLOWLIST = setOf("jpg", "jpeg", "png", "webp")

        /**
         * RP-03B fix: derives the final receipt-asset file name from the task's own
         * identity (receiptId + allowlisted source extension). The name is
         * deterministic per task so retries reuse it; a journal-recorded target name
         * is only ever a cross-check, never the source of the name.
         *
         * @return the derived final file name, or null when the extension is not
         *         in the fixed allowlist (caller must fail the task closed).
         */
        fun deriveAssetTargetName(receiptId: Long, sourceExtension: String): String? {
            val ext = sourceExtension.trim().lowercase()
            if (ext !in ASSET_EXTENSION_ALLOWLIST) return null
            return "restored_${receiptId}.$ext"
        }
    }
}
