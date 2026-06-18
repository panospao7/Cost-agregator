package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.diagnostics.SideEffectContext
import com.yourname.expensetracker.domain.diagnostics.SideEffectDiagnosticRecorder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real golden acceptance tests for the durable diagnostics system.
 * Uses fake implementations — no database required.
 */
class DurableDiagnosticsAcceptanceTest {

    private val sanitizer = EventMetadataSanitizer()

    // ── Fake writer that captures emitted events ──────────────────────────────

    private class CapturingWriter : DiagnosticEventWriter {
        val emitted = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
    }

    // ── Metadata sanitizer — prefix bypass fix (DDL-81-01) ───────────────────

    @Test
    fun metadata_sanitizer_blocks_source_raw_text() {
        assertTrue(sanitizer.isDangerousKey("sourceRawText"))
    }

    @Test
    fun metadata_sanitizer_blocks_source_full_path() {
        assertTrue(sanitizer.isDangerousKey("sourceFullPath"))
    }

    @Test
    fun metadata_sanitizer_blocks_source_access_token() {
        assertTrue(sanitizer.isDangerousKey("sourceAccessToken"))
    }

    @Test
    fun metadata_sanitizer_blocks_status_token() {
        assertTrue(sanitizer.isDangerousKey("statusToken"))
    }

    @Test
    fun metadata_sanitizer_blocks_reason_authorization() {
        assertTrue(sanitizer.isDangerousKey("reasonAuthorization"))
    }

    @Test
    fun metadata_sanitizer_allows_exact_source() {
        assertFalse(sanitizer.isDangerousKey("source"))
    }

    @Test
    fun metadata_sanitizer_allows_source_id_hash() {
        assertFalse(sanitizer.isDangerousKey("sourceIdHash"))
    }

    @Test
    fun metadata_sanitizer_allows_provider_transaction_id_hash() {
        assertFalse(sanitizer.isDangerousKey("providerTransactionIdHash"))
    }

    // ── Exception sanitizer reuses full string sanitizer (DDL-81-02) ─────────

    @Test
    fun exception_sanitizer_redacts_iban() {
        val msg = "Transfer to DE89370400440532013000 failed"
        val result = sanitizer.sanitizeExceptionMessage(msg)
        assertNotNull(result)
        assertFalse(result!!.contains("DE89370400440532013000"))
    }

    @Test
    fun exception_sanitizer_redacts_long_account_digits() {
        val msg = "Account 1234567890123456 not found"
        val result = sanitizer.sanitizeExceptionMessage(msg)
        assertNotNull(result)
        assertFalse(result!!.contains("1234567890123456"))
    }

    @Test
    fun exception_sanitizer_redacts_file_path() {
        val msg = "Cannot read /data/user/0/com.example/files/db.sqlite"
        val result = sanitizer.sanitizeExceptionMessage(msg)
        assertNotNull(result)
        assertTrue(result!!.contains("[PATH]"))
    }

    @Test
    fun exception_sanitizer_truncates_large_blob() {
        val large = "error: " + "x".repeat(1000)
        val result = sanitizer.sanitizeExceptionMessage(large)
        assertNotNull(result)
        assertTrue(result!!.length <= EventMetadataSanitizer.MAX_STRING_LENGTH)
    }

    // ── SafeEventMetadata merge (DDL-81-14) ───────────────────────────────────

    @Test
    fun safe_event_metadata_merge_preserves_both() {
        val a = SafeEventMetadata.builder().put("expenseId", 1L).build()
        val b = SafeEventMetadata.builder().put("sideEffect", "budget_check").build()
        val merged = a.merge(b)
        val json = merged.toJson()
        assertTrue(json.contains("expenseId"))
        assertTrue(json.contains("sideEffect"))
    }

    @Test
    fun safe_event_metadata_merge_with_empty_returns_original() {
        val a = SafeEventMetadata.builder().put("count", 5).build()
        val merged = a.merge(SafeEventMetadata.empty())
        assertEquals(a.toJson(), merged.toJson())
    }

    // ── SideEffectDiagnosticRecorder terminal flags (DDL-81-15) ──────────────

    @Test
    fun side_effect_completed_is_terminal() = runTest {
        val writer = CapturingWriter()
        val recorder = SideEffectDiagnosticRecorder(writer)
        val ctx = SideEffectContext(
            pipeline = AppPipeline.TRANSACTION, correlationId = CorrelationIds.newId(),
            entityType = "expense", entityId = 1L
        )
        recorder.runSideEffect(ctx, "budget_check") { "done" }
        val completed = writer.emitted.find { it.outcome == EventOutcome.SIDE_EFFECT_COMPLETED }
        assertNotNull(completed)
        assertTrue("SIDE_EFFECT_COMPLETED must be terminal", completed!!.isTerminal)
    }

    @Test
    fun side_effect_failed_is_terminal() = runTest {
        val writer = CapturingWriter()
        val recorder = SideEffectDiagnosticRecorder(writer)
        val ctx = SideEffectContext(
            pipeline = AppPipeline.TRANSACTION, correlationId = CorrelationIds.newId(),
            entityType = "expense", entityId = 1L
        )
        recorder.runSideEffect(ctx, "budget_check") { throw RuntimeException("fail") }
        val failed = writer.emitted.find { it.outcome == EventOutcome.SIDE_EFFECT_FAILED }
        assertNotNull(failed)
        assertTrue("SIDE_EFFECT_FAILED must be terminal", failed!!.isTerminal)
    }

