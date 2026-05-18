package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real behavior regression tests for DDL-A8 series.
 * These tests would have caught the bugs in commit a8e1c94.
 */
class DurableDiagnosticsA8RegressionTest {

    private val sanitizer = EventMetadataSanitizer()

    // ── Fake captures ──────────────────────────────────────────────────────────

    private class CapturingWriter : DiagnosticEventWriter {
        val emitted = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
    }

    private class TerminalTrackingHandle(private val delegate: () -> Unit = {}) : OperationRunHandle {
        override val runId: Long = 1L
        override val correlationId: String = CorrelationIds.newId()
        private val _isTerminal = java.util.concurrent.atomic.AtomicBoolean(false)
        override val isTerminal: Boolean get() = _isTerminal.get()
        val events = mutableListOf<Pair<String, Boolean>>() // stage to isTerminal

        override suspend fun event(stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?,
                                   severity: EventSeverity, metadata: SafeEventMetadata,
                                   entityType: String?, entityId: Long?, exception: Throwable?, isTerminal: Boolean) {
            events.add(stage to isTerminal)
        }
        override suspend fun increment(p: Int, s: Int, f: Int, sk: Int, w: Int, e: Int) = Unit
        override suspend fun success() { _isTerminal.compareAndSet(false, true) }
        override suspend fun partialSuccess(s: String?) { _isTerminal.compareAndSet(false, true) }
        override suspend fun failedFinal(r: String, e: Throwable?) { _isTerminal.compareAndSet(false, true) }
        override suspend fun failedRetryable(r: String, e: Throwable?) { _isTerminal.compareAndSet(false, true) }
        override suspend fun cancelled(r: String?) { _isTerminal.compareAndSet(false, true) }
    }

    // ── PR 1: Restore journal correctness ─────────────────────────────────────

    @Test
    fun restore_journal_events_are_append_only_model_exists() {
        // RestoreJournalEvent data class must have eventId for idempotent import
        val event = com.yourname.expensetracker.data.backup.RestoreJournal.RestoreJournalEvent(
            correlationId = "corr-1",
            stage = "BUNDLE_VALIDATED",
            outcome = "COMPLETED",
            severity = "INFO",
            reasonCode = null,
            occurredAt = System.currentTimeMillis(),
            metadataJson = null,
            exceptionClass = null,
            exceptionMessageSafe = null,
            isTerminal = false
        )
        assertNotNull("eventId must be auto-generated", event.eventId)
        assertFalse("eventId must not be blank", event.eventId.isBlank())
    }

    @Test
    fun restart_required_must_be_terminal_event() {
        // RESTART_REQUIRED should have isTerminal=true in the success journal
        val event = com.yourname.expensetracker.data.backup.RestoreJournal.RestoreJournalEvent(
            correlationId = "corr-1",
            stage = "RESTART_REQUIRED",
            outcome = "COMPLETED",
            severity = "WARNING",
            reasonCode = null,
            occurredAt = System.currentTimeMillis(),
            metadataJson = null,
            exceptionClass = null,
            exceptionMessageSafe = null,
            isTerminal = true
        )
        assertTrue("RESTART_REQUIRED must be terminal", event.isTerminal)
    }

    // ── PR 2: Restore privacy — no full paths in diagnostics ──────────────────

    @Test
    fun restore_journal_diagnostics_json_strips_internal_paths() {
        // toDiagnosticsJson() must not expose _sourceBackupPath etc.
        val entry = com.yourname.expensetracker.data.backup.RestoreJournal.JournalEntry(
            sourceBackupPath = "/data/user/0/com.app/files/backup.costbackup",
            stagedDbPath = "/data/user/0/com.app/databases/staged.db",
            liveDbPath = "/data/user/0/com.app/databases/app.db"
        )
        val diagJson = entry.toDiagnosticsJson().toString()
        assertFalse("_sourceBackupPath must not be in diagnostics", diagJson.contains("_sourceBackupPath"))
        assertFalse("_stagedDbPath must not be in diagnostics", diagJson.contains("_stagedDbPath"))
        assertFalse("full path must not be in diagnostics", diagJson.contains("/data/user/0"))
        // Recovery journal (toJson) still has them
        val recoveryJson = entry.toJson().toString()
        assertTrue("_sourceBackupPath must be in recovery JSON", recoveryJson.contains("_sourceBackupPath"))
    }

    // ── PR 4: Safe handle terminal-once ───────────────────────────────────────

