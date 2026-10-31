package com.yourname.expensetracker.ui.screens.installments.create

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10b-2: standalone plan form. A blank name never reaches the coordinator (INV-34),
 * no category is preselected and one is required (AMD-3, D-7 moot), the history-only count (OQ-18) is
 * bounded, the valid form reaches createStandalone with FIXED_PAYMENT terms, and the discard dialog,
 * AlreadyInState and cancellation behave as in the other installment flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentCreateViewModelTest : ViewModelTestUtils() {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>(relaxed = true)
    private val timeProvider = mockk<TimeProvider> { every { now() } returns 1_800_000_000_000L }
    private val categoryRepository = mockk<CategoryRepository>()

    private fun category(categoryId: Long, label: String): Category =
        mockk { every { id } returns categoryId; every { name } returns label }

    private fun viewModel(): InstallmentCreateViewModel {
        coEvery { categoryRepository.getAll() } returns listOf(category(3L, "Loans"))
        return InstallmentCreateViewModel(coordinator, InstallmentPostCommitDispatcher(runner), categoryRepository, timeProvider)
    }

    private fun InstallmentCreateViewModel.fillValid(name: String = "Home loan") {
        setName(name)
        setAmountText("420.00")
        setCountText("240")
        setCategory(3L)
    }

    @Test
    fun `categories load and nothing is preselected`() = runTest(testDispatcher) {
        val vm = viewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(InstallmentCategoryOption(3L, "Loans")), vm.state.value.categories)
        assertNull(vm.state.value.form.categoryId)
        assertFalse(vm.state.value.form.canSave)
    }

    @Test
    fun `a blank name is refused locally and the coordinator is not called`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.fillValid(name = "   ")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { coordinator.createStandalone(any(), any(), any()) }
        assertEquals(
            InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, InstallmentRefusalCode.PLAN_NAME_REQUIRED),
            vm.state.value.mutation
        )
    }

    @Test
    fun `a form without a category cannot be saved`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.setName("Home loan")
        vm.setAmountText("420.00")
        vm.setCountText("240")
        assertFalse(vm.state.value.form.canSave)
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { coordinator.createStandalone(any(), any(), any()) }
    }

    @Test
    fun `payment count and history-only count are bounded`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.fillValid()
        vm.setCountText("1")
        assertFalse(vm.state.value.form.canSave)
        vm.setCountText("481")
        assertFalse(vm.state.value.form.canSave)
        vm.setCountText("5")
        assertTrue(vm.state.value.form.canSave)
        vm.setPaidBeforeText("5")
        assertFalse(vm.state.value.form.canSave)
        vm.setPaidBeforeText("4")
        assertTrue(vm.state.value.form.canSave)
    }

    @Test
    fun `a valid form reaches createStandalone with fixed payment terms`() = runTest(testDispatcher) {
        val terms = slot<InstallmentPlanTerms>()
        coEvery { coordinator.createStandalone(capture(terms), any(), any()) } returns
            InstallmentOpResult.Done(InstallmentPlanRef(9L, InstallmentPlanStatus.ACTIVE))
        val vm = viewModel()
        vm.fillValid(name = "  Home loan  ")
        // S10b-2 F2 / deferral 49(b): the paid-before field is hidden until the first-due picker exists, so
        // even a stale non-zero text never reaches the coordinator: 0 is always sent.
        assertFalse(InstallmentCreateForm.SHOW_PAID_BEFORE_FIELD)
        vm.setPaidBeforeText("2")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.createStandalone(any(), 3L, any()) }
        assertEquals("Home loan", terms.captured.displayName)
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, terms.captured.splitMethod)
        assertEquals(420.0, terms.captured.fixedPaymentAmount!!, 0.0)
        assertNull(terms.captured.declaredTotal)
        assertEquals(240, terms.captured.installmentCount)
        assertEquals(0, terms.captured.paidBeforeTrackingCount)
        assertEquals(
            InstallmentPlanMutationState.Success(InstallmentPlanOperation.CREATE, false, 9L),
            vm.state.value.mutation
        )
    }

    @Test
    fun `a retry reuses the plan uuid and AlreadyInState is a silent success`() = runTest(testDispatcher) {
        val uuids = mutableListOf<String>()
        coEvery { coordinator.createStandalone(any(), any(), any()) } answers {
            uuids += firstArg<InstallmentPlanTerms>().planUuid
            InstallmentOpResult.AlreadyInState(InstallmentPlanRef(9L, InstallmentPlanStatus.ACTIVE))
        }
        val vm = viewModel()
        vm.fillValid()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, uuids.size)
        assertEquals(uuids[0], uuids[1])
        assertEquals(InstallmentPlanMutationState.Success(InstallmentPlanOperation.CREATE, true, 9L), vm.state.value.mutation)
    }

    @Test
    fun `a coordinator refusal keeps the form and editing clears it`() = runTest(testDispatcher) {
        coEvery { coordinator.createStandalone(any(), any(), any()) } returns
            InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT)
        val vm = viewModel()
        vm.fillValid()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, InstallmentRefusalCode.INVALID_COUNT),
            vm.state.value.mutation
        )
        assertEquals("Home loan", vm.state.value.form.name)
        vm.setCountText("12")
        assertEquals(InstallmentPlanMutationState.Idle, vm.state.value.mutation)
    }

    @Test
    fun `a dirty form asks before closing and a clean one closes`() = runTest(testDispatcher) {
        val vm = viewModel()
        var closed = 0
        vm.requestClose { closed++ }
        assertEquals(1, closed)

        vm.setName("Loan")
        vm.requestClose { closed++ }
        assertEquals(1, closed)
        assertTrue(vm.state.value.showDiscardDialog)
        vm.cancelDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
    }

    @Test
    fun `a CancellationException from the coordinator is not turned into a failure and the state returns to Idle`() =
        runTest(testDispatcher) {
            coEvery { coordinator.createStandalone(any(), any(), any()) } throws CancellationException("cancelled")
            val vm = viewModel()
            vm.fillValid()
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()

            val mutation = vm.state.value.mutation
            assertFalse(mutation is InstallmentPlanMutationState.Failed)
            assertFalse(mutation is InstallmentPlanMutationState.Success)
            // F3 / F7: a cancelled save must not leave the form stuck InProgress.
            assertEquals(InstallmentPlanMutationState.Idle, mutation)
        }

    @Test
    fun `resetSession after a success gives a new plan uuid and an Idle clean form`() = runTest(testDispatcher) {
        val uuids = mutableListOf<String>()
        coEvery { coordinator.createStandalone(any(), any(), any()) } answers {
            uuids += firstArg<InstallmentPlanTerms>().planUuid
            InstallmentOpResult.Done(InstallmentPlanRef(9L, InstallmentPlanStatus.ACTIVE))
        }
        val vm = viewModel()
        vm.fillValid()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.Success)

        vm.resetSession()

        val state = vm.state.value
        assertEquals(InstallmentPlanMutationState.Idle, state.mutation)
        assertEquals("", state.form.name)
        assertNull(state.form.categoryId)
        assertFalse(state.isDirty)
        assertFalse(state.showDiscardDialog)
        // Categories stay loaded for the next session.
        assertEquals(listOf(InstallmentCategoryOption(3L, "Loans")), state.categories)

        vm.fillValid()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, uuids.size)
        assertTrue("a new form session is a new plan", uuids[0] != uuids[1])
    }

    @Test
    fun `discarding a dirty form and reopening is clean with no draft and no dialog`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.setName("Draft loan")
        vm.setAmountText("99")
        vm.requestClose { }
        assertTrue(vm.state.value.showDiscardDialog)

        // Discard confirm: the screen closes and resets the session.
        vm.resetSession()

        val state = vm.state.value
        assertFalse(state.showDiscardDialog)
        assertFalse(state.isDirty)
        assertEquals("", state.form.name)
        assertEquals("", state.form.amountText)
        var closed = 0
        vm.requestClose { closed++ }
        assertEquals("a clean reopened form closes without asking", 1, closed)
    }

    @Test
    fun `resetSession is a no-op while a save is in progress`() = runTest(testDispatcher) {
        coEvery { coordinator.createStandalone(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = viewModel()
        vm.fillValid()
        vm.save()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.InProgress)

        vm.resetSession()

        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.InProgress)
        assertEquals("Home loan", vm.state.value.form.name)
    }

    @Test
    fun `resetSession recomputes the first due day from today after the day advances`() = runTest(testDispatcher) {
        val vm = viewModel()
        val firstDay = vm.state.value.form.firstDueEpochDay

        every { timeProvider.now() } returns 1_800_000_000_000L + 3L * 86_400_000L
        vm.resetSession()

        assertEquals(firstDay + 3L, vm.state.value.form.firstDueEpochDay)
        assertEquals(vm.state.value.form, vm.state.value.initialForm)
    }
}
