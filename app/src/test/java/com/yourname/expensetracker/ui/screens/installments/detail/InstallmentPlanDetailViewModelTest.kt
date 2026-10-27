package com.yourname.expensetracker.ui.screens.installments.detail

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentEntryCounts
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentEntryLine
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanDetail
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanSummary
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * loan-installments S10a-2: plan detail ViewModel. Covers progress math (owner-approvals 47(a)),
 * AE-18 row labels, awaiting confirmation (AE-10b), unavailable never zero (INV-17), the fixed
 * not-found state, and bounded failures with fixed strings (INV-31).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentPlanDetailViewModelTest : ViewModelTestUtils() {

    private val repository = mockk<InstallmentPlanReadRepository>()

    private fun counts(
        scheduled: Int = 0,
        paid: Int = 0,
        skipped: Int = 0,
        cancelled: Int = 0,
        reversed: Int = 0,
        paidBeforeTracking: Int = 0
    ) = InstallmentEntryCounts(scheduled, paid, skipped, cancelled, reversed, paidBeforeTracking)

    private fun entry(
        id: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        dueState: InstallmentDueState? = InstallmentDueState.UPCOMING,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        minor: Long = 5_000L,
        original: Long? = null,
        reason: String? = null,
        variance: InstallmentAmount? = null,
        dueDay: Long = 20_000L + id
    ) = InstallmentEntryLine(
        entryId = id,
        seq = id.toInt(),
        kind = kind,
        status = status,
        statusReason = reason,
        dueEpochDay = dueDay,
        scheduledMinor = minor,
        originalScheduledMinor = original,
        linkedExpenseId = null,
        dueState = dueState,
        variance = variance
    )

    private fun detail(
        planId: Long = 7L,
        name: String = "Laptop",
        lender: String? = null,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD,
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        counts: InstallmentEntryCounts = counts(scheduled = 2, paid = 1),
        remainingMinor: Long = 10_000L,
        paidSoFar: InstallmentAmount = InstallmentAmount.Available(5_000L),
        financingCost: InstallmentAmount = InstallmentAmount.Available(0L),
        endEpochDay: Long? = 20_100L,
        entries: List<InstallmentEntryLine> = emptyList()
    ) = InstallmentPlanDetail(
        summary = InstallmentPlanSummary(
            planId = planId,
            planUuid = "uuid-$planId",
            displayName = name,
            currency = currency,
            status = status,
            fulfilmentMode = mode,
            counts = counts,
            remainingScheduledMinor = remainingMinor,
            paidSoFar = paidSoFar,
            nextDue = null,
            endEpochDay = endEpochDay
        ),
        kind = InstallmentPlanKind.PURCHASE_ATTACHED,
        lenderLabel = lender,
        purchaseExpenseId = null,
        entries = entries,
        financingCost = financingCost,
        suggestions = emptyList()
    )

    private fun newViewModel() = InstallmentPlanDetailViewModel(repository)

    private fun startWith(detail: InstallmentPlanDetail?, planId: Long = 7L): InstallmentPlanDetailViewModel {
        every { repository.observeDetail(planId) } returns flowOf(detail)
        val vm = newViewModel()
        vm.start(planId)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun content(vm: InstallmentPlanDetailViewModel): InstallmentPlanDetailContent {
        val loadable = vm.state.value.loadable
        assertTrue("expected Data but was $loadable", loadable is LoadableUiState.Data)
        return (loadable as LoadableUiState.Data).value
    }

    @Test
    fun `loading is shown until the first emission`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<InstallmentPlanDetail?>()
        every { repository.observeDetail(7L) } returns source
        val vm = newViewModel()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)

        source.emit(detail())
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
    }

    @Test
    fun `header shows the plan name currency kind and lender when present`() = runTest(testDispatcher) {
        val c = content(startWith(detail(name = "Laptop", lender = "Demo Bank", currency = InstallmentCurrency.USD)))
        assertEquals("Laptop", c.displayName)
        assertEquals("Demo Bank", c.lenderLabel)
        assertEquals("USD", c.currencyCode)
        assertEquals(InstallmentPlanKind.PURCHASE_ATTACHED, c.kind)
        assertEquals(InstallmentPlanStatus.ACTIVE, c.status)
    }

    @Test
    fun `a blank or missing lender is not shown`() = runTest(testDispatcher) {
        assertNull(content(startWith(detail(lender = null))).lenderLabel)
        assertNull(content(startWith(detail(lender = "  "))).lenderLabel)
    }

    @Test
    fun `progress is paid plus paid before tracking of paid plus scheduled plus skipped`() = runTest(testDispatcher) {
        // n = 3 + 2 = 5; N = 5 + 5 scheduled + 1 skipped = 11; cancelled and reversed are not part of N.
        val plan = detail(counts = counts(scheduled = 5, paid = 3, skipped = 1, cancelled = 4, reversed = 2, paidBeforeTracking = 2))
        val c = content(startWith(plan))
        assertEquals(5, c.paidCount)
        assertEquals(11, c.totalCount)
        assertEquals(5, c.scheduledCount)
    }

    @Test
    fun `remaining scheduled payments are in the plan currency`() = runTest(testDispatcher) {
        val c = content(startWith(detail(currency = InstallmentCurrency.USD, remainingMinor = 12_556_080L)))
        assertEquals(125_560.80, c.remaining.amount, 0.0001)
        assertEquals("USD", c.remaining.currency)
    }

    @Test
    fun `AE-18 every entry status maps to its label`() = runTest(testDispatcher) {
        val entries = listOf(
            entry(1, InstallmentEntryStatus.SCHEDULED, InstallmentDueState.UPCOMING),
            entry(2, InstallmentEntryStatus.SCHEDULED, InstallmentDueState.DUE),
            entry(3, InstallmentEntryStatus.SCHEDULED, InstallmentDueState.OVERDUE),
            entry(4, InstallmentEntryStatus.PAID, dueState = null),
            entry(5, InstallmentEntryStatus.PAID, dueState = null, kind = InstallmentEntryKind.PAYOFF),
            entry(6, InstallmentEntryStatus.SKIPPED, dueState = null),
            entry(7, InstallmentEntryStatus.CANCELLED, dueState = null),
            entry(8, InstallmentEntryStatus.REVERSED, dueState = null),
            entry(9, InstallmentEntryStatus.PAID_BEFORE_TRACKING, dueState = null)
        )
        val c = content(startWith(detail(entries = entries)))
        assertEquals(
            listOf(
                InstallmentEntryLabelUi.UPCOMING,
                InstallmentEntryLabelUi.DUE_TODAY,
                InstallmentEntryLabelUi.OVERDUE,
                InstallmentEntryLabelUi.PAID,
                InstallmentEntryLabelUi.PAYOFF,
                InstallmentEntryLabelUi.SKIPPED,
                InstallmentEntryLabelUi.CANCELLED,
                InstallmentEntryLabelUi.REFUNDED,
                InstallmentEntryLabelUi.PAID_BEFORE_TRACKING
            ),
            c.entries.map { it.label }
        )
    }

    @Test
    fun `entry rows keep the schedule order amount and currency`() = runTest(testDispatcher) {
        val entries = listOf(entry(1, minor = 5_217L), entry(2, minor = 4_996L))
        val c = content(startWith(detail(entries = entries)))
        assertEquals(listOf(1L, 2L), c.entries.map { it.entryId })
        assertEquals(52.17, c.entries[0].amount.amount, 0.0001)
        assertEquals("EUR", c.entries[1].amount.currency)
    }

    @Test
    fun `AE-10b confirm mode active plan counts due and overdue entries as awaiting and not upcoming ones`() = runTest(testDispatcher) {
        val entries = listOf(
            entry(1, dueState = InstallmentDueState.OVERDUE, minor = 5_000L),
            entry(2, dueState = InstallmentDueState.DUE, minor = 5_000L),
            entry(3, dueState = InstallmentDueState.UPCOMING, minor = 5_000L),
            entry(4, InstallmentEntryStatus.PAID, dueState = null)
        )
        val c = content(startWith(detail(mode = InstallmentFulfilmentMode.CONFIRM, entries = entries)))
        assertEquals(2, c.awaitingCount)
        assertEquals(100.0, c.awaitingAmount!!.amount, 0.0001)
        assertEquals("EUR", c.awaitingAmount!!.currency)
        assertEquals(listOf(true, true, false, false), c.entries.map { it.awaitingConfirmation })
    }

    @Test
    fun `auto record plans have nothing awaiting confirmation`() = runTest(testDispatcher) {
        val entries = listOf(entry(1, dueState = InstallmentDueState.OVERDUE))
        val c = content(startWith(detail(mode = InstallmentFulfilmentMode.AUTO_RECORD, entries = entries)))
        assertEquals(0, c.awaitingCount)
        assertNull(c.awaitingAmount)
        assertFalse(c.entries.single().awaitingConfirmation)
    }

    @Test
    fun `a finished confirm mode plan has nothing awaiting confirmation`() = runTest(testDispatcher) {
        val entries = listOf(entry(1, dueState = InstallmentDueState.OVERDUE))
        val c = content(
            startWith(detail(status = InstallmentPlanStatus.CANCELLED, mode = InstallmentFulfilmentMode.CONFIRM, entries = entries))
        )
        assertEquals(0, c.awaitingCount)
        assertFalse(c.entries.single().awaitingConfirmation)
    }

    @Test
    fun `unavailable paid so far and financing cost are never zero`() = runTest(testDispatcher) {
        val drifted = detail(
            paidSoFar = InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT),
            financingCost = InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
        )
        val c = content(startWith(drifted))
        assertTrue(c.paidSoFar is InstallmentAmountUi.Unavailable)
        assertTrue(c.financingCost is InstallmentAmountUi.Unavailable)
        assertEquals(InstallmentFinancingNoteUi.PAYMENT_UNREADABLE, c.financingNote)
        // Remaining scheduled payments stay derived when paid-so-far is unavailable.
        assertEquals(100.0, c.remaining.amount, 0.0001)
    }

    @Test
    fun `a genuine zero stays a money value with its currency`() = runTest(testDispatcher) {
        val c = content(
            startWith(detail(paidSoFar = InstallmentAmount.Available(0L), financingCost = InstallmentAmount.Available(0L)))
        )
        val paid = c.paidSoFar
        assertTrue(paid is InstallmentAmountUi.Money)
        assertEquals(0.0, (paid as InstallmentAmountUi.Money).display.amount, 0.0)
        assertEquals("EUR", paid.display.currency)
        assertTrue(c.financingCost is InstallmentAmountUi.Money)
        assertNull(c.financingNote)
    }

    @Test
    fun `financing cost may be negative and keeps its sign`() = runTest(testDispatcher) {
        val c = content(startWith(detail(financingCost = InstallmentAmount.Available(-1_000L))))
        val cost = c.financingCost as InstallmentAmountUi.Money
        assertEquals(-10.0, cost.display.amount, 0.0001)
    }

    @Test
    fun `financing cost unavailable reasons map to bounded notes`() = runTest(testDispatcher) {
        fun noteFor(reason: InstallmentUnavailableReason) =
            content(startWith(detail(financingCost = InstallmentAmount.Unavailable(reason)))).financingNote

        assertEquals(InstallmentFinancingNoteUi.NEEDS_SAME_CURRENCY_PURCHASE, noteFor(InstallmentUnavailableReason.NO_PURCHASE))
        assertEquals(InstallmentFinancingNoteUi.NEEDS_SAME_CURRENCY_PURCHASE, noteFor(InstallmentUnavailableReason.CURRENCY_DIFFERS))
        assertEquals(InstallmentFinancingNoteUi.NOT_AVAILABLE_FOR_STATUS, noteFor(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE))
        assertEquals(InstallmentFinancingNoteUi.PAYMENT_UNREADABLE, noteFor(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE))
    }

    @Test
    fun `a paid entry variance unavailable is never zero and an available one is kept`() = runTest(testDispatcher) {
        val entries = listOf(
            entry(1, InstallmentEntryStatus.PAID, dueState = null, variance = InstallmentAmount.Available(200L)),
            entry(
                2, InstallmentEntryStatus.PAID, dueState = null,
                variance = InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
            ),
            entry(3)
        )
        val c = content(startWith(detail(entries = entries)))
        assertEquals(2.0, (c.entries[0].variance as InstallmentAmountUi.Money).display.amount, 0.0001)
        assertTrue(c.entries[1].variance is InstallmentAmountUi.Unavailable)
        assertNull(c.entries[2].variance)
    }

    @Test
    fun `original amount is shown only when the entry amount was adjusted`() = runTest(testDispatcher) {
        val entries = listOf(entry(1, minor = 4_996L, original = 5_000L), entry(2, minor = 5_000L, original = 5_000L), entry(3))
        val c = content(startWith(detail(entries = entries)))
        assertEquals(50.0, c.entries[0].originalAmount!!.amount, 0.0001)
        assertNull(c.entries[1].originalAmount)
        assertNull(c.entries[2].originalAmount)
    }

    @Test
    fun `row reasons are bounded to the entry status and unknown codes are dropped`() = runTest(testDispatcher) {
        val entries = listOf(
            entry(1, InstallmentEntryStatus.SKIPPED, dueState = null, reason = "USER_SKIP_LENDER_HOLIDAY"),
            entry(2, InstallmentEntryStatus.SKIPPED, dueState = null, reason = "USER_SKIP_WAIVED"),
            entry(3, InstallmentEntryStatus.CANCELLED, dueState = null, reason = "PLAN_STOPPED"),
            entry(4, InstallmentEntryStatus.CANCELLED, dueState = null, reason = "PAID_OFF_EARLY"),
            entry(5, InstallmentEntryStatus.CANCELLED, dueState = null, reason = "SHORTENED"),
            entry(6, InstallmentEntryStatus.SCHEDULED, InstallmentDueState.OVERDUE, reason = "EXPENSE_REMOVED"),
            entry(7, InstallmentEntryStatus.SKIPPED, dueState = null, reason = "some free text 4242"),
            entry(8, InstallmentEntryStatus.SKIPPED, dueState = null, reason = "PLAN_STOPPED"),
            entry(9, InstallmentEntryStatus.PAID, dueState = null, reason = "USER_SKIP_OTHER")
        )
        val c = content(startWith(detail(entries = entries)))
        assertEquals(
            listOf(
                InstallmentEntryReasonUi.SKIP_LENDER_HOLIDAY,
                InstallmentEntryReasonUi.SKIP_WAIVED,
                InstallmentEntryReasonUi.PLAN_STOPPED,
                InstallmentEntryReasonUi.PAID_OFF_EARLY,
                InstallmentEntryReasonUi.SHORTENED,
                InstallmentEntryReasonUi.EXPENSE_REMOVED,
                null,
                null,
                null
            ),
            c.entries.map { it.reason }
        )
    }

    @Test
    fun `end date unavailable is kept as null and never a date`() = runTest(testDispatcher) {
        assertNull(content(startWith(detail(status = InstallmentPlanStatus.COMPLETED, endEpochDay = null))).endEpochDay)
    }

    @Test
    fun `a missing plan shows the fixed not found state`() = runTest(testDispatcher) {
        val vm = startWith(null)
        val state = vm.state.value
        assertEquals(InstallmentDetailErrorCode.NOT_FOUND, state.errorCode)
        val loadable = state.loadable
        assertTrue(loadable is LoadableUiState.Error)
        assertEquals(UiText.StringResource(R.string.installments_error_not_found), (loadable as LoadableUiState.Error).message)
    }

    @Test
    fun `an invalid plan id is not found without reading the repository`() = runTest(testDispatcher) {
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            val vm = newViewModel()
            vm.start(id)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(InstallmentDetailErrorCode.NOT_FOUND, vm.state.value.errorCode)
            assertTrue(vm.state.value.loadable is LoadableUiState.Error)
        }
        verify(exactly = 0) { repository.observeDetail(any()) }
    }

    @Test
    fun `a plan deleted while open becomes not found`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<InstallmentPlanDetail?>()
        every { repository.observeDetail(7L) } returns source
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        source.emit(detail())
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Data)

        source.emit(null)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallmentDetailErrorCode.NOT_FOUND, vm.state.value.errorCode)
    }

    @Test
    fun `starting twice with the same id observes once`() = runTest(testDispatcher) {
        every { repository.observeDetail(7L) } returns flowOf(detail())
        val vm = newViewModel()
        vm.start(7L)
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { repository.observeDetail(7L) }
    }

    @Test
    fun `load failure maps to a bounded code and fixed string and never the exception message`() = runTest(testDispatcher) {
        val secret = "SQLITE_ERROR near Laptop 4242 EUR"
        every { repository.observeDetail(7L) } returns flow { throw IllegalStateException(secret) }
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        val loadable = state.loadable
        assertTrue(loadable is LoadableUiState.Error)
        val message = (loadable as LoadableUiState.Error).message
        assertEquals(UiText.StringResource(R.string.installments_error_load_detail), message)
        assertFalse(message.toString().contains(secret))
        assertEquals(InstallmentDetailErrorCode.LOAD_FAILED, state.errorCode)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun `refresh failure keeps the last loaded content`() = runTest(testDispatcher) {
        every { repository.observeDetail(7L) } returns flow {
            emit(detail())
            throw IllegalStateException("secret refresh failure")
        }
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
        assertTrue(vm.state.value.refreshFailed)
        assertEquals(InstallmentDetailErrorCode.REFRESH_FAILED, vm.state.value.errorCode)
    }

    @Test
    fun `retry recovers after a load failure`() = runTest(testDispatcher) {
        var calls = 0
        every { repository.observeDetail(7L) } answers {
            calls++
            if (calls == 1) flow { throw IllegalStateException("first read fails") } else flowOf(detail())
        }
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Error)

        vm.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
        assertNull(vm.state.value.errorCode)
    }

    @Test
    fun `starting with a changed id resets to loading and ignores the old plan`() = runTest(testDispatcher) {
        val source7 = MutableSharedFlow<InstallmentPlanDetail?>()
        val source8 = MutableSharedFlow<InstallmentPlanDetail?>()
        every { repository.observeDetail(7L) } returns source7
        every { repository.observeDetail(8L) } returns source8
        val vm = newViewModel()

        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        source7.emit(detail(planId = 7L, name = "Seven"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Seven", content(vm).displayName)

        vm.start(8L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)

        // The old plan's flow is no longer collected: a late emission must not overwrite the state.
        source7.emit(detail(planId = 7L, name = "Stale seven"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)

        source8.emit(detail(planId = 8L, name = "Eight"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Eight", content(vm).displayName)
    }

    @Test
    fun `stop cancels the collection and a later start restarts cleanly`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<InstallmentPlanDetail?>()
        every { repository.observeDetail(7L) } returns source
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        source.emit(detail(name = "Before stop"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Before stop", content(vm).displayName)

        vm.stop()
        source.emit(detail(name = "After stop"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Before stop", content(vm).displayName)

        // Same id after stop is not treated as already started.
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)
        source.emit(detail(name = "Restarted"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Restarted", content(vm).displayName)
        verify(exactly = 2) { repository.observeDetail(7L) }
    }

    @Test
    fun `an upstream CancellationException is rethrown and not turned into a load failure`() = runTest(testDispatcher) {
        every { repository.observeDetail(7L) } returns flow { throw CancellationException("upstream cancelled") }
        val vm = newViewModel()
        vm.start(7L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)
        assertNull(vm.state.value.errorCode)
        assertFalse(vm.state.value.refreshFailed)
    }

    @Test
    fun `a failure is logged with the bounded code only`() = runTest(testDispatcher) {
        val secret = "SQLITE_ERROR near Laptop 4242 EUR"
        val messages = mutableListOf<String>()
        var throwableLogged = false
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                if (t != null) throwableLogged = true
            }
        }
        Timber.plant(tree)
        try {
            every { repository.observeDetail(7L) } returns flow { throw IllegalStateException(secret) }
            val vm = newViewModel()
            vm.start(7L)
            testDispatcher.scheduler.advanceUntilIdle()
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("code=LOAD_FAILED"))
        assertFalse(messages.single().contains(secret))
        assertFalse(messages.single().contains("Laptop"))
        assertFalse(throwableLogged)
    }

    @Test
    fun `a paid entry paid exactly as scheduled has no variance`() = runTest(testDispatcher) {
        val entries = listOf(
            entry(1, InstallmentEntryStatus.PAID, dueState = null, variance = InstallmentAmount.Available(0L)),
            entry(2, InstallmentEntryStatus.PAID, dueState = null, variance = InstallmentAmount.Available(-300L)),
            entry(
                3, InstallmentEntryStatus.PAID, dueState = null,
                variance = InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE)
            )
        )
        val c = content(startWith(detail(entries = entries)))
        assertNull(c.entries[0].variance)
        assertEquals(-3.0, (c.entries[1].variance as InstallmentAmountUi.Money).display.amount, 0.0001)
        assertTrue(c.entries[2].variance is InstallmentAmountUi.Unavailable)
    }
}
