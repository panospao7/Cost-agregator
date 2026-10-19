package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.MaintenanceBlockedReason
import com.yourname.expensetracker.data.backup.MaintenanceDiagnosticRecord
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Drives the real production [SafeSinkOperationRunHandle]; only its sink, maintenance
 * mode and clock collaborators are faked.
 */
class SafeSinkOperationRunHandleTest {

    private class RecordingSink : MaintenanceSafeDiagnosticSink {
        val events = mutableListOf<DiagnosticEvent>()
        val modes = mutableListOf<RestoreMaintenanceMode.Mode>()
        var failure: Throwable? = null
        var attempts = 0

        override suspend fun recordBlockedOperation(
            operation: String,
            mode: RestoreMaintenanceMode.Mode,
            pipeline: String?,
            entity: String?,
            reason: MaintenanceBlockedReason
        ) = Unit

        override suspend fun recordDiagnosticEvent(
            event: DiagnosticEvent,
            mode: RestoreMaintenanceMode.Mode,
            writeFailure: Throwable?
        ) {
            attempts++
            failure?.let { throw it }
            events.add(event)
            modes.add(mode)
        }

        override fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>> = emptyFlow()

        override suspend fun clearOlderThan(cutoffMs: Long) = Unit
    }

    private fun maintenanceMode(mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING): RestoreMaintenanceMode =
        mockk<RestoreMaintenanceMode>().also { every { it.currentMode() } returns mode }

    private val fixedTime = object : TimeProvider {
        override fun now(): Long = 1_700_000_000_000L
    }

    private fun createHandle(
        sink: RecordingSink,
        operationType: String = "BACKUP_EXPORT",
        mode: RestoreMaintenanceMode = maintenanceMode()
    ) = SafeSinkOperationRunHandle(
        correlationId = "corr-test",
        operationType = operationType,
        safeSink = sink,
        restoreMaintenanceMode = mode,
        timeProvider = fixedTime
    )

    // ── (a) terminal-once ─────────────────────────────────────────────────────

