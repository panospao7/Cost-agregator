package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@Suppress("DEPRECATION_ERROR") // Tests for legacy markBillPaid until migration complete
class BillReminderManagerTest {

    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()

    private lateinit var manager: BillReminderManager

    @Before
    fun setUp() {
        manager = BillReminderManager(recurringExpenseRepository, timeProvider)
        every { timeProvider.now() } returns 0L
    }

    @Test
    fun `markBillPaid advances annually by one year`() = runTest {
        val expense = recurringExpense(id = 1L, frequency = RecurrenceFrequency.ANNUALLY, nextDate = date(2026, 1, 15))
        val updatedSlot = slot<ManualRecurringExpense>()
        coEvery { recurringExpenseRepository.getById(1L) } returns expense
        coEvery { recurringExpenseRepository.update(capture(updatedSlot)) } returns Unit

        manager.markBillPaid(1L)

        assertEquals(
            RecurrenceCalculator.calculateNextDate(expense.nextDate, expense.frequency),
            updatedSlot.captured.nextDate
        )
        assertEquals(RecurrenceFrequency.ANNUALLY, updatedSlot.captured.frequency)
    }

    @Test
    fun `markBillPaid advances semi annually by six months`() = runTest {
        val expense = recurringExpense(id = 2L, frequency = RecurrenceFrequency.SEMI_ANNUALLY, nextDate = date(2026, 2, 10))
        val updatedSlot = slot<ManualRecurringExpense>()
        coEvery { recurringExpenseRepository.getById(2L) } returns expense
        coEvery { recurringExpenseRepository.update(capture(updatedSlot)) } returns Unit

        manager.markBillPaid(2L)

        assertEquals(
            RecurrenceCalculator.calculateNextDate(expense.nextDate, expense.frequency),
            updatedSlot.captured.nextDate
        )
        assertEquals(RecurrenceFrequency.SEMI_ANNUALLY, updatedSlot.captured.frequency)
    }

    @Test
    fun `markBillPaid advances irregular by one month fallback`() = runTest {
        val expense = recurringExpense(id = 3L, frequency = RecurrenceFrequency.IRREGULAR, nextDate = date(2026, 3, 5))
        val updatedSlot = slot<ManualRecurringExpense>()
        coEvery { recurringExpenseRepository.getById(3L) } returns expense
        coEvery { recurringExpenseRepository.update(capture(updatedSlot)) } returns Unit

        manager.markBillPaid(3L)

        assertEquals(
            RecurrenceCalculator.calculateNextDate(expense.nextDate, expense.frequency),
            updatedSlot.captured.nextDate
        )
        assertEquals(RecurrenceFrequency.IRREGULAR, updatedSlot.captured.frequency)
    }

    @Test
    fun `getMonthlyBillsTotal includes annual semi annual and irregular semantics`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 10L, amount = 1200.0, frequency = RecurrenceFrequency.ANNUALLY),
            recurringExpense(id = 11L, amount = 600.0, frequency = RecurrenceFrequency.SEMI_ANNUALLY),
            recurringExpense(id = 12L, amount = 45.0, frequency = RecurrenceFrequency.IRREGULAR)
        )

        val total = manager.getMonthlyBillsTotal()

        val expected = listOf(
            1200.0 to RecurrenceFrequency.ANNUALLY,
            600.0 to RecurrenceFrequency.SEMI_ANNUALLY,
            45.0 to RecurrenceFrequency.IRREGULAR
        ).sumOf { (amount, frequency) ->
            RecurrenceCalculator.toMonthlyAmount(amount, frequency)
        }

        assertEquals(expected, total, 0.0001)
        coVerify(exactly = 1) { recurringExpenseRepository.getAll() }
    }

    @Test
    fun `getUpcomingReminders maps due today to critical and tomorrow to urgent`() = runTest {
        val now = date(2026, 1, 10)
        every { timeProvider.now() } returns now
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 1L, frequency = RecurrenceFrequency.MONTHLY, nextDate = now),
            recurringExpense(id = 2L, frequency = RecurrenceFrequency.MONTHLY, nextDate = date(2026, 1, 11)),
            recurringExpense(id = 3L, frequency = RecurrenceFrequency.MONTHLY, nextDate = date(2026, 1, 14)),
            recurringExpense(id = 4L, frequency = RecurrenceFrequency.MONTHLY, nextDate = date(2026, 1, 20))
        )

        val reminders = manager.getUpcomingReminders(daysAhead = 14).associateBy { it.recurringExpenseId }

        assertEquals(ReminderUrgency.CRITICAL, reminders.getValue(1L).urgency)
        assertEquals(ReminderUrgency.URGENT, reminders.getValue(2L).urgency)
        assertEquals(ReminderUrgency.WARNING, reminders.getValue(3L).urgency)
        assertEquals(ReminderUrgency.INFO, reminders.getValue(4L).urgency)
    }

    private fun recurringExpense(
        id: Long,
        amount: Double = 50.0,
        frequency: RecurrenceFrequency,
        nextDate: Long = date(2026, 1, 1)
    ): ManualRecurringExpense = ManualRecurringExpense(
        id = id,
        merchant = "Merchant $id",
        amount = amount,
        currency = "EUR",
        frequency = frequency,
        nextDate = nextDate,
        isActive = true
    )

    private fun date(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
