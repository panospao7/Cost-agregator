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

    @Test
    fun `empty batch returns zero actions`() {
        val batch = PostCommitActionBatch.empty("corr-empty")
        assertEquals("corr-empty", batch.correlationId)
        assertEquals(0, batch.actions.size)
    }

    @Test
    fun `plus operator merges two batches`() {
        val batch1 = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val batch2 = PostCommitActionBatch("corr-1", listOf(anotherAction))
        val merged = batch1 + batch2
        assertEquals(2, merged.actions.size)
        assertEquals("key-1", merged.actions[0].idempotencyKey)
        assertEquals("key-2", merged.actions[1].idempotencyKey)
    }

    @Test
    fun `normalized removes duplicate idempotency keys keeping first occurrence`() {
        val duplicateAction = sampleAction.copy(name = "duplicate", idempotencyKey = "key-1")
        val batch = PostCommitActionBatch(
            "corr-1",
            listOf(sampleAction, anotherAction, duplicateAction)
        )
        val normalized = batch.normalized()
        assertEquals(2, normalized.actions.size)
        assertEquals("test_action", normalized.actions[0].name)
        assertEquals("another_action", normalized.actions[1].name)
    }

    @Test
    fun `normalized on unique actions returns same count`() {
        val batch = PostCommitActionBatch(
            "corr-1",
            listOf(sampleAction, anotherAction)
        )
        val normalized = batch.normalized()
        assertEquals(2, normalized.actions.size)
    }

    @Test
    fun `normalized on empty batch returns empty`() {
        val batch = PostCommitActionBatch.empty("corr-empty")
        val normalized = batch.normalized()
        assertEquals(0, normalized.actions.size)
    }

    @Test
    fun `copy preserves immutability`() {
        val batch = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val copy = batch.copy(correlationId = "corr-2")
        assertNotEquals(batch.correlationId, copy.correlationId)
        assertEquals(batch.actions, copy.actions)
    }

    @Test
    fun `plus operator preserves original batches`() {
        val batch1 = PostCommitActionBatch("corr-1", listOf(sampleAction))
        val batch2 = PostCommitActionBatch("corr-1", listOf(anotherAction))
        val merged = batch1 + batch2
        assertEquals(1, batch1.actions.size)
        assertEquals(1, batch2.actions.size)
        assertEquals(2, merged.actions.size)
    }
}
