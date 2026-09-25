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
import org.junit.After
import timber.log.Timber
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.model.RecurrenceFrequency

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest : ViewModelTestUtils() {
    private val expenses = mockk<ExpenseRepository>(relaxed = true)
    private val categories = mockk<CategoryRepository>(relaxed = true)
    private val sourceLinks = mockk<SourceLinkQueryService>(relaxed = true)
    private val recurring = mockk<RecurringExpenseRepository>(relaxed = true)
    private lateinit var model: TransactionsViewModel
    private val hostile = IllegalStateException("secret merchant=private sql=/data/918")
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @After override fun tearDown() {
        Timber.uproot(logTree)
        super.tearDown()
    }

    private fun assertSafeLog(code: String) {
        assertEquals(listOf(null to "Transactions: $code class=IllegalStateException"), logs)
    }

    @Before override fun setup() {
        super.setup()
        Timber.plant(logTree)
        every { categories.allCategories } returns flowOf(emptyList())
        model = TransactionsViewModel(
            mockk<NotificationRepository>(relaxed = true), expenses, categories,
            recurring,
            mockk<MerchantLocationRepository>(relaxed = true),
            mockk<TimeProvider>(relaxed = true), mockk<GeocodingService>(relaxed = true),
            mockk<CurrencySettingsRepository>(relaxed = true), sourceLinks
        )
    }

    @Test fun `provenance failure is bounded`() = runTest(testDispatcher) {
        coEvery { sourceLinks.getLinksForExpense(any()) } throws hostile
        model.loadProvenanceForExpense(1)
        advanceUntilIdle()
        assertEquals("Unable to load provenance (TRANSACTION_LOAD_FAILED)", model.provenanceSummary.value)
        assertSafeLog("TRANSACTION_LOAD_FAILED")
    }

    @Test fun `provenance cancellation does not become a failure summary`() = runTest(testDispatcher) {
        coEvery { sourceLinks.getLinksForExpense(any()) } throws CancellationException("secret path")
        model.loadProvenanceForExpense(1)
        advanceUntilIdle()
        assertNull(model.provenanceSummary.value)
        assertTrue(logs.isEmpty())
    }

    @Test fun `delete failure emits bounded error`() = runTest(testDispatcher) {
        val expense = mockk<Expense>(relaxed = true)
        coEvery { expenses.deleteExpense(expense) } throws hostile
        val received = mutableListOf<UiText>()
        val collection = backgroundScope.launch { model.error.collect { received += it } }
        runCurrent()
        model.deleteExpense(expense)
        advanceUntilIdle()
        assertEquals("Failed to delete transaction (TRANSACTION_DELETE_FAILED)", (received.single() as UiText.DynamicString).value)
        assertSafeLog("TRANSACTION_DELETE_FAILED")
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
        assertEquals("Failed to update category (TRANSACTION_UPDATE_FAILED)", (received.single() as UiText.DynamicString).value)
        assertSafeLog("TRANSACTION_UPDATE_FAILED")
        collection.cancel()
    }

    private fun mutationCases(failure: Exception): List<Pair<String, () -> Unit>> {
        val expense = Expense(id = 1L, amount = 10.0, currency = "EUR", merchant = "Merchant", date = 1L, transactionType = TransactionType.PURCHASE)
        coEvery { expenses.deleteExpense(expense) } throws failure
        coEvery { expenses.updateExpenseCategory(expense, any()) } throws failure
        coEvery { expenses.updateExpenseCategoryBulk(any(), any()) } throws failure
        coEvery { expenses.updateExpenseMerchant(any(), any(), any()) } throws failure
        coEvery { expenses.updateExpenseTypeAndTransfer(any(), any(), any(), any()) } throws failure
        coEvery { expenses.updateTransferDetails(any(), any(), any(), any()) } throws failure
        coEvery { expenses.updateOwnership(any(), any(), any(), any(), any(), any(), any()) } throws failure
        coEvery { expenses.updateExpenseLocation(any(), any(), any(), any(), any(), any()) } throws failure
        coEvery { expenses.clearExpenseLocation(any()) } throws failure
        coEvery { recurring.addRecurringExpense(merchant = any(), amount = any(), frequency = any(), lastDate = any(), currency = any()) } throws failure
        return listOf(
            "Failed to delete transaction (TRANSACTION_DELETE_FAILED)" to { model.deleteExpense(expense) },
            "Failed to update category (TRANSACTION_UPDATE_FAILED)" to { model.updateCategory(expense, 2L) },
            "Failed to update category (TRANSACTION_UPDATE_FAILED)" to { model.updateCategory(expense, 2L, applyToAll = true) },
            "Failed to update merchant (TRANSACTION_UPDATE_FAILED)" to { model.updateMerchant(expense, "Renamed") },
            "Failed to update type (TRANSACTION_UPDATE_FAILED)" to { model.updateExpenseType(expense, TransactionType.PURCHASE) },
            "Failed to update (TRANSACTION_UPDATE_FAILED)" to { model.updateTransferDetails(expense, null, "account") },
            "Failed to update (TRANSACTION_UPDATE_FAILED)" to { model.updateOwnership(expense, false, "", false, "", "", "") },
            "Failed to save location (TRANSACTION_UPDATE_FAILED)" to { model.updateLocation(expense, 0.0, 0.0, null, null) },
            "Failed to clear location (TRANSACTION_UPDATE_FAILED)" to { model.clearLocation(expense) },
            "Failed to mark as recurring (TRANSACTION_UPDATE_FAILED)" to { model.markAsRecurring(expense, RecurrenceFrequency.MONTHLY) }
        )
    }

    @Test fun `every mutation failure emits fixed operation text and class only log`() = runTest(testDispatcher) {
        for ((expected, action) in mutationCases(hostile)) {
            logs.clear()
            model.error.test {
                action()
                assertEquals(expected, (awaitItem() as UiText.DynamicString).value)
                cancelAndIgnoreRemainingEvents()
            }
            assertSafeLog(if (expected.contains("TRANSACTION_DELETE_FAILED")) "TRANSACTION_DELETE_FAILED" else "TRANSACTION_UPDATE_FAILED")
            assertTrue(model.mutatingExpenseIds.value.isEmpty())
        }
    }

    @Test fun `mutation cancellations never become failure UI or logs`() = runTest(testDispatcher) {
        val received = mutableListOf<UiText>()
        val collection = backgroundScope.launch { model.error.collect { received += it } }
        runCurrent()
        for ((_, action) in mutationCases(CancellationException("private SQL /data/918"))) {
            action()
            advanceUntilIdle()
            assertTrue(received.isEmpty())
            assertTrue(logs.isEmpty())
            assertTrue(model.mutatingExpenseIds.value.isEmpty())
            assertFalse(model.isLoading.value)
        }
        collection.cancel()
    }

    @Test fun `reactive load failure keeps prior rows and emits bounded error`() = runTest(testDispatcher) {
        val rows = listOf(mockk<ExpenseWithCategory>(relaxed = true))
        every { expenses.getExpensesWithCategoryInPeriod(any(), any()) } returns flow {
            emit(rows)
            throw hostile
        }
        model.error.test {
            val errors = this
            model.transactions.test {
                awaitItem()
                assertEquals(rows, awaitItem())
                assertEquals("Failed to load transactions (TRANSACTION_LOAD_FAILED)", (errors.awaitItem() as UiText.DynamicString).value)
                assertEquals(rows, model.transactions.value)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertSafeLog("TRANSACTION_LOAD_FAILED")
    }

    @Test fun `initial page failure emits bounded load error`() = runTest(testDispatcher) {
        coEvery { expenses.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws hostile
        model.error.test {
            model.selectTab(TransactionsViewModel.TransactionTab.ALL)
            assertEquals("Failed to load transactions (TRANSACTION_LOAD_FAILED)", (awaitItem() as UiText.DynamicString).value)
            cancelAndIgnoreRemainingEvents()
        }
        assertSafeLog("TRANSACTION_LOAD_FAILED")
    }

    @Test fun `next page failure emits bounded load error`() = runTest(testDispatcher) {
        val firstPage = CompletableDeferred<Unit>()
        coEvery { expenses.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (secondArg<Int>() != 0) throw hostile
            firstPage.complete(Unit)
            List(TransactionsViewModel.PAGE_SIZE) { mockk<ExpenseWithCategory>(relaxed = true) }
        }
        model.error.test {
            model.selectTab(TransactionsViewModel.TransactionTab.ALL)
            firstPage.await()
            model.isLoading.first { !it }
            model.loadMore()
            assertEquals("Failed to load more transactions (TRANSACTION_LOAD_FAILED)", (awaitItem() as UiText.DynamicString).value)
            cancelAndIgnoreRemainingEvents()
        }
        assertSafeLog("TRANSACTION_LOAD_FAILED")
    }
}
