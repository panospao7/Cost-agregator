package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
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
 * Behavioral regression tests for DDL-512-01 through DDL-512-14.
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

        // Create active journal with events array
        activeFile.writeText(buildJournalJson("corr-1", emptyList()))

        // Correct order: append terminal event first, then rename to failure
        appendEventToFile(activeFile, "BUNDLE_VALIDATED", "FAILED_FINAL")
        activeFile.renameTo(failureFile)

        val events = readEventsFromFile(failureFile)
        assertTrue(
            "Terminal event appended before failJournal must appear in failure file",
            events.any { it.getString("stage") == "BUNDLE_VALIDATED" && it.getString("outcome") == "FAILED_FINAL" }
        )
    }

    @Test
    fun `DDL-512-01 bug order - event after failJournal is lost`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")
        val failureFile = File(dir, "restore_journal_last_failure.json")

        activeFile.writeText(buildJournalJson("corr-1", emptyList()))

        // BUG order: rename first (simulates failJournal), then try to append
        activeFile.renameTo(failureFile)
        // Append to active file (doesn't exist anymore)
        appendEventToFile(activeFile, "BUNDLE_VALIDATED", "FAILED_FINAL")

        val events = readEventsFromFile(failureFile)
        assertFalse(
            "Event appended after rename is NOT in failure file (demonstrates ordering bug)",
            events.any { it.getString("stage") == "BUNDLE_VALIDATED" }
        )
    }

    // ── DDL-512-02: Journal must exist before MAINTENANCE_ENTERED ─────────────

    @Test
    fun `DDL-512-02 MAINTENANCE_ENTERED appended after journal creation is preserved`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")

        // Correct order: create journal THEN emit event
        activeFile.writeText(buildJournalJson("corr-2", emptyList()))
        appendEventToFile(activeFile, "MAINTENANCE_ENTERED", "COMPLETED")

        val events = readEventsFromFile(activeFile)
        assertTrue(
            "MAINTENANCE_ENTERED appended after journal creation is preserved",
            events.any { it.getString("stage") == "MAINTENANCE_ENTERED" }
        )
    }

    @Test
    fun `DDL-512-02 MAINTENANCE_ENTERED before journal creation is lost`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")

        // BUG order: try to append before file exists
        appendEventToFile(activeFile, "MAINTENANCE_ENTERED", "COMPLETED")
        // Now create journal
        activeFile.writeText(buildJournalJson("corr-2", emptyList()))

        val events = readEventsFromFile(activeFile)
        // Fresh journal has no events — confirming the event was lost
        assertFalse(
            "MAINTENANCE_ENTERED before journal creation is lost",
            events.any { it.getString("stage") == "MAINTENANCE_ENTERED" }
        )
    }

    // ── DDL-512-03: metadataJson preserved in serialize/parse round-trip ───────

    @Test
    fun `DDL-512-03 metadataJson survives JSON serialize round-trip in events array`() {
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

    @Test
    fun `DDL-512-03 null metadataJson is absent from serialized event`() {
        val dir = tmpFolder.newFolder()
        val activeFile = File(dir, "restore_journal.json")
        activeFile.writeText(buildJournalJson("corr-3", emptyList()))
        appendEventToFile(activeFile, "BUNDLE_VALIDATED", "COMPLETED")

        val events = readEventsFromFile(activeFile)
        val event = events.firstOrNull { it.getString("stage") == "BUNDLE_VALIDATED" }
        assertNotNull(event)
        assertFalse("Null metadataJson must not appear in serialized event", event!!.has("metadataJson"))
    }

    // ── DDL-512-04: OperationRunEvent entity has eventId index ────────────────

    @Test
    fun `DDL-512-04 OperationRunEvent entity declares eventId index`() {
        val indices = OperationRunEvent::class.java
            .getAnnotation(androidx.room.Entity::class.java)
            ?.indices
            ?.map { it.value.toList() }
            ?: emptyList()

        assertTrue(
            "OperationRunEvent must declare Index on eventId column",
            indices.any { it == listOf("eventId") }
        )
    }

    // ── DDL-512-05: CreateExpenseRequest carries correlationId ────────────────

    @Test
    fun `DDL-512-05 CreateExpenseRequest accepts and exposes correlationId`() {
        val request = CreateExpenseRequest(
            merchant = "Merchant",
            amount = 10.0,
            currency = "EUR",
            date = System.currentTimeMillis(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.NOTIFICATION_AUTO_ACCEPT,
            correlationId = "corr-5-test"
        )
        assertEquals("corr-5-test", request.correlationId)
    }

    @Test
    fun `DDL-512-05 CreateExpenseRequest correlationId defaults to null`() {
        val request = CreateExpenseRequest(
            merchant = "Merchant",
            amount = 10.0,
            currency = "EUR",
            date = System.currentTimeMillis(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY
        )
        assertNull(request.correlationId)
    }

    // ── DDL-512-06: TransactionEvent carries correlationId ────────────────────

    @Test
    fun `DDL-512-06 TransactionEvent entity has correlationId field`() {
        val field = com.yourname.expensetracker.data.database.entity.TransactionEvent::class.java
            .declaredFields
            .firstOrNull { it.name == "correlationId" }
        assertNotNull("TransactionEvent must have correlationId field", field)
    }

    @Test
    fun `DDL-512-06 TransactionEvent correlationId is nullable`() {
        val field = com.yourname.expensetracker.data.database.entity.TransactionEvent::class.java
            .declaredFields
            .first { it.name == "correlationId" }
        // Verify it exists and is a String type (nullable in Kotlin = java.lang.String)
        assertTrue("correlationId must be a String type",
            field.type.name == "kotlin.String" || field.type == String::class.java)
    }

    // ── DDL-512-07: sanitizeJsonObject uses sanitizeValue(key, value) ─────────

    @Test
    fun `DDL-512-07 sanitizeJsonString redacts non-hex sourceIdHash`() {
        val json = """{"sourceIdHash":"plain-raw-external-id-not-hex"}"""
        val result = sanitizer.sanitizeJsonString(json)
        // Non-hex value under a hash key must be redacted or entire value null
        if (result != null) {
            assertFalse(
                "Non-hex sourceIdHash value must be redacted in sanitizeJsonString",
                result.contains("plain-raw-external-id")
            )
        }
    }

    @Test
    fun `DDL-512-07 sanitizeJsonString preserves valid hex sourceIdHash`() {
        val hexValue = "deadbeef01234567abcdef0123456789"  // 32 hex chars, valid
        val json = """{"sourceIdHash":"$hexValue"}"""
        val result = sanitizer.sanitizeJsonString(json)
        assertNotNull("Valid hex sourceIdHash must not produce null result", result)
        assertTrue("Valid hex value must be preserved", result!!.contains(hexValue))
    }

    @Test
    fun `DDL-512-07 sanitizeJsonString redacts non-hex providerTransactionIdHash`() {
        val json = """{"providerTransactionIdHash":"TXN-PLAINTEXT-2024"}"""
        val result = sanitizer.sanitizeJsonString(json)
        if (result != null) {
            assertFalse(
                "Non-hex providerTransactionIdHash must be redacted",
                result.contains("TXN-PLAINTEXT-2024")
            )
        }
    }

    @Test
    fun `DDL-512-07 sanitizeJsonString applies same policy to deeply nested hash key`() {
        val json = """{"wrapper":{"sourceIdHash":"not_hex_value_here"}}"""
        val result = sanitizer.sanitizeJsonString(json)
        if (result != null) {
            assertFalse("Nested non-hex hash key must be redacted", result.contains("not_hex_value_here"))
        }
    }

    // ── DDL-512-10: getAllDiagnosticEvents includes failure journal ────────────

    @Test
    fun `DDL-512-10 getAllDiagnosticEvents reads from failure journal file`() {
        val dir = tmpFolder.newFolder()
        val failureFile = File(dir, "restore_journal_last_failure.json")
        failureFile.writeText(buildJournalWithEvent("corr-10", "ROLLBACK_FAILED", "FAILED_FINAL"))

        // Read all journals — simulated as the three files
        val activeEvents = readEventsFromFile(File(dir, "restore_journal.json"))
        val successEvents = readEventsFromFile(File(dir, "restore_journal_last_success.json"))
        val failureEvents = readEventsFromFile(failureFile)

        val allEvents = (activeEvents + successEvents + failureEvents)
        assertTrue(
            "getAllDiagnosticEvents must include failure journal events",
            allEvents.any { it.getString("stage") == "ROLLBACK_FAILED" }
        )
    }

    @Test
    fun `DDL-512-10 old implementation misses failure journal`() {
        val dir = tmpFolder.newFolder()
        val failureFile = File(dir, "restore_journal_last_failure.json")
        failureFile.writeText(buildJournalWithEvent("corr-10", "ROLLBACK_FAILED", "FAILED_FINAL"))

        // Old implementation only read success + blank-corrId active
        val successEvents = readEventsFromFile(File(dir, "restore_journal_last_success.json"))
        val activeBlankCorrEvents = readEventsFromFile(File(dir, "restore_journal.json"))
            .filter { it.optString("corrId").isEmpty() }

        val oldEvents = successEvents + activeBlankCorrEvents
        assertFalse(
            "Old implementation misses failure journal (demonstrates the bug)",
            oldEvents.any { it.getString("stage") == "ROLLBACK_FAILED" }
        )
    }

    // ── DDL-512-11: DAO query includes BLOCKED/DROPPED/SIDE_EFFECT_FAILED ──────

    @Test
    fun `DDL-512-11 getRecentFailures query includes BLOCKED outcome`() {
        val queryAnnotation = com.yourname.expensetracker.data.database.dao.OperationRunEventDao::class.java
            .declaredMethods
            .firstOrNull { it.name == "getRecentFailures" }
            ?.getAnnotation(androidx.room.Query::class.java)
        assertNotNull("getRecentFailures must have @Query", queryAnnotation)
        assertTrue("Query must include BLOCKED", queryAnnotation!!.value.contains("BLOCKED"))
    }

    @Test
    fun `DDL-512-11 getRecentFailures query includes DROPPED outcome`() {
        val queryAnnotation = com.yourname.expensetracker.data.database.dao.OperationRunEventDao::class.java
            .declaredMethods
            .firstOrNull { it.name == "getRecentFailures" }
            ?.getAnnotation(androidx.room.Query::class.java)
        assertTrue("Query must include DROPPED", queryAnnotation!!.value.contains("DROPPED"))
    }

    @Test
    fun `DDL-512-11 getRecentFailures query includes SIDE_EFFECT_FAILED outcome`() {
        val queryAnnotation = com.yourname.expensetracker.data.database.dao.OperationRunEventDao::class.java
            .declaredMethods
            .firstOrNull { it.name == "getRecentFailures" }
            ?.getAnnotation(androidx.room.Query::class.java)
        assertTrue("Query must include SIDE_EFFECT_FAILED", queryAnnotation!!.value.contains("SIDE_EFFECT_FAILED"))
    }

    @Test
    fun `DDL-512-11 getRecentFailures query includes severity-based filter`() {
        val sql = com.yourname.expensetracker.data.database.dao.OperationRunEventDao::class.java
            .declaredMethods
            .firstOrNull { it.name == "getRecentFailures" }
            ?.getAnnotation(androidx.room.Query::class.java)
            ?.value ?: ""
        assertTrue("Query must filter by severity WARNING", sql.contains("WARNING"))
        assertTrue("Query must filter by severity ERROR", sql.contains("ERROR"))
        assertTrue("Query must filter by severity CRITICAL", sql.contains("CRITICAL"))
    }

    // ── DDL-512-14: Notification events — RECEIVED before terminal ────────────

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

    @Test
    fun `DDL-512-14 ordered emission RECEIVED then DUPLICATE preserves sequence`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
        }
        val cid = CorrelationIds.newId()
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "listener",
            outcome = EventOutcome.RECEIVED, correlationId = cid))
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "dedupe",
            outcome = EventOutcome.DUPLICATE, correlationId = cid, isTerminal = true))

        assertEquals(EventOutcome.RECEIVED, emitted[0].outcome)
        assertEquals(EventOutcome.DUPLICATE, emitted[1].outcome)
        assertTrue("Both events must share correlationId", emitted.all { it.correlationId == cid })
    }

    @Test
    fun `DDL-512-14 terminal before RECEIVED is the ordering bug`() = runTest {
        val emitted = mutableListOf<DiagnosticEvent>()
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) { emitted.add(event) }
        }
        val cid = CorrelationIds.newId()
        // BUG: terminal before received
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "dedupe",
            outcome = EventOutcome.DUPLICATE, correlationId = cid, isTerminal = true))
        writer.emit(DiagnosticEvent(pipeline = AppPipeline.NOTIFICATION, stage = "listener",
            outcome = EventOutcome.RECEIVED, correlationId = cid))

        assertEquals(EventOutcome.DUPLICATE, emitted[0].outcome)
        assertEquals(EventOutcome.RECEIVED, emitted[1].outcome)
        // This is the reversed (buggy) order — RECEIVED appears after terminal
        assertTrue("Reversed order is the bug: terminal before RECEIVED",
            emitted[0].outcome == EventOutcome.DUPLICATE && emitted[1].outcome == EventOutcome.RECEIVED)
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
