package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PostCommitActionBatchTest {

    private val sampleAction = PostCommitAction(
        pipeline = AppPipeline.TRANSACTION,
        name = "test_action",
        category = SideEffectCategory.BUDGET,
        triggerType = SideEffectTriggerType.EXPENSE_CREATED,
        targetEntityType = "expense",
        targetEntityId = 42L,
        source = "test",
        correlationId = "corr-1",
        causationId = "caus-1",
        idempotencyKey = "key-1",
        execute = { SideEffectOutcome.Completed }
    )

    private val anotherAction = sampleAction.copy(
        name = "another_action",
        idempotencyKey = "key-2"
    )

    private val duplicateAction = sampleAction.copy(name = "duplicate", idempotencyKey = "key-1")

    @Test
    fun `empty batch returns zero actions`() {
        val batch = PostCommitActionBatch.empty("corr-empty")
        assertEquals("corr-empty", batch.correlationId)
        assertEquals(0, batch.actions.size)
    }

    @Test
    fun `normalized keeps first occurrence per idempotency key`() {
        // row: label, input actions, expected action names in order
        val rows = listOf(
            Triple("empty batch", emptyList<PostCommitAction>(), emptyList<String>()),
            Triple("unique actions keep count", listOf(sampleAction, anotherAction), listOf("test_action", "another_action")),
            Triple(
                "duplicate idempotency key dropped keeping first occurrence",
                listOf(sampleAction, anotherAction, duplicateAction),
                listOf("test_action", "another_action")
            )
        )
        rows.forEach { (label, input, expectedNames) ->
            val normalized = PostCommitActionBatch("corr-1", input).normalized()
            assertEquals("normalized names for row: $label", expectedNames, normalized.actions.map { it.name })
        }
    }

    @Test
    fun `plus operator merges in order and preserves original batches`() {
        val batch1 = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val batch2 = PostCommitActionBatch("corr-1", listOf(anotherAction))
        val merged = batch1 + batch2
        assertEquals(2, merged.actions.size)
        assertEquals("key-1", merged.actions[0].idempotencyKey)
        assertEquals("key-2", merged.actions[1].idempotencyKey)
        assertEquals(1, batch1.actions.size)
        assertEquals(1, batch2.actions.size)
    }

    @Test
    fun `copy preserves immutability`() {
        val batch = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val copy = batch.copy(correlationId = "corr-2")
        assertNotEquals(batch.correlationId, copy.correlationId)
        assertEquals(batch.actions, copy.actions)
    }
}
