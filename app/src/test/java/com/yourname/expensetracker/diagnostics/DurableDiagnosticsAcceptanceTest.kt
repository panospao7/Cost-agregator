package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.diagnostics.SideEffectContext
import com.yourname.expensetracker.domain.diagnostics.SideEffectDiagnosticRecorder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real golden acceptance tests for the durable diagnostics system.
 * Uses fake implementations — no database required.
 *
 * Key classification, exception-message sanitization and hash-key validation
 * are covered in [EventMetadataSanitizerTest].
 */
class DurableDiagnosticsAcceptanceTest {

    // ── Fake writer that captures emitted events ──────────────────────────────

    private class CapturingWriter : DiagnosticEventWriter {
        val emitted = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
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
}
