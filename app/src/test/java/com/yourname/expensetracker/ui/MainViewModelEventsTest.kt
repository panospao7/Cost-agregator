package com.yourname.expensetracker.ui

import app.cash.turbine.test
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Navigation event delivery of MainViewModel (Channel.BUFFERED + receiveAsFlow,
 * MainViewModel.kt:46-82) and pending-review-count propagation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelEventsTest : ViewModelTestUtils() {

    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var viewModel: MainViewModel

    @Before
    override fun setup() {
        super.setup()
        reviewQueueRepository = mockk(relaxed = true)
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        restoreMaintenanceMode = mockk(relaxed = true)
        every { restoreMaintenanceMode.operationalStateFlow } returns
            MutableStateFlow<AppOperationalState>(AppOperationalState.Normal)
        viewModel = MainViewModel(reviewQueueRepository, restoreMaintenanceMode)
    }

    @Test
    fun `pendingReviewCount mirrors the repository count once collected`() = runTest(testDispatcher) {
        val counts = MutableStateFlow(0)
        every { reviewQueueRepository.getPendingReviewCount() } returns counts
        val vm = MainViewModel(reviewQueueRepository, restoreMaintenanceMode)

        vm.pendingReviewCount.test {
            assertEquals(0, awaitItem())
            counts.value = 7
            runCurrent()
            assertEquals(7, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `navigateToTab emits Tab request to navigationRequest`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.navigateToTab(2)
            runCurrent()
            assertEquals(MainNavigationRequest.Tab(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rapid navigateToTab delivers every request in order`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.navigateToTab(0)
            viewModel.navigateToTab(1)
            viewModel.navigateToTab(2)
            runCurrent()
            assertEquals(MainNavigationRequest.Tab(0), awaitItem())
            assertEquals(MainNavigationRequest.Tab(1), awaitItem())
            assertEquals(MainNavigationRequest.Tab(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `navigateToTransactions emits Transactions request carrying the filter`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            val filter = TransactionFilter(merchantName = "Test Merchant")
            viewModel.navigateToTransactions(filter)
            runCurrent()
            assertEquals(MainNavigationRequest.Transactions(filter), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `navigation requests sent before collection are delivered, not dropped`() = runTest(testDispatcher) {
        viewModel.navigateToTab(4)
        runCurrent()

        viewModel.navigationRequest.test {
            assertEquals(MainNavigationRequest.Tab(4), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
