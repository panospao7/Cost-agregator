package com.yourname.expensetracker.ui

import app.cash.turbine.test
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
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
            assertEquals(2, awaitItem())
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
            assertEquals(0, awaitItem())
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stress - navigationRequest is available`() = runTest(testDispatcher) {
        assertNotNull(viewModel.navigationRequest)
    }
}
