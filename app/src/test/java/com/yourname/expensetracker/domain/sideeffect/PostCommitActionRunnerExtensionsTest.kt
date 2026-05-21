package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostCommitActionRunnerExtensionsTest {

    private lateinit var runner: PostCommitActionRunner
    private val correlationId = "test-corr"
    private val logMessage = "test message"

    @Before
    fun setup() {
        runner = mockk()
    }

    private fun batchWithOneAction(): PostCommitActionBatch {
        val action = PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "expense",
            targetEntityId = 42L,
            source = "test",
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "key-1",
            execute = { SideEffectOutcome.Completed }
        )
        return PostCommitActionBatch(correlationId, listOf(action))
    }

    @Test
    fun `runBestEffortAfterCommit_empty_batch_returns_null`() = runTest {
        val emptyBatch = PostCommitActionBatch.empty(correlationId)
        val result = runner.runBestEffortAfterCommit(emptyBatch, logMessage)

        assertNull(result)
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `runBestEffortAfterCommit_success_returns_result`() = runTest {
        val batch = batchWithOneAction()
        val expectedResult = SideEffectBatchResult(
            correlationId = correlationId,
            completed = 1,
            skipped = 0,
            failedRetryable = 0,
            failedFinal = 0,
            cancelled = 0,
            outcomes = listOf(
                SideEffectActionResult(
                    idempotencyKey = "key-1",
                    name = "test_action",
                    outcome = SideEffectOutcome.Completed
                )
            )
        )
        coEvery { runner.run(batch) } returns expectedResult

        val result = runner.runBestEffortAfterCommit(batch, logMessage)

        assertEquals(expectedResult, result)
    }

    @Test
    fun `runBestEffortAfterCommit_non_cancellation_exception returns null`() = runTest {
        val batch = batchWithOneAction()
        val exception = RuntimeException("Something went wrong")
        coEvery { runner.run(batch) } throws exception

        val result = runner.runBestEffortAfterCommit(batch, logMessage)

        assertNull(result)
    }

    @Test
    fun `runBestEffortAfterCommit_cancellation_exception_rethrows`() = runTest {
        val batch = batchWithOneAction()
        val exception = CancellationException("Cancelled by user")
        coEvery { runner.run(batch) } throws exception

        try {
            runner.runBestEffortAfterCommit(batch, logMessage)
            assertTrue("Expected CancellationException to be rethrown", false)
        } catch (e: CancellationException) {
            assertEquals("Cancelled by user", e.message)
        }
    }
}
