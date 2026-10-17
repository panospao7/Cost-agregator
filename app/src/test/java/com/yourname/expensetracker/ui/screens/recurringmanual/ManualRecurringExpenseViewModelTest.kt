package com.yourname.expensetracker.ui.screens.recurringmanual

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.recurring.usecase.MarkPaidFailureCodes
import com.yourname.expensetracker.domain.recurring.usecase.MarkPaidResult
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualRecurringExpenseViewModelTest : ViewModelTestUtils() {

    private val recurringExpenseRepository = mockk<ManualRecurringExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val markPaidUseCase = mockk<MarkRecurringBillPaidUseCase>()

    private lateinit var viewModel: ManualRecurringExpenseViewModel

    private val fixedNow = 1_700_000_000_000L

    @Before
    override fun setup() {
        super.setup()
        every { timeProvider.now() } returns fixedNow
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()
        viewModel = newViewModel()
    }

    private fun currencyRepo(resolution: HomeCurrencyResolution): CurrencySettingsRepository {
        val repo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { repo.homeCurrency() } returns flowOf("EUR")
        coEvery { repo.resolveHomeCurrency() } returns resolution
        return repo
    }

    private fun newViewModel(
        resolution: HomeCurrencyResolution = HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
    ) = ManualRecurringExpenseViewModel(
        recurringExpenseRepository,
        timeProvider,
        currencySettingsRepository = currencyRepo(resolution),
        markRecurringBillPaid = markPaidUseCase
    )

    @Test
    fun `non EUR home currency is saved on the new rule`() = runTest(testDispatcher) {
        val saved = slot<ManualRecurringExpense>()
        coEvery { recurringExpenseRepository.insert(capture(saved)) } returns 11L
        viewModel = newViewModel(HomeCurrencyResolution.Resolved(CurrencyCode("USD")))
        advanceUntilIdle()

        viewModel.addRecurringExpense("Rent", 500.0, RecurrenceFrequency.MONTHLY, fixedNow + DAY_MS, null)
        advanceUntilIdle()

        assertEquals("USD", saved.captured.currency)
        assertEquals(null, viewModel.uiState.value.errorText)
    }

    @Test
    fun `unresolved home currency refuses save with a string resource error`() = runTest(testDispatcher) {
        viewModel = newViewModel(HomeCurrencyResolution.Failed("datastore-secret-detail"))
        advanceUntilIdle()

        viewModel.addRecurringExpense("Rent", 500.0, RecurrenceFrequency.MONTHLY, fixedNow + DAY_MS, null)
        advanceUntilIdle()

        coVerify(exactly = 0) { recurringExpenseRepository.insert(any()) }
        val state = viewModel.uiState.value
        assertEquals(UiText.StringResource(R.string.recurring_error_currency_unavailable), state.actionMessage)
        assertEquals(null, state.errorText)
        assertEquals(null, state.error)
    }

    @Test
    fun `mark paid failure never surfaces the exception message`() = runTest(testDispatcher) {
        val rule = recurringExpense(5L, "Gym", 30.0, RecurrenceFrequency.MONTHLY, fixedNow, true)
        coEvery { markPaidUseCase.invoke(5L, fixedNow) } throws IllegalStateException("boom-secret-detail")
        coEvery { markPaidUseCase.skip(5L, fixedNow) } returns MarkPaidResult.Failed(MarkPaidFailureCodes.SKIP_FAILED)

        viewModel.markAsPaid(rule)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(UiText.StringResource(R.string.recurring_error_generic), state.actionMessage)
        assertEquals(null, state.errorText) // action failure must not become a full-screen error
        assertEquals(null, state.error)
        assertFalse(state.toString().contains("boom-secret-detail"))
        assertTrue(state.inFlightRuleIds.isEmpty())

        viewModel.clearError()
        viewModel.markAlreadyRecorded(rule)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(UiText.StringResource(R.string.recurring_error_generic), state.actionMessage)
        assertEquals(null, state.errorText)
        assertFalse(state.toString().contains(MarkPaidFailureCodes.SKIP_FAILED))
    }

    @Test
    fun `action failure keeps the list visible and only load failure uses the error state`() =
        runTest(testDispatcher) {
            val rule = recurringExpense(5L, "Gym", 30.0, RecurrenceFrequency.MONTHLY, fixedNow, true)
            coEvery { recurringExpenseRepository.getAll() } returns listOf(rule)
            coEvery { markPaidUseCase.invoke(5L, fixedNow) } returns MarkPaidResult.Failed(MarkPaidFailureCodes.CREATE_FAILED)
            viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.markAsPaid(rule)
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(UiText.StringResource(R.string.recurring_error_generic), state.actionMessage)
            assertEquals(null, state.errorText)
            assertEquals(listOf(rule), state.recurringExpenses)
            assertTrue(state.loadableState is com.yourname.expensetracker.ui.model.LoadableUiState.Data)

            viewModel.consumeActionMessage()
            assertEquals(null, viewModel.uiState.value.actionMessage)

            coEvery { recurringExpenseRepository.getAll() } throws IllegalStateException("db-secret-detail")
            viewModel.refresh()
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertEquals(UiText.StringResource(R.string.recurring_error_generic), state.errorText)
            assertTrue(state.loadableState is com.yourname.expensetracker.ui.model.LoadableUiState.Error)
        }

    @Test
    fun `advanced only result shows an info message and no error`() = runTest(testDispatcher) {
        val rule = recurringExpense(7L, "Water", 10.0, RecurrenceFrequency.MONTHLY, fixedNow, true)
        coEvery { markPaidUseCase.skip(7L, fixedNow) } returns MarkPaidResult.AdvancedOnly

        viewModel.markAlreadyRecorded(rule)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(UiText.StringResource(R.string.recurring_advanced_only_info), state.actionMessage)
        assertEquals(null, state.errorText)
    }

    @Test
    fun `no change result shows the truthful nothing to mark message and not the moved forward one`() =
        runTest(testDispatcher) {
            val rule = recurringExpense(8L, "Rent", 400.0, RecurrenceFrequency.MONTHLY, fixedNow, true)
            coEvery { markPaidUseCase.invoke(8L, fixedNow) } returns MarkPaidResult.NoChange

            viewModel.markAsPaid(rule)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(UiText.StringResource(R.string.recurring_mark_paid_no_change_info), state.actionMessage)
            assertEquals(null, state.errorText)
            assertTrue(state.inFlightRuleIds.isEmpty())
        }

    @Test
    fun `irregular rules do not support mark paid and the view model ignores the actions`() =
        runTest(testDispatcher) {
            val irregular = recurringExpense(9L, "Irregular", 10.0, RecurrenceFrequency.IRREGULAR, fixedNow, true)
            val monthly = recurringExpense(10L, "Gym", 10.0, RecurrenceFrequency.MONTHLY, fixedNow, true)

            assertFalse(supportsMarkPaid(irregular))
            assertTrue(supportsMarkPaid(monthly))

            viewModel.markAsPaid(irregular)
            viewModel.markAlreadyRecorded(irregular)
            advanceUntilIdle()

            coVerify(exactly = 0) { markPaidUseCase.invoke(any(), any()) }
            coVerify(exactly = 0) { markPaidUseCase.skip(any(), any()) }
            assertTrue(viewModel.uiState.value.inFlightRuleIds.isEmpty())
            assertEquals(null, viewModel.uiState.value.actionMessage)
        }

    @Test
    fun `in flight flag blocks a second tap and clears afterwards`() = runTest(testDispatcher) {
        val rule = recurringExpense(5L, "Gym", 30.0, RecurrenceFrequency.MONTHLY, fixedNow, true)
        val gate = CompletableDeferred<Unit>()
        coEvery { markPaidUseCase.invoke(5L, fixedNow) } coAnswers {
            gate.await()
            MarkPaidResult.Paid(1L)
        }

        viewModel.markAsPaid(rule)
        assertTrue(5L in viewModel.uiState.value.inFlightRuleIds)
        viewModel.markAsPaid(rule) // ignored: still in flight
        advanceUntilIdle()
        assertTrue(5L in viewModel.uiState.value.inFlightRuleIds)

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { markPaidUseCase.invoke(5L, fixedNow) }
        assertTrue(viewModel.uiState.value.inFlightRuleIds.isEmpty())
        assertEquals(null, viewModel.uiState.value.errorText)
    }

    @Test
    fun `already recorded delegates to skip with the seen next date`() = runTest(testDispatcher) {
        val rule = recurringExpense(6L, "Water", 20.0, RecurrenceFrequency.MONTHLY, fixedNow + DAY_MS, true)
        coEvery { markPaidUseCase.skip(6L, fixedNow + DAY_MS) } returns MarkPaidResult.Skipped(3L)

        viewModel.markAlreadyRecorded(rule)
        advanceUntilIdle()

        coVerify(exactly = 1) { markPaidUseCase.skip(6L, fixedNow + DAY_MS) }
        coVerify(exactly = 0) { markPaidUseCase.invoke(any(), any()) }
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

        viewModel = newViewModel()
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

        viewModel = newViewModel()
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

        viewModel = newViewModel()
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

        viewModel = newViewModel()
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