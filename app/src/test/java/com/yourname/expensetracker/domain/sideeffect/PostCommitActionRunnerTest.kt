package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostCommitActionRunnerTest {

    private lateinit var eventWriter: FakeSideEffectEventWriter
    private lateinit var runner: PostCommitActionRunnerImpl

    private val sampleAction: PostCommitAction
        get() = PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "expense",
            targetEntityId = 42L,
            source = "test",
            correlationId = "corr-1",
            causationId = null,
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )

    @Before
    fun setup() {
        eventWriter = FakeSideEffectEventWriter()
        runner = PostCommitActionRunnerImpl(eventWriter = eventWriter)
    }

    @Test
    fun `emits started and completed for successful action`() = runTest {
        val batch = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val result = runner.run(batch)

        assertEquals(1, eventWriter.startedCount)
        assertEquals(1, eventWriter.completedCount)
        assertEquals(0, eventWriter.skippedCount)
        assertEquals(0, eventWriter.failedCount)
        assertEquals(0, eventWriter.cancelledCount)

        assertEquals(1, result.completed)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes[0].outcome is SideEffectOutcome.Completed)
    }

    @Test
    fun `emits skipped with reason`() = runTest {
        val skippedAction = sampleAction.copy(
            name = "skip_action",
            idempotencyKey = "key-skip",
            execute = { SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE) }
        )
        val batch = PostCommitActionBatch("corr-1", listOf(skippedAction))
        val result = runner.run(batch)

        assertEquals(1, eventWriter.startedCount)
        assertEquals(0, eventWriter.completedCount)
        assertEquals(1, eventWriter.skippedCount)
        assertEquals(0, eventWriter.failedCount)
        assertEquals(0, eventWriter.cancelledCount)

        assertEquals(1, result.skipped)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes[0].outcome is SideEffectOutcome.Skipped)
        assertEquals(
            SideEffectSkipReason.NOT_APPLICABLE,
            (result.outcomes[0].outcome as SideEffectOutcome.Skipped).reason
        )
    }

    @Test
    fun `emits failed retryable when action throws`() = runTest {
        val throwingAction = sampleAction.copy(
            name = "throw_action",
            idempotencyKey = "key-throw",
            execute = { throw RuntimeException("Something went wrong") }
        )
        val batch = PostCommitActionBatch("corr-1", listOf(throwingAction))
        val result = runner.run(batch)

        assertEquals(1, eventWriter.startedCount)
        assertEquals(0, eventWriter.completedCount)
        assertEquals(1, eventWriter.failedCount)
        assertEquals(0, eventWriter.cancelledCount)

        assertEquals(1, result.failedRetryable)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes[0].outcome is SideEffectOutcome.FailedRetryable)
    }

    @Test
    fun `emits cancelled and rethrows on CancellationException`() = runTest {
        val cancellingAction = sampleAction.copy(
            name = "cancel_action",
            idempotencyKey = "key-cancel",
            execute = { throw CancellationException("Cancelled by user") }
        )
        val batch = PostCommitActionBatch("corr-1", listOf(cancellingAction))

        try {
            runner.run(batch)
            // Should not reach here
            assertFalse("Expected CancellationException to be rethrown", true)
        } catch (e: CancellationException) {
            assertEquals("Cancelled by user", e.message)
        }

        assertEquals(1, eventWriter.startedCount)
        assertEquals(0, eventWriter.completedCount)
        assertEquals(0, eventWriter.failedCount)
        assertEquals(1, eventWriter.cancelledCount)
    }

    @Test
    fun `continues after non-cancellation failure`() = runTest {
        val firstAction = sampleAction.copy(
            name = "first_fail",
            idempotencyKey = "key-1",
            execute = { throw RuntimeException("First failed") }
        )
        val secondAction = sampleAction.copy(
            name = "second_succeed",
            idempotencyKey = "key-2",
            execute = { SideEffectOutcome.Completed }
        )
        val thirdAction = sampleAction.copy(
            name = "third_skip",
            idempotencyKey = "key-3",
            execute = { SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK) }
        )

        val batch = PostCommitActionBatch(
            "corr-1",
            listOf(firstAction, secondAction, thirdAction)
        )
        val result = runner.run(batch)

        assertEquals(3, eventWriter.startedCount)
        assertEquals(1, eventWriter.completedCount)
        assertEquals(1, eventWriter.skippedCount)
        assertEquals(1, eventWriter.failedCount)
        assertEquals(0, eventWriter.cancelledCount)

        assertEquals(1, result.completed)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failedRetryable)
        assertEquals(0, result.failedFinal)
        assertEquals(3, result.outcomes.size)
    }

    @Test
    fun `preserves action order`() = runTest {
        val action1 = sampleAction.copy(
            name = "first",
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )
        val action2 = sampleAction.copy(
            name = "second",
            idempotencyKey = "key-2",
            execute = { SideEffectOutcome.Completed }
        )
        val action3 = sampleAction.copy(
            name = "third",
            idempotencyKey = "key-3",
            execute = { SideEffectOutcome.Completed }
        )

        val batch = PostCommitActionBatch(
            "corr-1",
            listOf(action1, action2, action3)
        )
        val result = runner.run(batch)

        assertEquals(3, result.outcomes.size)
        assertEquals("first", result.outcomes[0].name)
        assertEquals("second", result.outcomes[1].name)
        assertEquals("third", result.outcomes[2].name)
    }

    @Test
    fun `empty batch returns zero counts`() = runTest {
        val batch = PostCommitActionBatch.empty("corr-empty")
        val result = runner.run(batch)

        assertEquals(0, result.completed)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failedRetryable)
        assertEquals(0, result.failedFinal)
        assertEquals(0, result.cancelled)
        assertEquals(0, result.outcomes.size)
        assertEquals("corr-empty", result.correlationId)

        assertEquals(0, eventWriter.startedCount)
        assertEquals(0, eventWriter.completedCount)
        assertEquals(0, eventWriter.skippedCount)
        assertEquals(0, eventWriter.failedCount)
        assertEquals(0, eventWriter.cancelledCount)
    }

    @Test
    fun `handles failed final outcome`() = runTest {
        val finalFailAction = sampleAction.copy(
            name = "final_fail",
            idempotencyKey = "key-final",
            execute = {
                SideEffectOutcome.FailedFinal(
                    reason = "Non-retryable error",
                    errorClass = "java.lang.IllegalStateException"
                )
            }
        )
        val batch = PostCommitActionBatch("corr-1", listOf(finalFailAction))
        val result = runner.run(batch)

        assertEquals(0, result.completed)
        assertEquals(1, result.failedFinal)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes[0].outcome is SideEffectOutcome.FailedFinal)
    }

    @Test
    fun `handles cancelled outcome`() = runTest {
        val cancelAction = sampleAction.copy(
            name = "cancel_outcome",
            idempotencyKey = "key-cancel-outcome",
            execute = { SideEffectOutcome.Cancelled("Voluntarily cancelled") }
        )
        val batch = PostCommitActionBatch("corr-1", listOf(cancelAction))
        val result = runner.run(batch)

        assertEquals(0, result.completed)
        assertEquals(1, result.cancelled)
        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes[0].outcome is SideEffectOutcome.Cancelled)
    }

    /**
     * A fake SideEffectEventWriter that records event counts.
     */
    private class FakeSideEffectEventWriter : SideEffectEventWriter {
        var startedCount = 0
        var completedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var cancelledCount = 0

        override suspend fun started(action: PostCommitAction) {
            startedCount++
        }

        override suspend fun completed(action: PostCommitAction) {
            completedCount++
        }

        override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) {
            skippedCount++
        }

        override suspend fun failed(
            action: PostCommitAction,
            retryable: Boolean,
            reason: String,
            error: Throwable?
        ) {
            failedCount++
        }

        override suspend fun cancelled(action: PostCommitAction, reason: String?) {
            cancelledCount++
        }
    }
}
