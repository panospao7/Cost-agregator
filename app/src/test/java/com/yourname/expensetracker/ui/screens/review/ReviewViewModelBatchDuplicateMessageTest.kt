package com.yourname.expensetracker.ui.screens.review

import android.net.Uri
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-12 12a review follow-up (ISSUE-2): the batch result message must surface
 * duplicates distinctly instead of folding them into the success count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewViewModelBatchDuplicateMessageTest : ViewModelTestUtils() {

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var viewModel: ReviewViewModel

    @Before
    override fun setup() {
        super.setup()
        receiptRepository = mockk(relaxed = true)
        val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
        val debugDataStorage = mockk<DebugDataStorage>(relaxed = true)

        every { reviewQueueRepository.getAllPendingReviews() } returns flowOf(emptyList())
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        coEvery { reviewQueueRepository.recoverStuckReviews() } returns 0
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        coEvery { debugDataStorage.load() } returns null

        viewModel = ReviewViewModel(
            repository = mockk(relaxed = true),
            reviewQueueRepository = reviewQueueRepository,
            categoryRepository = categoryRepository,
            receiptRepository = receiptRepository,
            expenseRepository = mockk(relaxed = true),
            debugDataStorage = debugDataStorage,
            geocodingService = mockk<GeocodingService>(relaxed = true),
            privacyGate = mockk(relaxed = true),
            explainPendingReviewUseCase = mockk<ExplainPendingReviewUseCase>(relaxed = true),
            suggestCategoryFallbackUseCase = mockk<SuggestCategoryFallbackUseCase>(relaxed = true),
            suggestReceiptExtractionUseCase = mockk<SuggestReceiptExtractionUseCase>(relaxed = true),
            judgePendingReviewDuplicateUseCase = mockk<JudgePendingReviewDuplicateUseCase>(relaxed = true),
            aiArtifactRepository = mockk<AiArtifactRepository>(relaxed = true),
            aiSettingsRepository = aiSettingsRepository,
            aiRuntimeDiagnostics = mockk<AiRuntimeDiagnostics>(relaxed = true),
            receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true),
            receiptDebugExporter = mockk(relaxed = true)
        )
    }

    @Test
    fun `batch message distinguishes duplicates from saves`() = runTest(testDispatcher) {
        coEvery { receiptRepository.processBatch(any(), any()) } returns ReceiptRepository.BatchResult(
            successCount = 2,
            failureCount = 0,
            errors = emptyList(),
            duplicateCount = 3
        )

        viewModel.processBatch(listOf(Uri.parse("content://test/1.jpg")))
        advanceUntilIdle()

        assertEquals(
            "Processed 2 saved, 3 duplicates.",
            viewModel.errorMessage.value
        )
    }

    @Test
    fun `batch message with failures and duplicates reports all three buckets`() = runTest(testDispatcher) {
        coEvery { receiptRepository.processBatch(any(), any()) } returns ReceiptRepository.BatchResult(
            successCount = 2,
            failureCount = 1,
            errors = listOf("Receipt input validation failed"),
            duplicateCount = 3
        )

        viewModel.processBatch(listOf(Uri.parse("content://test/1.jpg")))
        advanceUntilIdle()

        val message = viewModel.errorMessage.value
        assertEquals(
            "Processed 2 saved, 3 duplicates. 1 failed: Receipt input validation failed",
            message
        )
    }

    @Test
    fun `batch message without duplicates keeps legacy wording`() = runTest(testDispatcher) {
        coEvery { receiptRepository.processBatch(any(), any()) } returns ReceiptRepository.BatchResult(
            successCount = 4,
            failureCount = 0,
            errors = emptyList(),
            duplicateCount = 0
        )

        viewModel.processBatch(listOf(Uri.parse("content://test/1.jpg")))
        advanceUntilIdle()

        assertEquals(
            "Successfully processed all 4 receipts!",
            viewModel.errorMessage.value
        )
    }

    @Test
    fun `batch message with only failures keeps legacy failure wording`() = runTest(testDispatcher) {
        coEvery { receiptRepository.processBatch(any(), any()) } returns ReceiptRepository.BatchResult(
            successCount = 1,
            failureCount = 2,
            errors = listOf("Receipt input validation failed"),
            duplicateCount = 0
        )

        viewModel.processBatch(listOf(Uri.parse("content://test/1.jpg")))
        advanceUntilIdle()

        val message = viewModel.errorMessage.value
        assertEquals("Processed 1 ok. 2 failed: Receipt input validation failed", message)
    }
}
