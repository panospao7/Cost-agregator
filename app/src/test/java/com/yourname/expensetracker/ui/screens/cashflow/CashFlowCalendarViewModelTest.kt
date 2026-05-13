package com.yourname.expensetracker.ui.screens.cashflow

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.cashflow.CashFlowRiskLevel
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class CashFlowCalendarViewModelTest : ViewModelTestUtils() {

    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: CashFlowCalendarViewModel

    private val fixedNow = Date(1_710_000_000_000L)

    @Before
    override fun setup() {
        super.setup()

        every { timeProvider.now() } returns fixedNow.time
        coEvery { cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) } returns createMockCashFlows()
        coEvery { cashFlowCalculator.getUpcomingBills(30) } returns emptyList()

        viewModel = CashFlowCalendarViewModel(cashFlowCalculator, timeProvider, currencySettingsRepository = mockCurrencyRepo())
    }

    @Test
    fun `initial state loads month cashflow and upcoming bills`() = runTest(UnconfinedTestDispatcher()) {
        val monthRange = TimePeriodUtils.getMonthRange(fixedNow.time)

        viewModel.state.test {
            val initial = awaitItem()
            assertThat(initial.viewMode).isEqualTo(CalendarViewMode.MONTH)
            assertThat(initial.startingBalance).isEqualTo(0.0)
            assertThat(initial.currentMonth.time).isEqualTo(fixedNow.time)

            advanceUntilIdle()

            val loaded = awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }
            assertThat(loaded.isLoading).isFalse()
            assertThat(loaded.dailyCashFlows).hasSize(3)
            assertThat(loaded.currentMonth.time).isEqualTo(monthRange.first)
            assertThat(loaded.upcomingBillsCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            cashFlowCalculator.calculateDailyCashFlow(
                startDate = Date(monthRange.first),
                endDate = Date(monthRange.second),
                startingBalance = 0.0
            )
        }
        coVerify(exactly = 1) { cashFlowCalculator.getUpcomingBills(30) }
    }

    @Test
    fun `loadCashFlow emits loading then loaded state`() = runTest(UnconfinedTestDispatcher()) {
        val startDate = Date(TimePeriodUtils.getStartOfMonth(fixedNow.time))
        val endDate = Date(TimePeriodUtils.getEndOfMonth(fixedNow.time))

        coEvery { cashFlowCalculator.calculateDailyCashFlow(startDate, endDate, 0.0) } returns createMockCashFlows()

        viewModel.state.test {
            awaitItem() // initial
            advanceUntilIdle() // init load - executes init launch tasks
            awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }

            viewModel.loadCashFlow(startDate, endDate)
            advanceUntilIdle() // execute the launch so isLoading=true

            val loading = awaitState { it.isLoading }
            assertThat(loading.isLoading).isTrue()

            advanceUntilIdle()

            val loaded = awaitState { !it.isLoading && it.currentMonth == startDate }
            assertThat(loaded.isLoading).isFalse()
            assertThat(loaded.dailyCashFlows).hasSize(3)
            assertThat(loaded.currentMonth).isEqualTo(startDate)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setStartingBalance reloads using provided balance`() = runTest(UnconfinedTestDispatcher()) {
        viewModel.state.test {
            awaitItem() // initial
            advanceUntilIdle() // init load
            awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }

        viewModel.setStartingBalance(1_000.0)
        advanceUntilIdle() // execute the launch so startingBalance is set and loading begins

        val balanceUpdated = awaitState { it.startingBalance == 1_000.0 }
            assertThat(balanceUpdated.startingBalance).isEqualTo(1_000.0)

            val loading = awaitState { it.isLoading }
            assertThat(loading.isLoading).isTrue()

            advanceUntilIdle()

            val loaded = awaitState { !it.isLoading }
            assertThat(loaded.isLoading).isFalse()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(atLeast = 1) {
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), 1_000.0)
        }
    }

    @Test
    fun `selectDate and changeViewMode update state`() = runTest(UnconfinedTestDispatcher()) {
        val selectedDate = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 15) }.time

        viewModel.state.test {
            awaitItem()
            advanceUntilIdle()
            awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }

        viewModel.selectDate(selectedDate)
        advanceUntilIdle() // execute any pending work

        val afterDateSelect = awaitState { it.selectedDate == selectedDate }
            assertThat(afterDateSelect.selectedDate).isEqualTo(selectedDate)

            viewModel.changeViewMode(CalendarViewMode.WEEK)
            advanceUntilIdle() // execute any pending work
            val afterModeChange = awaitState { it.viewMode == CalendarViewMode.WEEK }
            assertThat(afterModeChange.viewMode).isEqualTo(CalendarViewMode.WEEK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `navigate month actions trigger calculator calls`() = runTest(testDispatcher) {
        advanceUntilIdle() // init work

        viewModel.navigateToPreviousMonth()
        viewModel.navigateToNextMonth()
        advanceUntilIdle()

        coVerify(atLeast = 3) {
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any())
        }
    }

    @Test
    fun `upcoming bills count reflects repository result`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { cashFlowCalculator.getUpcomingBills(30) } returns listOf(
            recurringPattern("rent", fixedNow.time + TimePeriodUtils.DAY_IN_MILLIS),
            recurringPattern("utilities", fixedNow.time + 2 * TimePeriodUtils.DAY_IN_MILLIS)
        )

        viewModel = CashFlowCalendarViewModel(cashFlowCalculator, timeProvider, currencySettingsRepository = mockCurrencyRepo())
        advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial or loaded
            advanceUntilIdle()
            val loaded = awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }
            assertThat(loaded.upcomingBillsCount).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `calculator failure surfaces as coroutine exception after loading state`() = runTest(UnconfinedTestDispatcher()) {
        val startDate = Date(TimePeriodUtils.getStartOfMonth(fixedNow.time))
        val endDate = Date(TimePeriodUtils.getEndOfMonth(fixedNow.time))

        // Init load succeeds
        coEvery { cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) } returns createMockCashFlows()

        viewModel.state.test {
            awaitItem() // initial
            advanceUntilIdle()
            awaitState { !it.isLoading && it.dailyCashFlows.isNotEmpty() }

            // Override mock to fail for the manual call
            coEvery { cashFlowCalculator.calculateDailyCashFlow(startDate, endDate, any()) } throws IllegalStateException("boom")

            viewModel.loadCashFlow(startDate, endDate)

            val loading = awaitState { it.isLoading }
            assertThat(loading.isLoading).isTrue()

            assertThrows(IllegalStateException::class.java) {
                advanceUntilIdle()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun mockCurrencyRepo(): CurrencySettingsRepository {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        return repo
    }

    private suspend fun ReceiveTurbine<CashFlowCalendarState>.awaitState(
        predicate: (CashFlowCalendarState) -> Boolean
    ): CashFlowCalendarState {
        var state = awaitItem()
        var attempts = 0
        while (!predicate(state) && attempts < 20) {
            state = awaitItem()
            attempts++
        }
        if (!predicate(state)) {
            throw AssertionError("Expected matching state was not emitted within 21 items. Last state=$state")
        }
        return state
    }

    private fun createMockCashFlows(): List<DailyCashFlow> {
        val base = Calendar.getInstance().apply {
            time = fixedNow
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return listOf(
            createDailyCashFlow(base.time, 100.0, 75.0),
            createDailyCashFlow((base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.time, 75.0, 125.0),
            createDailyCashFlow((base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }.time, 125.0, 110.0)
        )
    }

    private fun recurringPattern(name: String, nextExpectedDate: Long): RecurringPattern {
        return RecurringPattern(
            merchantName = name,
            averageAmount = 42.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 2,
            amountVariancePercent = 0.05,
            nextExpectedDate = nextExpectedDate,
            confidence = 0.8f,
            previousDates = emptyList()
        )
    }

    private fun createDailyCashFlow(
        date: Date,
        startingBalance: Double,
        endingBalance: Double
    ): DailyCashFlow {
        return DailyCashFlow(
            date = date,
            startingBalance = startingBalance,
            income = emptyList(),
            expenses = emptyList(),
            predictedRecurring = emptyList(),
            endingBalance = endingBalance,
            riskLevel = if (endingBalance < startingBalance) CashFlowRiskLevel.MEDIUM else CashFlowRiskLevel.LOW
        )
    }
}