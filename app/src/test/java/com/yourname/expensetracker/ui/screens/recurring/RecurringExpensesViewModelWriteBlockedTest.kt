package com.yourname.expensetracker.ui.screens.recurring

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense as PlannedExpenseEntity
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority as EntityPriority
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
import io.mockk.coVerify
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

    @Test
    fun `N-08-1 plannedExpenses and allPatterns preserve source currency independent of homeCurrency`() = runTest(testDispatcher) {
        every { currencyRepository.homeCurrency() } returns flowOf("EUR")
        every { recurringRepository.getAllFlow() } returns flowOf(
            listOf(
                ManualRecurringExpense(
                    id = 10L,
                    merchant = "Cloud Hosting",
                    amount = 29.99,
                    currency = "USD",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = 1_710_000_000_000L
                ),
                ManualRecurringExpense(
                    id = 11L,
                    merchant = "Gym",
                    amount = 45.0,
                    currency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = 1_710_000_000_000L
                )
            )
        )
        every { plannedRepository.getAllPlannedExpenses() } returns flowOf(
            listOf(
                PlannedExpenseEntity(
                    id = 20L,
                    description = "US Flight",
                    amount = 650.0,
                    currency = "USD",
                    date = 1_710_000_000_000L,
                    priority = EntityPriority.MUST
                ),
                PlannedExpenseEntity(
                    id = 21L,
                    description = "Local Train",
                    amount = 35.0,
                    currency = "EUR",
                    date = 1_710_000_000_000L,
                    priority = EntityPriority.LIKELY
                )
            )
        )
        val vm = RecurringExpensesViewModel(
            repository = mockk<FinancialWeatherRepository>(relaxed = true),
            recurringExpenseRepository = recurringRepository,
            plannedExpenseRepository = plannedRepository,
            recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true),
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            currencySettingsRepository = currencyRepository,
            timeProvider = mockk<TimeProvider>(relaxed = true)
        )

        vm.plannedExpenses.test {
            var items = awaitItem()
            if (items.isEmpty()) items = awaitItem()
            val byDesc = items.associateBy { it.description }
            assertEquals("USD", byDesc.getValue("US Flight").currency)
            assertEquals("EUR", byDesc.getValue("Local Train").currency)
            cancelAndIgnoreRemainingEvents()
        }

        vm.allPatterns.test {
            var patterns = awaitItem()
            if (patterns.isEmpty()) patterns = awaitItem()
            val byMerchant = patterns.associateBy { it.merchantName }
            assertEquals("USD", byMerchant.getValue("Cloud Hosting").currency)
            assertEquals("EUR", byMerchant.getValue("Gym").currency)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── P-04-2: planned-row "delete" skips the occurrence instead of deleting the row ──

    private fun newViewModel() = RecurringExpensesViewModel(
        repository = mockk<FinancialWeatherRepository>(relaxed = true),
        recurringExpenseRepository = recurringRepository,
        plannedExpenseRepository = plannedRepository,
        recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true),
        expenseRepository = mockk<ExpenseRepository>(relaxed = true),
        currencySettingsRepository = currencyRepository,
        timeProvider = mockk<TimeProvider>(relaxed = true)
    )

    private fun plannedEntity(id: Long, status: String, key: String?) = PlannedExpenseEntity(
        id = id,
        description = "Synthetic Planned $id",
        amount = 15.0,
        currency = "EUR",
        date = 1_710_000_000_000L,
        priority = EntityPriority.MUST,
        sourceOccurrenceKey = key,
        status = status
    )

    private suspend fun loadSinglePlanned(
        vm: RecurringExpensesViewModel
    ): com.yourname.expensetracker.domain.model.PlannedExpense {
        var item: com.yourname.expensetracker.domain.model.PlannedExpense? = null
        vm.plannedExpenses.test {
            var items = awaitItem()
            if (items.isEmpty()) items = awaitItem()
            item = items.single()
            cancelAndIgnoreRemainingEvents()
        }
        return item!!
    }

    @Test
    fun `P-04-2 deletePlannedExpense routes through skipOrDeletePlanned with the id and occurrence key`() =
        runTest(testDispatcher) {
            every { plannedRepository.getAllPlannedExpenses() } returns
                flowOf(listOf(plannedEntity(30L, "PLANNED", "RECURRING_RULE|1|1710000000000|MONTHLY")))
            val vm = newViewModel()

            vm.deletePlannedExpense(loadSinglePlanned(vm))
            advanceUntilIdle()

            coVerify(exactly = 1) {
                plannedRepository.skipOrDeletePlanned(30L, "RECURRING_RULE|1|1710000000000|MONTHLY")
            }
            coVerify(exactly = 0) { plannedRepository.deletePlannedExpenseById(any()) }
            coVerify(exactly = 0) { plannedRepository.deletePlannedExpense(any()) }
        }

    @Test
    fun `P-04-2 deletePlannedExpense for a manual row without a key still goes through skipOrDeletePlanned`() =
        runTest(testDispatcher) {
            every { plannedRepository.getAllPlannedExpenses() } returns
                flowOf(listOf(plannedEntity(31L, "PLANNED", null)))
            val vm = newViewModel()

            vm.deletePlannedExpense(loadSinglePlanned(vm))
            advanceUntilIdle()

            coVerify(exactly = 1) { plannedRepository.skipOrDeletePlanned(31L, null) }
        }

    @Test
    fun `P-04-2 plannedExpenses hides CANCELLED rows and keeps PLANNED and FULFILLED rows`() =
        runTest(testDispatcher) {
            every { plannedRepository.getAllPlannedExpenses() } returns flowOf(
                listOf(
                    plannedEntity(40L, "PLANNED", "k40"),
                    plannedEntity(41L, "CANCELLED", "k41"),
                    plannedEntity(42L, "FULFILLED", "k42")
                )
            )
            val vm = newViewModel()

            vm.plannedExpenses.test {
                var items = awaitItem()
                if (items.isEmpty()) items = awaitItem()
                assertEquals(setOf(40L, 42L), items.map { it.id }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `P-04-2 blocked skipOrDeletePlanned emits the maintenance message`() = runTest(testDispatcher) {
        coEvery { plannedRepository.skipOrDeletePlanned(any(), any()) } throws blockedWriteException()
        every { plannedRepository.getAllPlannedExpenses() } returns
            flowOf(listOf(plannedEntity(32L, "PLANNED", "k32")))
        val vm = newViewModel()
        val item = loadSinglePlanned(vm)

        vm.writeBlockedMessages.test {
            vm.deletePlannedExpense(item)
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }

    @Test
    fun `P-04-2 refused skip of a PAID or MISSED occurrence emits the generic recurring error`() =
        runTest(testDispatcher) {
            coEvery { plannedRepository.skipOrDeletePlanned(any(), any()) } throws
                IllegalArgumentException("Invalid recurring occurrence transition")
            every { plannedRepository.getAllPlannedExpenses() } returns
                flowOf(listOf(plannedEntity(33L, "PLANNED", "k33")))
            val vm = newViewModel()
            val item = loadSinglePlanned(vm)

            vm.writeBlockedMessages.test {
                vm.deletePlannedExpense(item)
                advanceUntilIdle()
                assertEquals(UiText.from(R.string.recurring_error_generic), awaitItem())
            }
        }
}
