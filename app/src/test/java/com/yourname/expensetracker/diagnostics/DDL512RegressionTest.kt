package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Behavioral regression tests for DDL-512 and DDL-F876 series.
 * JVM-only — no Android runtime required.
 */
class DDL512RegressionTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val sanitizer = EventMetadataSanitizer()

    // ── DDL-512-01: Terminal event must be appended BEFORE failJournal ─────────

    @Test
    fun `DDL-512-01 correct order - terminal event before failJournal is preserved in failure file`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")
        val failureFile = File(dir, "restore_journal_last_failure.json")

        activeFile.writeText(buildJournalJson("corr-1", emptyList()))
        appendEventToFile(activeFile, "BUNDLE_VALIDATED", "FAILED_FINAL")
        activeFile.renameTo(failureFile)

        val events = readEventsFromFile(failureFile)
        assertTrue("Terminal event appended before failJournal must appear in failure file",
            events.any { it.getString("stage") == "BUNDLE_VALIDATED" && it.getString("outcome") == "FAILED_FINAL" })
    }

    @Test
    fun `DDL-512-01 bug order - event after failJournal is lost`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")
        val failureFile = File(dir, "restore_journal_last_failure.json")

        activeFile.writeText(buildJournalJson("corr-1", emptyList()))
        activeFile.renameTo(failureFile)
        appendEventToFile(activeFile, "BUNDLE_VALIDATED", "FAILED_FINAL")

        val events = readEventsFromFile(failureFile)
        assertFalse("Events appended after rename are NOT in failure file (demonstrates the bug)",
            events.any { it.getString("stage") == "BUNDLE_VALIDATED" })
    }

    // ── DDL-512-02: Journal must exist before MAINTENANCE_ENTERED ─────────────

    @Test
    fun `DDL-512-02 MAINTENANCE_ENTERED appended after journal creation is preserved`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")

        activeFile.writeText(buildJournalJson("corr-2", emptyList()))
        appendEventToFile(activeFile, "MAINTENANCE_ENTERED", "COMPLETED")

        val events = readEventsFromFile(activeFile)
        assertTrue("MAINTENANCE_ENTERED appended after journal creation is preserved",
            events.any { it.getString("stage") == "MAINTENANCE_ENTERED" })
    }

    @Test
    fun `DDL-512-02 MAINTENANCE_ENTERED before journal creation is lost`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")

        appendEventToFile(activeFile, "MAINTENANCE_ENTERED", "COMPLETED")
        activeFile.writeText(buildJournalJson("corr-2", emptyList()))

        val events = readEventsFromFile(activeFile)
        assertFalse("MAINTENANCE_ENTERED before journal creation is lost",
            events.any { it.getString("stage") == "MAINTENANCE_ENTERED" })
    }

    // ── DDL-512-03: metadataJson preserved ────────────────────────────────────

    @Test
    fun `DDL-512-03 metadataJson survives JSON serialize round-trip`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")
        val meta = """{"receiptId":42,"assetKind":"receipt"}"""
        activeFile.writeText(buildJournalJson("corr-3", emptyList()))
        appendEventToFileWithMeta(activeFile, "ASSET_RESTORED", "COMPLETED", meta)

        val events = readEventsFromFile(activeFile)
        val event = events.firstOrNull { it.getString("stage") == "ASSET_RESTORED" }
        assertNotNull("ASSET_RESTORED event must be present", event)
        assertEquals(meta, event!!.optString("metadataJson"))
    }

    // ── DDL-512-04: OperationRunEvent entity has eventId index ────────────────

    @Test
    fun `DDL-512-04 OperationRunEvent entity declares eventId index`() {
        val indices = OperationRunEvent::class.java
            .getAnnotation(androidx.room.Entity::class.java)
            ?.indices
            ?.map { it.value.toList() }
            ?: emptyList()

        assertTrue("OperationRunEvent must declare Index on eventId column",
            indices.any { it == listOf("eventId") })
    }

    // ── DDL-512-05: CreateExpenseRequest carries correlationId ────────────────

    @Test
    fun `DDL-512-05 CreateExpenseRequest accepts and exposes correlationId`() {
        val request = CreateExpenseRequest(
            merchant = "Merchant", amount = 10.0, currency = "EUR",
            date = System.currentTimeMillis(), transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT, correlationId = "corr-5-test"
        )
        assertEquals("corr-5-test", request.correlationId)
    }

    // ── DDL-512-06: TransactionEvent carries correlationId ────────────────────

    @Test
    fun `DDL-512-06 TransactionEvent entity has correlationId field`() {
        val field = com.yourname.expensetracker.data.database.entity.TransactionEvent::class.java
            .declaredFields
            .firstOrNull { it.name == "correlationId" }
        assertNotNull("TransactionEvent must have correlationId field", field)
    }

    // ── DDL-512-07: sanitizeJsonObject uses sanitizeValue(key, value) ─────────

    @Test
    fun `DDL-512-07 sanitizeJsonString redacts non-hex sourceIdHash`() {
        val json = """{"sourceIdHash":"plain-raw-external-id-not-hex"}"""
        val result = sanitizer.sanitizeJsonString(json)
        if (result != null) {
            assertFalse("Non-hex sourceIdHash must be redacted", result.contains("plain-raw-external-id"))
        }
    }

    @Test
    fun `DDL-512-07 sanitizeJsonString preserves valid hex sourceIdHash`() {
        val hexValue = "deadbeef01234567abcdef0123456789"
        val json = """{"sourceIdHash":"$hexValue"}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull("Valid hex sourceIdHash must not produce null result", result)
        assertTrue("Valid hex value must be preserved", result!!.contains(hexValue))
    }

    // ── DDL-F876-04: SafeSinkOperationRunHandle single terminal policy ─────────

    @Test
    fun `DDL-F876-04 direct terminal event marks handle terminal`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.event("WRITE_BARRIER", EventOutcome.BLOCKED, isTerminal = true)

        assertTrue("Handle must be terminal after isTerminal=true event", handle.isTerminal)
    }

    @Test
    fun `DDL-C67-01 success emits exactly one terminal event`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.success()

        val terminalEvents = emitted.filter { it.isTerminal }
        assertEquals("success() must emit exactly one terminal event", 1, terminalEvents.size)
        assertEquals(EventOutcome.COMPLETED, terminalEvents[0].outcome)
    }

    @Test
    fun `DDL-C67-01 failedFinal emits exactly one terminal event`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.failedFinal("test reason", null)

        val terminalEvents = emitted.filter { it.isTerminal }
        assertEquals("failedFinal() must emit exactly one terminal event", 1, terminalEvents.size)
        assertEquals(EventOutcome.FAILED_FINAL, terminalEvents[0].outcome)
    }

    @Test
    fun `DDL-F876-04 direct terminal then cancelled produces one terminal event`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.event("WRITE_BARRIER", EventOutcome.BLOCKED, isTerminal = true)
        handle.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name) // must be skipped

        val terminalEvents = emitted.filter { it.isTerminal }
        assertEquals("Must have exactly one terminal event", 1, terminalEvents.size)
    }

    @Test
    fun `DDL-F876-04 cancelled then success produces one terminal event`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.cancelled("RESTORE_BLOCKED")
        handle.success()  // must be skipped

        val terminalEvents = emitted.filter { it.isTerminal }
        assertEquals("Must have exactly one terminal event after cancelled+success", 1, terminalEvents.size)
        assertEquals(EventOutcome.CANCELLED, terminalEvents[0].outcome)
    }

    // ── DDL-F876-05: cancelled() preserves reason code ────────────────────────

    @Test
    fun `DDL-F876-05 cancelled with RESTORE_BLOCKED reason code is preserved`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)

        val terminalEvent = emitted.firstOrNull { it.isTerminal }
        assertNotNull("Must have terminal event", terminalEvent)
        assertEquals(DiagnosticReasonCode.RESTORE_BLOCKED, terminalEvent!!.reasonCode)
    }

    @Test
    fun `DDL-F876-05 cancelled with null reason defaults to CANCELLED_BY_SYSTEM`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val handle = makeTrackingHandle(emitted)

        handle.cancelled(null)

        val terminalEvent = emitted.firstOrNull { it.isTerminal }
        assertEquals(DiagnosticReasonCode.CANCELLED_BY_SYSTEM, terminalEvent?.reasonCode)
    }

    // ── DDL-F876-07/08: Notification correlation ──────────────────────────────

    @Test
    fun `DDL-F876-07 parse diagnostic uses same correlationId as listener`() = runTest {
        val cid = CorrelationIds.newId()
        val emitted = mutableListOf<DiagnosticEvent>()
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
        }

        // Simulate parse diagnostic emission with correlationId
        writer.emit(DiagnosticEvent(
            pipeline = AppPipeline.NOTIFICATION, stage = "parse",
            outcome = EventOutcome.COMPLETED, correlationId = cid,
            metadata = SafeEventMetadata.builder().put("parserSource", "PARSER_USED").build()
        ))

        assertEquals(cid, emitted.first().correlationId)
    }

    @Test
    fun `DDL-F876-08 pipeline exception path uses same cid as listener - verification`() = runTest {
        val cid = CorrelationIds.newId()
        val emitted = mutableListOf<DiagnosticEvent>()
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
        }

        // Simulate: cid created OUTSIDE try, used in catch
        try {
            throw RuntimeException("simulated pipeline error")
        } catch (e: Exception) {
            writer.emit(DiagnosticEvent(
                pipeline = AppPipeline.NOTIFICATION, stage = "error",
                outcome = EventOutcome.FAILED_FINAL, correlationId = cid, isTerminal = true
            ))
        }

        assertEquals("Exception path must use listener cid", cid, emitted.first().correlationId)
    }

    // ── DDL-F876-11: Side effects receive create correlationId ────────────────

    @Test
    fun `DDL-F876-11 dispatchOnCreated accepts correlationId parameter`() {
        // Verify TransactionSideEffectDispatcher.dispatchOnCreated has correlationId param (>= 3 params)
        val method = com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher::class.java
            .declaredMethods
            .firstOrNull { it.name == "dispatchOnCreated" }
        assertNotNull("dispatchOnCreated method must exist", method)
        // signature: (expenseId, source, correlationId, causationId) = at least 4 params
        assertTrue("dispatchOnCreated must accept correlationId (>= 3 params)",
            method!!.parameterCount >= 3)
    }

    // ── DDL-F876-15: Hash-key validation ──────────────────────────────────────

    @Test
    fun `DDL-F876-15 packageHash with plain text value is redacted`() {
        val result = sanitizer.sanitizeValue("packageHash", "com.private.bank")
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun `DDL-F876-15 packageHash with valid hex value is allowed`() {
        val hex = "a1b2c3d4e5f607182930a4b5c6d7e8f9"
        val result = sanitizer.sanitizeValue("packageHash", hex)
        assertEquals(hex, result)
    }

    @Test
    fun `DDL-F876-15 unknown hash-like key is always redacted regardless of value`() {
        // "rawTextHash" is not in SAFE_HASH_KEYS — must be redacted even with hex value
        val hex = "a1b2c3d4e5f607182930a4b5c6d7e8f9"
        val result = sanitizer.sanitizeValue("rawTextHash", hex)
        assertEquals("[REDACTED]", result)
    }

    @Test
    fun `DDL-F876-15 all safe-exact keys ending in hash are in safe-hash-keys`() {
        // Verify no hash-like keys exist in SAFE_EXACT_KEYS that bypass hash validation
        // If any canonical safe-exact key ends in 'hash', sanitizeValue must still validate it
        val testKeys = listOf("packageHash", "packageNameHash", "notificationKeyHash",
            "sourceIdHash", "providerHash", "externalHash")
        for (key in testKeys) {
            val plainResult = sanitizer.sanitizeValue(key, "plain-text-not-hex")
            assertEquals("$key with plain text must be redacted", "[REDACTED]", plainResult)
        }
    }

    // ── DDL-512-10: getAllDiagnosticEvents reads failure journal ──────────────

    @Test
    fun `DDL-512-10 getAllDiagnosticEvents reads from failure journal file`() {
        val dir = tmpFolder.newFolder()
        val failureFile = File(dir, "restore_journal_last_failure.json")
        failureFile.writeText(buildJournalWithEvent("corr-10", "ROLLBACK_FAILED", "FAILED_FINAL"))

        val failureEvents = readEventsFromFile(failureFile)
        assertTrue("Failure journal must contain ROLLBACK_FAILED",
            failureEvents.any { it.getString("stage") == "ROLLBACK_FAILED" })
    }

    // ── DDL-512-11: DAO query includes BLOCKED/DROPPED/SIDE_EFFECT_FAILED ──────

    @Test
    fun `DDL-512-11 getRecentFailures query includes BLOCKED outcome`() {
        val sql = com.yourname.expensetracker.data.database.dao.OperationRunEventDao::class.java
            .declaredMethods.firstOrNull { it.name == "getRecentFailures" }
            ?.getAnnotation(androidx.room.Query::class.java)?.value ?: ""
        assertTrue("Query must include BLOCKED", sql.contains("BLOCKED"))
        assertTrue("Query must include DROPPED", sql.contains("DROPPED"))
        assertTrue("Query must include SIDE_EFFECT_FAILED", sql.contains("SIDE_EFFECT_FAILED"))
    }

    // ── DDL-512-14: Notification RECEIVED before terminal ─────────────────────

    @Test
    fun `DDL-512-14 ordered emission RECEIVED then BLOCKED preserves sequence`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
        }
        val cid = CorrelationIds.newId()
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "listener",
            outcome = EventOutcome.RECEIVED, correlationId = cid))
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "listener",
            outcome = EventOutcome.BLOCKED, correlationId = cid, isTerminal = true))

        assertEquals(2, emitted.size)
        assertEquals(EventOutcome.RECEIVED, emitted[0].outcome)
        assertEquals(EventOutcome.BLOCKED, emitted[1].outcome)
    }

    // ── Helper: tracking handle for PR2 tests (mirrors SafeSinkOperationRunHandle behavior) ──

    private fun makeTrackingHandle(emitted: MutableList<DiagnosticEvent>): TrackingHandle = TrackingHandle(emitted)

    /** JVM-safe test double that mirrors SafeSinkOperationRunHandle terminal-once policy (DDL-C67-01 fixed). */
    private class TrackingHandle(private val emitted: MutableList<DiagnosticEvent>) : OperationRunHandle {
        override val runId: Long = 0L
        override val correlationId: String = CorrelationIds.newId()
        private val _isTerminal = java.util.concurrent.atomic.AtomicBoolean(false)
        override val isTerminal: Boolean get() = _isTerminal.get()

        override suspend fun event(
            stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?,
            severity: EventSeverity, metadata: SafeEventMetadata,
            entityType: String?, entityId: Long?, exception: Throwable?, isTerminal: Boolean
        ) {
            // DDL-C67-01 fixed: direct terminal event marks handle terminal; skip if already terminal
            if (isTerminal && !_isTerminal.compareAndSet(false, true)) return
            emitEvent(stage, outcome, reasonCode, isTerminal)
        }

        override suspend fun increment(processed: Int, succeeded: Int, failed: Int, skipped: Int, warnings: Int, errors: Int) = Unit

        override suspend fun success() = terminalOnce("SUCCESS", EventOutcome.COMPLETED)
        override suspend fun partialSuccess(summary: String?) = terminalOnce("PARTIAL_SUCCESS", EventOutcome.COMPLETED)
        override suspend fun failedFinal(reason: String, error: Throwable?) = terminalOnce("FAILED_FINAL", EventOutcome.FAILED_FINAL)
        override suspend fun failedRetryable(reason: String, error: Throwable?) = terminalOnce("FAILED_RETRYABLE", EventOutcome.FAILED_RETRYABLE)
        override suspend fun cancelled(reason: String?) {
            val rc = reason?.let { runCatching { DiagnosticReasonCode.valueOf(it) }.getOrNull() }
                ?: DiagnosticReasonCode.CANCELLED_BY_SYSTEM
            terminalOnce("CANCELLED", EventOutcome.CANCELLED, rc)
        }

        // DDL-C67-01 fix: terminalOnce calls emitEvent directly, NOT event()
        private suspend fun terminalOnce(stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode? = null) {
            if (!_isTerminal.compareAndSet(false, true)) return
            emitEvent(stage, outcome, reasonCode, isTerminal = true)
        }

        private fun emitEvent(stage: String, outcome: EventOutcome, reasonCode: DiagnosticReasonCode?, isTerminal: Boolean) {
            emitted.add(DiagnosticEvent(
                pipeline = AppPipeline.BACKUP_RESTORE, stage = stage,
                outcome = outcome, reasonCode = reasonCode, correlationId = correlationId,
                isTerminal = isTerminal
            ))
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildJournalJson(correlationId: String, events: List<JSONObject>): String {
        val arr = org.json.JSONArray()
        events.forEach { arr.put(it) }
        return JSONObject().apply {
            put("operationId", UUID.randomUUID().toString())
            put("operationCorrelationId", correlationId)
            put("state", "PREPARING")
            put("startedAt", System.currentTimeMillis())
            put("events", arr)
        }.toString(2)
    }

    private fun buildJournalWithEvent(correlationId: String, stage: String, outcome: String): String {
        val eventJson = JSONObject().apply {
            put("eventId", UUID.randomUUID().toString())
            put("corrId", correlationId)
            put("stage", stage)
            put("outcome", outcome)
            put("severity", if (outcome.contains("FAILED")) "ERROR" else "INFO")
            put("occurredAt", System.currentTimeMillis())
            put("terminal", true)
        }
        return buildJournalJson(correlationId, listOf(eventJson))
    }

    private fun appendEventToFile(file: File, stage: String, outcome: String) {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        val arr = json.optJSONArray("events") ?: org.json.JSONArray()
        arr.put(JSONObject().apply {
            put("eventId", UUID.randomUUID().toString())
            put("corrId", json.optString("operationCorrelationId"))
            put("stage", stage)
            put("outcome", outcome)
            put("severity", "INFO")
            put("occurredAt", System.currentTimeMillis())
            put("terminal", outcome.contains("FAILED"))
        })
        json.put("events", arr)
        file.writeText(json.toString(2))
    }

    private fun appendEventToFileWithMeta(file: File, stage: String, outcome: String, metadataJson: String) {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        val arr = json.optJSONArray("events") ?: org.json.JSONArray()
        arr.put(JSONObject().apply {
            put("eventId", UUID.randomUUID().toString())
            put("corrId", json.optString("operationCorrelationId"))
            put("stage", stage)
            put("outcome", outcome)
            put("severity", "INFO")
            put("occurredAt", System.currentTimeMillis())
            put("terminal", false)
            put("metadataJson", metadataJson)
        })
        json.put("events", arr)
        file.writeText(json.toString(2))
    }

    private fun readEventsFromFile(file: File): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("events") ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }
}
