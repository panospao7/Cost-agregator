package com.yourname.expensetracker.ui.screens.installments.declare

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentActionFailureCode
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S12a: declare installments on an EXISTING expense. Covers AE-1 (existing-expense
 * path), OD-14 (plan currency vs purchase currency), AE-20 (the "What changes" / lock messaging is a
 * fixed string), AE-22 (refusals map to fixed strings), Undo (AE-14), the discard dialog, dormancy and
 * CancellationException handling. Every write is asserted to go through the coordinator only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeclareInstallmentsViewModelTest : ViewModelTestUtils() {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>(relaxed = true)
    private val timeProvider = mockk<TimeProvider> { every { now() } returns 1_800_000_000_000L }

    private val target = DeclareInstallmentsTarget(
        expenseId = 7L,
        merchant = "Demo Electronics",
        dateMillis = 1_799_000_000_000L,
        amount = 600.0,
        currency = "EUR"
    )

    private fun viewModel() = DeclareInstallmentsViewModel(coordinator, InstallmentPostCommitDispatcher(runner), timeProvider)

    private fun DeclareInstallmentsViewModel.openTarget(t: DeclareInstallmentsTarget = target) = open(t, enabled = true)

    private fun ref(id: Long = 9L) = InstallmentPlanRef(id, InstallmentPlanStatus.ACTIVE)

    @Test
    fun `flag off - open does nothing while the feature seam is disabled`() = runTest(testDispatcher) {
        val vm = viewModel()
        // S13-b (owner-approvals 91(b)): the flag-off path is now covered via the explicit seam.
        vm.open(target, enabled = false)
        assertNull(vm.state.value.target)
        assertNull(vm.state.value.form)
        coVerify(exactly = 0) { coordinator.declareOnExpense(any(), any(), any()) }
    }

    @Test
    fun `AE-1 existing expense - equal split reaches declareOnExpense and closes with a Declared notice`() =
        runTest(testDispatcher) {
            val terms = slot<InstallmentPlanTerms>()
            coEvery { coordinator.declareOnExpense(7L, capture(terms), any()) } returns InstallmentOpResult.Done(ref())
            val vm = viewModel()
            vm.openTarget()
            assertTrue(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { coordinator.declareOnExpense(7L, any(), any()) }
            assertEquals("Demo Electronics", terms.captured.displayName)
            assertEquals(InstallmentSplitMethod.EQUAL_SPLIT, terms.captured.splitMethod)
            assertEquals(600.0, terms.captured.declaredTotal!!, 0.0)
            assertNull(terms.captured.fixedPaymentAmount)
            assertEquals("EUR", terms.captured.currency)
            assertEquals(12, terms.captured.installmentCount)
            assertEquals(0, terms.captured.paidBeforeTrackingCount)
            // The sheet closes only on success and offers Undo.
            assertNull(vm.state.value.target)
            assertEquals(DeclareNotice.Declared(9L, "Demo Electronics"), vm.state.value.notice)
        }

    @Test
    fun `AlreadyInState is a silent success without a notice`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.AlreadyInState(ref())
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.state.value.target)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `a retry after a refusal reuses the plan uuid`() = runTest(testDispatcher) {
        val uuids = mutableListOf<String>()
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } answers {
            uuids += secondArg<InstallmentPlanTerms>().planUuid
            InstallmentOpResult.Refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT)
        }
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, uuids.size)
        assertEquals(uuids[0], uuids[1])
    }

    @Test
    fun `OD-14 a plan currency different from the purchase forces a fixed payment`() = runTest(testDispatcher) {
        val terms = slot<InstallmentPlanTerms>()
        coEvery { coordinator.declareOnExpense(any(), capture(terms), any()) } returns InstallmentOpResult.Done(ref())
        val vm = viewModel()
        vm.openTarget()
        vm.setCurrency(InstallmentCurrency.USD)

        assertTrue(vm.state.value.currencyDiffers)
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, vm.state.value.effectiveSplit)
        // No fixed payment yet: nothing is invented from a cross-currency conversion.
        assertFalse(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.declareOnExpense(any(), any(), any()) }

        vm.setFixedText("50.00")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, terms.captured.splitMethod)
        assertEquals("USD", terms.captured.currency)
        assertEquals(50.0, terms.captured.fixedPaymentAmount!!, 0.0)
        assertNull(terms.captured.declaredTotal)
    }

    @Test
    fun `F-2 the equal split chip is allowed only for the purchase currency`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.openTarget()
        assertTrue(vm.state.value.equalSplitAllowed)
        assertEquals(InstallmentSplitMethod.EQUAL_SPLIT, vm.state.value.effectiveSplit)

        vm.setCurrency(InstallmentCurrency.USD)
        assertFalse(vm.state.value.equalSplitAllowed)
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, vm.state.value.effectiveSplit)

        vm.setCurrency(InstallmentCurrency.EUR)
        assertTrue(vm.state.value.equalSplitAllowed)
    }

    @Test
    fun `F-2 the equal split chip is not allowed for a null plan currency or before a target is open`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            assertFalse(vm.state.value.equalSplitAllowed)

            vm.openTarget(target.copy(currency = "CHF"))
            assertNull(vm.state.value.form?.currency)
            assertFalse(vm.state.value.equalSplitAllowed)
            assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, vm.state.value.effectiveSplit)

            // A supported pick that differs from the unsupported purchase currency stays fixed-only.
            vm.setCurrency(InstallmentCurrency.EUR)
            assertFalse(vm.state.value.equalSplitAllowed)
        }

    @Test
    fun `an unsupported purchase currency needs a plan currency pick before saving`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.openTarget(target.copy(currency = "CHF"))
        assertNull(vm.state.value.form?.currency)
        assertFalse(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.declareOnExpense(any(), any(), any()) }
    }

    @Test
    fun `a blank name or a bad count never reaches the coordinator`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.openTarget()
        vm.setName("   ")
        assertFalse(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
        vm.setName("Laptop")
        vm.setCountText("1")
        assertFalse(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
        vm.setCountText("481")
        assertFalse(vm.state.value.canSave(java.time.ZoneId.systemDefault()))
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.declareOnExpense(any(), any(), any()) }
    }

    @Test
    fun `AE-22 refusals stay in the sheet and map to fixed strings`() = runTest(testDispatcher) {
        val expected = mapOf(
            InstallmentRefusalCode.NOT_MINE to R.string.declare_installments_refused_not_mine,
            InstallmentRefusalCode.SHARED_NOT_SUPPORTED to R.string.declare_installments_refused_shared,
            InstallmentRefusalCode.SPLIT_NOT_SUPPORTED to R.string.declare_installments_refused_shared,
            InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE to R.string.installments_action_refused_expense_not_eligible,
            InstallmentRefusalCode.ALREADY_DECLARED to R.string.declare_installments_refused_already_declared,
            InstallmentRefusalCode.FIRST_DUE_BEFORE_PURCHASE to R.string.declare_installments_refused_first_date,
            InstallmentRefusalCode.INVALID_COUNT to R.string.declare_installments_refused_count,
            InstallmentRefusalCode.UNSUPPORTED_CURRENCY to R.string.installments_action_refused_generic
        )
        for ((code, message) in expected) {
            coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Refused(code)
            val vm = viewModel()
            vm.openTarget()
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()

            val mutation = vm.state.value.mutation
            assertEquals(InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, code), mutation)
            assertEquals(code.name, message, declareMutationMessage(mutation))
            assertNotNull("the sheet stays open on a refusal: ${code.name}", vm.state.value.target)
            assertNull(vm.state.value.notice)
        }
    }

    @Test
    fun `an unexpected exception becomes a bounded failure with a fixed string and no text`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } throws IllegalStateException("SQL boom Demo Electronics 600")
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val mutation = vm.state.value.mutation
        assertEquals(InstallmentPlanMutationState.Failed(InstallmentPlanOperation.CREATE, InstallmentActionFailureCode.UNEXPECTED), mutation)
        assertEquals(R.string.installments_action_failed_unexpected, declareMutationMessage(mutation))
        assertNotNull(vm.state.value.target)
    }

    @Test
    fun `Undo deletes the plan through the coordinator and reports Undone`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Done(ref())
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.delete(9L) }
        assertEquals(DeclareNotice.Undone, vm.state.value.notice)
        assertEquals(InstallmentPlanMutationState.Idle, vm.state.value.mutation)
    }

    private fun batch(id: String) =
        com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch(
            id,
            listOf(mockk<com.yourname.expensetracker.domain.sideeffect.PostCommitAction>(relaxed = true))
        )

    @Test
    fun `declare and undo run the post-commit batch of a Done result through the runner`() = runTest(testDispatcher) {
        val declareBatch = batch("declare")
        val undoBatch = batch("undo")
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref(), listOf(declareBatch))
        coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Done(ref(), listOf(undoBatch))
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { runner.run(declareBatch) }

        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { runner.run(undoBatch) }
    }

    @Test
    fun `the refresh signal is sent once per committed declare, including AlreadyInState`() =
        runTest(testDispatcher) {
            coEvery { coordinator.declareOnExpense(any(), any(), any()) } returnsMany listOf(
                InstallmentOpResult.Done(ref()),
                InstallmentOpResult.AlreadyInState(ref())
            )
            coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Done(ref())
            val vm = viewModel()
            val signals = mutableListOf<Unit>()
            val collector = launch(testDispatcher) { vm.changed.collect { signals += it } }

            vm.openTarget()
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, signals.size)

            // Re-composition or replay of the notice sends nothing: the signal is not in the snackbar.
            vm.consumeNotice(vm.state.value.notice)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, signals.size)

            // A replayed declare with the same uuid is AlreadyInState and must still signal once.
            vm.openTarget()
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()
            // AlreadyInState: closed silently (no notice) but the refresh signal is still sent.
            assertNull(vm.state.value.notice)
            assertEquals(2, signals.size)
            collector.cancel()
        }

    @Test
    fun `a committed undo sends one refresh signal`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Done(ref())
        val vm = viewModel()
        val signals = mutableListOf<Unit>()
        val collector = launch(testDispatcher) { vm.changed.collect { signals += it } }
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, signals.size)
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, signals.size)
        collector.cancel()
    }

    @Test
    fun `a refused declare sends no refresh signal`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns
            InstallmentOpResult.Refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT)
        val vm = viewModel()
        val signals = mutableListOf<Unit>()
        val collector = launch(testDispatcher) { vm.changed.collect { signals += it } }
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(signals.isEmpty())
        collector.cancel()
    }

    @Test
    fun `consumeNotice with an expected notice clears only that notice`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.consumeNotice(DeclareNotice.Undone)
        assertEquals(DeclareNotice.Declared(9L, "Demo Electronics"), vm.state.value.notice)
        vm.consumeNotice(DeclareNotice.Declared(9L, "Demo Electronics"))
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `opening another sheet keeps a pending Undo notice`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.openTarget(target.copy(expenseId = 8L))
        assertEquals(DeclareNotice.Declared(9L, "Demo Electronics"), vm.state.value.notice)
        assertNotNull(vm.state.value.target)
    }

    @Test
    fun `Undo with a recorded payment fails with the fixed End plan message`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_HAS_PAYMENTS)
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeclareNotice.UndoFailed(9L, paymentRecorded = true), vm.state.value.notice)
    }

    @Test
    fun `Undo failing for another reason is not reported as a recorded payment`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } returns InstallmentOpResult.Done(ref())
        coEvery { coordinator.delete(9L) } returns InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT)
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeclareNotice.UndoFailed(9L, paymentRecorded = false), vm.state.value.notice)
    }

    @Test
    fun `Undo without a Declared notice does nothing`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.delete(any()) }
    }

    @Test
    fun `discard dialog - clean form closes, dirty form asks first`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.openTarget()
        vm.requestClose()
        assertNull(vm.state.value.target)

        vm.openTarget()
        vm.setCountText("6")
        vm.requestClose()
        assertTrue(vm.state.value.showDiscardDialog)
        assertNotNull(vm.state.value.target)
        vm.cancelDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
        vm.requestClose()
        vm.confirmDiscard()
        assertNull(vm.state.value.target)
        assertFalse(vm.state.value.showDiscardDialog)
    }

    @Test
    fun `a CancellationException is rethrown and never becomes a failure`() = runTest(testDispatcher) {
        coEvery { coordinator.declareOnExpense(any(), any(), any()) } throws CancellationException("cancelled")
        val vm = viewModel()
        vm.openTarget()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        val mutation = vm.state.value.mutation
        assertFalse(mutation is InstallmentPlanMutationState.Failed)
        assertFalse(mutation is InstallmentPlanMutationState.Success)
        assertEquals(InstallmentPlanMutationState.Idle, mutation)
    }

    @Test
    fun `AE-20 every refusal code and every failure code maps to a fixed message resource`() {
        // Messages are resource ids, never built from captured text. A missing mapping would be null here
        // (or fail to compile in the exhaustive `when`s the function delegates to).
        for (code in InstallmentRefusalCode.values()) {
            assertNotNull(
                "refusal ${code.name}",
                declareMutationMessage(InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, code))
            )
        }
        for (code in InstallmentActionFailureCode.values()) {
            assertNotNull(
                "failure ${code.name}",
                declareMutationMessage(InstallmentPlanMutationState.Failed(InstallmentPlanOperation.CREATE, code))
            )
        }
        assertNull(declareMutationMessage(InstallmentPlanMutationState.Idle))
    }
}