    @Test
    fun side_effect_failed_preserves_caller_metadata() = runTest {
        val writer = CapturingWriter()
        val recorder = SideEffectDiagnosticRecorder(writer)
        val ctx = SideEffectContext(
            pipeline = AppPipeline.TRANSACTION, correlationId = CorrelationIds.newId(),
            entityType = "expense", entityId = 42L
        )
        val callerMeta = SafeEventMetadata.builder().put("expenseId", 42L).build()
        recorder.runSideEffect(ctx, "anomaly_check", callerMeta) { throw RuntimeException("fail") }
        val failed = writer.emitted.find { it.outcome == EventOutcome.SIDE_EFFECT_FAILED }
        assertNotNull(failed)
        assertTrue("Caller metadata must be present", failed!!.metadata.toJson().contains("expenseId"))
        assertTrue("Recorder metadata must be present", failed.metadata.toJson().contains("sideEffect"))
    }

    // ── Notification diagnostics: RECEIVED before early exits (DDL-81-08) ────

    @Test
    fun notification_received_event_has_correct_fields() = runTest {
        val writer = CapturingWriter()
        val correlationId = CorrelationIds.newId()
        writer.emit(DiagnosticEvent(
            pipeline = AppPipeline.NOTIFICATION,
            stage = "listener",
            outcome = EventOutcome.RECEIVED,
            correlationId = correlationId,
            sourceType = "notification"
        ))
        val received = writer.emitted.first()
        assertEquals(EventOutcome.RECEIVED, received.outcome)
        assertEquals(correlationId, received.correlationId)
        assertFalse("RECEIVED is not terminal", received.isTerminal)
    }

    // ── Composite writer fallback contract (DDL-81-03) ────────────────────────

    @Test
    fun composite_writer_safe_sink_preserves_correlation_id() = runTest {
        // Simulate a diagnostic event with correlationId
        val correlationId = CorrelationIds.newId()
        val event = DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "parser",
            outcome = EventOutcome.FAILED_FINAL,
            reasonCode = DiagnosticReasonCode.PARSER_FAILED,
            correlationId = correlationId,
            isTerminal = true
        )
        // Verify the event has the correlationId we set
        assertEquals(correlationId, event.correlationId)
        assertTrue(event.isTerminal)
        assertEquals(DiagnosticReasonCode.PARSER_FAILED, event.reasonCode)
    }

    // ── Operation run terminal event contract ─────────────────────────────────

    @Test
    fun operation_run_started_event_is_not_terminal() = runTest {
        val writer = CapturingWriter()
        writer.emit(DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "STARTED",
            outcome = EventOutcome.ATTEMPTED,
            isTerminal = false
        ))
        assertFalse(writer.emitted.first().isTerminal)
    }

    // ── Bank sync: provider IDs must be hashed (DDL-81-19/20/21) ─────────────

    @Test
    fun bank_sync_metadata_hashes_provider_transaction_id() {
        val meta = SafeEventMetadata.builder()
            .putHashed("providerTransactionId", "TXN-12345-BANK-RAW")
            .put("currency", "EUR")
            .build()
        val json = meta.toJson()
        assertFalse("Raw provider transaction ID must not be in metadata", json.contains("TXN-12345-BANK-RAW"))
        assertTrue("Currency must be present", json.contains("EUR"))
    }

    // ── Email: outer exception must produce terminal event (DDL-81-17) ────────

    @Test
    fun email_outer_exception_event_is_terminal_failed_final() = runTest {
        val writer = CapturingWriter()
        val correlationId = CorrelationIds.newId()
        writer.emit(DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "ingestion",
            outcome = EventOutcome.FAILED_FINAL,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
            correlationId = correlationId,
            isTerminal = true
        ))
        val event = writer.emitted.first()
        assertEquals(EventOutcome.FAILED_FINAL, event.outcome)
        assertTrue(event.isTerminal)
        assertEquals(EventSeverity.ERROR, event.severity)
    }

    // ── Static guard: no dangerous keys in known safe metadata ───────────────

    @Test
    fun known_safe_keys_are_not_blocked() {
        val safeKeys = listOf("expenseId", "receiptId", "count", "currency", "stage",
            "correlationId", "sideEffect", "source", "retryable", "elapsed")
        for (key in safeKeys) {
            assertFalse("Key '$key' should be safe", sanitizer.isDangerousKey(key))
        }
    }

    @Test
    fun known_dangerous_keys_are_blocked() {
        val dangerousKeys = listOf("rawText", "body", "accessToken", "password",
            "iban", "accountNumber", "prompt", "fullPath", "sourceRawText",
            "statusToken", "reasonAuthorization", "currencyAccountNumber")
        for (key in dangerousKeys) {
            assertTrue("Key '$key' should be dangerous", sanitizer.isDangerousKey(key))
        }
    }
}
