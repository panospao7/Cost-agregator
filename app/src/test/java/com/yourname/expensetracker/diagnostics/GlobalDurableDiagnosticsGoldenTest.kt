package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Global golden tests for the durable diagnostics system.
 * These tests verify the core contracts without requiring a database.
 *
 * Metadata and exception-message sanitization is covered in [EventMetadataSanitizerTest].
 */
class GlobalDurableDiagnosticsGoldenTest {

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

    @Test
    fun `default_event_ids_are_non_blank_and_distinct`() {
        val first = DiagnosticEvent(pipeline = AppPipeline.EMAIL, stage = "s", outcome = EventOutcome.RECEIVED)
        val second = DiagnosticEvent(pipeline = AppPipeline.EMAIL, stage = "s", outcome = EventOutcome.RECEIVED)
        assertTrue("first eventId must be non-blank", first.eventId.isNotBlank())
        assertTrue("second eventId must be non-blank", second.eventId.isNotBlank())
        assertFalse("default eventIds must be distinct", first.eventId == second.eventId)
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
}
