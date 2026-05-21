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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticSideEffectEventWriterTest {

    private lateinit var diagnosticWriter: DiagnosticEventWriter
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
        diagnosticWriter = mockk(relaxed = true)
        sanitizer = EventMetadataSanitizer()
        timeProvider = mockk(relaxed = true)

        writer = DiagnosticSideEffectEventWriter(
            writer = FakeDiagnosticEventWriter(capturedEvents),
            sanitizer = sanitizer,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `started event has correct outcome and isTerminal false`() = runTest {
        writer.started(sampleAction)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.SIDE_EFFECT_STARTED, event.outcome)
        assertFalse(event.isTerminal)
        assertEquals("SIDE_EFFECT", event.stage)
        assertEquals(AppPipeline.TRANSACTION, event.pipeline)
    }

    @Test
    fun `completed event has correct outcome and isTerminal true`() = runTest {
        writer.completed(sampleAction)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.SIDE_EFFECT_COMPLETED, event.outcome)
        assertTrue(event.isTerminal)
        assertEquals("SIDE_EFFECT", event.stage)
    }

    @Test
    fun `skipped event maps reason correctly`() = runTest {
        writer.skipped(sampleAction, SideEffectSkipReason.NOT_APPLICABLE)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.SKIPPED, event.outcome)
        assertTrue(event.isTerminal)
        assertNull(event.reasonCode)
    }

    @Test
    fun `skipped with privacy denied maps to reason code`() = runTest {
        writer.skipped(sampleAction, SideEffectSkipReason.PRIVACY_DENIED)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(DiagnosticReasonCode.PRIVACY_DENIED, event.reasonCode)
    }

    @Test
    fun `skipped with duplicate maps to reason code`() = runTest {
        writer.skipped(sampleAction, SideEffectSkipReason.DUPLICATE)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(DiagnosticReasonCode.DUPLICATE, event.reasonCode)
    }

    @Test
    fun `skipped with permission denied maps to reason code`() = runTest {
        writer.skipped(sampleAction, SideEffectSkipReason.PERMISSION_DENIED)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(DiagnosticReasonCode.PERMISSION_DENIED, event.reasonCode)
    }

    @Test
    fun `failed retryable has correct outcome and severity`() = runTest {
        writer.failed(sampleAction, retryable = true, reason = "Network error", error = null)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.FAILED_RETRYABLE, event.outcome)
        assertTrue(event.isTerminal)
    }

    @Test
    fun `failed final has correct outcome`() = runTest {
        writer.failed(sampleAction, retryable = false, reason = "Fatal error", error = null)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.FAILED_FINAL, event.outcome)
        assertTrue(event.isTerminal)
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
    fun `cancelled event has correct outcome`() = runTest {
        writer.cancelled(sampleAction, "User interrupted")
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        assertTrue(event.isTerminal)
        assertEquals(DiagnosticReasonCode.CANCELLED_BY_SYSTEM, event.reasonCode)
    }

    @Test
    fun `cancelled event with null reason`() = runTest {
        writer.cancelled(sampleAction, null)
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals(EventOutcome.CANCELLED, event.outcome)
        assertTrue(event.isTerminal)
    }

    @Test
    fun `event has correct entity type and id`() = runTest {
        writer.started(sampleAction)
        val event = capturedEvents[0]
        assertEquals("expense", event.entityType)
        assertEquals(42L, event.entityId)
    }

    @Test
    fun `event has correct source type`() = runTest {
        writer.started(sampleAction)
        val event = capturedEvents[0]
        assertEquals("test_source", event.sourceType)
    }

    @Test
    fun `event has correlation and causation ids`() = runTest {
        writer.started(sampleAction)
        val event = capturedEvents[0]
        assertEquals("corr-123", event.correlationId)
        assertEquals("caus-456", event.causationId)
    }

    @Test
    fun `metadata contains side effect info`() = runTest {
        writer.started(sampleAction)
        val event = capturedEvents[0]
        val metadataJson = event.metadata.toJson()
        assertTrue(metadataJson.contains("sideEffectName"))
        assertTrue(metadataJson.contains("test_action"))
        assertTrue(metadataJson.contains("category"))
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
