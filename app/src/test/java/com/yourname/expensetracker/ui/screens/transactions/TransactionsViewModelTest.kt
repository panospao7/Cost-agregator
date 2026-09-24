package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.*
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.provenance.SourceLinkQueryService
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest : ViewModelTestUtils() {
    private val expenses = mockk<ExpenseRepository>(relaxed = true)
    private val categories = mockk<CategoryRepository>(relaxed = true)
    private val sourceLinks = mockk<SourceLinkQueryService>(relaxed = true)
    private lateinit var model: TransactionsViewModel
    private val hostile = IllegalStateException("secret merchant=private sql=/data/918")

    @Before override fun setup() {
        super.setup()
        every { categories.allCategories } returns flowOf(emptyList())
        model = TransactionsViewModel(
            mockk<NotificationRepository>(relaxed = true), expenses, categories,
            mockk<RecurringExpenseRepository>(relaxed = true),
            mockk<MerchantLocationRepository>(relaxed = true),
            mockk<TimeProvider>(relaxed = true), mockk<GeocodingService>(relaxed = true),
            mockk<CurrencySettingsRepository>(relaxed = true), sourceLinks
        )
    }

    @Test fun `provenance failure is bounded`() = runTest(testDispatcher) {
        coEvery { sourceLinks.getLinksForExpense(any()) } throws hostile
        model.loadProvenanceForExpense(1)
        advanceUntilIdle()
        assertEquals("Unable to load provenance (UNKNOWN_ERROR)", model.provenanceSummary.value)
    }

    @Test fun `provenance cancellation does not become a failure summary`() = runTest(testDispatcher) {
        coEvery { sourceLinks.getLinksForExpense(any()) } throws CancellationException("secret path")
        model.loadProvenanceForExpense(1)
        advanceUntilIdle()
        assertNull(model.provenanceSummary.value)
    }

    @Test fun `delete failure emits bounded error`() = runTest(testDispatcher) {
        val expense = mockk<Expense>(relaxed = true)
        coEvery { expenses.deleteExpense(expense) } throws hostile
        val received = mutableListOf<UiText>()
        val collection = backgroundScope.launch { model.error.collect { received += it } }
        runCurrent()
        model.deleteExpense(expense)
        advanceUntilIdle()
        assertEquals("Failed to delete transaction (UNKNOWN_ERROR)", (received.single() as UiText.DynamicString).value)
        collection.cancel()
    }

    @Test fun `category failure emits bounded error`() = runTest(testDispatcher) {
        val expense = mockk<Expense>(relaxed = true)
        coEvery { expenses.updateExpenseCategory(expense, 2L) } throws hostile
        val received = mutableListOf<UiText>()
        val collection = backgroundScope.launch { model.error.collect { received += it } }
        runCurrent()
        model.updateCategory(expense, 2L)
        advanceUntilIdle()
        assertEquals("Failed to update category (UNKNOWN_ERROR)", (received.single() as UiText.DynamicString).value)
        collection.cancel()
    }
}
