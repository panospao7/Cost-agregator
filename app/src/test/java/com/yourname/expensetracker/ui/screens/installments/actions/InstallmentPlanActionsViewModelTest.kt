package com.yourname.expensetracker.ui.screens.installments.actions

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10b-2: End plan flows (domain-spec AE-7 Stop, AE-8 Refund, AE-9 Early payoff,
 * AE-14 Delete) and the restricted Unlink. Each confirmation reaches exactly one coordinator
 * operation; refusals map to fixed strings; AlreadyInState is silent; the discard dialog guards an
 * edited payoff form; cancellation is never swallowed. "Delete expense and reopen" (OQ-21) is not
 * part of this ViewModel (INV-23 guard), so there is nothing to test for it here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentPlanActionsViewModelTest : ViewModelTestUtils() {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>(relaxed = true)
    private val timeProvider = mockk<TimeProvider> { every { now() } returns 1_800_000_000_000L }

    private fun viewModel() = InstallmentPlanActionsViewModel(coordinator, InstallmentPostCommitDispatcher(runner), timeProvider)

    private fun context(
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        kind: InstallmentPlanKind = InstallmentPlanKind.STANDALONE,
        unpaid: Int = 3,
        hasPayments: Boolean = false
    ) = InstallmentPlanActionContext(
        planId = 7L,
        status = status,
        kind = kind,
        unpaidCount = unpaid,
        paidCount = 2,
        unpaidTotal = MoneyDisplayUi.from(150.0, "EUR"),
        unpaidTotalMinor = 15_000L,
        hasPayments = hasPayments
    )

    private fun planDone(status: InstallmentPlanStatus = InstallmentPlanStatus.CANCELLED) =
        InstallmentOpResult.Done(InstallmentPlanRef(7L, status))

    private fun runTestVm(block: suspend (InstallmentPlanActionsViewModel) -> Unit) = runTest(testDispatcher) {
        block(viewModel())
    }

    // ------------------------------------------------------------------ AE-7 Stop

    @Test
    fun `AE-7 stop reaches stop only and closes the dialog with a notice`() = runTestVm { vm ->
        coEvery { coordinator.stop(any()) } returns planDone()
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.STOP)
        assertEquals(InstallmentPlanDialogKind.STOP, vm.state.value.dialog?.kind)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.stop(7L) }
        coVerify(exactly = 0) { coordinator.refund(any(), any()) }
        coVerify(exactly = 0) { coordinator.payOff(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { coordinator.delete(any()) }
        assertNull(vm.state.value.dialog)
        assertEquals(InstallmentPlanNotice.STOPPED, vm.state.value.notice)
        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.STOP, false, 7L), vm.state.value.mutation)
    }

    @Test
    fun `an option the plan status does not allow is not offered`() = runTestVm { vm ->
        vm.openEnd(context(status = InstallmentPlanStatus.COMPLETED))
        vm.pickEndOption(InstallmentEndOption.STOP)
        assertEquals(InstallmentPlanDialogKind.END_CHOOSER, vm.state.value.dialog?.kind)
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)
        assertEquals(InstallmentPlanDialogKind.END_CHOOSER, vm.state.value.dialog?.kind)
        vm.pickEndOption(InstallmentEndOption.REFUND)
        assertEquals(InstallmentPlanDialogKind.REFUND_QUESTION, vm.state.value.dialog?.kind)
    }

    // ------------------------------------------------------------------ AE-8 Refund

    @Test
    fun `AE-8 refund not yet recorded reaches refund with false`() = runTestVm { vm ->
        coEvery { coordinator.refund(any(), any()) } returns planDone(InstallmentPlanStatus.REFUNDED)
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.REFUND)
        vm.answerRefundAlreadyRecorded(false)
        assertEquals(InstallmentPlanDialogKind.REFUND_CONFIRM, vm.state.value.dialog?.kind)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.refund(7L, false) }
        coVerify(exactly = 0) { coordinator.stop(any()) }
        assertEquals(InstallmentPlanNotice.REFUNDED, vm.state.value.notice)
    }

    @Test
    fun `AE-8 refund already recorded offers Stop and reaches refund with true`() = runTestVm { vm ->
        coEvery { coordinator.refund(any(), any()) } returns planDone()
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.REFUND)
        vm.answerRefundAlreadyRecorded(true)
        assertEquals(InstallmentPlanDialogKind.REFUND_ALREADY_RECORDED, vm.state.value.dialog?.kind)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.refund(7L, true) }
        assertEquals(InstallmentPlanNotice.STOPPED, vm.state.value.notice)
    }

    // ------------------------------------------------------------------ AE-9 Early payoff

    @Test
    fun `AE-9 payoff with the default amount sends null and today`() = runTestVm { vm ->
        coEvery { coordinator.payOff(any(), any(), any(), any(), any()) } returns planDone(InstallmentPlanStatus.PAID_OFF)
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)
        val today = vm.state.value.dialog!!.todayEpochDay

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.payOff(7L, null, today, today, any()) }
        assertEquals(InstallmentPlanNotice.PAID_OFF, vm.state.value.notice)
    }

    @Test
    fun `AE-9 payoff with an edited amount sends it, and a future date or bad amount cannot confirm`() = runTestVm { vm ->
        coEvery { coordinator.payOff(any(), any(), any(), any(), any()) } returns planDone(InstallmentPlanStatus.PAID_OFF)
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)
        val today = vm.state.value.dialog!!.todayEpochDay

        vm.setPayoffEpochDay(today + 1)
        assertFalse(vm.state.value.dialog!!.canConfirm)
        vm.confirm()
        vm.setPayoffEpochDay(today)
        vm.setPayoffAmountText("abc")
        assertFalse(vm.state.value.dialog!!.canConfirm)
        vm.confirm()
        coVerify(exactly = 0) { coordinator.payOff(any(), any(), any(), any(), any()) }

        vm.setPayoffAmountText("120.50")
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { coordinator.payOff(7L, 120.5, today, today, any()) }
    }

    @Test
    fun `payoff is offered only with at least one unpaid payment`() {
        assertTrue(InstallmentPlanAvailability.of(context(unpaid = 1)).payOff)
        assertFalse(InstallmentPlanAvailability.of(context(unpaid = 0)).payOff)
    }

    // ------------------------------------------------------------------ AE-14 Delete = undo

    @Test
    fun `AE-14 delete reaches delete only and reports the deletion`() = runTestVm { vm ->
        coEvery { coordinator.delete(any()) } returns planDone(InstallmentPlanStatus.ACTIVE)
        vm.openDelete(context())
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.delete(7L) }
        coVerify(exactly = 0) { coordinator.stop(any()) }
        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.DELETE, false, 7L), vm.state.value.mutation)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `delete is offered only without payments, by status`() {
        assertTrue(InstallmentPlanAvailability.of(context()).delete)
        assertTrue(InstallmentPlanAvailability.of(context(status = InstallmentPlanStatus.CANCELLED)).delete)
        assertFalse(InstallmentPlanAvailability.of(context(hasPayments = true)).delete)
        assertFalse(InstallmentPlanAvailability.of(context(status = InstallmentPlanStatus.REFUNDED)).delete)
        assertFalse(InstallmentPlanAvailability.of(context(status = InstallmentPlanStatus.COMPLETED)).delete)
    }

    // ------------------------------------------------------------------ restricted Unlink (OQ-21)

    @Test
    fun `unlink reaches unlinkExpense for the entry and nothing else`() = runTestVm { vm ->
        coEvery { coordinator.unlinkExpense(any()) } returns
            InstallmentOpResult.Done(InstallmentEntryRef(5L, 7L, InstallmentEntryStatus.SCHEDULED, InstallmentPlanStatus.ACTIVE, null))
        vm.openUnlink(context(), entryId = 5L, seq = 3)
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.unlinkExpense(5L) }
        coVerify(exactly = 0) { coordinator.delete(any()) }
        assertEquals(InstallmentPlanNotice.UNLINKED, vm.state.value.notice)
    }

    // ------------------------------------------------------------------ refusals, failures, silent success

    @Test
    fun `refusal maps to a fixed string and keeps the dialog open`() = runTestVm { vm ->
        coEvery { coordinator.stop(any()) } returns InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.STOP)
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        val mutation = vm.state.value.mutation
        assertEquals(InstallmentPlanMutationState.Refused(InstallmentPlanOperation.STOP, InstallmentRefusalCode.ILLEGAL_TRANSITION), mutation)
        assertEquals(R.string.installments_action_refused_already_updated, installmentPlanMutationMessage(mutation))
        assertNotNull(vm.state.value.dialog)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `a bounded failure maps to a fixed string`() = runTestVm { vm ->
        coEvery { coordinator.delete(any()) } returns InstallmentOpResult.Failed(InstallmentOpFailure.PLAN_NOT_FOUND)
        vm.openDelete(context())
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        val mutation = vm.state.value.mutation
        assertEquals(InstallmentPlanMutationState.Failed(InstallmentPlanOperation.DELETE, InstallmentActionFailureCode.NOT_FOUND), mutation)
        assertEquals(R.string.installments_action_failed_not_found, installmentPlanMutationMessage(mutation))
    }

    @Test
    fun `an unexpected exception becomes a bounded failure without its message`() = runTestVm { vm ->
        coEvery { coordinator.stop(any()) } throws IllegalStateException("secret plan name")
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.STOP)
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentPlanMutationState.Failed(InstallmentPlanOperation.STOP, InstallmentActionFailureCode.UNEXPECTED),
            vm.state.value.mutation
        )
    }

    @Test
    fun `AlreadyInState is a silent success without a notice`() = runTestVm { vm ->
        coEvery { coordinator.stop(any()) } returns InstallmentOpResult.AlreadyInState(InstallmentPlanRef(7L, InstallmentPlanStatus.CANCELLED))
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.STOP)
        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.STOP, true, 7L), vm.state.value.mutation)
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `a CancellationException from the coordinator is not turned into a failure and the state returns to Idle`() =
        runTestVm { vm ->
            coEvery { coordinator.stop(any()) } throws CancellationException("cancelled")
            vm.openEnd(context())
            vm.pickEndOption(InstallmentEndOption.STOP)
            vm.confirm()
            testDispatcher.scheduler.advanceUntilIdle()

            val mutation = vm.state.value.mutation
            assertFalse(mutation is InstallmentPlanMutationState.Failed)
            assertFalse(mutation is InstallmentPlanMutationState.Success)
            // F3 / F7: a cancelled run must not leave the flow stuck InProgress.
            assertEquals(InstallmentPlanMutationState.Idle, mutation)
        }

    @Test
    fun `F5 refund already recorded on a COMPLETED plan closes with a fixed notice and runs nothing`() = runTestVm { vm ->
        vm.openEnd(context(status = InstallmentPlanStatus.COMPLETED))
        vm.pickEndOption(InstallmentEndOption.REFUND)
        assertEquals(InstallmentPlanDialogKind.REFUND_QUESTION, vm.state.value.dialog?.kind)

        vm.answerRefundAlreadyRecorded(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.dialog)
        assertEquals(InstallmentPlanNotice.NOTHING_TO_CHANGE, vm.state.value.notice)
        assertEquals(InstallmentPlanMutationState.Idle, vm.state.value.mutation)
        coVerify(exactly = 0) { coordinator.refund(any(), any()) }
        coVerify(exactly = 0) { coordinator.stop(any()) }
    }

    // ------------------------------------------------------------------ post-commit dispatch (InstallmentPostCommitDispatcher)

    private fun batch(correlationId: String, vararg keys: String) = PostCommitActionBatch(
        correlationId = correlationId,
        actions = keys.map { key ->
            PostCommitAction(
                pipeline = AppPipeline.TRANSACTION,
                name = "test_action",
                category = SideEffectCategory.BUDGET,
                triggerType = SideEffectTriggerType.EXPENSE_CREATED,
                targetEntityType = "expense",
                targetEntityId = 42L,
                source = "test",
                correlationId = correlationId,
                causationId = null,
                idempotencyKey = key,
                execute = { SideEffectOutcome.Completed }
            )
        }
    )

    @Test
    fun `a non-empty post-commit batch from refund reaches the runner exactly once after the coordinator returns`() =
        runTestVm { vm ->
            val committed = batch("corr-refund", "key-1")
            val order = mutableListOf<String>()
            coEvery { coordinator.refund(7L, false) } coAnswers {
                order += "coordinator"
                InstallmentOpResult.Done(InstallmentPlanRef(7L, InstallmentPlanStatus.REFUNDED), listOf(committed))
            }
            coEvery { runner.run(committed) } coAnswers {
                order += "runner"
                mockk(relaxed = true)
            }
            vm.openEnd(context())
            vm.pickEndOption(InstallmentEndOption.REFUND)
            vm.answerRefundAlreadyRecorded(false)

            vm.confirm()
            assertTrue("nothing is dispatched before the coordinator completed", order.isEmpty())
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { runner.run(committed) }
            coVerify(exactly = 1) { runner.run(any()) }
            assertEquals(listOf("coordinator", "runner"), order)
            assertEquals(InstallmentPlanNotice.REFUNDED, vm.state.value.notice)
        }

    @Test
    fun `a non-empty post-commit batch from payOff reaches the runner once and an empty batch is skipped`() =
        runTestVm { vm ->
            val committed = batch("corr-payoff", "key-1", "key-2")
            val empty = PostCommitActionBatch.empty("corr-empty")
            coEvery { coordinator.payOff(any(), any(), any(), any(), any()) } returns
                InstallmentOpResult.Done(InstallmentPlanRef(7L, InstallmentPlanStatus.PAID_OFF), listOf(committed, empty))
            vm.openEnd(context())
            vm.pickEndOption(InstallmentEndOption.PAY_OFF)

            vm.confirm()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { runner.run(committed) }
            coVerify(exactly = 1) { runner.run(any()) }
            assertEquals(InstallmentPlanNotice.PAID_OFF, vm.state.value.notice)
        }

    @Test
    fun `AlreadyInState from refund dispatches nothing`() = runTestVm { vm ->
        coEvery { coordinator.refund(7L, false) } returns
            InstallmentOpResult.AlreadyInState(InstallmentPlanRef(7L, InstallmentPlanStatus.REFUNDED))
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.REFUND)
        vm.answerRefundAlreadyRecorded(false)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `a failing post-commit batch does not change the refund success`() = runTestVm { vm ->
        val committed = batch("corr-refund", "key-1")
        coEvery { coordinator.refund(7L, false) } returns
            InstallmentOpResult.Done(InstallmentPlanRef(7L, InstallmentPlanStatus.REFUNDED), listOf(committed))
        coEvery { runner.run(committed) } throws IllegalStateException("secret batch detail")
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.REFUND)
        vm.answerRefundAlreadyRecorded(false)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { runner.run(committed) }
        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.REFUND, false, 7L), vm.state.value.mutation)
        assertEquals(InstallmentPlanNotice.REFUNDED, vm.state.value.notice)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `a failing post-commit batch does not change the payOff success`() = runTestVm { vm ->
        val committed = batch("corr-payoff", "key-1")
        coEvery { coordinator.payOff(any(), any(), any(), any(), any()) } returns
            InstallmentOpResult.Done(InstallmentPlanRef(7L, InstallmentPlanStatus.PAID_OFF), listOf(committed))
        coEvery { runner.run(committed) } throws IllegalStateException("secret batch detail")
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)

        vm.confirm()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { runner.run(committed) }
        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.PAY_OFF, false, 7L), vm.state.value.mutation)
        assertEquals(InstallmentPlanNotice.PAID_OFF, vm.state.value.notice)
    }

    // ------------------------------------------------------------------ discard dialog

    @Test
    fun `an edited payoff form asks before it closes, an untouched one closes`() = runTestVm { vm ->
        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)
        vm.requestDismiss()
        assertNull(vm.state.value.dialog)

        vm.openEnd(context())
        vm.pickEndOption(InstallmentEndOption.PAY_OFF)
        vm.setPayoffAmountText("100.00")
        vm.requestDismiss()
        assertTrue(vm.state.value.showDiscardDialog)
        assertNotNull(vm.state.value.dialog)

        vm.cancelDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
        vm.requestDismiss()
        vm.confirmDiscard()
        assertNull(vm.state.value.dialog)
        assertFalse(vm.state.value.showDiscardDialog)
    }
}
