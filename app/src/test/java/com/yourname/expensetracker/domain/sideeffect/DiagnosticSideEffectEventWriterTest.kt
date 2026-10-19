package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticSideEffectEventWriterTest {

    private lateinit var sanitizer: EventMetadataSanitizer
    private lateinit var timeProvider: TimeProvider
    private lateinit var writer: DiagnosticSideEffectEventWriter

    private val capturedEvents = mutableListOf<DiagnosticEvent>()

    private val sampleAction = PostCommitAction(
        pipeline = AppPipeline.TRANSACTION,
        name = "test_action",
        category = SideEffectCategory.BUDGET,
        triggerType = SideEffectTriggerType.EXPENSE_CREATED,
        targetEntityType = "expense",
        targetEntityId = 42L,
        source = "test_source",
        correlationId = "corr-123",
        causationId = "caus-456",
        idempotencyKey = "idem-789",
        execute = { SideEffectOutcome.Completed }
    )

    @Before
    fun setup() {
        capturedEvents.clear()
        sanitizer = EventMetadataSanitizer()
        timeProvider = mockk(relaxed = true)

        writer = DiagnosticSideEffectEventWriter(
            writer = FakeDiagnosticEventWriter(capturedEvents),
            sanitizer = sanitizer,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `started and completed events have correct outcome terminal flag and stage`() = runTest {
        // started: not terminal
        writer.started(sampleAction)
        assertEquals("one event after started", 1, capturedEvents.size)
        val started = capturedEvents[0]
        assertEquals("started outcome", EventOutcome.SIDE_EFFECT_STARTED, started.outcome)
        assertEquals("started isTerminal", false, started.isTerminal)
        assertEquals("started stage", "SIDE_EFFECT", started.stage)
        assertEquals("started pipeline", AppPipeline.TRANSACTION, started.pipeline)

        // completed: terminal
        capturedEvents.clear()
        writer.completed(sampleAction)
        assertEquals("one event after completed", 1, capturedEvents.size)
        val completed = capturedEvents[0]
        assertEquals("completed outcome", EventOutcome.SIDE_EFFECT_COMPLETED, completed.outcome)
        assertTrue("completed isTerminal", completed.isTerminal)
        assertEquals("completed stage", "SIDE_EFFECT", completed.stage)
    }

    @Test
    fun `skipped event maps each skip reason to its diagnostic reason code`() = runTest {
        // rows: skip reason -> expected reason code (null = no mapped code)
        val rows = listOf(
            SideEffectSkipReason.NOT_APPLICABLE to null,
            SideEffectSkipReason.PRIVACY_DENIED to DiagnosticReasonCode.PRIVACY_DENIED,
            SideEffectSkipReason.DUPLICATE to DiagnosticReasonCode.DUPLICATE,
            SideEffectSkipReason.PERMISSION_DENIED to DiagnosticReasonCode.PERMISSION_DENIED
        )
        rows.forEach { (skipReason, expectedCode) ->
            capturedEvents.clear()
            writer.skipped(sampleAction, skipReason)
            assertEquals("one event for row: $skipReason", 1, capturedEvents.size)
            val event = capturedEvents[0]
            assertEquals("outcome for row: $skipReason", EventOutcome.SKIPPED, event.outcome)
            assertTrue("isTerminal for row: $skipReason", event.isTerminal)
            if (expectedCode == null) {
                assertNull("reasonCode for row: $skipReason", event.reasonCode)
            } else {
                assertEquals("reasonCode for row: $skipReason", expectedCode, event.reasonCode)
            }
        }
    }

    @Test
    fun `failed event outcome follows retryable flag and is terminal`() = runTest {
        // rows: retryable flag, reason -> expected outcome
        val rows = listOf(
            Triple(true, "Network error", EventOutcome.FAILED_RETRYABLE),
            Triple(false, "Fatal error", EventOutcome.FAILED_FINAL)
        )
        rows.forEach { (retryable, reason, expectedOutcome) ->
            capturedEvents.clear()
            writer.failed(sampleAction, retryable = retryable, reason = reason, error = null)
            assertEquals("one event for row retryable=$retryable", 1, capturedEvents.size)
            val event = capturedEvents[0]
            assertEquals("outcome for row retryable=$retryable", expectedOutcome, event.outcome)
            assertTrue("isTerminal for row retryable=$retryable", event.isTerminal)
        }
    }

    @Test
    fun `failed event includes exception`() = runTest {
        val exception = RuntimeException("Test exception")
        writer.failed(sampleAction, retryable = true, reason = "Error", error = exception)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertNotNull(event.exception)
        assertEquals("Test exception", event.exception?.message)
    }

    @Test
    fun `cancelled event is terminal for both provided and null reason`() = runTest {
        writer.cancelled(sampleAction, "User interrupted")
        assertEquals("one event for row: reason provided", 1, capturedEvents.size)
        val withReason = capturedEvents[0]
        assertEquals("outcome for row: reason provided", EventOutcome.CANCELLED, withReason.outcome)
        assertTrue("isTerminal for row: reason provided", withReason.isTerminal)
        assertEquals(
            "reasonCode for row: reason provided",
            DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
            withReason.reasonCode
        )

        capturedEvents.clear()
        writer.cancelled(sampleAction, null)
        assertEquals("one event for row: null reason", 1, capturedEvents.size)
        val nullReason = capturedEvents[0]
        assertEquals("outcome for row: null reason", EventOutcome.CANCELLED, nullReason.outcome)
        assertTrue("isTerminal for row: null reason", nullReason.isTerminal)
    }

    @Test
    fun `event carries entity source correlation causation and side effect metadata`() = runTest {
        writer.started(sampleAction)
        val event = capturedEvents[0]
        assertEquals("entityType", "expense", event.entityType)
        assertEquals("entityId", 42L, event.entityId)
        assertEquals("sourceType", "test_source", event.sourceType)
        assertEquals("correlationId", "corr-123", event.correlationId)
        assertEquals("causationId", "caus-456", event.causationId)
        val metadataJson = event.metadata.toJson()
        assertTrue("metadata sideEffectName key", metadataJson.contains("sideEffectName"))
        assertTrue("metadata action name", metadataJson.contains("test_action"))
        assertTrue("metadata category key", metadataJson.contains("category"))
    }

    /**
     * A fake DiagnosticEventWriter that captures emitted events.
     */
    private class FakeDiagnosticEventWriter(
        private val captured: MutableList<DiagnosticEvent>
    ) : DiagnosticEventWriter {
        override suspend fun emit(event: DiagnosticEvent) {
            captured.add(event)
        }
    }
}