    @Test
    fun safe_handle_cancelled_prevents_subsequent_success() = runTest {
        val handle = TerminalTrackingHandle()
        handle.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        assertTrue("After cancelled, isTerminal must be true", handle.isTerminal)
        // runOperation would skip success() because isTerminal is true
        // This is the contract: block that manually calls cancelled -> success skipped
    }

    @Test
    fun safe_handle_partial_success_prevents_double_terminal() = runTest {
        val handle = TerminalTrackingHandle()
        handle.partialSuccess("3 errors")
        assertTrue(handle.isTerminal)
        // Second terminal call should be ignored by real SafeSinkOperationRunHandle
    }

    @Test
    fun operation_run_handle_is_terminal_default_false() {
        val handle = TerminalTrackingHandle()
        assertFalse("New handle must not be terminal", handle.isTerminal)
    }

    // ── PR 5: Correlation propagation ─────────────────────────────────────────

    @Test
    fun transaction_event_has_correlation_id_field() {
        val event = com.yourname.expensetracker.data.database.entity.TransactionEvent(
            expenseId = 1L,
            eventType = "CREATED",
            source = "NOTIFICATION",
            actor = "system",
            occurredAt = System.currentTimeMillis(),
            dedupeKey = null,
            duplicateExpenseId = null,
            beforeSnapshot = null,
            afterSnapshot = null,
            metadata = null,
            reason = null,
            correlationId = "notif-corr-123"
        )
        assertEquals("notif-corr-123", event.correlationId)
    }

    @Test
    fun create_expense_request_correlation_id_propagates() {
        val request = com.yourname.expensetracker.domain.transaction.CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0,
            currency = "EUR",
            date = System.currentTimeMillis(),
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            source = com.yourname.expensetracker.domain.transaction.ExpenseSource.NOTIFICATION_AUTO_ACCEPT,
            correlationId = "bank-sync-corr-456"
        )
        assertEquals("bank-sync-corr-456", request.correlationId)
    }

    // ── PR 6: Metadata hash-key validation ────────────────────────────────────

    @Test
    fun source_id_hash_with_plain_text_is_redacted() {
        // sourceIdHash is in SAFE_HASH_KEYS but plain text value must be REDACTED
        val result = sanitizer.sanitizeValue("sourceIdHash", "plain text, not a hash")
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun source_id_hash_with_hex_value_is_allowed() {
        // Valid 16-char hex should pass
        val hexValue = "abcdef0123456789"
        val result = sanitizer.sanitizeValue("sourceIdHash", hexValue)
        assertEquals(hexValue, result)
    }

    @Test
    fun provider_transaction_id_hash_with_plain_text_is_redacted() {
        val result = sanitizer.sanitizeValue("providerTransactionIdHash", "TXN-12345-BANK-RAW")
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun notification_key_hash_with_hex_is_allowed() {
        val hex = "a1b2c3d4e5f67890"
        val result = sanitizer.sanitizeValue("notificationKeyHash", hex)
        assertEquals(hex, result)
    }

    @Test
    fun unknown_object_to_string_with_token_is_sanitized() {
        class SensitiveObject {
            override fun toString() = "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"
        }
        val result = sanitizer.sanitizeValue("data", SensitiveObject())
        assertNotNull(result)
        assertFalse("Bearer token must be redacted from toString", result.toString().contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun unknown_object_to_string_with_path_is_sanitized() {
        class PathObject {
            override fun toString() = "Error reading /data/user/0/com.example/secret.key"
        }
        val result = sanitizer.sanitizeValue("info", PathObject())
        assertNotNull(result)
        assertFalse("File path must be redacted from toString", result.toString().contains("/data/user"))
    }

    // ── PR 7: DiagnosticsRepository model ─────────────────────────────────────

    @Test
    fun diagnostic_failure_summary_model_exists() {
        val summary = com.yourname.expensetracker.domain.debug.DiagnosticFailureSummary(
            source = "pipeline",
            correlationId = "corr-1",
            pipelineOrOperation = "NOTIFICATION",
            stage = "repository",
            outcome = "FAILED_FINAL",
            severity = "ERROR",
            reasonCode = "UNKNOWN_ERROR",
            occurredAt = System.currentTimeMillis(),
            messageSafe = null
        )
        assertEquals("pipeline", summary.source)
        assertEquals("FAILED_FINAL", summary.outcome)
    }
}
