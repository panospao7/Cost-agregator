package com.yourname.expensetracker.ui.screens.recurring

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIX-104 F: confirming a detected pattern whose amount the coordinator rejects must not crash,
 * must emit only the generic bounded message, and must not refresh the list. Valid confirms
 * still insert and refresh; cancellation is never converted into a message.
 *
 * The refresh trigger is private, so a refresh is observed as one extra recompute of
 * [allPatterns] (one extra `getExpensesSince` read).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringExpensesViewModelInvalidAmountTest : ViewModelTestUtils() {

    private val plannedRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val currencyRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val engine = mockk<RecurringExpenseEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val ruleDao = mockk<ManualRecurringExpenseDao>(relaxed = true)
    private val patternReads = AtomicInteger(0)

    private val genericMessage = UiText.StringResource(R.string.recurring_error_generic)

    private fun pattern(amount: Double, currency: String = "EUR") = RecurringPattern(
        merchantName = "Netflix",
        averageAmount = amount,
        currency = currency,
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
        every { plannedRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { timeProvider.now() } returns 1_700_000_000_000L
        every { ruleDao.getAllActiveFlow() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesSince(any()) } coAnswers {
            patternReads.incrementAndGet()
            emptyList()
        }
        coEvery { engine.getPatterns(any<List<com.yourname.expensetracker.data.database.entity.Expense>>()) } returns emptyList()
    }

    /** Real repository over the REAL coordinator; only persistence peripherals are relaxed mocks. */
    private fun realCoordinatorViewModel(): RecurringExpensesViewModel {
        val coordinator = RecurringRuleLifecycleCoordinator(
            database = mockk(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = mockk(relaxed = true),
            reminderDeliveryDao = mockk(relaxed = true),
            plannedExpenseDao = mockk(relaxed = true),
            lifecycleEventDao = mockk<RecurringLifecycleEventDao>(relaxed = true),
            lifecycleCoordinator = dagger.Lazy { mockk(relaxed = true) },
            expander = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            materializer = mockk(relaxed = true),
            expenseDao = mockk(relaxed = true),
            eventWriter = mockk(relaxed = true),
            planProjectionService = dagger.Lazy { mockk(relaxed = true) }
        )
        val repository = RecurringExpenseRepository(
            writeBarrier = mockk(relaxed = true),
            dao = ruleDao,
            lifecycleEventDao = mockk(relaxed = true),
            timeProvider = timeProvider,
            ruleLifecycleCoordinator = dagger.Lazy { coordinator }
        )
        return viewModelWith(repository)
    }

    private fun viewModelWith(repository: RecurringExpenseRepository) = RecurringExpensesViewModel(
        repository = mockk<FinancialWeatherRepository>(relaxed = true),
        recurringExpenseRepository = repository,
        plannedExpenseRepository = plannedRepository,
        recurringExpenseEngine = engine,
        expenseRepository = expenseRepository,
        currencySettingsRepository = currencyRepository,
        timeProvider = timeProvider
    )

    @Test
    fun `FIX-104 F zero amount pattern through real coordinator emits generic message and does not refresh`() =
        runTest(testDispatcher) {
            val viewModel = realCoordinatorViewModel()
            backgroundScope.launch { viewModel.allPatterns.collect { } }
            advanceUntilIdle()
            val readsBefore = patternReads.get()
            assertTrue("list collector must have loaded once", readsBefore >= 1)

            viewModel.writeBlockedMessages.test {
                viewModel.confirmPattern(pattern(amount = 0.0))
                advanceUntilIdle()
                assertEquals(genericMessage, awaitItem())
                expectNoEvents()
            }

            assertEquals("refreshTrigger must not be bumped", readsBefore, patternReads.get())
            coVerify(exactly = 0) { ruleDao.insert(any()) }
        }

    @Test
    fun `FIX-104 F invalid currency pattern through real coordinator emits generic message and does not refresh`() =
        runTest(testDispatcher) {
            val viewModel = realCoordinatorViewModel()
            backgroundScope.launch { viewModel.allPatterns.collect { } }
            advanceUntilIdle()
            val readsBefore = patternReads.get()

            viewModel.writeBlockedMessages.test {
                viewModel.confirmPattern(pattern(amount = 12.0, currency = "EU"))
                advanceUntilIdle()
                assertEquals(genericMessage, awaitItem())
                expectNoEvents()
            }

            assertEquals(readsBefore, patternReads.get())
            coVerify(exactly = 0) { ruleDao.insert(any()) }
        }

    @Test
    fun `FIX-104 F valid pattern still inserts and refreshes`() = runTest(testDispatcher) {
        val recurringRepository = mockk<RecurringExpenseRepository>(relaxed = true)
        every { recurringRepository.getAllFlow() } returns flowOf(emptyList())
        coEvery { recurringRepository.insert(any()) } returns 5L
        val viewModel = viewModelWith(recurringRepository)
        backgroundScope.launch { viewModel.allPatterns.collect { } }
        advanceUntilIdle()
        val readsBefore = patternReads.get()
        assertTrue(readsBefore >= 1)

        viewModel.writeBlockedMessages.test {
            viewModel.confirmPattern(pattern(amount = 12.0))
            advanceUntilIdle()
            expectNoEvents()
        }

        coVerify(exactly = 1) {
            recurringRepository.insert(
                match<ManualRecurringExpense> {
                    it.merchant == "Netflix" && it.amount == 12.0 && it.currency == "EUR"
                }
            )
        }
        assertEquals("a successful confirm must refresh once", readsBefore + 1, patternReads.get())
    }

    @Test
    fun `FIX-104 F cancellation during confirm is not converted into a message or refresh`() =
        runTest(testDispatcher) {
            val recurringRepository = mockk<RecurringExpenseRepository>(relaxed = true)
            every { recurringRepository.getAllFlow() } returns flowOf(emptyList())
            coEvery { recurringRepository.insert(any()) } throws CancellationException("cancelled")
            val viewModel = viewModelWith(recurringRepository)
            backgroundScope.launch { viewModel.allPatterns.collect { } }
            advanceUntilIdle()
            val readsBefore = patternReads.get()

            viewModel.writeBlockedMessages.test {
                viewModel.confirmPattern(pattern(amount = 12.0))
                advanceUntilIdle()
                expectNoEvents()
            }

            assertEquals(readsBefore, patternReads.get())
        }
}