    @Test
    fun `success then failedFinal emits exactly one terminal event`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)

        handle.success()
        assertTrue(handle.isTerminal)
        handle.failedFinal("late failure", null)

        assertEquals(1, sink.events.size)
        assertEquals("SUCCESS", sink.events[0].stage)
        assertEquals(EventOutcome.COMPLETED, sink.events[0].outcome)
        assertTrue(sink.events[0].isTerminal)
    }

    @Test
    fun `partialSuccess then success and failedFinal emit only the partial success terminal event`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)

        handle.partialSuccess("some rows skipped")
        assertTrue(handle.isTerminal)
        handle.success()
        handle.failedFinal("late failure", null)

        assertEquals(1, sink.events.size)
        assertEquals("PARTIAL_SUCCESS", sink.events[0].stage)
        assertEquals(EventOutcome.COMPLETED, sink.events[0].outcome)
        assertTrue(sink.events[0].isTerminal)
    }

    @Test
    fun `cancelled then success emits only the cancelled terminal event`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)

        handle.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        handle.success()

        assertEquals(1, sink.events.size)
        assertEquals(EventOutcome.CANCELLED, sink.events[0].outcome)
        assertEquals(DiagnosticReasonCode.RESTORE_BLOCKED, sink.events[0].reasonCode)
    }

    @Test
    fun `direct terminal event marks handle terminal and later terminal calls emit nothing`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)
        assertFalse(handle.isTerminal)

        handle.event("WRITE_BARRIER", EventOutcome.BLOCKED, isTerminal = true)
        assertTrue(handle.isTerminal)
        handle.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        handle.partialSuccess("ignored")
        handle.event("WRITE_BARRIER_AGAIN", EventOutcome.BLOCKED, isTerminal = true)

        assertEquals(1, sink.events.size)
        assertEquals("WRITE_BARRIER", sink.events[0].stage)
    }

    @Test
    fun `non-terminal stage event does not mark handle terminal`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)

        handle.event("STARTED", EventOutcome.ATTEMPTED, isTerminal = false)

        assertFalse(handle.isTerminal)
        handle.success()
        assertEquals(listOf("STARTED", "SUCCESS"), sink.events.map { it.stage })
    }

    @Test
    fun `cancelled preserves known reason code and defaults unknown or null to CANCELLED_BY_SYSTEM`() = runTest {
        val known = RecordingSink().also { createHandle(it).cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name) }
        val unknown = RecordingSink().also { createHandle(it).cancelled("NOT_A_REAL_REASON_CODE") }
        val nullReason = RecordingSink().also { createHandle(it).cancelled(null) }

        assertEquals(DiagnosticReasonCode.RESTORE_BLOCKED, known.events.single().reasonCode)
        assertEquals(DiagnosticReasonCode.CANCELLED_BY_SYSTEM, unknown.events.single().reasonCode)
        assertEquals(DiagnosticReasonCode.CANCELLED_BY_SYSTEM, nullReason.events.single().reasonCode)
    }

    @Test
    fun `terminal summary carries accumulated counters and the current maintenance mode`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink, mode = maintenanceMode(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING))

        handle.increment(processed = 5, succeeded = 3, failed = 1, skipped = 1, warnings = 2, errors = 1)
        handle.increment(processed = 2, succeeded = 2)
        handle.success()

        val event = sink.events.single()
        assertEquals(AppPipeline.BACKUP_RESTORE, event.pipeline)
        assertEquals("corr-test", event.correlationId)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, sink.modes.single())
        val json = JSONObject(event.metadata.toJson())
        assertEquals(7, json.getInt("rowsProcessed"))
        assertEquals(5, json.getInt("rowsSucceeded"))
        assertEquals(1, json.getInt("rowsFailed"))
        assertEquals(1, json.getInt("rowsSkipped"))
        assertEquals(2, json.getInt("warningCount"))
        assertEquals(1, json.getInt("errorCount"))
    }

    // ── (b) regular sink failures are swallowed ───────────────────────────────

    @Test
    fun `regular sink exceptions do not propagate from stage or terminal calls`() = runTest {
        val failures = listOf<Throwable>(
            IllegalStateException("sink exploded"),
            IOException("disk full")
        )
        for (failure in failures) {
            val sink = RecordingSink().apply { this.failure = failure }
            val handle = createHandle(sink)

            handle.event("STAGE", EventOutcome.ATTEMPTED, isTerminal = false)
            handle.increment(processed = 1)
            handle.success()

            assertEquals("sink must have been attempted for ${failure::class.simpleName}", 2, sink.attempts)
            assertTrue(handle.isTerminal)
            assertTrue(sink.events.isEmpty())
        }
    }

    @Test
    fun `handle stays usable after a swallowed sink failure on a stage event`() = runTest {
        val sink = RecordingSink().apply { failure = IllegalStateException("transient") }
        val handle = createHandle(sink)

        handle.event("STAGE_LOST", EventOutcome.ATTEMPTED, isTerminal = false)
        assertFalse(handle.isTerminal)

        sink.failure = null
        handle.failedRetryable("retry later", null)

        assertTrue(handle.isTerminal)
        assertEquals(listOf("FAILED_RETRYABLE"), sink.events.map { it.stage })
    }

    @Test
    fun `terminal state is latched before emission so a failed terminal emit is not retried by later terminal calls`() = runTest {
        val sink = RecordingSink().apply { failure = IllegalStateException("transient") }
        val handle = createHandle(sink)

        handle.success()
        assertTrue(handle.isTerminal)

        sink.failure = null
        handle.failedFinal("second terminal", null)

        assertEquals(1, sink.attempts)
        assertTrue(sink.events.isEmpty())
    }

    // ── (c) CancellationException must propagate ──────────────────────────────

    @Test
    fun `cancellation from sink propagates out of a stage event`() = runTest {
        val sink = RecordingSink().apply { failure = CancellationException("cancelled in sink") }
        val handle = createHandle(sink)

        assertFailsWith<CancellationException> {
            handle.event("STAGE", EventOutcome.ATTEMPTED, isTerminal = false)
        }
        assertFalse(handle.isTerminal)
    }

    @Test
    fun `cancellation from sink propagates out of terminal success and the handle stays terminal-once`() = runTest {
        val cancellation = CancellationException("cancelled in sink")
        val sink = RecordingSink().apply { failure = cancellation }
        val handle = createHandle(sink)

        val thrown = assertFailsWith<CancellationException> { handle.success() }
        assertSame(cancellation, thrown)
        // Terminal state is latched before emission: a retry after cancellation emits nothing.
        assertTrue(handle.isTerminal)
        sink.failure = null
        handle.failedFinal("after cancellation", null)
        assertEquals(1, sink.attempts)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `cancellation from sink propagates out of failedFinal and cancelled`() = runTest {
        assertFailsWith<CancellationException> {
            createHandle(RecordingSink().apply { failure = CancellationException("c1") })
                .failedFinal("boom", IllegalStateException("boom"))
        }
        assertFailsWith<CancellationException> {
            createHandle(RecordingSink().apply { failure = CancellationException("c2") })
                .cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)
        }
    }

    @Test
    fun `cancellation from sink propagates out of direct terminal event`() = runTest {
        val sink = RecordingSink().apply { failure = CancellationException("cancelled in sink") }
        val handle = createHandle(sink)

        assertFailsWith<CancellationException> {
            handle.event("WRITE_BARRIER", EventOutcome.BLOCKED, isTerminal = true)
        }
        assertTrue(handle.isTerminal)
    }

    // ── (d) metadata sanitization is applied to the reason carried in the event ──

    /**
     * Narrow claim: a bearer token and a data-directory path inside the free-text failure
     * reason (`statusReason` is free text, not a bounded reason code) are redacted from the
     * metadata JSON, and the exception message is not copied into the metadata. It does NOT
     * prove that arbitrary free-text reasons are safe.
     */
    @Test
    fun `failedFinal reason redacts a bearer token and a data path from metadata and does not copy the exception message into it`() = runTest {
        val sink = RecordingSink()
        val handle = createHandle(sink)
        val secretToken = "abcdefghijklmnop1234567890"
        val secretPath = "/data/user/0/com.private.app/files/secret-backup.bin"
        val error = IllegalStateException("Failed reading $secretPath")

        handle.failedFinal("Bearer $secretToken rejected at $secretPath", error)

        val event = sink.events.single()
        val metadataJson = event.metadata.toJson()
        assertFalse("bearer token must not reach metadata", metadataJson.contains(secretToken))
        assertFalse("file path must not reach metadata", metadataJson.contains("/data/user/0"))
        assertFalse("exception message must not be copied into metadata", metadataJson.contains("Failed reading"))
        // The raw Throwable is forwarded as-is on purpose: the sink is the sanitization point for
        // the exception (not the handle), and the handle must not stringify it into metadata.
        assertSame(error, event.exception)
        assertEquals(EventOutcome.FAILED_FINAL, event.outcome)
        assertEquals(EventSeverity.ERROR, event.severity)
    }
}
