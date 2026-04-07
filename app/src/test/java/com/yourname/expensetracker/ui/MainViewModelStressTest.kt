package com.yourname.expensetracker.ui

import app.cash.turbine.test
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("Stress test: may hang in CI, run manually")
class MainViewModelStressTest : ViewModelTestUtils() {

    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var viewModel: MainViewModel

    @Before
    override fun setup() {
        super.setup()
        reviewQueueRepository = mockk(relaxed = true)
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        viewModel = MainViewModel(reviewQueueRepository)
    }

    @Test
    fun `stress - initial pendingReviewCount is zero`() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(0, viewModel.pendingReviewCount.value)
    }

    @Test
    fun `stress - navigateToTab emits to navigationRequest`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.navigateToTab(2)
            advanceUntilIdle()
            assertEquals(MainNavigationRequest.Tab(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - rapid navigateToTab emits multiple values`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            viewModel.navigateToTab(0)
            viewModel.navigateToTab(1)
            viewModel.navigateToTab(2)
            advanceUntilIdle()
            assertEquals(MainNavigationRequest.Tab(0), awaitItem())
            assertEquals(MainNavigationRequest.Tab(1), awaitItem())
            assertEquals(MainNavigationRequest.Tab(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - navigateToTransactions emits transaction request`() = runTest(testDispatcher) {
        viewModel.navigationRequest.test {
            val filter = TransactionFilter(merchantName = "Test Merchant")
            viewModel.navigateToTransactions(filter)
            advanceUntilIdle()
            assertEquals(MainNavigationRequest.Transactions(filter), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - navigationRequest is available`() = runTest(testDispatcher) {
        assertNotNull(viewModel.navigationRequest)
    }
}
