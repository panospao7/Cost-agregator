package com.yourname.expensetracker.ui.screens.reminder

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.reminder.BillReminder
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.reminder.ReminderUrgency
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillRemindersViewModelTest : ViewModelTestUtils() {

    private val billReminderManager = mockk<BillReminderManager>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

    @Before
    override fun setup() {
        super.setup()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("USD")
        coEvery { billReminderManager.getUpcomingReminders(any()) } returns emptyList()
        coEvery { billReminderManager.getMonthlyBillsTotal() } returns available(0.0, "USD")
    }

    private fun createViewModel(): BillRemindersViewModel {
        return BillRemindersViewModel(billReminderManager, currencySettingsRepository)
    }

    @Test
    fun `initial state shows empty reminders and zero total`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.reminders.value.isEmpty())
        assertEquals(0.0, availableAmount(vm.monthlyTotal.value), 0.001)
    }

    @Test
    fun `loads reminders from manager and shows total`() = runTest(testDispatcher) {
        val testReminders = listOf(
            BillReminder(
                recurringExpenseId = 1L,
                merchant = "Netflix",
                amount = 15.99,
                currency = "USD",
                dueDate = 1_735_689_600_000L,
                daysUntilDue = 5,
                isOverdue = false,
                urgency = ReminderUrgency.WARNING
            ),
            BillReminder(
                recurringExpenseId = 2L,
                merchant = "Electric Bill",
                amount = 85.00,
                currency = "USD",
                dueDate = 1_735_689_600_000L,
                daysUntilDue = 1,
                isOverdue = false,
                urgency = ReminderUrgency.URGENT
            )
        )

        coEvery { billReminderManager.getUpcomingReminders(any()) } returns testReminders
        coEvery { billReminderManager.getMonthlyBillsTotal() } returns available(100.99, "USD")

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2, vm.reminders.value.size)
        assertEquals("Netflix", vm.reminders.value[0].merchant)
        assertEquals("Electric Bill", vm.reminders.value[1].merchant)
        assertEquals(100.99, availableAmount(vm.monthlyTotal.value), 0.001)
    }

    @Test
    fun `refresh reloads reminders and total`() = runTest(testDispatcher) {
        val testReminders = listOf(
            BillReminder(
                recurringExpenseId = 1L,
                merchant = "Netflix",
                amount = 15.99,
                currency = "USD",
                dueDate = 1_735_689_600_000L,
                daysUntilDue = 5,
                isOverdue = false,
                urgency = ReminderUrgency.WARNING
            )
        )

        coEvery { billReminderManager.getUpcomingReminders(any()) } returnsMany listOf(
            emptyList(),
            testReminders
        )
        coEvery { billReminderManager.getMonthlyBillsTotal() } returnsMany listOf(
            available(0.0, "USD"),
            available(15.99, "USD")
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.reminders.value.isEmpty())

        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, vm.reminders.value.size)
        assertEquals("Netflix", vm.reminders.value[0].merchant)
        assertEquals(15.99, availableAmount(vm.monthlyTotal.value), 0.001)
    }

    @Test
    fun `loads empty reminders when manager throws`() = runTest(testDispatcher) {
        coEvery { billReminderManager.getUpcomingReminders(any()) } throws RuntimeException("DB error")
        coEvery { billReminderManager.getMonthlyBillsTotal() } throws RuntimeException("DB error")

        val vm = createViewModel()
        advanceUntilIdle()

        // Should gracefully handle errors with empty state
        assertTrue(vm.reminders.value.isEmpty())
        assertTrue(vm.monthlyTotal.value is MoneyAggregateResult.Unavailable)
    }

    @Test
    fun `home currency changes recompute rather than relabel the previous total`() = runTest(testDispatcher) {
        val homeCurrencies = MutableSharedFlow<String>(replay = 1).apply { tryEmit("USD") }
        every { currencySettingsRepository.homeCurrency() } returns homeCurrencies
        coEvery { billReminderManager.getMonthlyBillsTotal() } returnsMany listOf(
            available(100.0, "USD"),
            available(90.0, "EUR")
        )

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals("USD", availableCurrency(vm.monthlyTotal.value))
        assertEquals(100.0, availableAmount(vm.monthlyTotal.value), 0.001)

        homeCurrencies.emit("EUR")
        advanceUntilIdle()

        assertEquals("EUR", availableCurrency(vm.monthlyTotal.value))
        assertEquals(90.0, availableAmount(vm.monthlyTotal.value), 0.001)
    }

    @Test
    fun `currency changes and refresh share one latest-only monthly calculation`() = runTest(testDispatcher) {
        for (refreshFirst in listOf(true, false)) {
            val homeCurrencies = MutableStateFlow("USD")
            val oldResult = CompletableDeferred<MoneyAggregateResult>()
            var calls = 0
            var oldCalculationCancelled = false
            every { currencySettingsRepository.homeCurrency() } returns homeCurrencies
            coEvery { billReminderManager.getMonthlyBillsTotal() } coAnswers {
                when (++calls) {
                    1 -> available(100.0, "USD")
                    2 -> try {
                        oldResult.await()
                    } catch (e: CancellationException) {
                        oldCalculationCancelled = true
                        throw e
                    }
                    3 -> available(90.0, "EUR")
                    else -> error("Unexpected monthly calculation")
                }
            }

            val vm = createViewModel()
            try {
                runCurrent()
                assertEquals(1, calls)
                if (refreshFirst) vm.refresh() else homeCurrencies.value = "EUR"
                runCurrent()
                assertEquals(2, calls)

                if (refreshFirst) homeCurrencies.value = "EUR" else vm.refresh()
                runCurrent()
                assertTrue("Superseded calculation must be cancelled", oldCalculationCancelled)
                assertEquals(3, calls)
                assertEquals("EUR", availableCurrency(vm.monthlyTotal.value))
                assertEquals(90.0, availableAmount(vm.monthlyTotal.value), 0.001)

                oldResult.complete(available(999.0, "USD"))
                runCurrent()
                assertEquals("EUR", availableCurrency(vm.monthlyTotal.value))
                assertEquals(90.0, availableAmount(vm.monthlyTotal.value), 0.001)
            } finally {
                oldResult.cancel()
                vm.viewModelScope.cancel()
                runCurrent()
            }
        }
    }

    @Test
    fun `manual refresh can recover after the currency observer fails`() = runTest(testDispatcher) {
        every { currencySettingsRepository.homeCurrency() } returns flow<String> {
            throw IllegalStateException("TEST_SETTINGS_UNAVAILABLE")
        }
        var calls = 0
        coEvery { billReminderManager.getMonthlyBillsTotal() } coAnswers {
            if (++calls == 1) throw IllegalStateException("TEST_RESOLUTION_UNAVAILABLE")
            available(90.0, "EUR")
        }
        val vm = createViewModel()
        try {
            runCurrent()
            assertEquals(1, calls)
            assertTrue(vm.monthlyTotal.value is MoneyAggregateResult.Unavailable)
            vm.refresh()
            runCurrent()
            assertEquals(2, calls)
            assertEquals("EUR", availableCurrency(vm.monthlyTotal.value))
            assertEquals(90.0, availableAmount(vm.monthlyTotal.value), 0.001)
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    @Test
    fun `reminder amount displays recognized active and historical source currencies`() {
        assertTrue(!SupportedCurrency.HRK.isActive)
        listOf("USD", "EUR", "HRK").forEach { sourceCurrency ->
            val reminder = reminder(sourceCurrency)
            val formatted = formatBillReminderAmount(reminder)

            assertEquals(CurrencyFormatter.formatMoney(reminder.amount, sourceCurrency), formatted)
            assertTrue(formatted != "—")
        }
    }

    @Test
    fun `reminder amount suppresses unknown currency instead of guessing a label`() {
        listOf("ZZZ", "", " ", "UNKNOWN").forEach { sourceCurrency ->
            assertEquals("—", formatBillReminderAmount(reminder(sourceCurrency)))
        }
    }

    private fun reminder(currency: String): BillReminder = BillReminder(
        recurringExpenseId = 1L,
        merchant = "Saved obligation",
        amount = 100.0,
        currency = currency,
        dueDate = 1_735_689_600_000L,
        daysUntilDue = 5,
        isOverdue = false,
        urgency = ReminderUrgency.WARNING
    )

    private fun available(amount: Double, currency: String): MoneyAggregateResult.Available =
        MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(amount, CurrencyCode(currency), transactionCount = 1)
        )

    private fun availableAmount(result: MoneyAggregateResult): Double =
        (result as MoneyAggregateResult.Available).aggregate.displayAmount

    private fun availableCurrency(result: MoneyAggregateResult): String =
        (result as MoneyAggregateResult.Available).aggregate.displayCurrency.code
}
