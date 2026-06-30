package com.yourname.expensetracker.domain.workers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════════
// PR12I-1: Durable Terminal Diagnostic Sink — file-backed JSONL persistence
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Durable file-backed implementation of [WorkerTerminalDiagnosticSink].
 *
 * Writes sanitized JSONL (one JSON object per line) to a file in the app's
 * internal storage. Each event records ONLY structured metadata — no raw
 * exception messages, stack traces, notification content, bank data,
 * receipt/OCR text, account IDs, or raw payloads are ever persisted.
 *
 * Features:
 * - Appends one line per event (JSONL format)
 * - Creates the `diagnostics/` directory on first use
 * - Synchronizes file access via a [Mutex] to prevent interleaved writes
 * - Caps file size at 512 KB; rotates to a `.1` backup when exceeded
 * - Keeps at most 2 files (current + `.1`)
 * - Never throws into the worker path; falls back to [Timber.e] on failure
 *
 * @param context Android context used to resolve [Context.filesDir]
 */
@Singleton
class FileWorkerTerminalDiagnosticSink @Inject constructor(
    private val context: Context
) : WorkerTerminalDiagnosticSink {

    private val mutex = Mutex()

    private val diagnosticsDir: File by lazy {
        File(context.filesDir, "diagnostics").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private val currentFile: File by lazy {
        File(diagnosticsDir, FILE_NAME)
    }

    private val backupFile: File by lazy {
        File(diagnosticsDir, BACKUP_FILE_NAME)
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    override fun recordWorkerTerminalWriteFailure(
        workerName: String,
        runId: Long,
        correlationId: String?,
        workId: String?,
        runAttempt: Int?,
        intendedStatus: String,
        reasonCode: String?,
        failureCode: String,
        errorClass: String?,
        timestampMs: Long
    ) {
        val jsonLine = buildJsonLine(
            schemaVersion = SCHEMA_VERSION,
            timestampMs = timestampMs,
            workerName = workerName,
            runId = runId,
            correlationId = correlationId,
            workId = workId,
            runAttempt = runAttempt,
            intendedStatus = intendedStatus,
            reasonCode = reasonCode,
            failureCode = failureCode,
            errorClass = errorClass
        )
        try {
            runBlocking {
                mutex.withLock {
                    appendLineSafely(jsonLine)
                }
            }
        } catch (e: Exception) {
            // Never throw into the worker path.
            Timber.e(e, "FileWorkerTerminalDiagnosticSink: failed to record terminal diagnostic")
        }
    }

    // ------------------------------------------------------------------
    // Internal: file I/O (must be called inside mutex.withLock)
    // ------------------------------------------------------------------

    private fun appendLineSafely(line: String) {
        try {
            ensureDirectoryExists()
            rotateIfNeeded()
            currentFile.appendText(line + "\n")
        } catch (e: IOException) {
            // Never throw into the worker path.
            Timber.e(e, "FileWorkerTerminalDiagnosticSink: I/O error appending diagnostic")
        }
    }

    private fun ensureDirectoryExists() {
        if (!diagnosticsDir.exists()) {
            diagnosticsDir.mkdirs()
        }
    }

    /**
     * If [currentFile] exceeds [MAX_FILE_SIZE_BYTES], renames it to [backupFile]
     * (deleting any existing backup first), then starts fresh.
     */
    private fun rotateIfNeeded() {
        if (currentFile.exists() && currentFile.length() > MAX_FILE_SIZE_BYTES) {
            backupFile.delete()
            currentFile.renameTo(backupFile)
        }
    }

    // ------------------------------------------------------------------
    // Internal: JSON building (manual, no external JSON lib needed)
    // ------------------------------------------------------------------

    private fun buildJsonLine(
        schemaVersion: Int,
        timestampMs: Long,
        workerName: String,
        runId: Long,
        correlationId: String?,
        workId: String?,
        runAttempt: Int?,
        intendedStatus: String,
        reasonCode: String?,
        failureCode: String,
        errorClass: String?
    ): String = buildString {
        append("{")
        append("\"schemaVersion\":").append(schemaVersion)
        append(",\"timestampMs\":").append(timestampMs)
        append(",\"workerName\":").append(jsonString(workerName))
        append(",\"runId\":").append(runId)
        append(",\"correlationId\":").append(jsonNullOrString(correlationId))
        append(",\"workId\":").append(jsonNullOrString(workId))
        append(",\"runAttempt\":").append(jsonNullOrInt(runAttempt))
        append(",\"intendedStatus\":").append(jsonString(intendedStatus))
        append(",\"reasonCode\":").append(jsonNullOrString(reasonCode))
        append(",\"failureCode\":").append(jsonString(failureCode))
        append(",\"errorClass\":").append(jsonNullOrString(errorClass))
        append("}")
    }

    private fun jsonString(s: String): String =
        "\"${escapeJsonContent(s)}\""

    private fun jsonNullOrString(s: String?): String =
        if (s == null) "null" else jsonString(s)

    private fun jsonNullOrInt(v: Int?): String =
        if (v == null) "null" else v.toString()

    /**
     * Escapes a string for safe inclusion in a JSON value.
     * Handles: `"`, `\`, `/`, `\b`, `\f`, `\n`, `\r`, `\t`, and control chars (U+0000–U+001F).
     */
    private fun escapeJsonContent(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                in '\u0000'..'\u001F' -> {
                    // Unicode escape for other control characters
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Companion
    // ------------------------------------------------------------------

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_NAME = "worker_terminal_diagnostics.jsonl"
        private const val BACKUP_FILE_NAME = "worker_terminal_diagnostics.jsonl.1"
        private const val MAX_FILE_SIZE_BYTES = 512L * 1024L  // 512 KB
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Composite sink — combines file + logging for debug builds
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Combines multiple [WorkerTerminalDiagnosticSink] implementations so that
 * terminal write failures are recorded to both a durable file and logcat.
 *
 * Each delegate is invoked inside a try/catch so a failure in one sink
 * never prevents the other from recording.
 *
 * Typical debug usage in [com.yourname.expensetracker.di.DiagnosticsModule]:
 * ```kotlin
 * @Binds @Singleton
 * abstract fun bindWorkerTerminalDiagnosticSink(
 *     impl: CompositeWorkerTerminalDiagnosticSink
 * ): WorkerTerminalDiagnosticSink
 * ```
 */
class CompositeWorkerTerminalDiagnosticSink(
    private val fileSink: WorkerTerminalDiagnosticSink,
    private val loggingSink: WorkerTerminalDiagnosticSink
) : WorkerTerminalDiagnosticSink {

    override fun recordWorkerTerminalWriteFailure(
        workerName: String,
        runId: Long,
        correlationId: String?,
        workId: String?,
        runAttempt: Int?,
        intendedStatus: String,
        reasonCode: String?,
        failureCode: String,
        errorClass: String?,
        timestampMs: Long
    ) {
        try {
            fileSink.recordWorkerTerminalWriteFailure(
                workerName, runId, correlationId, workId, runAttempt,
                intendedStatus, reasonCode, failureCode, errorClass, timestampMs
            )
        } catch (_: Exception) { /* no-op: logging sink still runs */ }
        try {
            loggingSink.recordWorkerTerminalWriteFailure(
                workerName, runId, correlationId, workId, runAttempt,
                intendedStatus, reasonCode, failureCode, errorClass, timestampMs
            )
        } catch (_: Exception) { /* no-op */ }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Reader helper — parses JSONL diagnostics into typed events (for tests/debug)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Structured representation of a single terminal diagnostic event
 * read from the JSONL file.
 */
data class TerminalDiagnosticEvent(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("timestampMs") val timestampMs: Long,
    @SerializedName("workerName") val workerName: String,
    @SerializedName("runId") val runId: Long,
    @SerializedName("correlationId") val correlationId: String?,
    @SerializedName("workId") val workId: String?,
    @SerializedName("runAttempt") val runAttempt: Int?,
    @SerializedName("intendedStatus") val intendedStatus: String,
    @SerializedName("reasonCode") val reasonCode: String?,
    @SerializedName("failureCode") val failureCode: String,
    @SerializedName("errorClass") val errorClass: String?
)

/**
 * Reads and parses the durable terminal diagnostic JSONL file into
 * a list of [TerminalDiagnosticEvent] entries.
 *
 * Usage (in tests):
 * ```kotlin
 * val events = WorkerTerminalDiagnosticReader.readAll(context)
 * assertEquals(1, events.size)
 * assertEquals("NotificationIntakeWorker", events[0].workerName)
 * ```
 */
object WorkerTerminalDiagnosticReader {
    private val gson = Gson()

    /**
     * Reads all diagnostic events from the JSONL file in
     * `[context.filesDir]/diagnostics/worker_terminal_diagnostics.jsonl`.
     *
     * Returns an empty list if the file does not exist or is unreadable.
     */
    fun readAll(context: Context): List<TerminalDiagnosticEvent> {
        val file = File(context.filesDir, "diagnostics/worker_terminal_diagnostics.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                gson.fromJson(line, TerminalDiagnosticEvent::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }
}
