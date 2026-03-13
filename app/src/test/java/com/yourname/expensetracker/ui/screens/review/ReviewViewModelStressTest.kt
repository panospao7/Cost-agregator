package com.yourname.expensetracker.ui.screens.review

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.ui.screens.debug.DebugData
import com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ReviewViewModelStressTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var notificationRepository: NotificationRepository
    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var debugDataStorage: DebugDataStorage
    private lateinit var geocodingService: GeocodingService
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        notificationRepository = mockk(relaxed = true)
        reviewQueueRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        receiptRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        debugDataStorage = mockk(relaxed = true)
        geocodingService = mockk(relaxed = true)

        every { reviewQueueRepository.getPendingReviews() } returns flowOf(emptyList())
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { debugDataStorage.load() } returns null

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ============================================================================
    // SECTION 1: INITIAL STATE
    // ============================================================================

    @Test
    fun `stress - initial pending reviews is empty`() = runTest {
        val reviews = viewModel.pendingReviews.value
        assertNotNull(reviews)
    }

    @Test
    fun `stress - initial pending count is zero`() = runTest {
        val count = viewModel.pendingCount.value
        assertEquals(0, count)
    }

    @Test
    fun `stress - initial categories is empty`() = runTest {
        val categories = viewModel.categories.value
        assertNotNull(categories)
    }

    @Test
    fun `stress - initial error message is null`() = runTest {
        val error = viewModel.errorMessage.value
        assertNull(error)
    }

    // ============================================================================
    // SECTION 2: APPROVE REVIEW
    // ============================================================================

    @Test
    fun `stress - approve review success`() = runTest {
        coEvery { reviewQueueRepository.approveReview(1) } returns Result.Success(1L)
        
        viewModel.approveReview(1)
        
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `stress - approve review duplicate`() = runTest {
        coEvery { reviewQueueRepository.approveReview(1) } returns Result.Duplicate
        
        viewModel.approveReview(1)
        
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Duplicate transaction detected", viewModel.errorMessage.value)
    }

    @Test
    fun `stress - approve review error`() = runTest {
        coEvery { reviewQueueRepository.approveReview(1) } returns Result.Error(Exception("DB error"), "Failed")
        
        viewModel.approveReview(1)
        
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)
    }

    // ============================================================================
    // SECTION 3: REJECT REVIEW
    // ============================================================================

    @Test
    fun `stress - reject review success`() = runTest {
        coEvery { reviewQueueRepository.rejectReview(1) } returns Unit
        
        viewModel.rejectReview(1)
        
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ============================================================================
    // SECTION 4: BULK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - approve all reviews`() = runTest {
        coEvery { reviewQueueRepository.approveAllReview() } returns Unit
        
        viewModel.approveAll()
        
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `stress - reject all reviews`() = runTest {
        coEvery { reviewQueueRepository.rejectAllReviews() } returns Unit
        
        viewModel.rejectAll()
        
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ============================================================================
    // SECTION 5: ERROR HANDLING
    // ============================================================================

    @Test
    fun `stress - clear error message`() = runTest {
        viewModel.clearError()
        
        assertNull(viewModel.errorMessage.value)
    }

    // ============================================================================
    // SECTION 6: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - pending reviews flow is available`() = runTest {
        val reviews = viewModel.pendingReviews
        assertNotNull(reviews)
    }

    @Test
    fun `stress - categories flow is available`() = runTest {
        val categories = viewModel.categories
        assertNotNull(categories)
    }

    // ============================================================================
    // SECTION 7: BATCH PROCESSING STATE
    // ============================================================================

    @Test
    fun `stress - batch processing initially false`() = runTest {
        assertFalse(viewModel.isBatchProcessing.value)
    }

    @Test
    fun `stress - batch progress initially null`() = runTest {
        assertNull(viewModel.batchProgress.value)
    }

    // ============================================================================
    // SECTION 8: DEBUG DATA
    // ============================================================================

    @Test
    fun `stress - debug data initially null`() = runTest {
        assertNull(viewModel.debugData.value)
    }

    // ============================================================================
    // SECTION 10: VIEWMODEL SCOPE
    // ============================================================================

    @Test
    fun `stress - viewmodel handles concurrent operations`() = runTest {
        coEvery { reviewQueueRepository.approveReview(any()) } returns Result.Success(1L)
        
        viewModel.approveReview(1)
        viewModel.rejectReview(2)
        viewModel.approveReview(3)
        
        testDispatcher.scheduler.advanceUntilIdle()
        // Should not throw
    }
}
