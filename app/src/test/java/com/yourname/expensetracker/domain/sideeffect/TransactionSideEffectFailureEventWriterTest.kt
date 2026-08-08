package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * T3A / G-TIME-01 focused tests for [TransactionSideEffectFailureEventWriter].
 *
 * The mirror SIDE_EFFECT_FAILED event must be stamped with the injected
 * [FakeTimeProvider] clock (a single deterministic read), never
 * `System.currentTimeMillis()`, and the written [TransactionContext] must stay
 * internally consistent (startedAtMs defaults to occurredAt).
 */
class TransactionSideEffectFailureEventWriterTest {

    /** Fixed "now" chosen far from the real wall clock so the assertion is meaningful. */
    private val fixedNow = 1_900_000_000_000L

    private val contextSlot = slot<TransactionContext>()
    private val eventSlot = slot<TransactionLifecycleEvent>()
    private lateinit var transactionEventWriter: TransactionLifecycleEventWriter
    private lateinit var writer: TransactionSideEffectFailureEventWriter

    private val expenseAction = PostCommitAction(
        pipeline = AppPipeline.TRANSACTION,
        name = "test_action",
        category = SideEffectCategory.BUDGET,
        triggerType = SideEffectTriggerType.EXPENSE_CREATED,
        targetEntityType = "Expense",
        targetEntityId = 42L,
        source = "test_source",
        correlationId = "corr-123",
        causationId = null,
        idempotencyKey = "idem-789",
        execute = { SideEffectOutcome.Completed }
    )

    @Before
    fun setup() {
        transactionEventWriter = mockk(relaxed = true)
        coEvery { transactionEventWriter.write(capture(contextSlot), capture(eventSlot)) } returns Unit
        writer = TransactionSideEffectFailureEventWriter(
            transactionEventWriter = transactionEventWriter,
            timeProvider = FakeTimeProvider(fixedNow)
        )
    }

    @Test
    fun `failed event occurredAt equals the injected fixed time`() = runTest {
        writer.failed(expenseAction, retryable = true, reason = "transient", error = null)

        coVerify(exactly = 1) { transactionEventWriter.write(any(), any()) }
        assertEquals(
            "occurredAt must come from the injected TimeProvider",
            fixedNow, contextSlot.captured.occurredAt
        )
    }

    @Test
    fun `failed event keeps startedAtMs consistent with occurredAt`() = runTest {
        writer.failed(expenseAction, retryable = false, reason = "final", error = RuntimeException("boom"))

        val context = contextSlot.captured
        assertEquals(fixedNow, context.occurredAt)
        assertEquals(
            "startedAtMs defaults to occurredAt so the single-read event stays internally consistent",
            context.occurredAt, context.startedAtMs
        )
        assertTrue(
            "ordering contract: a transaction cannot start after it occurred",
            context.startedAtMs <= context.occurredAt
        )
    }

    @Test
    fun `failed event mirrors the SIDE_EFFECT_FAILED payload`() = runTest {
        writer.failed(expenseAction, retryable = true, reason = "retryable failure", error = null)

        val event = eventSlot.captured
        assertEquals(LifecycleEventType.SIDE_EFFECT_FAILED.name, event.eventType)
        assertEquals("system:post_commit_action_runner", event.actor)
        assertEquals(42L, event.expenseId)
        assertEquals("corr-123", event.correlationId)
        assertEquals("test_source", event.source)
        assertEquals("post_commit_action_runner", contextSlot.captured.source)
        assertTrue("metadata must carry the bounded actionName field", event.metadata.toJson().contains("test_action"))
    }

    @Test
    fun `failed propagates CancellationException instead of swallowing it`() = runTest {
        val throwingWriter = mockk<TransactionLifecycleEventWriter>(relaxed = true)
        coEvery { throwingWriter.write(any(), any()) } throws CancellationException("cancelled downstream")

        val writer = TransactionSideEffectFailureEventWriter(
            transactionEventWriter = throwingWriter,
            timeProvider = FakeTimeProvider(fixedNow)
        )

        try {
            writer.failed(expenseAction, retryable = true, reason = "x", error = null)
            fail("CancellationException must propagate out of failed()")
        } catch (e: CancellationException) {
            // Expected: the failure event writer must not swallow system cancellation.
        }
    }
}
