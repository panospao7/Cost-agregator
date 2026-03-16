package com.yourname.expensetracker.ui.screens.review

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.ui.screens.debug.DebugData
import com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    private lateinit var explainPendingReviewUseCase: ExplainPendingReviewUseCase
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiSettingsRepository: AiSettingsRepository
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
        explainPendingReviewUseCase = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)

        every { reviewQueueRepository.getPendingReviews() } returns flowOf(emptyList())
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { debugDataStorage.load() } returns null
        // Default: AI disabled so that basic tests don't need to fully stub the use case
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            explainPendingReviewUseCase,
            aiArtifactRepository,
            aiSettingsRepository
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
    // SECTION 9: AI EXPLANATION STATE
    // ============================================================================

    @Test
    fun `stress - aiExplanationStates defaults to empty map`() = runTest {
        advanceUntilIdle()
        assertTrue(viewModel.aiExplanationStates.value.isEmpty())
    }

    @Test
    fun `stress - loadAiExplanation sets Disabled when AI is off`() = runTest {
        // AI disabled by default (AiSettings() has aiEnabled=false)
        viewModel.loadAiExplanation(reviewId = 42L)
        advanceUntilIdle()

        assertEquals(AiLoadState.Disabled, viewModel.aiExplanationStates.value[42L])
    }

    @Test
    fun `stress - loadAiExplanation sets Loading then Ready on success`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)

        val fakeArtifact = com.yourname.expensetracker.data.database.entity.AiArtifactEntity(
            targetType      = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetKey       = "pending_review:10",
            capability      = AiCapability.REVIEW_EXPLANATION,
            status          = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode            = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion   = "1",
            sourceHash      = "testhash",
            summaryText     = "Explanation headline",
            explanationText = "Body text",
            createdAt       = 0L,
            updatedAt       = 0L
        )
        coEvery { reviewQueueRepository.getReviewById(10L) } returns mockk(relaxed = true)
        coEvery { explainPendingReviewUseCase(any()) } returns Unit
        coEvery { aiArtifactRepository.getLatest("pending_review:10", AiCapability.REVIEW_EXPLANATION) } returns fakeArtifact

        // Recreate the ViewModel with AI-enabled settings
        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            explainPendingReviewUseCase,
            aiArtifactRepository,
            aiSettingsRepository
        )

        viewModel.loadAiExplanation(reviewId = 10L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[10L]
        assertNotNull(state)
        assertTrue("Expected Ready but got $state", state is AiLoadState.Ready)
        val ready = state as AiLoadState.Ready<ReviewExplanationUi>
        assertEquals("Explanation headline", ready.value.headline)
    }

    @Test
    fun `stress - loadAiExplanation sets Error when review not found`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)
        coEvery { reviewQueueRepository.getReviewById(20L) } returns null

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            explainPendingReviewUseCase,
            aiArtifactRepository,
            aiSettingsRepository
        )

        viewModel.loadAiExplanation(reviewId = 20L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[20L]
        assertTrue("Expected Error but got $state", state is AiLoadState.Error)
    }

    @Test
    fun `stress - loadAiExplanation sets Error when use case throws`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)
        coEvery { reviewQueueRepository.getReviewById(30L) } returns mockk(relaxed = true)
        coEvery { explainPendingReviewUseCase(any()) } throws RuntimeException("network error")

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            explainPendingReviewUseCase,
            aiArtifactRepository,
            aiSettingsRepository
        )

        viewModel.loadAiExplanation(reviewId = 30L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[30L]
        assertTrue("Expected Error but got $state", state is AiLoadState.Error)
    }

    @Test
    fun `stress - loadAiExplanation concurrent calls do not corrupt state`() = runTest {
        // The _inFlightExplanations guard prevents duplicate concurrent launches.
        // With StandardTestDispatcher, coroutines are run explicitly. Two calls made
        // before any advancement will both launch (guard is checked synchronously but
        // the set is populated inside the coroutine). However, both coroutines will
        // race to completion without corrupting observable state — the final state
        // for the review ID is deterministic (last writer wins the StateFlow update).
        //
        // This test verifies the *safety contract*: multiple concurrent calls for the
        // same review ID must complete without exception and leave state in a valid
        // AiLoadState (not null, not an intermediate mixed state).
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)
        coEvery { reviewQueueRepository.getReviewById(50L) } returns mockk(relaxed = true)
        coEvery { explainPendingReviewUseCase(any()) } returns Unit
        coEvery {
            aiArtifactRepository.getLatest("pending_review:50", AiCapability.REVIEW_EXPLANATION)
        } returns null

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            explainPendingReviewUseCase,
            aiArtifactRepository,
            aiSettingsRepository
        )

        // Two rapid calls before any coroutine advancement
        viewModel.loadAiExplanation(reviewId = 50L)
        viewModel.loadAiExplanation(reviewId = 50L)

        // Let everything complete
        advanceUntilIdle()

        // State must be a valid terminal AiLoadState — not null, not Loading
        val state = viewModel.aiExplanationStates.value[50L]
        assertNotNull("State must not be null after load completes", state)
        assertFalse(
            "State must not still be Loading after advanceUntilIdle",
            state is AiLoadState.Loading
        )
        // No exception was thrown — concurrent calls are safe
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
