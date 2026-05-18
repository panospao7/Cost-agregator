package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Global golden tests for the durable diagnostics system.
 * These tests verify the core contracts without requiring a database.
 */
class GlobalDurableDiagnosticsGoldenTest {

    private val sanitizer = EventMetadataSanitizer()

    // ── Metadata safety ───────────────────────────────────────────────────────

    @Test
    fun `diagnostic_metadata_never_contains_raw_sensitive_keys`() {
        val blocked = listOf("rawText", "raw_text", "rawOcr", "accessToken",
            "access_token", "prompt", "body", "emailBody", "fullPath", "filePath",
            "iban", "accountNumber", "password", "secret", "authorization")
        for (key in blocked) {
            assertTrue("Key '$key' should be dangerous", sanitizer.isDangerousKey(key))
        }
    }

    @Test
    fun `safe_key_prefixes_are_not_blocked`() {
        val safe = listOf("expenseId", "receiptId", "operationType", "stage",
            "count", "elapsed", "correlationId", "entityId", "reason", "sideEffect",
            "delivered", "currency", "confidence")
        for (key in safe) {
            assertFalse("Key '$key' should NOT be dangerous", sanitizer.isDangerousKey(key))
        }
    }

    @Test
    fun `metadata_sanitizer_blocks_nested_prompt`() {
        val json = """{"outer":{"prompt":"do something bad"}}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull(result)
        assertFalse("Nested prompt should be redacted", result!!.contains("do something bad"))
    }

    @Test
    fun `metadata_sanitizer_redacts_bearer_token_value`() {
        val json = """{"info":"Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull(result)
        assertFalse("Bearer token should be redacted", result!!.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `metadata_sanitizer_redacts_jwt_like_value`() {
        val json = """{"token":"abc123def456.ghi789jkl012.mno345pqr678"}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull(result)
        assertFalse("JWT-like value should be redacted", result!!.contains("abc123def456"))
    }

    @Test
    fun `metadata_sanitizer_truncates_long_strings`() {
        val longValue = "a".repeat(1000)
        val json = """{"description":"$longValue"}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull(result)
        assertTrue("Long string should be truncated",
            result!!.length < json.length)
    }

    @Test
    fun `safe_event_metadata_put_does_not_throw_for_blocked_key`() {
        // Non-throwing builder — blocked keys are silently redacted
        val meta = SafeEventMetadata.builder()
            .put("rawText", "sensitive data")
            .put("expenseId", 123L)
            .build()
        val json = meta.toJson()
        assertFalse("rawText value should be redacted", json.contains("sensitive data"))
        assertTrue("expenseId should be present", json.contains("expenseId"))
    }

    @Test
    fun `metadata_sanitizer_sanitize_json_returns_null_for_empty`() {
        assertNull(sanitizer.sanitizeJsonString(null))
        assertNull(sanitizer.sanitizeJsonString(""))
        assertNull(sanitizer.sanitizeJsonString("{}"))
    }

    // ── CorrelationIds ────────────────────────────────────────────────────────

    @Test
    fun `diagnostic_writer_generates_unique_correlation_ids`() {
        val ids = (1..100).map { CorrelationIds.newId() }.toSet()
        assertEquals("All correlation IDs should be unique", 100, ids.size)
    }

    @Test
    fun `correlation_id_is_uuid_format`() {
        val id = CorrelationIds.newId()
        assertTrue("Correlation ID should be UUID format",
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    // ── DiagnosticEvent construction ──────────────────────────────────────────

    @Test
    fun `diagnostic_event_has_auto_generated_correlation_id`() {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.NOTIFICATION,
            stage = "listener",
            outcome = EventOutcome.RECEIVED
        )
        assertNotNull(event.correlationId)
        assertTrue(event.correlationId.isNotBlank())
    }

    @Test
    fun `diagnostic_event_defaults_are_correct`() {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.TRANSACTION,
            stage = "create",
            outcome = EventOutcome.CREATED
        )
        assertEquals(EventSeverity.INFO, event.severity)
        assertNull(event.reasonCode)
        assertNull(event.entityType)
        assertNull(event.entityId)
        assertFalse(event.isTerminal)
        assertTrue(event.metadata.isEmpty())
    }

    @Test
    fun `terminal_event_has_is_terminal_true`() {
        val event = DiagnosticEvent(
            pipeline = AppPipeline.NOTIFICATION,
            stage = "filter",
            outcome = EventOutcome.DROPPED,
            reasonCode = DiagnosticReasonCode.FILTER_REJECTED,
            isTerminal = true
        )
        assertTrue(event.isTerminal)
        assertEquals(DiagnosticReasonCode.FILTER_REJECTED, event.reasonCode)
    }

    // ── EventOutcome taxonomy ─────────────────────────────────────────────────

    @Test
    fun `all_required_outcomes_exist`() {
        val required = listOf(
            "RECEIVED", "ATTEMPTED", "COMPLETED", "CREATED", "UPDATED", "DELETED",
            "LINKED", "DUPLICATE", "DROPPED", "SKIPPED", "BLOCKED",
            "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED",
            "SIDE_EFFECT_STARTED", "SIDE_EFFECT_COMPLETED", "SIDE_EFFECT_FAILED"
        )
        val names = EventOutcome.entries.map { it.name }
        for (r in required) {
            assertTrue("EventOutcome.$r must exist", names.contains(r))
        }
    }

    @Test
    fun `all_required_reason_codes_exist`() {
        val required = listOf(
            "PRIVACY_DENIED", "RESTORE_BLOCKED", "FILTER_REJECTED", "BLOCKED_PACKAGE",
            "DUPLICATE", "VALIDATION_FAILED", "PARSER_FAILED", "TOKEN_INVALID",
            "CANCELLED_BY_SYSTEM", "SIDE_EFFECT_EXCEPTION"
        )
        val names = DiagnosticReasonCode.entries.map { it.name }
        for (r in required) {
            assertTrue("DiagnosticReasonCode.$r must exist", names.contains(r))
        }
    }

    @Test
    fun `all_required_pipelines_exist`() {
        val required = listOf(
            "NOTIFICATION", "TRANSACTION", "RECEIPT", "RECURRING",
            "BUDGET", "BACKUP_RESTORE", "WORKER", "BANK", "EMAIL", "EXPORT_IMPORT"
        )
        val names = AppPipeline.entries.map { it.name }
        for (r in required) {
            assertTrue("AppPipeline.$r must exist", names.contains(r))
        }
    }

    // ── Exception message sanitization ────────────────────────────────────────

    @Test
    fun `exception_message_sanitizer_strips_file_paths`() {
        val msg = "Failed to read /data/user/0/com.example/files/db.sqlite"
        val result = sanitizer.sanitizeExceptionMessage(msg)
        assertNotNull(result)
        assertFalse("File path should be stripped", result!!.contains("/data/user"))
        assertTrue("PATH placeholder should be present", result.contains("[PATH]"))
    }

    @Test
    fun `exception_message_sanitizer_handles_null`() {
        assertNull(sanitizer.sanitizeExceptionMessage(null))
    }

    @Test
    fun `exception_message_sanitizer_truncates_long_messages`() {
        val long = "error: " + "x".repeat(1000)
        val result = sanitizer.sanitizeExceptionMessage(long)
        assertNotNull(result)
        assertTrue("Exception message should be truncated",
            result!!.length <= EventMetadataSanitizer.MAX_STRING_LENGTH)
    }
}
