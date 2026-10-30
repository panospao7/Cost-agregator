package com.yourname.expensetracker.ui.screens.installments.history

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentEntryCounts
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistoryItem
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentHistorySummary
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
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
 * loan-installments S10a-2: history ViewModel. Covers the AE-7 (Stop) and AE-11 (completion)
 * history figures, refunded plans (a legitimate zero), unavailable never zero (INV-17), filters,
 * the empty state, and bounded failures with fixed strings (INV-31).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentHistoryViewModelTest : ViewModelTestUtils() {

    private val repository = mockk<InstallmentPlanReadRepository>()

    private fun day(year: Int, month: Int, dayOfMonth: Int) = LocalDate.of(year, month, dayOfMonth).toEpochDay()

    private fun counts(
        paid: Int = 0,
        skipped: Int = 0,
        cancelled: Int = 0,
        reversed: Int = 0,
        paidBeforeTracking: Int = 0
    ) = InstallmentEntryCounts(
        scheduled = 0,
        paid = paid,
        skipped = skipped,
        cancelled = cancelled,
        reversed = reversed,
        paidBeforeTracking = paidBeforeTracking
    )

    private fun item(
        planId: Long,
        status: InstallmentPlanStatus,
        name: String = "Plan $planId",
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        start: Long? = day(2027, 4, 10),
        end: Long? = day(2028, 3, 10),
        total: InstallmentAmount = InstallmentAmount.Available(60_000L),
        financing: InstallmentAmount = InstallmentAmount.Available(0L),
        counts: InstallmentEntryCounts = counts(paid = 12)
    ) = InstallmentHistoryItem(
        planId = planId,
        planUuid = "uuid-$planId",
        displayName = name,
        summary = InstallmentHistorySummary(
            currency = currency,
            finalStatus = status,
            startEpochDay = start,
            endEpochDay = end,
            totalCounted = total,
            counts = counts,
            financingCost = financing
        )
    )

    private fun newViewModel(items: List<InstallmentHistoryItem>): InstallmentHistoryViewModel {
        every { repository.observeHistory() } returns flowOf(items)
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun cards(vm: InstallmentHistoryViewModel): List<InstallmentHistoryCardUi> {
        val loadable = vm.state.value.loadable
        assertTrue("expected Data but was $loadable", loadable is LoadableUiState.Data)
        return (loadable as LoadableUiState.Data).value.cards
    }

    @Test
    fun `loading is shown until the first emission`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<List<InstallmentHistoryItem>>()
        every { repository.observeHistory() } returns source
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)

        source.emit(listOf(item(1, InstallmentPlanStatus.COMPLETED)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Data)
    }

    @Test
    fun `AE-7 a stopped plan shows cancelled dates total and counts`() = runTest(testDispatcher) {
        // "Cancelled, 2027-04-10 -> 2027-07-05, total 150.00 EUR, 3 paid / 9 cancelled".
        val stopped = item(
            1, InstallmentPlanStatus.CANCELLED, name = "Demo Electronics",
            start = day(2027, 4, 10), end = day(2027, 7, 5),
            total = InstallmentAmount.Available(15_000L),
            financing = InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE),
            counts = counts(paid = 3, cancelled = 9)
        )
        val card = cards(newViewModel(listOf(stopped))).single()

        assertEquals(InstallmentPlanStatus.CANCELLED, card.finalStatus)
        assertEquals(day(2027, 4, 10), card.startEpochDay)
        assertEquals(day(2027, 7, 5), card.endEpochDay)
        val total = card.totalCounted as InstallmentAmountUi.Money
        assertEquals(150.0, total.display.amount, 0.0001)
        assertEquals("EUR", total.display.currency)
        assertEquals(3, card.paidCount)
        assertEquals(9, card.cancelledCount)
        // Financing cost is not derivable for a cancelled plan: Unavailable, never 0.
        assertTrue(card.financingCost is InstallmentAmountUi.Unavailable)
    }

    @Test
    fun `AE-11 a completed plan shows its dates total paid count and a real zero financing cost`() = runTest(testDispatcher) {
        // "Demo Electronics, EUR, 2027-04-10 -> 2028-03-10, Completed, total 600.00, 12 paid, financing cost 0.00".
        val completed = item(
            1, InstallmentPlanStatus.COMPLETED, name = "Demo Electronics",
            start = day(2027, 4, 10), end = day(2028, 3, 10),
            total = InstallmentAmount.Available(60_000L),
            financing = InstallmentAmount.Available(0L),
            counts = counts(paid = 12)
        )
        val card = cards(newViewModel(listOf(completed))).single()

        assertEquals("Demo Electronics", card.displayName)
        assertEquals("EUR", card.currencyCode)
        assertEquals(InstallmentPlanStatus.COMPLETED, card.finalStatus)
        assertEquals(day(2027, 4, 10), card.startEpochDay)
        assertEquals(day(2028, 3, 10), card.endEpochDay)
        assertEquals(600.0, (card.totalCounted as InstallmentAmountUi.Money).display.amount, 0.0001)
        assertEquals(12, card.paidCount)
        val financing = card.financingCost
        assertTrue("a real zero stays a money value", financing is InstallmentAmountUi.Money)
        assertEquals(0.0, (financing as InstallmentAmountUi.Money).display.amount, 0.0)
        assertEquals("EUR", financing.display.currency)
    }

    @Test
    fun `a refunded plan total is a legitimate zero and not unavailable`() = runTest(testDispatcher) {
        val refunded = item(
            1, InstallmentPlanStatus.REFUNDED,
            total = InstallmentAmount.Available(0L),
            financing = InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE),
            counts = counts(reversed = 3, cancelled = 9)
        )
        val card = cards(newViewModel(listOf(refunded))).single()
        val total = card.totalCounted as InstallmentAmountUi.Money
        assertEquals(0.0, total.display.amount, 0.0)
        assertEquals(3, card.reversedCount)
    }

    @Test
    fun `unavailable total dates and financing cost are never zero`() = runTest(testDispatcher) {
        val drifted = item(
            1, InstallmentPlanStatus.PAID_OFF, currency = InstallmentCurrency.USD,
            start = null, end = null,
            total = InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT),
            financing = InstallmentAmount.Unavailable(InstallmentUnavailableReason.NO_PURCHASE)
        )
        val card = cards(newViewModel(listOf(drifted))).single()
        assertTrue(card.totalCounted is InstallmentAmountUi.Unavailable)
        assertTrue(card.financingCost is InstallmentAmountUi.Unavailable)
        assertNull(card.startEpochDay)
        assertNull(card.endEpochDay)
        assertEquals("USD", card.currencyCode)
    }

    @Test
    fun `paid before tracking and skipped counts are carried`() = runTest(testDispatcher) {
        val plan = item(1, InstallmentPlanStatus.COMPLETED, counts = counts(paid = 8, skipped = 1, paidBeforeTracking = 3))
        val card = cards(newViewModel(listOf(plan))).single()
        assertEquals(8, card.paidCount)
        assertEquals(1, card.skippedCount)
        assertEquals(3, card.paidBeforeTrackingCount)
    }

    @Test
    fun `the read model order is kept`() = runTest(testDispatcher) {
        val items = listOf(
            item(3, InstallmentPlanStatus.COMPLETED),
            item(1, InstallmentPlanStatus.CANCELLED),
            item(2, InstallmentPlanStatus.PAID_OFF)
        )
        assertEquals(listOf(3L, 1L, 2L), cards(newViewModel(items)).map { it.planId })
    }

    @Test
    fun `empty when there are no finished plans`() = runTest(testDispatcher) {
        val vm = newViewModel(emptyList())
        val loadable = vm.state.value.loadable
        assertTrue(loadable is LoadableUiState.Empty)
        assertEquals(
            UiText.StringResource(R.string.installments_history_empty_body),
            (loadable as LoadableUiState.Empty).reason
        )
    }

    @Test
    fun `a filter shows only plans with that final status`() = runTest(testDispatcher) {
        val vm = newViewModel(
            listOf(
                item(1, InstallmentPlanStatus.COMPLETED),
                item(2, InstallmentPlanStatus.CANCELLED),
                item(3, InstallmentPlanStatus.CANCELLED),
                item(4, InstallmentPlanStatus.REFUNDED),
                item(5, InstallmentPlanStatus.PAID_OFF)
            )
        )
        assertEquals(5, cards(vm).size)

        vm.selectFilter(InstallmentHistoryFilter.CANCELLED)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallmentHistoryFilter.CANCELLED, vm.state.value.filter)
        assertEquals(listOf(2L, 3L), cards(vm).map { it.planId })

        vm.selectFilter(InstallmentHistoryFilter.PAID_OFF)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(5L), cards(vm).map { it.planId })

        vm.selectFilter(InstallmentHistoryFilter.ALL)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(5, cards(vm).size)
    }

    @Test
    fun `a filter that matches nothing is data with no cards and not the empty state`() = runTest(testDispatcher) {
        val vm = newViewModel(listOf(item(1, InstallmentPlanStatus.COMPLETED)))
        vm.selectFilter(InstallmentHistoryFilter.REFUNDED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(cards(vm).isEmpty())
        assertEquals(InstallmentHistoryFilter.REFUNDED, vm.state.value.filter)
    }

    @Test
    fun `load failure maps to a bounded code and fixed string and never the exception message`() = runTest(testDispatcher) {
        val secret = "SQLITE_ERROR near Demo Electronics 600.00 EUR"
        every { repository.observeHistory() } returns flow { throw IllegalStateException(secret) }
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        val loadable = state.loadable
        assertTrue(loadable is LoadableUiState.Error)
        val message = (loadable as LoadableUiState.Error).message
        assertEquals(UiText.StringResource(R.string.installments_error_load_history), message)
        assertFalse(message.toString().contains(secret))
        assertEquals(InstallmentHistoryErrorCode.LOAD_FAILED, state.errorCode)
        assertFalse(state.refreshFailed)
    }

    @Test
    fun `refresh failure keeps the last loaded content`() = runTest(testDispatcher) {
        every { repository.observeHistory() } returns flow {
            emit(listOf(item(1, InstallmentPlanStatus.COMPLETED)))
            throw IllegalStateException("secret refresh failure")
        }
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, cards(vm).size)
        assertTrue(vm.state.value.refreshFailed)
        assertEquals(InstallmentHistoryErrorCode.REFRESH_FAILED, vm.state.value.errorCode)
    }

    @Test
    fun `retry recovers after a load failure`() = runTest(testDispatcher) {
        var calls = 0
        every { repository.observeHistory() } answers {
            calls++
            if (calls == 1) {
                flow { throw IllegalStateException("first read fails") }
            } else {
                flowOf(listOf(item(1, InstallmentPlanStatus.COMPLETED)))
            }
        }
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.loadable is LoadableUiState.Error)

        vm.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, cards(vm).size)
        assertNull(vm.state.value.errorCode)
    }

    @Test
    fun `the filter still works after a failed refresh and keeps the refresh failed state`() = runTest(testDispatcher) {
        every { repository.observeHistory() } returns flow {
            emit(
                listOf(
                    item(1, InstallmentPlanStatus.COMPLETED),
                    item(2, InstallmentPlanStatus.CANCELLED),
                    item(3, InstallmentPlanStatus.CANCELLED)
                )
            )
            throw IllegalStateException("secret refresh failure")
        }
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, cards(vm).size)
        assertTrue(vm.state.value.refreshFailed)

        vm.selectFilter(InstallmentHistoryFilter.CANCELLED)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(InstallmentHistoryFilter.CANCELLED, vm.state.value.filter)
        assertEquals(listOf(2L, 3L), cards(vm).map { it.planId })
        assertTrue(vm.state.value.refreshFailed)
        assertEquals(InstallmentHistoryErrorCode.REFRESH_FAILED, vm.state.value.errorCode)

        vm.selectFilter(InstallmentHistoryFilter.ALL)
        assertEquals(3, cards(vm).size)
    }

    @Test
    fun `stop cancels the collection and start restarts it`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<List<InstallmentHistoryItem>>()
        every { repository.observeHistory() } returns source
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        source.emit(listOf(item(1, InstallmentPlanStatus.COMPLETED)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(1L), cards(vm).map { it.planId })

        vm.stop()
        source.emit(listOf(item(1, InstallmentPlanStatus.COMPLETED), item(2, InstallmentPlanStatus.CANCELLED)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(1L), cards(vm).map { it.planId })

        vm.start()
        testDispatcher.scheduler.advanceUntilIdle()
        source.emit(listOf(item(2, InstallmentPlanStatus.CANCELLED)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(2L), cards(vm).map { it.planId })
    }

    @Test
    fun `start while a collection is running does not collect twice`() = runTest(testDispatcher) {
        val source = MutableSharedFlow<List<InstallmentHistoryItem>>()
        every { repository.observeHistory() } returns source
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.start()
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { repository.observeHistory() }
    }

    @Test
    fun `an upstream CancellationException is rethrown and not turned into a load failure`() = runTest(testDispatcher) {
        every { repository.observeHistory() } returns flow { throw CancellationException("upstream cancelled") }
        val vm = InstallmentHistoryViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoadableUiState.Loading, vm.state.value.loadable)
        assertNull(vm.state.value.errorCode)
        assertFalse(vm.state.value.refreshFailed)
    }

    @Test
    fun `a failure is logged with the bounded code only`() = runTest(testDispatcher) {
        val secret = "SQLITE_ERROR near Demo Electronics 600.00 EUR"
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
            every { repository.observeHistory() } returns flow { throw IllegalStateException(secret) }
            InstallmentHistoryViewModel(repository)
            testDispatcher.scheduler.advanceUntilIdle()
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("code=LOAD_FAILED"))
        assertFalse(messages.single().contains(secret))
        assertFalse(messages.single().contains("Demo Electronics"))
        assertFalse(throwableLogged)
    }
}
