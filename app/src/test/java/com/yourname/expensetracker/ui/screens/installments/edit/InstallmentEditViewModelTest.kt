package com.yourname.expensetracker.ui.screens.installments.edit

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.readmodel.EntryMoney
import com.yourname.expensetracker.domain.installment.readmodel.EntrySnapshot
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanReadModel
import com.yourname.expensetracker.domain.installment.readmodel.PlanSnapshot
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentActionFailureCode
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.create.InstallmentCategoryOption
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
 * loan-installments S10b-2: Edit plan details (spec 7.4 E3). A blank name never reaches the coordinator
 * (INV-34); a STANDALONE plan needs a category (INV-33) while a purchase plan may have none; the full
 * loaded category, currency and untouched fields are sent back unchanged; AlreadyInState closes the
 * overlay silently; cancellation is never converted into a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentEditViewModelTest : ViewModelTestUtils() {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>(relaxed = true)
    private val readRepository = mockk<InstallmentPlanReadRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val timeProvider = mockk<TimeProvider> { every { now() } returns 1_800_000_000_000L }

    private fun category(categoryId: Long, label: String): Category =
        mockk { every { id } returns categoryId; every { name } returns label }

    /** A plan detail built from synthetic snapshot inputs through the pure read model. */
    private fun detail(
        kind: InstallmentPlanKind = InstallmentPlanKind.STANDALONE,
        categoryId: Long? = 3L,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        lender: String? = "Synthetic bank",
        currency: InstallmentCurrency = InstallmentCurrency.USD,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE
    ): InstallmentPlanDetail = InstallmentPlanReadModel.detail(
        PlanSnapshot(
            planId = 7L,
            planUuid = "plan-uuid-7",
            categoryId = categoryId,
            displayName = "Home loan",
            lenderLabel = lender,
            kind = kind,
            fulfilmentMode = mode,
            status = status,
            currency = currency,
            purchaseExpenseId = null,
            purchase = null,
            terminalEpochDay = null,
            entries = listOf(
                EntrySnapshot(
                    entryId = 1L,
                    seq = 1,
                    originalScheduledMinor = null,
                    linkedExpenseId = null,
                    statusReason = null,
                    money = EntryMoney(InstallmentEntryKind.REGULAR, InstallmentEntryStatus.SCHEDULED, 20_000L, 5_000L, null)
                )
            )
        ),
        todayEpochDay = 20_000L
    )

    private fun loadedViewModel(loaded: InstallmentPlanDetail = detail()): InstallmentEditViewModel {
        coEvery { readRepository.loadDetail(7L) } returns loaded
        coEvery { categoryRepository.getAll() } returns listOf(category(3L, "Loans"), category(4L, "Home"))
        val vm = InstallmentEditViewModel(
            coordinator,
            InstallmentPostCommitDispatcher(runner),
            readRepository,
            categoryRepository,
            timeProvider
        )
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun done() = InstallmentOpResult.Done(InstallmentPlanRef(7L, InstallmentPlanStatus.ACTIVE))

    @Test
    fun `the loaded plan prefills the form with category, currency and categories and is not dirty`() =
        runTest(testDispatcher) {
            val vm = loadedViewModel()

            val state = vm.state.value
            assertTrue(state.loaded)
            assertFalse(state.loadFailed)
            assertEquals("Home loan", state.form.name)
            assertEquals("Synthetic bank", state.form.lender)
            assertEquals(3L, state.form.categoryId)
            assertEquals(InstallmentFulfilmentMode.CONFIRM, state.form.mode)
            assertEquals("USD", state.currencyCode)
            assertEquals(
                listOf(InstallmentCategoryOption(3L, "Loans"), InstallmentCategoryOption(4L, "Home")),
                state.categories
            )
            assertFalse(state.isDirty)
            assertFalse("an unchanged form cannot be saved", state.canSave)
        }

    @Test
    fun `a missing plan is a bounded load failure and cannot be saved`() = runTest(testDispatcher) {
        coEvery { readRepository.loadDetail(7L) } returns null
        coEvery { categoryRepository.getAll() } returns emptyList()
        val vm = InstallmentEditViewModel(
            coordinator,
            InstallmentPostCommitDispatcher(runner),
            readRepository,
            categoryRepository,
            timeProvider
        )
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.canSave)
        coVerify(exactly = 0) { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `INV-34 a blank name is refused locally and the coordinator is not called`() = runTest(testDispatcher) {
        val vm = loadedViewModel()
        vm.setName("   ")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(
            InstallmentPlanMutationState.Refused(InstallmentPlanOperation.EDIT, InstallmentRefusalCode.PLAN_NAME_REQUIRED),
            vm.state.value.mutation
        )
    }

    @Test
    fun `INV-33 canSave requires a category for a STANDALONE plan`() = runTest(testDispatcher) {
        val vm = loadedViewModel(detail(kind = InstallmentPlanKind.STANDALONE, categoryId = null))

        vm.setName("Home loan renamed")
        assertNull(vm.state.value.form.categoryId)
        assertFalse(vm.state.value.canSave)
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) }

        vm.setCategory(4L)
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `a purchase attached plan can be saved without a category`() = runTest(testDispatcher) {
        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } returns done()
        val vm = loadedViewModel(detail(kind = InstallmentPlanKind.PURCHASE_ATTACHED, categoryId = null))

        vm.setName("Laptop")
        assertTrue(vm.state.value.canSave)
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.editDetails(7L, "Laptop", "Synthetic bank", null, InstallmentFulfilmentMode.CONFIRM, "USD", any()) }
    }

    @Test
    fun `editDetails receives the loaded category and currency, trimmed name and the civil today`() =
        runTest(testDispatcher) {
            val today = slot<Long>()
            coEvery {
                coordinator.editDetails(any(), any(), any(), any(), any(), any(), capture(today))
            } returns done()
            val vm = loadedViewModel()

            vm.setName("  Home loan 2  ")
            vm.setLender("   ")
            vm.setMode(InstallmentFulfilmentMode.AUTO_RECORD)
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                coordinator.editDetails(
                    7L,
                    "Home loan 2",
                    null,
                    3L,
                    InstallmentFulfilmentMode.AUTO_RECORD,
                    "USD",
                    any()
                )
            }
            // 1_800_000_000_000 ms is 2027-01-15T08:00Z: civil day 20833 in UTC, 20832 at UTC-12.
            assertTrue("civil day ${today.captured}", today.captured in 20_832L..20_833L)
            assertEquals(
                InstallmentPlanMutationState.Success(InstallmentPlanOperation.EDIT, false, 7L),
                vm.state.value.mutation
            )
        }

    @Test
    fun `AlreadyInState is a silent success that closes the overlay`() = runTest(testDispatcher) {
        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } returns
            InstallmentOpResult.AlreadyInState(InstallmentPlanRef(7L, InstallmentPlanStatus.ACTIVE))
        val vm = loadedViewModel()

        vm.setName("Home loan 2")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentPlanMutationState.Success(InstallmentPlanOperation.EDIT, true, 7L),
            vm.state.value.mutation
        )
    }

    @Test
    fun `a refusal keeps the form and an unexpected exception becomes a bounded failure`() = runTest(testDispatcher) {
        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } returns
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        val vm = loadedViewModel()
        vm.setName("Home loan 2")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            InstallmentPlanMutationState.Refused(InstallmentPlanOperation.EDIT, InstallmentRefusalCode.ILLEGAL_TRANSITION),
            vm.state.value.mutation
        )
        assertEquals("Home loan 2", vm.state.value.form.name)

        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("secret plan name")
        vm.setName("Home loan 3")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            InstallmentPlanMutationState.Failed(InstallmentPlanOperation.EDIT, InstallmentActionFailureCode.UNEXPECTED),
            vm.state.value.mutation
        )
    }

    @Test
    fun `a CancellationException from the coordinator is not turned into a failure and the state returns to Idle`() =
        runTest(testDispatcher) {
            coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("cancelled")
            val vm = loadedViewModel()

            vm.setName("Home loan 2")
            vm.save()
            testDispatcher.scheduler.advanceUntilIdle()

            val mutation = vm.state.value.mutation
            assertFalse(mutation is InstallmentPlanMutationState.Failed)
            assertFalse(mutation is InstallmentPlanMutationState.Success)
            // F3 / F7: a cancelled save must not leave the form stuck InProgress.
            assertEquals(InstallmentPlanMutationState.Idle, mutation)
        }

    @Test
    fun `resetSession after a success is Idle and the next start reloads the plan`() = runTest(testDispatcher) {
        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } returns done()
        val vm = loadedViewModel()
        vm.setName("Home loan 2")
        vm.save()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.Success)

        vm.resetSession()

        val reset = vm.state.value
        assertEquals(InstallmentPlanMutationState.Idle, reset.mutation)
        assertFalse(reset.loaded)
        assertFalse(reset.showDiscardDialog)
        assertEquals("", reset.form.name)

        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { readRepository.loadDetail(7L) }
        assertTrue(vm.state.value.loaded)
        assertEquals("Home loan", vm.state.value.form.name)
        assertFalse(vm.state.value.isDirty)
    }

    @Test
    fun `discarding a dirty form and reopening is clean with no draft and no dialog`() = runTest(testDispatcher) {
        val vm = loadedViewModel()
        vm.setName("Draft name")
        vm.requestClose { }
        assertTrue(vm.state.value.showDiscardDialog)

        // Discard confirm: the screen closes and resets the session.
        vm.resetSession()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.showDiscardDialog)
        assertFalse(state.isDirty)
        assertEquals("Home loan", state.form.name)
    }

    @Test
    fun `resetSession is a no-op while a save is in progress`() = runTest(testDispatcher) {
        coEvery { coordinator.editDetails(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = loadedViewModel()
        vm.setName("Home loan 2")
        vm.save()
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.InProgress)

        vm.resetSession()

        assertTrue(vm.state.value.mutation is InstallmentPlanMutationState.InProgress)
        assertTrue(vm.state.value.loaded)
        assertEquals("Home loan 2", vm.state.value.form.name)
    }

    @Test
    fun `F4 a COMPLETED plan disables the mode chips and ignores a mode change`() = runTest(testDispatcher) {
        val vm = loadedViewModel(detail(status = InstallmentPlanStatus.COMPLETED))

        assertEquals(InstallmentPlanStatus.COMPLETED, vm.state.value.status)
        assertFalse(vm.state.value.modeEditable)
        vm.setMode(InstallmentFulfilmentMode.AUTO_RECORD)
        assertEquals(InstallmentFulfilmentMode.CONFIRM, vm.state.value.form.mode)
        assertFalse(vm.state.value.isDirty)

        val active = loadedViewModel()
        assertTrue(active.state.value.modeEditable)
    }

    @Test
    fun `a dirty form asks before it closes and an untouched one closes`() = runTest(testDispatcher) {
        val vm = loadedViewModel()
        var closed = 0

        vm.requestClose { closed++ }
        assertEquals(1, closed)
        assertFalse(vm.state.value.showDiscardDialog)

        vm.setName("Home loan 2")
        vm.requestClose { closed++ }
        assertEquals(1, closed)
        assertTrue(vm.state.value.showDiscardDialog)

        vm.cancelDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
    }
}
