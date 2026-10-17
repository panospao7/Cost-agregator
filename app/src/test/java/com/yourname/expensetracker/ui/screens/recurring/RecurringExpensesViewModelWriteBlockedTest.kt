package com.yourname.expensetracker.ui.screens.recurring

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** R18 review: recurring rule writes rejected by the maintenance write barrier emit the bounded message. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringExpensesViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val recurringRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val plannedRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val currencyRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private lateinit var viewModel: RecurringExpensesViewModel

    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    private val pattern = RecurringPattern(
        merchantName = "Netflix",
        averageAmount = 12.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 1,
        amountVariancePercent = 0.0,
        nextExpectedDate = 1_710_000_000_000L,
        confidence = 0.9f,
        previousDates = emptyList()
    )

    @Before
    override fun setup() {
        super.setup()
        every { currencyRepository.homeCurrency() } returns flowOf("EUR")
        every { recurringRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        viewModel = RecurringExpensesViewModel(
            repository = mockk<FinancialWeatherRepository>(relaxed = true),
            recurringExpenseRepository = recurringRepository,
            plannedExpenseRepository = plannedRepository,
            recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true),
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            currencySettingsRepository = currencyRepository,
            timeProvider = mockk<TimeProvider>(relaxed = true)
        )
    }

    @Test
    fun `blocked confirmPattern insert emits maintenance message without crashing`() = runTest(testDispatcher) {
        coEvery { recurringRepository.insert(any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.confirmPattern(pattern)
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }

    @Test
    fun `blocked deleteManualRule emits maintenance message without crashing`() = runTest(testDispatcher) {
        coEvery { recurringRepository.deleteById(any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.deleteManualRule(pattern.copy(id = 4L))
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }
}
