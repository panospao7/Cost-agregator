package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

@Suppress("DEPRECATION_ERROR") // Tests for legacy markBillPaid until migration complete
class BillReminderManagerTest {

    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()
    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>()
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()

    private lateinit var manager: BillReminderManager

    @Before
    fun setUp() {
        manager = BillReminderManager(
            recurringExpenseRepository,
            timeProvider,
            multiCurrencyRepository,
            currencySettingsRepository
        )
        every { timeProvider.now() } returns 0L
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), any())
        } returns MoneyAggregateResult.Available(MoneyAggregate.empty(CurrencyCode("EUR")))
    }

    @Test
    fun `markBillPaid remains prohibited for annual rules`() = runTest {
        val failure = runCatching { manager.markBillPaid(1L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `markBillPaid remains prohibited for semi annual rules`() = runTest {
        val failure = runCatching { manager.markBillPaid(2L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `markBillPaid remains prohibited for irregular rules`() = runTest {
        val failure = runCatching { manager.markBillPaid(3L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coVerify(exactly = 0) { recurringExpenseRepository.update(any()) }
    }

    @Test
    fun `getMonthlyBillsTotal includes annual semi annual and irregular semantics`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 10L, amount = 1200.0, frequency = RecurrenceFrequency.ANNUALLY),
            recurringExpense(id = 11L, amount = 600.0, frequency = RecurrenceFrequency.SEMI_ANNUALLY),
            recurringExpense(id = 12L, amount = 45.0, frequency = RecurrenceFrequency.IRREGULAR)
        )

        val expected = listOf(
            1200.0 to RecurrenceFrequency.ANNUALLY,
            600.0 to RecurrenceFrequency.SEMI_ANNUALLY,
            45.0 to RecurrenceFrequency.IRREGULAR
        ).sumOf { (amount, frequency) ->
            RecurrenceCalculator.toMonthlyAmount(amount, frequency)
        }

        val expectedResult = MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(expected, CurrencyCode("EUR"), transactionCount = 3)
        )
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), "EUR")
        } returns expectedResult

        val total = manager.getMonthlyBillsTotal()

        assertEquals(expectedResult, total)
        coVerify(exactly = 1) { recurringExpenseRepository.getAll() }
        coVerify(exactly = 1) {
            multiCurrencyRepository.aggregateDisplayAmounts(
                listOf(
                    RecurrenceCalculator.toMonthlyAmount(1200.0, RecurrenceFrequency.ANNUALLY) to "EUR",
                    RecurrenceCalculator.toMonthlyAmount(600.0, RecurrenceFrequency.SEMI_ANNUALLY) to "EUR",
                    RecurrenceCalculator.toMonthlyAmount(45.0, RecurrenceFrequency.IRREGULAR) to "EUR"
                ),
                listOf(1, 1, 1),
                "EUR"
            )
        }
    }

    @Test
    fun `getMonthlyBillsTotal converts source currencies before presenting the summary`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 20L, amount = 100.0, frequency = RecurrenceFrequency.MONTHLY, currency = "EUR"),
            recurringExpense(id = 21L, amount = 100.0, frequency = RecurrenceFrequency.MONTHLY, currency = "USD")
        )
        val expected = MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(190.0, CurrencyCode("EUR"), transactionCount = 2)
        )
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(
                listOf(100.0 to "EUR", 100.0 to "USD"),
                listOf(1, 1),
                "EUR"
            )
        } returns expected

        assertEquals(expected, manager.getMonthlyBillsTotal())
    }

    @Test
    fun `getMonthlyBillsTotal preserves partial conversion state`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 22L, amount = 100.0, frequency = RecurrenceFrequency.MONTHLY, currency = "EUR"),
            recurringExpense(id = 23L, amount = 100.0, frequency = RecurrenceFrequency.MONTHLY, currency = "USD")
        )
        val expected = MoneyAggregateResult.Available(
            MoneyAggregate.singleCurrency(100.0, CurrencyCode("EUR"), transactionCount = 1).copy(
                conversionQuality = ConversionQuality.PARTIAL,
                warningMessage = "MISSING_RATE"
            )
        )
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(
                listOf(100.0 to "EUR", 100.0 to "USD"),
                listOf(1, 1),
                "EUR"
            )
        } returns expected

        assertEquals(expected, manager.getMonthlyBillsTotal())
    }

    @Test
    fun `getMonthlyBillsTotal preserves total conversion unavailability`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(id = 24L, amount = 100.0, frequency = RecurrenceFrequency.MONTHLY, currency = "USD")
        )
        val expected = MoneyAggregateResult.Unavailable(
            reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
            requestedRateBasis = RateBasis.LATEST_AVAILABLE
        )
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(
                listOf(100.0 to "USD"),
                listOf(1),
                "EUR"
            )
        } returns expected

        assertEquals(expected, manager.getMonthlyBillsTotal())
    }

    @Test
    fun `getMonthlyBillsTotal propagates cancellation`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } throws
            CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.getMonthlyBillsTotal() }
        coVerify(exactly = 0) { recurringExpenseRepository.getAll() }
    }

    @Test
    fun `getMonthlyBillsTotal returns unavailable when home currency resolution fails`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("SETTINGS_READ_FAILED")

        val result = manager.getMonthlyBillsTotal()

        assertTrue(result is MoneyAggregateResult.Unavailable)
        assertEquals(
            MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE.name,
            (result as MoneyAggregateResult.Unavailable).reason
        )
        coVerify(exactly = 0) { recurringExpenseRepository.getAll() }
        coVerify(exactly = 0) {
            multiCurrencyRepository.aggregateDisplayAmounts(any(), any(), any())
        }
    }

    @Test
    fun `getMonthlyBillsTotal preserves known currency zero for no active rules`() = runTest {
        coEvery { recurringExpenseRepository.getAll() } returns listOf(
            recurringExpense(
                id = 30L,
                frequency = RecurrenceFrequency.MONTHLY,
                isActive = false
            )
        )
        val expected = MoneyAggregateResult.Available(MoneyAggregate.empty(CurrencyCode("EUR")))
        coEvery {
            multiCurrencyRepository.aggregateDisplayAmounts(emptyList(), emptyList(), "EUR")
        } returns expected

        assertEquals(expected, manager.getMonthlyBillsTotal())
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
        nextDate: Long = date(2026, 1, 1),
        currency: String = "EUR",
        isActive: Boolean = true
    ): ManualRecurringExpense = ManualRecurringExpense(
        id = id,
        merchant = "Merchant $id",
        amount = amount,
        currency = currency,
        frequency = frequency,
        nextDate = nextDate,
        isActive = isActive
    )

    private fun date(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
