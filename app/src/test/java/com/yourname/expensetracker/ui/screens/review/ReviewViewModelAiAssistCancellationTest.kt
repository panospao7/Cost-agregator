package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * R6b (E8) — ReviewViewModel AI-assist catches must rethrow [CancellationException]
 * (no Error state, in-flight guard released by `finally`) and must never log the
 * throwable or its message for generic failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelAiAssistCancellationTest : ViewModelTestUtils() {

    private val sentinel = "R6BSENTINEL_7d41"

    private val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
    private val aiSettingsRepository = mockk<AiSettingsRepository>()
    private val explainPendingReviewUseCase = mockk<ExplainPendingReviewUseCase>(relaxed = true)
    private val suggestCategoryFallbackUseCase = mockk<SuggestCategoryFallbackUseCase>(relaxed = true)
    private val suggestReceiptExtractionUseCase = mockk<SuggestReceiptExtractionUseCase>(relaxed = true)
    private val judgePendingReviewDuplicateUseCase = mockk<JudgePendingReviewDuplicateUseCase>(relaxed = true)

    private lateinit var viewModel: ReviewViewModel

    private val reviewId = 30L
    private val receiptId = 7L

    private val item = PendingReviewWithReceipt(
        PendingReview(
            id = reviewId,
            rawNotificationId = null,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Lidl",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.4f,
            matchType = "FALLBACK",
            explanation = "weak",
            packageName = "pkg",
            notificationTitle = null,
            notificationText = null
        ),
        ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "LIDL TOTAL 12.34",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = null,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.2f
        )
    )

    @Before
    override fun setup() {
        super.setup()
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                reviewExplanationEnabled = true,
                categorizationFallbackEnabled = true,
                receiptAssistEnabled = true,
                dedupeJudgeEnabled = true
            )
        )
        coEvery { reviewQueueRepository.getPendingReviewWithReceiptById(reviewId) } returns item
        coEvery { reviewQueueRepository.getReviewById(reviewId) } returns item.review
        viewModel = ReviewViewModel(
            mockk(relaxed = true), // notificationRepository
            reviewQueueRepository,
            mockk(relaxed = true), // categoryRepository
            mockk(relaxed = true), // receiptRepository
            mockk(relaxed = true), // expenseRepository
            mockk(relaxed = true), // debugDataStorage
            mockk(relaxed = true), // geocodingService
            mockk(relaxed = true), // privacyGate
            explainPendingReviewUseCase,
            suggestCategoryFallbackUseCase,
            suggestReceiptExtractionUseCase,
            judgePendingReviewDuplicateUseCase,
            mockk(relaxed = true), // aiArtifactRepository
            aiSettingsRepository,
            mockk(relaxed = true), // aiRuntimeDiagnostics
            receiptLifecycleCoordinator = mockk(relaxed = true),
            receiptDebugExporter = mockk(relaxed = true)
        )
    }

    @After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                if (::viewModel.isInitialized) {
                    viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            super.tearDown()
        }
    }

    private inline fun withPlantedLogs(block: (MutableList<Pair<Throwable?, String>>) -> Unit) {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            block(logs)
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun assertNoLeak(logs: List<Pair<Throwable?, String>>) {
        assertTrue("expected a bounded log line", logs.any { it.second.contains("IllegalStateException") })
        assertTrue(
            "no log may carry a throwable or the sentinel",
            logs.all { it.first == null && !it.second.contains(sentinel) }
        )
    }

    private fun assistStates() = viewModel.reviewCaptureAssistStates.value[reviewId]

    // ── CancellationException: no Error state, in-flight released ─────────────

    @Test
    fun `category assist cancellation publishes no Error and releases in-flight guard`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { suggestCategoryFallbackUseCase(item, any()) } throws CancellationException(sentinel)

        viewModel.requestCategoryAssist(reviewId)
        advanceUntilIdle()

        assertFalse(assistStates()?.categorySuggestion is AiLoadState.Error)
        // Second request only reaches the use case again when the in-flight key was released.
        viewModel.requestCategoryAssist(reviewId)
        advanceUntilIdle()
        coVerify(exactly = 2) { suggestCategoryFallbackUseCase(item, any()) }
    }

    @Test
    fun `receipt assist cancellation publishes no Error and releases in-flight guard`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { suggestReceiptExtractionUseCase(receiptId, any()) } throws CancellationException(sentinel)

        viewModel.requestReceiptAssist(reviewId)
        advanceUntilIdle()

        assertFalse(assistStates()?.receiptSuggestion is AiLoadState.Error)
        viewModel.requestReceiptAssist(reviewId)
        advanceUntilIdle()
        coVerify(exactly = 2) { suggestReceiptExtractionUseCase(receiptId, any()) }
    }

    @Test
    fun `dedupe assist cancellation publishes no Error and releases in-flight guard`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { judgePendingReviewDuplicateUseCase(item, any()) } throws CancellationException(sentinel)

        viewModel.requestDedupeAssist(reviewId)
        advanceUntilIdle()

        assertFalse(assistStates()?.dedupeSuggestion is AiLoadState.Error)
        viewModel.requestDedupeAssist(reviewId)
        advanceUntilIdle()
        coVerify(exactly = 2) { judgePendingReviewDuplicateUseCase(item, any()) }
    }

    @Test
    fun `ai explanation cancellation publishes no Error and releases in-flight guard`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { explainPendingReviewUseCase(any()) } throws CancellationException(sentinel)

        viewModel.loadAiExplanation(reviewId)
        advanceUntilIdle()

        assertFalse(viewModel.aiExplanationStates.value[reviewId] is AiLoadState.Error)
        viewModel.loadAiExplanation(reviewId)
        advanceUntilIdle()
        coVerify(exactly = 2) { explainPendingReviewUseCase(any()) }
    }

    // ── Generic exception: bounded UI state, no throwable / message logged ────

    @Test
    fun `category assist generic exception logs no throwable or message`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { suggestCategoryFallbackUseCase(item, any()) } throws IllegalStateException(sentinel)

        withPlantedLogs { logs ->
            viewModel.requestCategoryAssist(reviewId)
            advanceUntilIdle()

            assertEquals(AiLoadState.Error("AI assist failed"), assistStates()?.categorySuggestion)
            assertNoLeak(logs)
        }
    }

    @Test
    fun `receipt assist generic exception logs no throwable or message`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { suggestReceiptExtractionUseCase(receiptId, any()) } throws IllegalStateException(sentinel)

        withPlantedLogs { logs ->
            viewModel.requestReceiptAssist(reviewId)
            advanceUntilIdle()

            assertEquals(AiLoadState.Error("AI assist failed"), assistStates()?.receiptSuggestion)
            assertNoLeak(logs)
        }
    }

    @Test
    fun `dedupe assist generic exception logs no throwable or message`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { judgePendingReviewDuplicateUseCase(item, any()) } throws IllegalStateException(sentinel)

        withPlantedLogs { logs ->
            viewModel.requestDedupeAssist(reviewId)
            advanceUntilIdle()

            assertEquals(AiLoadState.Error("AI assist failed"), assistStates()?.dedupeSuggestion)
            assertNoLeak(logs)
        }
    }

    @Test
    fun `ai explanation generic exception logs no throwable or message`() = runTest(testDispatcher) {
        advanceUntilIdle()
        coEvery { explainPendingReviewUseCase(any()) } throws IllegalStateException(sentinel)

        withPlantedLogs { logs ->
            viewModel.loadAiExplanation(reviewId)
            advanceUntilIdle()

            assertTrue(viewModel.aiExplanationStates.value[reviewId] is AiLoadState.Error)
            assertNoLeak(logs)
        }
    }
}
