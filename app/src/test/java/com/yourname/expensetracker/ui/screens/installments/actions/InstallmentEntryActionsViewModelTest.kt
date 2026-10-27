package com.yourname.expensetracker.ui.screens.installments.actions

import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryRef
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10b-1: entry row actions (ux-flows 3.6: Record, Link, Skip, Adjust, Unskip and
 * the row Undo; the domain-spec AE-7/8/9/14 are S10b-2). Each flow reaches exactly one coordinator
 * operation with the right arguments, refusal and failure codes map to fixed strings (INV-31), the
 * mutation state moves idle -> in progress -> success / refused / failed, the row Undo runs the
 * inverse operation, a sheet with input asks before it closes, screen exit never replays a stale
 * Undo, and cancellation is never swallowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentEntryActionsViewModelTest : ViewModelTestUtils() {

    private val coordinator = mockk<InstallmentPlanLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>(relaxed = true)
    private val timeProvider = mockk<TimeProvider> { every { now() } returns 1_800_000_000_000L }

    private fun viewModel() = InstallmentEntryActionsViewModel(coordinator, runner, timeProvider)

    private fun suggestion(expenseId: Long = 99L) = InstallmentLinkSuggestionUi(
        expenseId = expenseId,
        amount = MoneyDisplayUi.from(50.0, "EUR"),
        dateEpochDay = 20_001L,
        deltaMinor = 0L
    )

    private fun target(suggestions: List<InstallmentLinkSuggestionUi> = listOf(suggestion())) = InstallmentActionTarget(
        entryId = 5L,
        seq = 3,
        dueEpochDay = 20_000L,
        scheduledMinor = 5_000L,
        currencyCode = "EUR",
        amount = MoneyDisplayUi.from(50.0, "EUR"),
        suggestions = suggestions
    )

    private fun ref(
        status: InstallmentEntryStatus = InstallmentEntryStatus.PAID,
        planStatus: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE
    ) = InstallmentEntryRef(5L, 7L, status, planStatus, null)

    private fun done(r: InstallmentEntryRef = ref()) = InstallmentOpResult.Done(r)

    // ------------------------------------------------------------------ record (ux-flows 3.6)

    @Test
    fun `record reaches confirmEntry only and closes the sheet on success`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())

        vm.submit()
        // In progress before the coordinator call completes.
        assertEquals(InstallmentMutationState.InProgress(InstallmentOperation.RECORD), vm.state.value.mutation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.confirmEntry(5L, any(), any()) }
        coVerify(exactly = 0) { coordinator.linkExpense(any(), any()) }
        coVerify(exactly = 0) { coordinator.skip(any(), any()) }
        assertEquals(
            InstallmentMutationState.Success(InstallmentOperation.RECORD, alreadyInState = false),
            vm.state.value.mutation
        )
        assertNull(vm.state.value.sheet)
        assertEquals(InstallmentActionNotice.RECORDED, vm.state.value.notice?.notice)
        // Record has no clean inverse: no Undo.
        assertNull(vm.state.value.notice?.undo)
        confirmVerified(coordinator)
    }

    @Test
    fun `record passes today as a civil epoch day`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        // 1_800_000_000_000 ms is within one day of epoch day 20_833 in every zone.
        coVerify { coordinator.confirmEntry(5L, match { it in 20_832L..20_834L }, any()) }
        confirmVerified(coordinator)
    }

    // ------------------------------------------------------------------ link (ux-flows 3.6)

    @Test
    fun `link needs a selection then reaches linkExpense and offers undo`() = runTest(testDispatcher) {
        coEvery { coordinator.linkExpense(any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.LINK, target())

        vm.submit() // nothing selected: no coordinator call
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.linkExpense(any(), any()) }

        vm.selectExpense(99L)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.linkExpense(5L, 99L) }
        assertEquals(InstallmentActionNotice.LINKED, vm.state.value.notice?.notice)
        assertEquals(InstallmentUndo.Unlink(5L), vm.state.value.notice?.undo)
        confirmVerified(coordinator)
    }

    @Test
    fun `undo of link calls unlinkExpense and offers no further undo`() = runTest(testDispatcher) {
        coEvery { coordinator.unlinkExpense(any()) } returns done(ref(InstallmentEntryStatus.SCHEDULED))
        val vm = viewModel()

        vm.undo(InstallmentUndo.Unlink(5L))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.unlinkExpense(5L) }
        assertEquals(InstallmentActionNotice.UNDONE, vm.state.value.notice?.notice)
        assertNull(vm.state.value.notice?.undo)
        confirmVerified(coordinator)
    }

    // ------------------------------------------------------------------ skip / unskip (ux-flows 3.6)

    @Test
    fun `skip needs a reason then reaches skip with that reason`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns done(ref(InstallmentEntryStatus.SKIPPED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())

        vm.submit() // no reason: no coordinator call
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { coordinator.skip(any(), any()) }

        vm.setSkipReason(InstallmentSkipReasonUi.WAIVED)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.skip(5L, InstallmentStatusReason.USER_SKIP_WAIVED) }
        assertEquals(InstallmentActionNotice.SKIPPED, vm.state.value.notice?.notice)
        assertEquals(InstallmentUndo.Unskip(5L), vm.state.value.notice?.undo)
        confirmVerified(coordinator)
    }

    @Test
    fun `every skip reason maps to a bounded user skip status reason`() {
        InstallmentSkipReasonUi.entries.forEach { assertTrue(it.reason.isUserSkipReason) }
    }

    @Test
    fun `skipping the last open payment shows the completed notice`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns
            done(ref(InstallmentEntryStatus.SKIPPED, InstallmentPlanStatus.COMPLETED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstallmentActionNotice.SKIPPED_LAST_PAYMENT, vm.state.value.notice?.notice)
        coVerify(exactly = 1) { coordinator.skip(5L, InstallmentStatusReason.USER_SKIP_OTHER) }
        confirmVerified(coordinator)
    }

    @Test
    fun `undo of skip calls unskip`() = runTest(testDispatcher) {
        coEvery { coordinator.unskip(any()) } returns done(ref(InstallmentEntryStatus.SCHEDULED))
        val vm = viewModel()

        vm.undo(InstallmentUndo.Unskip(5L))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.unskip(5L) }
        assertEquals(InstallmentActionNotice.UNDONE, vm.state.value.notice?.notice)
        confirmVerified(coordinator)
    }

    @Test
    fun `unskip sheet reaches unskip`() = runTest(testDispatcher) {
        coEvery { coordinator.unskip(any()) } returns done(ref(InstallmentEntryStatus.SCHEDULED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.UNSKIP, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.unskip(5L) }
        assertEquals(InstallmentActionNotice.UNSKIPPED, vm.state.value.notice?.notice)
        confirmVerified(coordinator)
    }

    // ------------------------------------------------------------------ adjust

    @Test
    fun `adjust with a new amount sends only the amount`() = runTest(testDispatcher) {
        coEvery { coordinator.adjustEntry(any(), any(), any()) } returns done(ref(InstallmentEntryStatus.SCHEDULED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.ADJUST, target())
        vm.setAmountText("52,50")
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.adjustEntry(5L, 52.5, null) }
        val undo = vm.state.value.notice?.undo as InstallmentUndo.RestoreSchedule
        assertEquals(50.0, undo.previousAmount!!, 0.0)
        assertNull(undo.previousDueEpochDay)
        confirmVerified(coordinator)
    }

    @Test
    fun `adjust with a new date sends only the date and undo restores it`() = runTest(testDispatcher) {
        coEvery { coordinator.adjustEntry(any(), any(), any()) } returns done(ref(InstallmentEntryStatus.SCHEDULED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.ADJUST, target())
        vm.setDueEpochDay(20_010L)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.adjustEntry(5L, null, 20_010L) }
        val undo = vm.state.value.notice?.undo as InstallmentUndo.RestoreSchedule
        assertNull(undo.previousAmount)
        assertEquals(20_000L, undo.previousDueEpochDay)

        vm.undo(undo)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { coordinator.adjustEntry(5L, null, 20_000L) }
        confirmVerified(coordinator)
    }

    @Test
    fun `adjust with nothing changed or an invalid amount does not call the coordinator`() = runTest(testDispatcher) {
        coEvery { coordinator.adjustEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.ADJUST, target())

        vm.submit() // unchanged
        vm.setAmountText("0")
        vm.submit() // not positive
        vm.setAmountText("12.345")
        vm.submit() // more than two decimals: never rounded silently
        vm.setAmountText("abc")
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { coordinator.adjustEntry(any(), any(), any()) }
        confirmVerified(coordinator)
    }

    @Test
    fun `amount input is strict and exact`() {
        assertEquals(5_250L, InstallmentAmountInput.parseMinor("52,5"))
        assertEquals(5_250L, InstallmentAmountInput.parseMinor(" 52.50 "))
        assertEquals(1L, InstallmentAmountInput.parseMinor("0.01"))
        assertNull(InstallmentAmountInput.parseMinor(""))
        assertNull(InstallmentAmountInput.parseMinor("-5"))
        assertNull(InstallmentAmountInput.parseMinor("1e3"))
        assertNull(InstallmentAmountInput.parseMinor("1.234"))
        assertEquals("50.00", InstallmentAmountInput.formatMinor(5_000L))
        assertEquals("0.05", InstallmentAmountInput.formatMinor(5L))
    }

    // ------------------------------------------------------------------ refusal and failure mapping

    // The three mapping tests are smoke-only: the `when` expressions are total at compile time, and a
    // resource id is never 0 in a unit test, so they prove no throw and a non-zero id, not the copy.
    @Test
    fun `smoke only - every refusal code maps to a string resource id without throwing`() {
        InstallmentRefusalCode.entries.forEach { code ->
            assertNotEquals("no string for $code", 0, InstallmentActionMessages.refusalText(code))
        }
    }

    @Test
    fun `smoke only - every coordinator failure maps to a bounded code and a string resource id`() {
        InstallmentOpFailure.entries.forEach { failure ->
            val code = InstallmentActionMessages.failureCode(failure)
            assertNotEquals("no string for $failure", 0, InstallmentActionMessages.failureText(code))
        }
    }

    @Test
    fun `smoke only - every notice maps to a string resource id without throwing`() {
        InstallmentActionNotice.entries.forEach { assertNotEquals(0, InstallmentActionMessages.noticeText(it)) }
    }

    @Test
    fun `a refusal keeps the sheet open and carries the bounded code`() = runTest(testDispatcher) {
        coEvery { coordinator.linkExpense(any(), any()) } returns
            InstallmentOpResult.Refused(InstallmentRefusalCode.CURRENCY_MISMATCH)
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.LINK, target())
        vm.selectExpense(99L)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentMutationState.Refused(InstallmentOperation.LINK, InstallmentRefusalCode.CURRENCY_MISMATCH),
            vm.state.value.mutation
        )
        assertNotNull(vm.state.value.sheet)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `a coordinator failure is a bounded failed state and keeps the sheet open`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns
            InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT)
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentMutationState.Failed(InstallmentOperation.RECORD, InstallmentActionFailureCode.CONFLICT),
            vm.state.value.mutation
        )
        assertNotNull(vm.state.value.sheet)
    }

    @Test
    fun `an unexpected exception becomes a bounded failure and its message is not kept`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } throws IllegalStateException("secret merchant text")
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        val mutation = vm.state.value.mutation
        assertEquals(
            InstallmentMutationState.Failed(InstallmentOperation.RECORD, InstallmentActionFailureCode.UNEXPECTED),
            mutation
        )
        assertFalse(mutation.toString().contains("secret"))
    }

    @Test
    fun `already in state is a silent success with no notice and no undo`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns InstallmentOpResult.AlreadyInState(ref())
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentMutationState.Success(InstallmentOperation.SKIP, alreadyInState = true),
            vm.state.value.mutation
        )
        // ux-flows 3.6: AlreadyInState is silent (no snackbar), so there is no Undo either.
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.sheet)
        coVerify(exactly = 1) { coordinator.skip(5L, InstallmentStatusReason.USER_SKIP_OTHER) }
        confirmVerified(coordinator)
    }

    @Test
    fun `link that is already in state gives no notice and no undo`() = runTest(testDispatcher) {
        coEvery { coordinator.linkExpense(any(), any()) } returns InstallmentOpResult.AlreadyInState(ref())
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.LINK, target())
        vm.selectExpense(99L)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            InstallmentMutationState.Success(InstallmentOperation.LINK, alreadyInState = true),
            vm.state.value.mutation
        )
        assertNull(vm.state.value.notice)
        coVerify(exactly = 1) { coordinator.linkExpense(5L, 99L) }
        confirmVerified(coordinator)
    }

    @Test
    fun `undo is ignored while another action is in progress`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit() // in progress: the coordinator call has not run yet

        vm.undo(InstallmentUndo.Unskip(5L))
        vm.undo(InstallmentUndo.Unlink(5L))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.confirmEntry(5L, any(), any()) }
        coVerify(exactly = 0) { coordinator.unskip(any()) }
        coVerify(exactly = 0) { coordinator.unlinkExpense(any()) }
        confirmVerified(coordinator)
    }

    @Test
    fun `illegal transition refusal maps to the already updated copy, not the generic refusal`() {
        assertEquals(
            com.yourname.expensetracker.R.string.installments_action_refused_already_updated,
            InstallmentActionMessages.refusalText(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        )
    }

    @Test
    fun `a failed undo shows a fixed notice`() = runTest(testDispatcher) {
        coEvery { coordinator.unskip(any()) } returns InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        val vm = viewModel()
        vm.undo(InstallmentUndo.Unskip(5L))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstallmentActionNotice.UNDO_FAILED, vm.state.value.notice?.notice)
    }

    @Test
    fun `an empty post-commit batch list is not dispatched`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { runner.run(any()) }
    }

    // ------------------------------------------------------------------ post-commit dispatch

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
    fun `a non-empty post-commit batch from Done reaches the runner exactly once after the coordinator returns`() =
        runTest(testDispatcher) {
            val committed = batch("corr-1", "key-1")
            val order = mutableListOf<String>()
            coEvery { coordinator.confirmEntry(any(), any(), any()) } coAnswers {
                order += "coordinator"
                InstallmentOpResult.Done(ref(), listOf(committed))
            }
            coEvery { runner.run(committed) } coAnswers {
                order += "runner"
                mockk(relaxed = true)
            }
            val vm = viewModel()
            vm.open(InstallmentEntryActionKind.RECORD, target())

            vm.submit()
            // Nothing is dispatched before the coordinator call has completed.
            assertTrue(order.isEmpty())
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { runner.run(committed) }
            coVerify(exactly = 1) { runner.run(any()) }
            assertEquals(listOf("coordinator", "runner"), order)
            assertEquals(
                InstallmentMutationState.Success(InstallmentOperation.RECORD, alreadyInState = false),
                vm.state.value.mutation
            )
        }

    @Test
    fun `only the non-empty batches of a Done result are dispatched, in order`() = runTest(testDispatcher) {
        val first = batch("corr-1", "key-1")
        val empty = PostCommitActionBatch.empty("corr-2")
        val second = batch("corr-3", "key-2", "key-3")
        val order = mutableListOf<String>()
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns
            InstallmentOpResult.Done(ref(), listOf(first, empty, second))
        coEvery { runner.run(any()) } coAnswers {
            order += firstArg<PostCommitActionBatch>().correlationId
            mockk(relaxed = true)
        }
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { runner.run(any()) }
        assertEquals(listOf("corr-1", "corr-3"), order)
    }

    @Test
    fun `a non-empty batch is not dispatched when the result is not Done`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns InstallmentOpResult.AlreadyInState(ref())
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns
            InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        val vm = viewModel()

        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `a post-commit batch that throws does not change the success result or state`() = runTest(testDispatcher) {
        val committed = batch("corr-1", "key-1")
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns InstallmentOpResult.Done(ref(), listOf(committed))
        coEvery { runner.run(committed) } throws IllegalStateException("secret merchant text")
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())

        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { runner.run(committed) }
        val mutation = vm.state.value.mutation
        assertEquals(InstallmentMutationState.Success(InstallmentOperation.RECORD, alreadyInState = false), mutation)
        assertNull(vm.state.value.sheet)
        assertEquals(InstallmentActionNotice.RECORDED, vm.state.value.notice?.notice)
        assertFalse(vm.state.value.toString().contains("secret"))
    }

    @Test
    fun `a failing post-commit batch does not stop the next batch`() = runTest(testDispatcher) {
        val first = batch("corr-1", "key-1")
        val second = batch("corr-2", "key-2")
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns InstallmentOpResult.Done(ref(), listOf(first, second))
        coEvery { runner.run(first) } throws IllegalStateException("first batch fails")
        coEvery { runner.run(second) } returns mockk(relaxed = true)
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { runner.run(first) }
        coVerify(exactly = 1) { runner.run(second) }
        assertEquals(
            InstallmentMutationState.Success(InstallmentOperation.RECORD, alreadyInState = false),
            vm.state.value.mutation
        )
    }

    @Test
    fun `a CancellationException from the post-commit batch run propagates and is not swallowed`() = runTest(testDispatcher) {
        val first = batch("corr-1", "key-1")
        val second = batch("corr-2", "key-2")
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns InstallmentOpResult.Done(ref(), listOf(first, second))
        coEvery { runner.run(first) } throws CancellationException("cancelled")
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())

        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        // The launched job was cancelled: the later batch never ran and no Success or Failed state was produced.
        coVerify(exactly = 1) { runner.run(first) }
        coVerify(exactly = 0) { runner.run(second) }
        assertTrue(vm.state.value.mutation is InstallmentMutationState.InProgress)
        assertNotNull(vm.state.value.sheet)
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `cancellation is rethrown and never becomes a failure`() = runTest(testDispatcher) {
        var coordinatorCalls = 0
        coEvery { coordinator.confirmEntry(any(), any(), any()) } coAnswers {
            coordinatorCalls++
            throw CancellationException("cancelled")
        }
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        // The call ran and threw. A swallowed cancellation would have reached the generic catch and
        // produced Failed(UNEXPECTED) plus the fixed failure state; a rethrown one ends the launched
        // job silently, so the state stays InProgress and nothing downstream (notice, post-commit
        // dispatch, a second coordinator call) happens.
        assertEquals(1, coordinatorCalls)
        assertEquals(InstallmentMutationState.InProgress(InstallmentOperation.RECORD), vm.state.value.mutation)
        assertFalse(vm.state.value.mutation is InstallmentMutationState.Failed)
        assertNull(vm.state.value.notice)
        assertNotNull(vm.state.value.sheet)
        coVerify(exactly = 0) { runner.run(any()) }

        // The job is gone, so the guard against a double submit is the only thing left: still one call.
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, coordinatorCalls)
    }

    // ------------------------------------------------------------------ screen exit

    @Test
    fun `screen exit clears the notice, the discard dialog and an idle sheet`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns done(ref(InstallmentEntryStatus.SKIPPED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallmentUndo.Unskip(5L), vm.state.value.notice?.undo) // a stale Undo is pending

        vm.open(InstallmentEntryActionKind.ADJUST, target())
        vm.setAmountText("60.00")
        vm.requestDismiss() // discard dialog up
        assertTrue(vm.state.value.showDiscardDialog)

        vm.onScreenExit(changingConfigurations = false)

        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.showDiscardDialog)
        assertEquals(InstallmentMutationState.Idle, vm.state.value.mutation)
        coVerify(exactly = 1) { coordinator.skip(5L, InstallmentStatusReason.USER_SKIP_OTHER) }
        confirmVerified(coordinator)
    }

    @Test
    fun `screen exit for a rotation keeps the sheet and its input but drops the notice`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns done(ref(InstallmentEntryStatus.SKIPPED))
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.WAIVED)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.open(InstallmentEntryActionKind.ADJUST, target())
        vm.setAmountText("60.00")
        vm.requestDismiss()

        vm.onScreenExit(changingConfigurations = true)

        assertNull(vm.state.value.notice)
        assertFalse(vm.state.value.showDiscardDialog)
        assertEquals(InstallmentActionSheetState(InstallmentEntryActionKind.ADJUST, target(), amountText = "60.00"), vm.state.value.sheet)
    }

    @Test
    fun `screen exit keeps the state of an action in progress and attaches no stale notice when it finishes`() =
        runTest(testDispatcher) {
            coEvery { coordinator.skip(any(), any()) } returns done(ref(InstallmentEntryStatus.SKIPPED))
            val vm = viewModel()
            vm.open(InstallmentEntryActionKind.SKIP, target())
            vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
            vm.submit()

            vm.onScreenExit(changingConfigurations = false)

            // Running: neither the sheet nor the mutation is reset.
            assertEquals(InstallmentMutationState.InProgress(InstallmentOperation.SKIP), vm.state.value.mutation)
            assertNotNull(vm.state.value.sheet)

            testDispatcher.scheduler.advanceUntilIdle()

            // It still committed once, but no Undo notice is left to replay on re-entry.
            coVerify(exactly = 1) { coordinator.skip(5L, InstallmentStatusReason.USER_SKIP_OTHER) }
            assertEquals(
                InstallmentMutationState.Success(InstallmentOperation.SKIP, alreadyInState = false),
                vm.state.value.mutation
            )
            assertNull(vm.state.value.notice)
            confirmVerified(coordinator)
        }

    @Test
    fun `opening a sheet after a screen exit makes notices attach again`() = runTest(testDispatcher) {
        coEvery { coordinator.skip(any(), any()) } returns done(ref(InstallmentEntryStatus.SKIPPED))
        val vm = viewModel()
        vm.onScreenExit(changingConfigurations = false)

        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)
        vm.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstallmentUndo.Unskip(5L), vm.state.value.notice?.undo)
    }

    // ------------------------------------------------------------------ discard

    @Test
    fun `a sheet with input asks before closing`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.SKIP, target())
        vm.setSkipReason(InstallmentSkipReasonUi.OTHER)

        vm.requestDismiss()
        assertTrue(vm.state.value.showDiscardDialog)
        assertNotNull(vm.state.value.sheet)

        vm.cancelDiscard()
        assertFalse(vm.state.value.showDiscardDialog)
        assertNotNull(vm.state.value.sheet)

        vm.requestDismiss()
        vm.confirmDiscard()
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.showDiscardDialog)
    }

    @Test
    fun `a sheet without input closes immediately`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.ADJUST, target())

        vm.requestDismiss() // prefilled values are not input

        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.showDiscardDialog)
    }

    @Test
    fun `an adjust edit counts as input and reverting it does not`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.ADJUST, target())
        vm.setAmountText("60.00")
        assertTrue(vm.state.value.sheet!!.hasInput)
        vm.setAmountText("50.00")
        assertFalse(vm.state.value.sheet!!.hasInput)
    }

    @Test
    fun `nothing closes while an action is running`() = runTest(testDispatcher) {
        coEvery { coordinator.confirmEntry(any(), any(), any()) } returns done()
        val vm = viewModel()
        vm.open(InstallmentEntryActionKind.RECORD, target())
        vm.submit()

        vm.requestDismiss()
        vm.submit() // double tap: still one write

        assertNotNull(vm.state.value.sheet)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { coordinator.confirmEntry(any(), any(), any()) }
    }
}
