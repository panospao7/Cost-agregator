package com.yourname.expensetracker.ui.screens.installments.overview

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAwaitingTotals
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentEntryCounts
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentNextDue
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentPlanSummary
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10a-1: overview ViewModel. Covers AE-5 (per-currency lines, none for an absent
 * currency), AE-10b (awaiting confirmation), AE-18 (figures and labels), unavailable never zero
 * (INV-17), and the loading, empty and error states with bounded messages (INV-31).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentOverviewViewModelTest : ViewModelTestUtils() {

    private val repository = mockk<InstallmentPlanReadRepository>()

    private fun counts(scheduled: Int = 0, paid: Int = 0, skipped: Int = 0, paidBeforeTracking: Int = 0) =
        InstallmentEntryCounts(
            scheduled = scheduled,
            paid = paid,
            skipped = skipped,
            cancelled = 0,
            reversed = 0,
            paidBeforeTracking = paidBeforeTracking
        )

    private fun summary(
        planId: Long,
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD,
        counts: InstallmentEntryCounts = counts(scheduled = 6, paid = 6),
        remainingMinor: Long = 30_000L,
        paidSoFar: InstallmentAmount = InstallmentAmount.Available(30_000L),
        nextDue: InstallmentNextDue? = null,
        endEpochDay: Long? = 20_000L,
        name: String = "Plan $planId"
    ) = InstallmentPlanSummary(
        planId = planId,
        planUuid = "uuid-$planId",
        displayName = name,
        currency = currency,
        status = InstallmentPlanStatus.ACTIVE,
        fulfilmentMode = mode,
        counts = counts,
        remainingScheduledMinor = remainingMinor,
        paidSoFar = paidSoFar,
        nextDue = nextDue,
        endEpochDay = endEpochDay
    )

    private fun due(entryId: Long, dueDay: Long, state: InstallmentDueState, minor: Long = 5_000L) =
        InstallmentNextDue(entryId = entryId, seq = 1, dueEpochDay = dueDay, scheduledMinor = minor, dueState = state)

    private fun overview(
        plans: List<InstallmentPlanSummary> = emptyList(),
        remaining: Map<InstallmentCurrency, Long> = emptyMap(),
        awaiting: Map<InstallmentCurrency, InstallmentAwaitingTotals> = emptyMap(),
        unreadable: Int = 0
    ) = InstallmentOverview(
        plans = plans,
        remainingScheduledByCurrency = remaining,
        awaitingConfirmationByCurrency = awaiting,
        unreadablePlanCount = unreadable
    )

    private fun given(overviewFlow: Flow<InstallmentOverview>, finishedPlans: Int = 0) {
        every { repository.observeOverview() } returns overviewFlow
        every { repository.observeHistory() } returns flowOf(List(finishedPlans) { mockk<InstallmentHistoryItem>() })
    }

    private fun newViewModel() = InstallmentOverviewViewModel(repository)

    private fun content(vm: InstallmentOverviewViewModel): InstallmentOverviewContent {
        val loadable = vm.state.value.loadable
        assertTrue("expected Data but was $loadable", loadable is LoadableUiState.Data)
        return (loadable as LoadableUiState.Data).value
    }

    @Test
    fun `loading is shown until the first emission`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<InstallmentOverview>()
        given(source)
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)

        source.emit(overview(listOf(summary(1)), remaining = mapOf(InstallmentCurrency.EUR to 30_000L)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
    }

    @Test
    fun `AE-5 per currency lines with no combined total and no line for an absent currency`() = runTest(testDispatcher) {
        given(
            flowOf(
                overview(
                    plans = listOf(
                        summary(1, InstallmentCurrency.EUR),
                        summary(2, InstallmentCurrency.USD),
                        summary(3, InstallmentCurrency.EUR)
                    ),
                    remaining = mapOf(InstallmentCurrency.USD to 50_000L, InstallmentCurrency.EUR to 52_400L)
                )
            )
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val lines = content(vm).remainingLines
        assertEquals(listOf("EUR", "USD"), lines.map { it.currencyCode })
        assertEquals(524.0, lines[0].remaining.amount, 0.0001)
        assertEquals("EUR", lines[0].remaining.currency)
        assertEquals(2, lines[0].planCount)
        assertEquals(500.0, lines[1].remaining.amount, 0.0001)
        assertEquals("USD", lines[1].remaining.currency)
        assertEquals(1, lines[1].planCount)
    }

    @Test
    fun `AE-5 a currency with no plan has no line`() = runTest(testDispatcher) {
        given(
            flowOf(
                overview(
                    plans = listOf(summary(1, InstallmentCurrency.EUR)),
                    // USD is present in the totals map but has no plan: the mapper must drop it.
                    remaining = mapOf(
                        InstallmentCurrency.EUR to 30_000L,
                        InstallmentCurrency.USD to 12_000L
                    )
                )
            )
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val lines = content(vm).remainingLines
        assertEquals(listOf("EUR"), lines.map { it.currencyCode })
        assertFalse(lines.any { it.currencyCode == "USD" })
    }

    @Test
    fun `AE-10b confirm mode due payments are awaiting confirmation and not counted`() = runTest(testDispatcher) {
        val overdueConfirm = summary(
            1, mode = InstallmentFulfilmentMode.CONFIRM,
            counts = counts(scheduled = 3, paid = 1),
            nextDue = due(10, 19_990L, InstallmentDueState.OVERDUE)
        )
        val overdueAuto = summary(
            2, mode = InstallmentFulfilmentMode.AUTO_RECORD,
            counts = counts(scheduled = 3, paid = 1),
            nextDue = due(20, 19_990L, InstallmentDueState.OVERDUE)
        )
        val upcomingConfirm = summary(
            3, mode = InstallmentFulfilmentMode.CONFIRM,
            counts = counts(scheduled = 3, paid = 1),
            nextDue = due(30, 20_100L, InstallmentDueState.UPCOMING)
        )
        given(
            flowOf(
                overview(
                    plans = listOf(overdueConfirm, overdueAuto, upcomingConfirm),
                    remaining = mapOf(InstallmentCurrency.EUR to 90_000L),
                    awaiting = mapOf(InstallmentCurrency.EUR to InstallmentAwaitingTotals(count = 2, minor = 10_000L))
                )
            )
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val c = content(vm)
        assertEquals(1, c.awaitingLines.size)
        assertEquals("EUR", c.awaitingLines[0].currencyCode)
        assertEquals(2, c.awaitingLines[0].count)
        assertEquals(100.0, c.awaitingLines[0].amount.amount, 0.0001)
        val byId = c.plans.associateBy { it.planId }
        assertTrue(byId.getValue(1).awaitingConfirmation)
        assertFalse("auto-record plans are not awaiting confirmation", byId.getValue(2).awaitingConfirmation)
        assertFalse("upcoming payments are not awaiting", byId.getValue(3).awaitingConfirmation)
    }

    @Test
    fun `no awaiting lines when nothing is awaiting`() = runTest(testDispatcher) {
        given(flowOf(overview(listOf(summary(1)), remaining = mapOf(InstallmentCurrency.EUR to 30_000L))))
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(content(vm).awaitingLines.isEmpty())
    }

    @Test
    fun `AE-18 fixed payment standalone plan shows remaining scheduled payments and its end date`() = runTest(testDispatcher) {
        // 240 x 523.17 EUR = 125,560.80 EUR remaining at creation, first due 2027-05-01 (epoch day 20,939).
        val plan = summary(
            1,
            counts = counts(scheduled = 240),
            remainingMinor = 12_556_080L,
            paidSoFar = InstallmentAmount.Available(0L),
            nextDue = due(1, 20_939L, InstallmentDueState.UPCOMING, minor = 52_317L),
            endEpochDay = 21_000L + 17_000L
        )
        given(flowOf(overview(listOf(plan), remaining = mapOf(InstallmentCurrency.EUR to 12_556_080L))))
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val c = content(vm)
        assertEquals(125_560.80, c.remainingLines.single().remaining.amount, 0.0001)
        val card = c.plans.single()
        assertEquals(125_560.80, card.remaining.amount, 0.0001)
        assertEquals(240, card.scheduledCount)
        assertEquals(0, card.paidCount)
        assertEquals(240, card.totalCount)
        assertEquals(38_000L, card.endEpochDay)
        assertEquals(523.17, card.nextDue!!.amount.amount, 0.0001)
        assertEquals("EUR", card.nextDue!!.amount.currency)
    }

    @Test
    fun `progress counts paid before tracking as paid and skipped as part of the schedule`() = runTest(testDispatcher) {
        val plan = summary(1, counts = counts(scheduled = 5, paid = 3, skipped = 1, paidBeforeTracking = 2))
        given(flowOf(overview(listOf(plan), remaining = mapOf(InstallmentCurrency.EUR to 25_000L))))
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val card = content(vm).plans.single()
        assertEquals(5, card.paidCount)
        assertEquals(11, card.totalCount)
    }

    @Test
    fun `unavailable paid so far is never shown as zero`() = runTest(testDispatcher) {
        val drifted = summary(
            1,
            paidSoFar = InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT)
        )
        val genuineZero = summary(2, paidSoFar = InstallmentAmount.Available(0L))
        given(
            flowOf(
                overview(
                    listOf(drifted, genuineZero),
                    remaining = mapOf(InstallmentCurrency.EUR to 60_000L)
                )
            )
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val byId = content(vm).plans.associateBy { it.planId }
        val unavailable = byId.getValue(1).paidSoFar
        assertTrue(unavailable is InstallmentAmountUi.Unavailable)
        assertEquals(
            InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT,
            (unavailable as InstallmentAmountUi.Unavailable).reason
        )
        // A real zero stays a money value with its currency.
        val zero = byId.getValue(2).paidSoFar
        assertTrue(zero is InstallmentAmountUi.Money)
        assertEquals(0.0, (zero as InstallmentAmountUi.Money).display.amount, 0.0)
        assertEquals("EUR", zero.display.currency)
        // Remaining scheduled payments stay derived when paid-so-far is unavailable.
        assertEquals(300.0, byId.getValue(1).remaining.amount, 0.0001)
    }

    @Test
    fun `unreadable plans are surfaced as a count and never as an empty overview`() = runTest(testDispatcher) {
        given(flowOf(overview(unreadable = 2)))
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val c = content(vm)
        assertEquals(2, c.unreadablePlanCount)
        assertTrue(c.plans.isEmpty())
        assertNull(vm.state.value.emptyKind)
    }

    @Test
    fun `empty with no plans ever`() = runTest(testDispatcher) {
        given(flowOf(overview()), finishedPlans = 0)
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val loadable = vm.state.value.loadable
        assertTrue(loadable is LoadableUiState.Empty)
        assertEquals(InstallmentOverviewEmptyKind.NO_PLANS, vm.state.value.emptyKind)
        assertEquals(
            UiText.StringResource(R.string.installments_empty_body),
            (loadable as LoadableUiState.Empty).reason
        )
    }

    @Test
    fun `empty variant when only finished plans exist`() = runTest(testDispatcher) {
        given(flowOf(overview()), finishedPlans = 2)
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadable is LoadableUiState.Empty)
        assertEquals(InstallmentOverviewEmptyKind.NO_ACTIVE_PLANS, vm.state.value.emptyKind)
    }

    @Test
    fun `history read failure never blocks the overview`() = runTest(testDispatcher) {
        every { repository.observeOverview() } returns
            flowOf(overview(listOf(summary(1)), remaining = mapOf(InstallmentCurrency.EUR to 30_000L)))
        every { repository.observeHistory() } returns flow { throw IllegalStateException("history boom") }
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
        assertFalse(vm.state.value.refreshFailed)
    }

    @Test
    fun `load failure maps to a bounded code and fixed string and never the exception message`() = runTest(testDispatcher) {
        val secret = "SQLITE_ERROR near Laptop 4242 EUR"
        given(flow { throw IllegalStateException(secret) })
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        val loadable = state.loadable
        assertTrue(loadable is LoadableUiState.Error)
        val message = (loadable as LoadableUiState.Error).message
        assertEquals(UiText.StringResource(R.string.installments_error_load), message)
        assertFalse(message.toString().contains(secret))
        assertEquals(InstallmentOverviewErrorCode.LOAD_FAILED, state.errorCode)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun `refresh failure keeps the last loaded content`() = runTest(testDispatcher) {
        val loaded = overview(listOf(summary(1)), remaining = mapOf(InstallmentCurrency.EUR to 30_000L))
        given(
            flow {
                emit(loaded)
                throw IllegalStateException("secret refresh failure")
            }
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loadable is LoadableUiState.Data)
        assertEquals(1, (state.loadable as LoadableUiState.Data).value.plans.size)
        assertTrue(state.refreshFailed)
        assertEquals(InstallmentOverviewErrorCode.REFRESH_FAILED, state.errorCode)
    }

    @Test
    fun `retry recovers after a load failure`() = runTest(testDispatcher) {
        val loaded = overview(listOf(summary(1)), remaining = mapOf(InstallmentCurrency.EUR to 30_000L))
        var calls = 0
        every { repository.observeOverview() } answers {
            calls++
            if (calls == 1) flow { throw IllegalStateException("first read fails") } else flowOf(loaded)
        }
        every { repository.observeHistory() } returns flowOf(emptyList())
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Error)

        vm.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
        assertNull(vm.state.value.errorCode)
    }

    @Test
    fun `next due card picks the nearest payment that is not overdue`() = runTest(testDispatcher) {
        val overdue = summary(1, nextDue = due(1, 19_900L, InstallmentDueState.OVERDUE), name = "Overdue plan")
        val later = summary(2, nextDue = due(2, 20_200L, InstallmentDueState.UPCOMING), name = "Later plan")
        val sooner = summary(3, nextDue = due(3, 20_050L, InstallmentDueState.UPCOMING, minor = 7_500L), name = "Sooner plan")
        given(
            flowOf(
                overview(
                    listOf(overdue, later, sooner),
                    remaining = mapOf(InstallmentCurrency.EUR to 90_000L)
                )
            )
        )
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val card = content(vm).nextDueCard
        assertNotNull(card)
        assertEquals(3L, card!!.planId)
        assertEquals(75.0, card.nextDue.amount.amount, 0.0001)
    }

    @Test
    fun `no next due card when every upcoming payment is overdue`() = runTest(testDispatcher) {
        val overdue = summary(1, nextDue = due(1, 19_900L, InstallmentDueState.OVERDUE))
        given(flowOf(overview(listOf(overdue), remaining = mapOf(InstallmentCurrency.EUR to 30_000L))))
        val vm = newViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(content(vm).nextDueCard)
    }
}
