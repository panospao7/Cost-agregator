package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * S6c-4 (INV-30, owner item 17): the pre-backup reconcile runs `reconcile()`, dispatches every
 * `Done.postCommit` batch, and reports Reconciled or NotReconciled. JVM only: the coordinator and
 * the runner are mocks, so these tests pin the reconciler's own contract (result mapping, batch
 * dispatch, containment, cancellation, bounded diagnostics). The real-Room behaviour is covered by
 * DatabaseBackupRepositoryPreReconcileTest.
 */
class InstallmentPreBackupReconcilerTest {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
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

    private fun batch(id: String) = PostCommitActionBatch.empty(id)

    private fun batchResult(id: String) = SideEffectBatchResult(id, 0, 0, 0, 0, 0, emptyList())

    /** Runner that records the correlation id of every dispatched batch; [failOn] ids throw. */
    private fun runner(failOn: Set<String> = emptySet(), cancelOn: String? = null): PostCommitActionRunner =
        object : PostCommitActionRunner {
            override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
                dispatched += batch.correlationId
                if (batch.correlationId == cancelOn) throw CancellationException("test cancellation")
                if (batch.correlationId in failOn) throw IllegalStateException("batch-secret-${batch.correlationId}")
                return batchResult(batch.correlationId)
            }
        }

    private fun reconciler(runner: PostCommitActionRunner = runner()) =
        InstallmentPreBackupReconciler(Lazy { coordinator }, Lazy { runner })

    private fun stubReconcile(result: InstallmentOpResult<InstallmentReconcileReport>) {
        coEvery { coordinator.reconcile() } returns result
    }

    // ------------------------------------------------------------------ Reconciled

    @Test
    fun a_clean_done_reconcile_is_Reconciled_and_every_batch_is_dispatched_in_order() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(
                InstallmentReconcileReport(r2Reopened = 1, orphanFlagsCleared = 1),
                listOf(batch("b1"), batch("b2"), batch("b3"))
            )
        )

        val result = reconciler().reconcileBeforeBackup()

        assertEquals(PreBackupReconcileResult.Reconciled, result)
        assertEquals(listOf("b1", "b2", "b3"), dispatched)
    }

    @Test
    fun a_clean_done_reconcile_without_batches_is_Reconciled_and_the_runner_is_not_built() = runTest {
        stubReconcile(InstallmentOpResult.Done(InstallmentReconcileReport()))
        var runnerBuilt = false
        val subject = InstallmentPreBackupReconciler(Lazy { coordinator }, Lazy {
            runnerBuilt = true
            runner()
        })

        assertEquals(PreBackupReconcileResult.Reconciled, subject.reconcileBeforeBackup())
        assertTrue(dispatched.isEmpty())
        assertFalse("nothing to dispatch: the runner is never needed", runnerBuilt)
    }

    @Test
    fun neither_dependency_is_resolved_before_the_first_call() = runTest {
        var coordinatorBuilt = 0
        var runnerBuilt = 0
        val subject = InstallmentPreBackupReconciler(
            Lazy {
                coordinatorBuilt++
                coordinator
            },
            Lazy {
                runnerBuilt++
                runner()
            }
        )

        assertEquals(0, coordinatorBuilt)
        assertEquals(0, runnerBuilt)

        stubReconcile(InstallmentOpResult.Done(InstallmentReconcileReport(), listOf(batch("b1"))))
        subject.reconcileBeforeBackup()

        assertEquals(1, coordinatorBuilt)
        assertEquals(1, runnerBuilt)
    }

    // ------------------------------------------------------------------ NotReconciled: the report

    @Test
    fun plansFailed_makes_it_NotReconciled_and_the_committed_batches_are_still_dispatched() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(
                InstallmentReconcileReport(r2Reopened = 1, plansFailed = 1),
                listOf(batch("b1"), batch("b2"))
            )
        )

        val result = reconciler().reconcileBeforeBackup()

        assertEquals(
            PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.RECONCILE_INCOMPLETE),
            result
        )
        assertEquals(listOf("b1", "b2"), dispatched)
    }

    @Test
    fun orphanFlagsFailed_makes_it_NotReconciled_and_the_committed_batches_are_still_dispatched() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(
                InstallmentReconcileReport(orphanFlagsCleared = 2, orphanFlagsFailed = 1),
                listOf(batch("b1"))
            )
        )

        val result = reconciler().reconcileBeforeBackup()

        assertEquals(
            PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.RECONCILE_INCOMPLETE),
            result
        )
        assertEquals(listOf("b1"), dispatched)
    }

    @Test
    fun both_failure_counters_set_is_still_one_bounded_reason() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(InstallmentReconcileReport(plansFailed = 3, orphanFlagsFailed = 4))
        )

        assertEquals(
            PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.RECONCILE_INCOMPLETE),
            reconciler().reconcileBeforeBackup()
        )
    }

    // ------------------------------------------------------------------ NotReconciled: not Done

    @Test
    fun every_non_Done_result_is_NotReconciled_NOT_DONE() = runTest {
        val nonDone: List<InstallmentOpResult<InstallmentReconcileReport>> = listOf(
            InstallmentOpResult.AlreadyInState(InstallmentReconcileReport()),
            InstallmentOpResult.Refused(InstallmentRefusalCode.NOT_MINE),
            InstallmentOpResult.RateRefused(RateRefusalReason.NO_RATE),
            InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT)
        )
        for (result in nonDone) {
            stubReconcile(result)

            assertEquals(
                "result ${result.javaClass.simpleName}",
                PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.NOT_DONE),
                reconciler().reconcileBeforeBackup()
            )
        }
        assertTrue("nothing to dispatch for a non-Done result", dispatched.isEmpty())
    }

    // ------------------------------------------------------------------ NotReconciled: exceptions

    @Test
    fun an_exception_is_NotReconciled_EXCEPTION_and_its_message_never_reaches_the_logs_or_the_result() = runTest {
        coEvery { coordinator.reconcile() } throws IllegalStateException("secret-merchant-text-12345")

        val result = reconciler().reconcileBeforeBackup()

        assertEquals(PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.EXCEPTION), result)
        assertFalse(result.toString().contains("secret-merchant-text-12345"))
        assertTrue("class simple name is logged", logs.lines.any { it.contains("IllegalStateException") })
        assertTrue("message must not leak", logs.lines.none { it.contains("secret-merchant-text-12345") })
    }

    @Test
    fun a_throwing_dependency_resolution_is_also_contained() = runTest {
        val subject = InstallmentPreBackupReconciler(
            Lazy { throw IllegalArgumentException("graph-secret-999") },
            Lazy { runner() }
        )

        assertEquals(
            PreBackupReconcileResult.NotReconciled(PreBackupNotReconciledReason.EXCEPTION),
            subject.reconcileBeforeBackup()
        )
        assertTrue(logs.lines.none { it.contains("graph-secret-999") })
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun a_CancellationException_from_reconcile_propagates_unchanged() = runTest {
        val cancellation = CancellationException("test cancellation")
        coEvery { coordinator.reconcile() } throws cancellation

        try {
            reconciler().reconcileBeforeBackup()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun a_CancellationException_from_a_batch_propagates_and_stops_the_dispatch() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(
                InstallmentReconcileReport(),
                listOf(batch("b1"), batch("b2"), batch("b3"))
            )
        )

        try {
            reconciler(runner(cancelOn = "b2")).reconcileBeforeBackup()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("test cancellation", e.message)
        }
        assertEquals("the batch after the cancelled one is not started", listOf("b1", "b2"), dispatched)
    }

    // ------------------------------------------------------------------ batch containment

    @Test
    fun a_failing_batch_does_not_stop_later_batches_and_does_not_make_the_reconcile_incomplete() = runTest {
        stubReconcile(
            InstallmentOpResult.Done(
                InstallmentReconcileReport(r2Reopened = 1),
                listOf(batch("b1"), batch("b2"), batch("b3"))
            )
        )

        val result = reconciler(runner(failOn = setOf("b1", "b2"))).reconcileBeforeBackup()

        assertEquals(listOf("b1", "b2", "b3"), dispatched)
        // A batch carries post-commit side effects only; the stored state is reconciled.
        assertEquals(PreBackupReconcileResult.Reconciled, result)
        assertTrue(logs.lines.any { it.contains("IllegalStateException") })
        assertTrue("batch message must not leak", logs.lines.none { it.contains("batch-secret") })
    }

    @Test
    fun reconcile_is_called_exactly_once_per_invocation() = runTest {
        stubReconcile(InstallmentOpResult.Done(InstallmentReconcileReport()))

        reconciler().reconcileBeforeBackup()

        coVerify(exactly = 1) { coordinator.reconcile() }
    }
}
