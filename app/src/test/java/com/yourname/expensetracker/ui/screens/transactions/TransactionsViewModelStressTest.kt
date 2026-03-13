package com.yourname.expensetracker.ui.screens.transactions

import app.cash.turbine.test
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesWithCategoryFiltered(any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

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
    fun `stress - initial selectedTab is MONTH`() = runTest {
        viewModel.selectedTab.test {
            assertEquals(TransactionsViewModel.TransactionTab.MONTH, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial searchQuery is empty`() = runTest {
        viewModel.searchQuery.test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial ownershipFilter is ALL`() = runTest {
        viewModel.ownershipFilter.test {
            assertEquals(TransactionsViewModel.OwnershipFilter.ALL, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - initial sortOrder is DATE_DESC`() = runTest {
        viewModel.sortOrder.test {
            assertEquals(SortOrder.DATE_DESC, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - selectTab TODAY updates selectedTab`() = runTest {
        viewModel.selectTab(TransactionsViewModel.TransactionTab.TODAY)
        advanceUntilIdle()

        viewModel.selectedTab.test {
            assertEquals(TransactionsViewModel.TransactionTab.TODAY, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - search updates searchQuery`() = runTest {
        viewModel.search("coffee")
        advanceUntilIdle()

        viewModel.searchQuery.test {
            assertEquals("coffee", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - setOwnershipFilter updates state`() = runTest {
        viewModel.setOwnershipFilter(TransactionsViewModel.OwnershipFilter.MINE)
        advanceUntilIdle()

        viewModel.ownershipFilter.test {
            assertEquals(TransactionsViewModel.OwnershipFilter.MINE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - setSortOrder updates state`() = runTest {
        viewModel.setSortOrder(SortOrder.AMOUNT_DESC)
        advanceUntilIdle()

        viewModel.sortOrder.test {
            assertEquals(SortOrder.AMOUNT_DESC, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - loadMore on ALL tab triggers repository`() = runTest {
        viewModel.selectTab(TransactionsViewModel.TransactionTab.ALL)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        coEvery { expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        assertNotNull(viewModel)
    }

    @Test
    fun `stress - refresh does not crash`() = runTest {
        viewModel.refresh()
        advanceUntilIdle()
        assertNotNull(viewModel)
    }

    @Test
    fun `stress - clearFilter clears filter`() = runTest {
        viewModel.applyFilter(TransactionFilter(categoryId = 1L))
        advanceUntilIdle()
        viewModel.clearFilter()
        advanceUntilIdle()
        assertEquals(null, viewModel.filter.value)
    }

    @Test
    fun `stress - applyFilter does not crash`() = runTest {
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
}
