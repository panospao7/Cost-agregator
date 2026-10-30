package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * S10b-1 F3: the single post-commit dispatcher shared by the due-recording worker, the pre-backup
 * reconciler and the entry-actions ViewModel. Pins skip-empty, order, failure isolation with a
 * class-name-only log, and cancellation propagation. JVM only; the runner is a fake.
 */
class InstallmentPostCommitDispatcherTest {

    private val dispatched = mutableListOf<String>()
    private val logs = CapturingTree()

    @Before
    fun plantLogs() {
        logs.plant()
    }

    @After
    fun uprootLogs() {
        logs.uproot()
    }

    private fun batch(id: String) = PostCommitActionBatch(id, listOf(mockk<PostCommitAction>(relaxed = true)))

    private fun runner(failOn: Set<String> = emptySet(), cancelOn: String? = null): PostCommitActionRunner =
        object : PostCommitActionRunner {
            override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
                dispatched += batch.correlationId
                if (batch.correlationId == cancelOn) throw CancellationException("test cancellation")
                if (batch.correlationId in failOn) throw IllegalStateException("batch-secret-${batch.correlationId}")
                return SideEffectBatchResult(batch.correlationId, 0, 0, 0, 0, 0, emptyList())
            }
        }

    @Test
    fun an_empty_list_dispatches_nothing() = runTest {
        InstallmentPostCommitDispatcher(runner()).dispatch(emptyList())

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun a_batch_without_actions_is_skipped() = runTest {
        InstallmentPostCommitDispatcher(runner()).dispatch(
            listOf(PostCommitActionBatch.empty("empty"), batch("b1"))
        )

        assertEquals(listOf("b1"), dispatched)
    }

    @Test
    fun batches_run_in_order() = runTest {
        InstallmentPostCommitDispatcher(runner()).dispatch(listOf(batch("b1"), batch("b2"), batch("b3")))

        assertEquals(listOf("b1", "b2", "b3"), dispatched)
    }

    @Test
    fun a_failing_batch_is_isolated_and_logged_by_class_name_only() = runTest {
        InstallmentPostCommitDispatcher(runner(failOn = setOf("b2")))
            .dispatch(listOf(batch("b1"), batch("b2"), batch("b3")))

        assertEquals(listOf("b1", "b2", "b3"), dispatched)
        assertTrue("class simple name is logged", logs.lines.any { it.contains("IllegalStateException") })
        assertTrue("message must not leak", logs.lines.none { it.contains("batch-secret") })
    }

    @Test
    fun cancellation_propagates_and_stops_the_remaining_batches() = runTest {
        val subject = InstallmentPostCommitDispatcher(runner(cancelOn = "b2"))

        try {
            subject.dispatch(listOf(batch("b1"), batch("b2"), batch("b3")))
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // expected
        }

        assertEquals(listOf("b1", "b2"), dispatched)
    }
}
