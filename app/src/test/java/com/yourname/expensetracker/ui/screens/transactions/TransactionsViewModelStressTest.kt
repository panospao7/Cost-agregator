package com.yourname.expensetracker.ui.screens.transactions

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.SortOrder
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("Stress test: may hang in CI, run manually")
class TransactionsViewModelStressTest : ViewModelTestUtils() {

    private lateinit var notificationRepository: NotificationRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var merchantLocationRepository: MerchantLocationRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var geocodingService: GeocodingService

    private lateinit var viewModel: TransactionsViewModel

    @Before
    override fun setup() {
        super.setup()
        notificationRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        geocodingService = mockk(relaxed = true)

        val now = 1_700_000_000_000L
        every { timeProvider.now() } returns now
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesWithCategoryFiltered(any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        viewModel = TransactionsViewModel(
            notificationRepository,
            expenseRepository,
            categoryRepository,
            recurringExpenseRepository,
            merchantLocationRepository,
            timeProvider,
            geocodingService
        )
    }

    @Test
    fun `stress - initial selectedTab is MONTH`() = runTest(testDispatcher) {
        viewModel.selectedTab.test {
            assertEquals(TransactionsViewModel.TransactionTab.MONTH, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial searchQuery is empty`() = runTest(testDispatcher) {
        viewModel.searchQuery.test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial ownershipFilter is ALL`() = runTest(testDispatcher) {
        viewModel.ownershipFilter.test {
            assertEquals(TransactionsViewModel.OwnershipFilter.ALL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial sortOrder is DATE_DESC`() = runTest(testDispatcher) {
        viewModel.sortOrder.test {
            assertEquals(SortOrder.DATE_DESC, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - selectTab TODAY updates selectedTab`() = runTest(testDispatcher) {
        viewModel.selectTab(TransactionsViewModel.TransactionTab.TODAY)
        advanceUntilIdle()

        viewModel.selectedTab.test {
            assertEquals(TransactionsViewModel.TransactionTab.TODAY, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - search updates searchQuery`() = runTest(testDispatcher) {
        viewModel.search("coffee")
        advanceUntilIdle()

        viewModel.searchQuery.test {
            assertEquals("coffee", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - setOwnershipFilter updates state`() = runTest(testDispatcher) {
        viewModel.setOwnershipFilter(TransactionsViewModel.OwnershipFilter.MINE)
        advanceUntilIdle()

        viewModel.ownershipFilter.test {
            assertEquals(TransactionsViewModel.OwnershipFilter.MINE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - setSortOrder updates state`() = runTest(testDispatcher) {
        viewModel.setSortOrder(SortOrder.AMOUNT_DESC)
        advanceUntilIdle()

        viewModel.sortOrder.test {
            assertEquals(SortOrder.AMOUNT_DESC, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - loadMore on ALL tab triggers repository`() = runTest(testDispatcher) {
        viewModel.isLoading.test {
            assertEquals(false, awaitItem())
            viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.isLoadingMoreState.test {
            assertEquals(false, awaitItem())
            viewModel.loadMore()
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(atLeast = 2) {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertNotNull(viewModel)
    }

    @Test
    fun `stress - loadMore stops after empty page on ALL tab`() = runTest(testDispatcher) {
        val firstPage = buildPage(size = TransactionsViewModel.PAGE_SIZE, merchantPrefix = "page1")
        coEvery {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(firstPage, emptyList())

        viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
        advanceUntilIdle()
        assertFalse(viewModel.hasReachedEnd.value)

        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(viewModel.hasReachedEnd.value)

        viewModel.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 2) {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `stress - updateMerchant refreshes ALL paged expenses`() = runTest(testDispatcher) {
        val initialPage = listOf(buildExpenseWithCategory(id = 1L, merchant = "Old Merchant"))
        val refreshedPage = listOf(buildExpenseWithCategory(id = 1L, merchant = "New Merchant"))

        coEvery {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(initialPage, refreshedPage)

        val collectJob = backgroundScope.launch {
            viewModel.transactions.collect { }
        }

        viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
        advanceUntilIdle()
        assertEquals("Old Merchant", viewModel.transactions.value.single().expense.merchant)

        viewModel.updateMerchant(initialPage.single().expense, "New Merchant")
        advanceUntilIdle()

        assertEquals("New Merchant", viewModel.transactions.value.single().expense.merchant)

        coVerify(exactly = 1) { expenseRepository.updateExpenseMerchant(initialPage.single().expense, "New Merchant", false) }
        coVerify(exactly = 2) {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        collectJob.cancel()
    }

    @Test
    fun `stress - refresh does not crash`() = runTest(testDispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        assertNotNull(viewModel)
    }

    @Test
    fun `stress - clearFilter clears filter`() = runTest(testDispatcher) {
        viewModel.applyFilter(TransactionFilter(categoryId = 1L))
        advanceUntilIdle()
        viewModel.clearFilter()
        advanceUntilIdle()
        assertEquals(null, viewModel.filter.value)
    }

    @Test
    fun `stress - applyFilter does not crash`() = runTest(testDispatcher) {
        val filter = TransactionFilter(
            categoryId = 1L,
            merchantName = "Test",
            transactionType = TransactionType.PURCHASE,
            dateRange = Pair(0L, System.currentTimeMillis())
        )
        viewModel.applyFilter(filter)
        advanceUntilIdle()
        assertEquals(filter, viewModel.filter.value)
    }

    @Test
    fun `stress - applyFilter on ALL tab reloads paged data with assistant filter params`() = runTest(testDispatcher) {
        val filter = TransactionFilter(
            categoryId = 5L,
            merchantName = "Lidl",
            transactionType = TransactionType.PURCHASE,
            dateRange = Pair(100L, 200L),
            minAmount = 10.0,
            maxAmount = 50.0
        )

        viewModel.isLoading.test {
            assertEquals(false, awaitItem())
            viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            viewModel.applyFilter(filter)
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(atLeast = 1) {
            expenseRepository.getExpensesPagedDynamic(
                limit = any(),
                offset = any(),
                searchQuery = any(),
                startDate = 100L,
                endDate = 200L,
                transactionType = TransactionType.PURCHASE,
                categoryId = 5L,
                merchantName = "Lidl",
                ownershipFilter = any(),
                minAmount = 10.0,
                maxAmount = 50.0,
                sortOrder = any()
            )
        }
    }

    @Test
    fun `stress - clearFilter on ALL tab preserves filter reset`() = runTest(testDispatcher) {
        viewModel.isLoading.test {
            assertEquals(false, awaitItem())
            viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            viewModel.applyFilter(TransactionFilter(categoryId = 1L, merchantName = "Test"))
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.clearFilter()
        advanceUntilIdle()

        assertEquals(null, viewModel.filter.value)
    }

    private fun buildPage(size: Int, merchantPrefix: String): List<ExpenseWithCategory> {
        return (1..size).map { index ->
            buildExpenseWithCategory(
                id = index.toLong(),
                merchant = "$merchantPrefix-$index"
            )
        }
    }

    private fun buildExpenseWithCategory(
        id: Long,
        merchant: String
    ): ExpenseWithCategory {
        return ExpenseWithCategory(
            expense = Expense(
                id = id,
                amount = 12.5,
                merchant = merchant,
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L
            ),
            category = null
        )
    }
}
