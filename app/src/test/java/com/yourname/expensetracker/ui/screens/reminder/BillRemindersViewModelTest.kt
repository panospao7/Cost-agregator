package com.yourname.expensetracker.ui.screens.reminder

import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.reminder.BillReminder
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.reminder.ReminderUrgency
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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
        coEvery { billReminderManager.getMonthlyBillsTotal() } returns 0.0
    }

    private fun createViewModel(): BillRemindersViewModel {
        return BillRemindersViewModel(billReminderManager, currencySettingsRepository)
    }

    @Test
    fun `initial state shows empty reminders and zero total`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.reminders.value.isEmpty())
        assertEquals(0.0, vm.monthlyTotal.value, 0.001)
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
        coEvery { billReminderManager.getMonthlyBillsTotal() } returns 100.99

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2, vm.reminders.value.size)
        assertEquals("Netflix", vm.reminders.value[0].merchant)
        assertEquals("Electric Bill", vm.reminders.value[1].merchant)
        assertEquals(100.99, vm.monthlyTotal.value, 0.001)
    }

    @Test
    fun `markBillPaid refreshes reminders`() = runTest(testDispatcher) {
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
            // First call (init) — empty
            emptyList(),
            // After markBillPaid (refresh) — has data
            testReminders
        )
        coEvery { billReminderManager.getMonthlyBillsTotal() } returns 15.99

        val vm = createViewModel()
        advanceUntilIdle()

        // Initially empty
        assertTrue(vm.reminders.value.isEmpty())

        // Mark a bill as paid — triggers refresh
        vm.markBillPaid(1L)
        advanceUntilIdle()

        // Now reminders should be loaded
        assertEquals(1, vm.reminders.value.size)
        assertEquals("Netflix", vm.reminders.value[0].merchant)
    }

    @Test
    fun `loads empty reminders when manager throws`() = runTest(testDispatcher) {
        coEvery { billReminderManager.getUpcomingReminders(any()) } throws RuntimeException("DB error")
        coEvery { billReminderManager.getMonthlyBillsTotal() } throws RuntimeException("DB error")

        val vm = createViewModel()
        advanceUntilIdle()

        // Should gracefully handle errors with empty state
        assertTrue(vm.reminders.value.isEmpty())
        assertEquals(0.0, vm.monthlyTotal.value, 0.001)
    }
}
