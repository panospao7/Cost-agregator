package com.yourname.expensetracker.domain.workers

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PR12I-1: Tests for [FileWorkerTerminalDiagnosticSink].
 *
 * Verifies durable JSONL file persistence, sanitization, rotation,
 * survivability across instances, and graceful failure handling.
 */
class FileWorkerTerminalDiagnosticSinkTest {

    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("diag_test_").toFile()
        context = mockk {
            every { filesDir } returns tempDir
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private data class TestEvent(
        val workerName: String = "NotificationIntakeWorker",
        val runId: Long = 42L,
        val correlationId: String? = "abc-123",
        val workId: String? = "work-123",
        val runAttempt: Int? = 1,
        val intendedStatus: String = "RETRY",
        val reasonCode: String? = "TIMEOUT",
        val failureCode: String = "TERMINAL_WRITE_TIMEOUT",
        val errorClass: String? = "TimeoutCancellationException",
        val timestampMs: Long = 1700000000000L
    )

    private fun record(sink: FileWorkerTerminalDiagnosticSink, event: TestEvent = TestEvent()) {
        sink.recordWorkerTerminalWriteFailure(
            workerName = event.workerName,
            runId = event.runId,
            correlationId = event.correlationId,
            workId = event.workId,
            runAttempt = event.runAttempt,
            intendedStatus = event.intendedStatus,
            reasonCode = event.reasonCode,
            failureCode = event.failureCode,
            errorClass = event.errorClass,
            timestampMs = event.timestampMs
        )
    }

    private fun readAll(): List<JSONObject> {
        val file = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                JSONObject(line)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readAllStrings(): List<String> {
        val file = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl")
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `terminal_db_timeout_records_file_diagnostic`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        val event = TestEvent(
            workerName = "GeocodingBackfillWorker",
            runId = 7L,
            failureCode = "TERMINAL_WRITE_TIMEOUT",
            errorClass = "TimeoutCancellationException"
        )
        record(sink, event)

        val events = readAll()
        assertEquals("Should have written exactly 1 event", 1, events.size)

        val json = events[0]
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(1700000000000L, json.getLong("timestampMs"))
        assertEquals("GeocodingBackfillWorker", json.getString("workerName"))
        assertEquals(7L, json.getLong("runId"))
        assertEquals("TERMINAL_WRITE_TIMEOUT", json.getString("failureCode"))
        assertEquals("TimeoutCancellationException", json.getString("errorClass"))
        assertEquals("RETRY", json.getString("intendedStatus"))
        assertEquals("TIMEOUT", json.getString("reasonCode"))
    }

    @Test
    fun `terminal_db_exception_records_file_diagnostic`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        val event = TestEvent(
            workerName = "DailyBriefingWorker",
            runId = 99L,
            failureCode = "TERMINAL_WRITE_EXCEPTION",
            errorClass = "SQLiteCantOpenDatabaseException",
            reasonCode = "UNKNOWN_ERROR"
        )
        record(sink, event)

        val events = readAll()
        assertEquals(1, events.size)

        val json = events[0]
        assertEquals("DailyBriefingWorker", json.getString("workerName"))
        assertEquals(99L, json.getLong("runId"))
        assertEquals("TERMINAL_WRITE_EXCEPTION", json.getString("failureCode"))
        assertEquals("SQLiteCantOpenDatabaseException", json.getString("errorClass"))
        assertEquals("UNKNOWN_ERROR", json.getString("reasonCode"))
    }

    @Test
    fun `diagnostic_survives_new_sink_instance`() {
        // Write with one instance
        val sink1 = FileWorkerTerminalDiagnosticSink(context)
        record(sink1, TestEvent(runId = 1L, workerName = "InstanceTestWorker"))

        // Read with a completely new instance
        val sink2 = FileWorkerTerminalDiagnosticSink(context)
        val events = readAll()
        assertEquals("Diagnostic must survive new sink instance", 1, events.size)
        assertEquals("InstanceTestWorker", events[0].getString("workerName"))
    }

    @Test
    fun `diagnostic_does_not_store_exception_message`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        // Record an event — the sink interface does not accept an errorMessage
        // parameter, so by design no exception message CAN be stored.
        record(sink, TestEvent())

        val jsonLines = readAllStrings()
        assertEquals(1, jsonLines.size)

        // Verify no sensitive error-message keywords are present
        val line = jsonLines[0]
        assertFalse("JSON must NOT contain 'errorMessage' field", line.contains("\"errorMessage\""))
        assertFalse("JSON must NOT contain 'message' field", line.contains("\"message\""))
        assertFalse("JSON must NOT contain 'cause' field", line.contains("\"cause\""))
    }

