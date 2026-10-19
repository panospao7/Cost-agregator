package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.OwnershipFilter as RepositoryOwnershipFilter
import com.yourname.expensetracker.data.repository.SortOrder
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Paging behaviour of [TransactionsViewModel] on the ALL tab (loadMore, applyFilter,
 * clearFilter, updateMerchant refresh). Production loads pages inside
 * `withContext(Dispatchers.IO)`, so waits use a repository-call signal plus
 * `isLoading` / `isLoadingMoreState` instead of advanceUntilIdle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelPagingTest : ViewModelTestUtils() {

    private data class PagedCall(
        val limit: Int,
        val offset: Int,
        val categoryId: Long?,
        val merchantName: String?
    )

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var viewModel: TransactionsViewModel

    private val pageCalls = CopyOnWriteArrayList<PagedCall>()
    private val callCounter = AtomicInteger(0)

    @Volatile
    private var repoCalled = CompletableDeferred<Unit>()

    @Before
    override fun setup() {
        super.setup()
        expenseRepository = mockk(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

        every { timeProvider.now() } returns 1_700_000_000_000L
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        viewModel = TransactionsViewModel(
            mockk<NotificationRepository>(relaxed = true),
            expenseRepository,
            categoryRepository,
            mockk<RecurringExpenseRepository>(relaxed = true),
            mockk<MerchantLocationRepository>(relaxed = true),
            timeProvider,
            mockk<GeocodingService>(relaxed = true),
            currencySettingsRepository,
            sourceLinkQueryService = mockk(relaxed = true),
            transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
        )
    }

    /** Pages are returned in call order; calls past the end return an empty page. */
    private fun stubPages(vararg pages: List<ExpenseWithCategory>) {
        coEvery {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            pageCalls += PagedCall(
                limit = arg<Int>(0),
                offset = arg<Int>(1),
                categoryId = arg<Long?>(6),
                merchantName = arg<String?>(7)
            )
            val result = pages.getOrElse(callCounter.getAndIncrement()) { emptyList() }
            repoCalled.complete(Unit)
            result
        }
    }

    private suspend fun awaitInitialLoad(action: () -> Unit) {
        repoCalled = CompletableDeferred()
        action()
        repoCalled.await()
        viewModel.isLoading.first { !it }
    }

    private suspend fun awaitLoadMore(action: () -> Unit) {
        repoCalled = CompletableDeferred()
        action()
        repoCalled.await()
        viewModel.isLoadingMoreState.first { !it }
    }

    @Test
    fun `loadMore on ALL tab requests next page at PAGE_SIZE offset and appends rows`() = runTest(testDispatcher) {
        val pageSize = TransactionsViewModel.PAGE_SIZE
        assertEquals(50, pageSize)
        stubPages(
            buildPage(size = pageSize, startId = 1L, merchantPrefix = "page1"),
            buildPage(size = pageSize, startId = 1L + pageSize, merchantPrefix = "page2")
        )

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        assertFalse(viewModel.hasReachedEnd.value)

        awaitLoadMore { viewModel.loadMore() }

        assertEquals(
            listOf(
                PagedCall(limit = 50, offset = 0, categoryId = null, merchantName = null),
                PagedCall(limit = 50, offset = 50, categoryId = null, merchantName = null)
            ),
            pageCalls.toList()
        )
        assertFalse(viewModel.hasReachedEnd.value)
        val rows = viewModel.transactions.first { it.size == 2 * pageSize }
        assertEquals("page1-1", rows.first().expense.merchant)
        assertEquals("page2-100", rows.last().expense.merchant)
    }

    @Test
    fun `loadMore stops after empty page on ALL tab`() = runTest(testDispatcher) {
        stubPages(
            buildPage(size = TransactionsViewModel.PAGE_SIZE, startId = 1L, merchantPrefix = "page1"),
            emptyList()
        )

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        assertFalse(viewModel.hasReachedEnd.value)

        awaitLoadMore { viewModel.loadMore() }
        assertTrue(viewModel.hasReachedEnd.value)

        // Reached end: further loadMore must not touch the repository.
        viewModel.loadMore()

        assertEquals(2, pageCalls.size)
        coVerify(exactly = 2) {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `loadMore with short final page marks end of list`() = runTest(testDispatcher) {
        stubPages(
            buildPage(size = TransactionsViewModel.PAGE_SIZE, startId = 1L, merchantPrefix = "page1"),
            buildPage(size = 10, startId = 1000L, merchantPrefix = "tail")
        )

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        assertFalse(viewModel.hasReachedEnd.value)

        awaitLoadMore { viewModel.loadMore() }

        assertTrue(viewModel.hasReachedEnd.value)
        val rows = viewModel.transactions.first { it.size == TransactionsViewModel.PAGE_SIZE + 10 }
        assertEquals("tail-1000", rows[TransactionsViewModel.PAGE_SIZE].expense.merchant)
    }

    @Test
    fun `updateMerchant refreshes ALL paged expenses with loaded count plus one`() = runTest(testDispatcher) {
        val initialPage = listOf(buildExpenseWithCategory(id = 1L, merchant = "Old Merchant"))
        val refreshedPage = listOf(buildExpenseWithCategory(id = 1L, merchant = "New Merchant"))
        stubPages(initialPage, refreshedPage)

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        assertEquals(
            "Old Merchant",
            viewModel.transactions.first { it.size == 1 }.single().expense.merchant
        )

        repoCalled = CompletableDeferred()
        viewModel.updateMerchant(initialPage.single().expense, "New Merchant")
        repoCalled.await()

        val refreshed = viewModel.transactions.first { it.singleOrNull()?.expense?.merchant == "New Merchant" }
        assertEquals(1L, refreshed.single().expense.id)

        coVerify(exactly = 1) { expenseRepository.updateExpenseMerchant(initialPage.single().expense, "New Merchant", false) }
        assertEquals(
            listOf(
                PagedCall(limit = 50, offset = 0, categoryId = null, merchantName = null),
                // loadedCount (1) + 1, always from offset 0
                PagedCall(limit = 2, offset = 0, categoryId = null, merchantName = null)
            ),
            pageCalls.toList()
        )
    }

    @Test
    fun `applyFilter on ALL tab reloads page zero with every filter param passed through`() = runTest(testDispatcher) {
        val filter = TransactionFilter(
            categoryId = 5L,
            merchantName = "Lidl",
            transactionType = TransactionType.PURCHASE,
            dateRange = Pair(100L, 200L),
            minAmount = 10.0,
            maxAmount = 50.0
        )
        stubPages()

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        awaitInitialLoad { viewModel.applyFilter(filter) }

        assertEquals(filter, viewModel.filter.value)
        coVerify(exactly = 1) {
            expenseRepository.getExpensesPagedDynamic(
                limit = 50,
                offset = 0,
                searchQuery = null,
                startDate = 100L,
                endDate = 200L,
                transactionType = TransactionType.PURCHASE,
                categoryId = 5L,
                merchantName = "Lidl",
                ownershipFilter = RepositoryOwnershipFilter.ALL,
                minAmount = 10.0,
                maxAmount = 50.0,
                sortOrder = SortOrder.DATE_DESC
            )
        }
        assertEquals(2, pageCalls.size)
    }

    @Test
    fun `clearFilter on ALL tab reloads page zero without filter params`() = runTest(testDispatcher) {
        stubPages()

        awaitInitialLoad { viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL) }
        awaitInitialLoad {
            viewModel.applyFilter(TransactionFilter(categoryId = 1L, merchantName = "Test"))
        }
        awaitInitialLoad { viewModel.clearFilter() }

        assertNull(viewModel.filter.value)
        assertEquals(
            listOf(
                PagedCall(limit = 50, offset = 0, categoryId = null, merchantName = null),
                PagedCall(limit = 50, offset = 0, categoryId = 1L, merchantName = "Test"),
                PagedCall(limit = 50, offset = 0, categoryId = null, merchantName = null)
            ),
            pageCalls.toList()
        )
    }

    private fun buildPage(size: Int, startId: Long, merchantPrefix: String): List<ExpenseWithCategory> {
        return (0 until size).map { index ->
            val id = startId + index
            buildExpenseWithCategory(id = id, merchant = "$merchantPrefix-$id")
        }
    }

    private fun buildExpenseWithCategory(id: Long, merchant: String): ExpenseWithCategory {
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
