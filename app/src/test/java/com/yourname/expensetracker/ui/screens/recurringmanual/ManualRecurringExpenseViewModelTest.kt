package com.yourname.expensetracker.ui.screens.recurringmanual

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualRecurringExpenseViewModelTest : ViewModelTestUtils() {

    private val recurringExpenseRepository = mockk<ManualRecurringExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var viewModel: ManualRecurringExpenseViewModel

    private val fixedNow = 1_700_000_000_000L

    @Before
    override fun setup() {
        super.setup()
        every { timeProvider.now() } returns fixedNow
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        viewModel = ManualRecurringExpenseViewModel(recurringExpenseRepository, timeProvider, currencySettingsRepository = currencyRepo)
    }

    @Test
    fun `initial state shows recurring expenses`() = runTest(testDispatcher) {
        val expenses = listOf(
            recurringExpense(
                id = 2L,
                merchant = "Netflix",
                amount = 15.99,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = fixedNow + 2 * DAY_MS,
                isActive = true
            ),
            recurringExpense(
                id = 1L,
                merchant = "Gym",
                amount = 30.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = fixedNow + DAY_MS,
                isActive = false
            )
        )
        coEvery { recurringExpenseRepository.getAll() } returns expenses

        viewModel = ManualRecurringExpenseViewModel(recurringExpenseRepository, timeProvider, currencySettingsRepository = mockk())
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(2, state.recurringExpenses.size)
            assertEquals(1L, state.recurringExpenses.first().id) // sorted by nextDate
            assertEquals(1, state.activeCount)
            assertTrue(state.totalMonthly > 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add recurring expense updates list`() = runTest(testDispatcher) {
        val added = recurringExpense(
            id = 10L,
            merchant = "Spotify",
            amount = 9.99,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + 3 * DAY_MS,
            isActive = true
        )

        coEvery { recurringExpenseRepository.getAll() } returnsMany listOf(emptyList(), listOf(added))
        coEvery { recurringExpenseRepository.insert(any()) } returns 10L

        viewModel = ManualRecurringExpenseViewModel(recurringExpenseRepository, timeProvider, currencySettingsRepository = mockk())
        advanceUntilIdle()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.recurringExpenses.isEmpty())

            viewModel.addRecurringExpense(
                merchant = "Spotify",
                amount = 9.99,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = fixedNow + 3 * DAY_MS,
                note = "Music"
            )
            advanceUntilIdle()

            var updated = awaitItem()
            while (updated.isLoading) {
                updated = awaitItem()
            }
            assertFalse(updated.isLoading)
            assertEquals(1, updated.recurringExpenses.size)
            assertEquals("Spotify", updated.recurringExpenses.first().merchant)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { recurringExpenseRepository.insert(any()) }
    }

    @Test
    fun `toggle active status`() = runTest(testDispatcher) {
        val activeExpense = recurringExpense(
            id = 5L,
            merchant = "Cloud Storage",
            amount = 4.99,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + DAY_MS,
            isActive = true
        )
        val inactiveExpense = activeExpense.copy(isActive = false)

        coEvery { recurringExpenseRepository.getAll() } returnsMany listOf(
            listOf(activeExpense),
            listOf(inactiveExpense)
        )

        viewModel = ManualRecurringExpenseViewModel(recurringExpenseRepository, timeProvider, currencySettingsRepository = mockk())
        advanceUntilIdle()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.recurringExpenses.first().isActive)

            viewModel.toggleStatus(id = 5L, currentStatus = true)
            advanceUntilIdle()

            var updated = awaitItem()
            while (updated.isLoading) {
                updated = awaitItem()
            }
            assertFalse(updated.isLoading)
            assertFalse(updated.recurringExpenses.first().isActive)
            assertEquals(0, updated.activeCount)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { recurringExpenseRepository.setActiveStatus(5L, false) }
    }

    @Test
    fun `delete recurring expense`() = runTest(testDispatcher) {
        val toDelete = recurringExpense(
            id = 7L,
            merchant = "Old Service",
            amount = 12.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + DAY_MS,
            isActive = true
        )
        val remaining = recurringExpense(
            id = 8L,
            merchant = "Kept Service",
            amount = 8.0,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + 2 * DAY_MS,
            isActive = true
        )

        coEvery { recurringExpenseRepository.getAll() } returnsMany listOf(
            listOf(toDelete, remaining),
            listOf(remaining)
        )

        viewModel = ManualRecurringExpenseViewModel(recurringExpenseRepository, timeProvider, currencySettingsRepository = mockk())
        advanceUntilIdle()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertEquals(2, initial.recurringExpenses.size)

            viewModel.deleteExpense(7L)
            advanceUntilIdle()

            var updated = awaitItem()
            while (updated.isLoading) {
                updated = awaitItem()
            }
            assertFalse(updated.isLoading)
            assertEquals(1, updated.recurringExpenses.size)
            assertEquals(8L, updated.recurringExpenses.first().id)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { recurringExpenseRepository.deleteById(7L) }
    }

    private fun recurringExpense(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: RecurrenceFrequency,
        nextDate: Long,
        isActive: Boolean
    ) = ManualRecurringExpense(
        id = id,
        merchant = merchant,
        amount = amount,
        frequency = frequency,
        nextDate = nextDate,
        note = null,
        isSubscription = false,
        isActive = isActive,
        createdAt = fixedNow
    )

    companion object {
        private const val DAY_MS = 86_400_000L
    }
}