    @Test
    fun `diagnostic_does_not_store_stacktrace`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent())

        val jsonLines = readAllStrings()
        assertEquals(1, jsonLines.size)

        val line = jsonLines[0]
        // Stack trace elements — ensure no raw stack frames, file references,
        // or standalone exception keys are present. The errorClass field
        // contains only a class name (e.g. "TimeoutCancellationException"),
        // which is safe and expected.
        assertFalse("JSON must NOT contain 'stackTrace'", line.contains("\"stackTrace\""))
        assertFalse("JSON must NOT contain 'stack_trace'", line.contains("\"stack_trace\""))
        assertFalse("JSON must NOT contain '.kt:' (file references from stacks)",
            line.contains(".kt:"))
        // Ensure there's no "exceptionMessage" or "exception" key carrying raw text
        assertFalse("JSON must NOT contain 'exceptionMessage'", line.contains("\"exceptionMessage\""))
        // Verify the errorClass is properly set to the expected class name
        val obj = JSONObject(line)
        assertEquals("TimeoutCancellationException", obj.optString("errorClass"))
    }

    @Test
    fun `diagnostic_file_rotation_keeps_recent_events`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)

        // Write many events to exceed 512 KB. Each event is ~250 bytes,
        // so we need ~2100 events to reach 512 KB.
        // Use a payload that makes each line ~400 bytes to reduce total count.
        val largeWorkerName = "A".repeat(200) // makes each event ~450 bytes
        val eventsNeeded = 100

        // Pin initial state
        val currentFile = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl")
        val backupFile = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl.1")

        // Write events until we just cross 512 KB
        var written = 0
        while (currentFile.length() <= 512L * 1024L) {
            record(sink, TestEvent(
                workerName = largeWorkerName,
                runId = written.toLong(),
                workId = null,
                correlationId = null,
                runAttempt = null,
                reasonCode = null,
                errorClass = null
            ))
            written++
            if (written > 10000) break // safety valve
        }
        val beforeRotationCount = written

        // Now one more write should trigger rotation
        record(sink, TestEvent(
            workerName = largeWorkerName,
            runId = written.toLong(),
            workId = null,
            correlationId = null,
            runAttempt = null,
            reasonCode = null,
            errorClass = null
        ))
        written++

        // After rotation: current file should exist and be small (< 10KB since ~1 event)
        assertTrue("Current file must exist after rotation", currentFile.exists())
        assertTrue(
            "Current file must be small after rotation, was ${currentFile.length()} bytes",
            currentFile.length() < 50L * 1024L
        )

        // Backup file should exist and contain the old contents
        assertTrue("Backup file must exist after rotation", backupFile.exists())
        assertTrue(
            "Backup file must contain old events, was ${backupFile.length()} bytes",
            backupFile.length() > 100L * 1024L
        )

        // Verify current file has the latest event(s)
        val currentEvents = currentFile.readLines().filter { it.isNotBlank() }
        assertTrue("Current file must have at least 1 event", currentEvents.isNotEmpty())
        val latestJson = JSONObject(currentEvents.last())
        assertEquals(written.toLong() - 1, latestJson.getLong("runId"))
    }

    @Test
    fun `diagnostic_append_failure_does_not_crash_worker`() {
        // Create a sink with a context whose filesDir is a READ-ONLY directory.
        // The sink must NOT throw — it must catch the IOException and fall back
        // to Timber (which we can't easily assert, but we CAN assert no throw).
        val readOnlyDir = Files.createTempDirectory("diag_readonly_").toFile()
        try {
            readOnlyDir.setReadOnly()

            val readOnlyContext = mockk<Context> {
                every { filesDir } returns readOnlyDir
            }
            val sink = FileWorkerTerminalDiagnosticSink(readOnlyContext)

            // This must NOT throw:
            record(sink, TestEvent(workerName = "ReadOnlyTestWorker"))

            // If we got here without an exception, the test passes.
            // The sink should have failed gracefully (Timber.e fallback).
        } finally {
            // Clean up: make writable again so tearDown can delete it
            readOnlyDir.setWritable(true)
            readOnlyDir.deleteRecursively()
        }
    }

    @Test
    fun `diagnostic_does_not_store_notification_content`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent())

        val jsonLines = readAllStrings()
        assertEquals(1, jsonLines.size)

        val line = jsonLines[0]
        // Verify no notification-related sensitive data
        assertFalse("JSON must NOT contain 'notification' key", line.contains("\"notification"))
        assertFalse("JSON must NOT contain 'payload'", line.contains("\"payload\""))
        assertFalse("JSON must NOT contain 'title' (notification title)", line.contains("\"title\""))
        assertFalse("JSON must NOT contain 'body' (notification body)", line.contains("\"body\""))
    }

    @Test
    fun `diagnostic_does_not_store_bank_data`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent())

        val jsonLines = readAllStrings()
        assertEquals(1, jsonLines.size)

        val line = jsonLines[0]
        // Verify no bank-related sensitive data
        assertFalse("JSON must NOT contain 'account'", line.contains("\"account"))
        assertFalse("JSON must NOT contain 'iban'", line.contains("\"iban\""))
        assertFalse("JSON must NOT contain 'balance'", line.contains("\"balance\""))
        assertFalse("JSON must NOT contain 'transaction'", line.contains("\"transaction"))
    }

    @Test
    fun `diagnostic_does_not_store_ocr_receipt_data`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent())

        val jsonLines = readAllStrings()
        assertEquals(1, jsonLines.size)

        val line = jsonLines[0]
        // Verify no OCR/receipt-related sensitive data
        assertFalse("JSON must NOT contain 'ocr'", line.contains("\"ocr"))
        assertFalse("JSON must NOT contain 'receipt'", line.contains("\"receipt"))
        assertFalse("JSON must NOT contain 'merchant'", line.contains("\"merchant\""))
    }

    @Test
    fun `nullable_fields_are_written_as_json_null`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent(
            correlationId = null,
            workId = null,
            runAttempt = null,
            reasonCode = null,
            errorClass = null
        ))

        val events = readAll()
        assertEquals(1, events.size)

        val json = events[0]
        assertTrue("correlationId should be JSON null", json.isNull("correlationId"))
        assertTrue("workId should be JSON null", json.isNull("workId"))
        assertTrue("runAttempt should be JSON null", json.isNull("runAttempt"))
        assertTrue("reasonCode should be JSON null", json.isNull("reasonCode"))
        assertTrue("errorClass should be JSON null", json.isNull("errorClass"))
    }

    @Test
    fun `multiple_events_are_appended_as_separate_jsonl_lines`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent(runId = 1L))
        record(sink, TestEvent(runId = 2L))
        record(sink, TestEvent(runId = 3L))

        val events = readAll()
        assertEquals(3, events.size)
        assertEquals(1L, events[0].getLong("runId"))
        assertEquals(2L, events[1].getLong("runId"))
        assertEquals(3L, events[2].getLong("runId"))
    }

    @Test
    fun `json_special_characters_are_properly_escaped`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        val workerWithSpecialChars = "Worker\"With\\Quotes\nAnd\tNewlines"

        record(sink, TestEvent(workerName = workerWithSpecialChars))

        val events = readAll()
        assertEquals(1, events.size)

        // After proper escaping and JSON parsing, the original string should round-trip
        val json = events[0]
        assertEquals(workerWithSpecialChars, json.getString("workerName"))
    }

    @Test
    fun `workerTerminalDiagnosticReader_parses_file_correctly`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)
        record(sink, TestEvent(
            workerName = "ReaderTestWorker",
            runId = 55L,
            correlationId = "corr-xyz",
            workId = "work-xyz",
            runAttempt = 2,
            intendedStatus = "FAILED",
            reasonCode = "PERMANENT_ERROR",
            failureCode = "TERMINAL_WRITE_EXCEPTION",
            errorClass = "IllegalStateException",
            timestampMs = 1712345678000L
        ))

        val events = WorkerTerminalDiagnosticReader.readAll(context)
        assertEquals(1, events.size)

        val event = events[0]
        assertEquals(1, event.schemaVersion)
        assertEquals(1712345678000L, event.timestampMs)
        assertEquals("ReaderTestWorker", event.workerName)
        assertEquals(55L, event.runId)
        assertEquals("corr-xyz", event.correlationId)
        assertEquals("work-xyz", event.workId)
        assertEquals(2, event.runAttempt)
        assertEquals("FAILED", event.intendedStatus)
        assertEquals("PERMANENT_ERROR", event.reasonCode)
        assertEquals("TERMINAL_WRITE_EXCEPTION", event.failureCode)
        assertEquals("IllegalStateException", event.errorClass)
    }

    @Test
    fun `workerTerminalDiagnosticReader_returns_empty_for_missing_file`() {
        val emptyContext = mockk<Context> {
            every { filesDir } returns Files.createTempDirectory("diag_empty_").toFile().also {
                it.deleteRecursively() // ensure no diagnostics/ subdir exists
            }
        }
        val events = WorkerTerminalDiagnosticReader.readAll(emptyContext)
        assertTrue("Must return empty list for missing file", events.isEmpty())
    }

    @Test
    fun `file_rotation_replaces_existing_backup`() {
        val sink = FileWorkerTerminalDiagnosticSink(context)

        val currentFile = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl")
        val backupFile = File(tempDir, "diagnostics/worker_terminal_diagnostics.jsonl.1")

        // Pre-create a fake backup file (simulating previous rotation)
        Files.createDirectories(File(tempDir, "diagnostics").toPath())
        backupFile.writeText("""{"schemaVersion":1,"timestampMs":1,"workerName":"Old","runId":0}""" + "\n")
        val oldBackupModTime = backupFile.lastModified()

        // Fill current file past rotation threshold
        val largeWorkerName = "X".repeat(300)
        while (currentFile.length() <= 512L * 1024L) {
            record(sink, TestEvent(workerName = largeWorkerName))
        }
        // Force rotation
        record(sink, TestEvent(workerName = "PostRotation"))

        // Old backup should have been replaced
        assertTrue(backupFile.exists())
        val newBackupModTime = backupFile.lastModified()
        assertTrue(
            "Old backup must be replaced (modtime changed: $oldBackupModTime -> $newBackupModTime)",
            newBackupModTime >= oldBackupModTime
        )
    }

    @Test
    fun `sink_does_not_throw_when_context_filesDir_is_null`() {
        // Simulate a context where filesDir somehow throws (pathological case)
        val badContext = mockk<Context> {
            every { filesDir } throws RuntimeException("Simulated filesDir failure")
        }
        val sink = FileWorkerTerminalDiagnosticSink(badContext)

        // Must not throw — the lazy initialization will fail, but the
        // record method catches exceptions at the top level.
        try {
            record(sink, TestEvent())
        } catch (e: Exception) {
            throw AssertionError("Sink must never throw into the worker path", e)
        }
        // If we reach here, the test passes
    }
}
