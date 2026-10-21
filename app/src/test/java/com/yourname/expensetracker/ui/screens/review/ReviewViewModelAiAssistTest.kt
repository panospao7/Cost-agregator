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
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeGenerationResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.debug.DebugData
import com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelAiAssistTest {

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
    private lateinit var suggestCategoryFallbackUseCase: SuggestCategoryFallbackUseCase
    private lateinit var suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase
    private lateinit var judgePendingReviewDuplicateUseCase: JudgePendingReviewDuplicateUseCase
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var receiptLifecycleCoordinator: ReceiptLifecycleCoordinator
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
        suggestCategoryFallbackUseCase = mockk(relaxed = true)
        suggestReceiptExtractionUseCase = mockk(relaxed = true)
        judgePendingReviewDuplicateUseCase = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(mockk<TimeProvider> { every { now() } returns 1_000L }))
        receiptLifecycleCoordinator = mockk(relaxed = true)

        every { reviewQueueRepository.getAllPendingReviews() } returns flowOf(emptyList())
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { debugDataStorage.load() } returns null
        // Default: AI disabled so that basic tests don't need to fully stub the use case
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())

        viewModel = newViewModel(receiptLifecycleCoordinator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(coordinator: ReceiptLifecycleCoordinator): ReviewViewModel =
        ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            mockk(relaxed = true),
            explainPendingReviewUseCase,
            suggestCategoryFallbackUseCase,
            suggestReceiptExtractionUseCase,
            judgePendingReviewDuplicateUseCase,
            aiArtifactRepository,
            aiSettingsRepository,
            aiRuntimeDiagnostics,
            receiptLifecycleCoordinator = coordinator,
            receiptDebugExporter = mockk(relaxed = true)
        )

    // ============================================================================
    // APPROVE REVIEW
    // ============================================================================

    @Test
    fun `approve review duplicate`() = runTest {
        coEvery { reviewQueueRepository.approveReview(1) } returns Result.Duplicate

        viewModel.approveReview(1)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Duplicate transaction detected", viewModel.errorMessage.value)
    }

    @Test
    fun `approve review error`() = runTest {
        coEvery { reviewQueueRepository.approveReview(1) } returns Result.Error(Exception("DB error"), "Failed")

        viewModel.approveReview(1)

        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun `approveReviewWithEdits short-circuits bulk updates on duplicate`() = runTest {
        coEvery {
            reviewQueueRepository.approveReview(
                reviewId = 90L,
                finalAmount = 12.0,
                finalMerchant = "Edited",
                finalCategoryId = 5L,
                finalDate = 1000L,
                finalType = null,
                finalLatitude = null,
                finalLongitude = null,
                finalAddress = null,
                finalPlaceId = null
            )
        } returns Result.Duplicate

        viewModel.approveReviewWithEdits(
            reviewId = 90L,
            finalAmount = 12.0,
            finalMerchant = "Edited",
            finalCategoryId = 5L,
            finalDate = 1000L,
            finalType = null,
            applyToAll = true,
            approveAllPending = true
        )

        advanceUntilIdle()

        // Note: production fetches the original review first, so getReviewById is not asserted.
        coVerify(exactly = 0) { reviewQueueRepository.getPendingReviewsByMerchant(any()) }
        coVerify(exactly = 0) { expenseRepository.updateExpenseCategoryBulk(any(), any()) }
        coVerify(exactly = 0) { expenseRepository.updateExpenseMerchantBulk(any(), any()) }
        assertEquals("Duplicate transaction detected", viewModel.errorMessage.value)
    }

    @Test
    fun `approveReviewWithEdits threads place id to repository`() = runTest {
        coEvery {
            reviewQueueRepository.approveReview(
                reviewId = 91L,
                finalAmount = 20.0,
                finalMerchant = "Merchant",
                finalCategoryId = 2L,
                finalDate = 2000L,
                finalType = null,
                finalLatitude = 37.98,
                finalLongitude = 23.72,
                finalAddress = "Athens",
                finalPlaceId = "N123"
            )
        } returns Result.Success(91L)

        viewModel.approveReviewWithEdits(
            reviewId = 91L,
            finalAmount = 20.0,
            finalMerchant = "Merchant",
            finalCategoryId = 2L,
            finalDate = 2000L,
            finalType = null,
            finalLatitude = 37.98,
            finalLongitude = 23.72,
            finalAddress = "Athens",
            finalPlaceId = "N123"
        )

        advanceUntilIdle()

        coVerify {
            reviewQueueRepository.approveReview(
                reviewId = 91L,
                finalAmount = 20.0,
                finalMerchant = "Merchant",
                finalCategoryId = 2L,
                finalDate = 2000L,
                finalType = null,
                finalLatitude = 37.98,
                finalLongitude = 23.72,
                finalAddress = "Athens",
                finalPlaceId = "N123"
            )
        }
    }

    // ============================================================================
    // AI EXPLANATION
    // ============================================================================

    @Test
    fun `loadAiExplanation sets Disabled when AI is off`() = runTest {
        // AI disabled by default (AiSettings() has aiEnabled=false)
        viewModel.loadAiExplanation(reviewId = 42L)
        advanceUntilIdle()

        assertEquals(AiLoadState.Disabled, viewModel.aiExplanationStates.value[42L])
    }

    @Test
    fun `loadAiExplanation sets Loading then Ready on success`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)

        val fakeArtifact = com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType      = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetKey       = "pending_review:10",
            capability      = AiCapability.REVIEW_EXPLANATION,
            status          = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode            = com.yourname.expensetracker.domain.ai.model.AiMode.CLOUD,
            provider        = "google-ai-studio",
            modelName       = "gemini-2.5-flash",
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
        viewModel = newViewModel(mockk())

        viewModel.loadAiExplanation(reviewId = 10L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[10L]
        assertNotNull(state)
        assertTrue("Expected Ready but got $state", state is AiLoadState.Ready)
        val ready = state as AiLoadState.Ready<ReviewExplanationUi>
        assertEquals("Explanation headline", ready.value.headline)
        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", ready.value.diagnostics)
    }

    @Test
    fun `loadAiExplanation surfaces on-device diagnostics when artifact is local`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)

        val fakeArtifact = com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetKey = "pending_review:11",
            capability = AiCapability.REVIEW_EXPLANATION,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano-review",
            promptVersion = "1",
            sourceHash = "testhash",
            summaryText = "Explanation headline",
            explanationText = "Explanation body",
            createdAt = 0L,
            updatedAt = 0L,
            expiresAt = Long.MAX_VALUE
        )
        coEvery { reviewQueueRepository.getReviewById(11L) } returns mockk(relaxed = true)
        coEvery { explainPendingReviewUseCase(any()) } returns Unit
        coEvery { aiArtifactRepository.getLatest("pending_review:11", AiCapability.REVIEW_EXPLANATION) } returns fakeArtifact

        viewModel = newViewModel(mockk())

        viewModel.loadAiExplanation(reviewId = 11L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[11L]
        assertNotNull(state)
        assertTrue(state is AiLoadState.Ready)
        val ready = state as AiLoadState.Ready<ReviewExplanationUi>
        assertEquals("On-device - mlkit-genai-nano - gemini-nano-review", ready.value.diagnostics)
    }

    @Test
    fun `loadAiExplanation sets Error when review not found`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)
        coEvery { reviewQueueRepository.getReviewById(20L) } returns null

        viewModel = newViewModel(mockk())

        viewModel.loadAiExplanation(reviewId = 20L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[20L]
        assertTrue("Expected Error but got $state", state is AiLoadState.Error)
    }

    @Test
    fun `loadAiExplanation sets Error when use case throws`() = runTest {
        val enabledSettings = AiSettings(
            aiEnabled = true,
            reviewExplanationEnabled = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings)
        coEvery { reviewQueueRepository.getReviewById(30L) } returns mockk(relaxed = true)
        coEvery { explainPendingReviewUseCase(any()) } throws RuntimeException("network error")

        viewModel = newViewModel(mockk())

        viewModel.loadAiExplanation(reviewId = 30L)
        advanceUntilIdle()

        val state = viewModel.aiExplanationStates.value[30L]
        assertTrue("Expected Error but got $state", state is AiLoadState.Error)
    }

    @Test
    fun `loadAiExplanation concurrent calls do not corrupt state`() = runTest {
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

        viewModel = newViewModel(mockk())

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
    // ASSIST DIAGNOSTICS
    // ============================================================================

    @Test
    fun `requestCategoryAssist stores Ready state`() = runTest {
        val item = PendingReviewWithReceipt(
            review = mockk(relaxed = true) {
                every { id } returns 60L
            },
            receipt = null
        )
        val reviewsFlow = MutableStateFlow(listOf(item))
        every { reviewQueueRepository.getAllPendingReviews() } returns reviewsFlow
        coEvery { reviewQueueRepository.getPendingReviewWithReceiptById(60L) } returns item
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, categorizationFallbackEnabled = true)
        )
        coEvery { suggestCategoryFallbackUseCase(item, false) } returns CategoryAssistGenerationResult.Success(
            suggestion = CategoryAssistSuggestion(
                categoryId = 1L,
                categoryName = "Groceries",
                rationale = "supermarket merchant"
            ),
            fromCache = false
        )
        coEvery {
            aiArtifactRepository.getLatest("pending_review:60", AiCapability.CATEGORIZATION_FALLBACK)
        } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 60L,
            targetKey = "pending_review:60",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        viewModel = newViewModel(mockk())

        advanceUntilIdle()
        viewModel.requestCategoryAssist(60L)
        advanceUntilIdle()

        val state = viewModel.reviewCaptureAssistStates.value[60L]?.categorySuggestion
        assertTrue(state is AiLoadState.Ready)
        assertEquals(
            "On-device - mlkit-genai-nano - gemini-nano",
            viewModel.reviewCaptureAssistStates.value[60L]?.categoryDiagnostics
        )
    }

    @Test
    fun `requestCategoryAssist keeps failed artifact diagnostics on error`() = runTest {
        val item = PendingReviewWithReceipt(
            review = mockk(relaxed = true) {
                every { id } returns 62L
            },
            receipt = null
        )
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))
        coEvery { reviewQueueRepository.getPendingReviewWithReceiptById(62L) } returns item
        coEvery { suggestCategoryFallbackUseCase(item, false) } returns CategoryAssistGenerationResult.Error(
            "AI category assist failed."
        )
        coEvery {
            aiArtifactRepository.getLatest("pending_review:62", AiCapability.CATEGORIZATION_FALLBACK)
        } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 62L,
            targetKey = "pending_review:62",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.FAILED,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            sourceHash = "hash",
            errorMessage = "backend error",
            createdAt = 0L,
            updatedAt = 0L
        )

        viewModel = newViewModel(mockk())

        advanceUntilIdle()
        viewModel.requestCategoryAssist(62L)
        advanceUntilIdle()

        val state = viewModel.reviewCaptureAssistStates.value[62L]?.categorySuggestion
        assertTrue(state is AiLoadState.Error)
        assertEquals(
            "Cloud - google-ai-studio - gemini-2.5-flash",
            viewModel.reviewCaptureAssistStates.value[62L]?.categoryDiagnostics
        )
    }

    @Test
    fun `requestDedupeAssist stores Ready state`() = runTest {
        val item = PendingReviewWithReceipt(
            review = mockk(relaxed = true) {
                every { id } returns 61L
            },
            receipt = null
        )
        val reviewsFlow = MutableStateFlow(listOf(item))
        every { reviewQueueRepository.getAllPendingReviews() } returns reviewsFlow
        coEvery { reviewQueueRepository.getPendingReviewWithReceiptById(61L) } returns item
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, dedupeJudgeEnabled = true)
        )
        coEvery { judgePendingReviewDuplicateUseCase(item, false) } returns DedupeJudgeGenerationResult.Success(
            suggestion = DedupeJudgeSuggestion(
                verdict = DuplicateVerdict.UNCERTAIN,
                rationale = "two similar matches"
            ),
            fromCache = false
        )
        coEvery {
            aiArtifactRepository.getLatest("pending_review:61", AiCapability.DEDUPE_JUDGE)
        } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 61L,
            targetKey = "pending_review:61",
            capability = AiCapability.DEDUPE_JUDGE,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano-dedupe",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        viewModel = newViewModel(mockk())

        advanceUntilIdle()
        viewModel.requestDedupeAssist(61L)
        advanceUntilIdle()

        val state = viewModel.reviewCaptureAssistStates.value[61L]?.dedupeSuggestion
        assertTrue(state is AiLoadState.Ready)
        assertEquals(
            "On-device - mlkit-genai-nano - gemini-nano-dedupe",
            viewModel.reviewCaptureAssistStates.value[61L]?.dedupeDiagnostics
        )
    }

    @Test
    fun `requestDedupeAssist keeps failed artifact diagnostics on error`() = runTest {
        val item = PendingReviewWithReceipt(
            review = mockk(relaxed = true) {
                every { id } returns 63L
            },
            receipt = null
        )
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))
        coEvery { reviewQueueRepository.getPendingReviewWithReceiptById(63L) } returns item
        coEvery { judgePendingReviewDuplicateUseCase(item, false) } returns DedupeJudgeGenerationResult.Error(
            "AI duplicate assist failed."
        )
        coEvery {
            aiArtifactRepository.getLatest("pending_review:63", AiCapability.DEDUPE_JUDGE)
        } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 63L,
            targetKey = "pending_review:63",
            capability = AiCapability.DEDUPE_JUDGE,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.FAILED,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano-dedupe",
            promptVersion = "v1",
            sourceHash = "hash",
            errorMessage = "backend error",
            createdAt = 0L,
            updatedAt = 0L
        )

        viewModel = newViewModel(mockk())

        advanceUntilIdle()
        viewModel.requestDedupeAssist(63L)
        advanceUntilIdle()

        val state = viewModel.reviewCaptureAssistStates.value[63L]?.dedupeSuggestion
        assertTrue(state is AiLoadState.Error)
        assertEquals(
            "On-device - mlkit-genai-nano - gemini-nano-dedupe",
            viewModel.reviewCaptureAssistStates.value[63L]?.dedupeDiagnostics
        )
    }

    // ============================================================================
    // APPLY CATEGORY SUGGESTION
    // ============================================================================

    private fun category(id: Long, name: String) =
        Category(id = id, name = name, icon = "X", color = "#112233")

    @Test
    fun `applyCategorySuggestion emits OpenEditWithPrefill and does not mark artifact applied`() = runTest {
        every { categoryRepository.allCategories } returns flowOf(listOf(category(7L, "Transport")))
        val viewModelState = com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
            categorySuggestion = AiLoadState.Ready(
                CategoryAssistSuggestion(categoryId = 7L, categoryName = "Transport")
            )
        )

        viewModel = newViewModel(mockk())

        setAssistStates(mapOf(70L to viewModelState))
        coEvery {
            aiArtifactRepository.getLatest("pending_review:70", AiCapability.CATEGORIZATION_FALLBACK)
        } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            id = 10L,
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 70L,
            targetKey = "pending_review:70",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        // categories is WhileSubscribed and uiEvents has no replay: subscribe to both first.
        backgroundScope.launch { viewModel.categories.collect { } }
        val events = mutableListOf<ReviewUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiEvents.collect { events.add(it) } }
        advanceUntilIdle()
        assertEquals(listOf(7L), viewModel.categories.value.map { it.id })

        viewModel.applyCategorySuggestion(70L)
        runCurrent()

        assertEquals(
            listOf<ReviewUiEvent>(ReviewUiEvent.OpenEditWithPrefill(reviewId = 70L, categoryId = 7L)),
            events
        )
        viewModel.onEvent(ReviewEvent.ConsumePrefilledCategorySuggestion(70L))
        advanceUntilIdle()
        // Artifact is marked applied only after a successful save, never on apply.
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(10L) }
    }

    // ============================================================================
    // QUICK APPROVE
    // ============================================================================

    @Test
    fun `requestQuickApprovePreview opens when category assist is ready`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, reviewQuickApproveEnabled = true))
        every { categoryRepository.allCategories } returns flowOf(listOf(category(4L, "Groceries")))
        val item = PendingReviewWithReceipt(
            review = mockk(relaxed = true) {
                every { id } returns 71L
                every { suggestedMerchant } returns "Lidl"
                every { suggestedAmount } returns 12.34
                every { suggestedCurrency } returns "EUR"
            },
            receipt = null
        )
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))

        viewModel = newViewModel(mockk())

        setAssistStates(
            mapOf(
                71L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    categorySuggestion = AiLoadState.Ready(CategoryAssistSuggestion(4L, "Groceries")),
                    categoryDiagnostics = "Cloud - google-ai-studio - gemini-2.5-flash",
                    dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(DuplicateVerdict.LIKELY_DISTINCT))
                )
            )
        )

        backgroundScope.launch { viewModel.categories.collect { } }
        advanceUntilIdle()
        assertTrue(viewModel.canOfferQuickApprove(71L))

        viewModel.requestQuickApprovePreview(71L)

        assertEquals(71L, viewModel.quickApprovePreview.value?.reviewId)
        assertEquals("Groceries", viewModel.quickApprovePreview.value?.categoryName)
    }

    private fun setAssistStates(
        states: Map<Long, com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState>
    ) {
        val field = ReviewViewModel::class.java.getDeclaredField("_reviewCaptureAssistStates")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<Map<Long, com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState>>
        stateFlow.value = states
    }

    /** A pending review that satisfies the S6-008 data-validity gates of canOfferQuickApprove. */
    private fun quickApprovableReview(reviewId: Long) = PendingReviewWithReceipt(
        review = mockk(relaxed = true) {
            every { id } returns reviewId
            every { suggestedMerchant } returns "Lidl"
            every { suggestedAmount } returns 12.34
            every { suggestedCurrency } returns "EUR"
        },
        receipt = null
    )

    /** One-condition-broken row for the quick-approve negative table. */
    private data class QuickApproveRow(
        val name: String,
        val verdict: DuplicateVerdict = DuplicateVerdict.LIKELY_DISTINCT,
        val amount: Double? = 12.34,
        val merchant: String = "Lidl",
        val currency: String = "EUR",
        val suggestedCategoryId: Long = 4L,
        val knownCategoryIds: List<Long> = listOf(4L),
        val expectOpen: Boolean = false
    )

    @Test
    fun `quick approve preview stays closed when any single eligibility condition is broken`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, reviewQuickApproveEnabled = true))
        val rows = listOf(
            QuickApproveRow("positive control: every condition valid", expectOpen = true),
            QuickApproveRow("dedupe LIKELY_DUPLICATE", verdict = DuplicateVerdict.LIKELY_DUPLICATE),
            QuickApproveRow("dedupe UNCERTAIN (S6-010)", verdict = DuplicateVerdict.UNCERTAIN),
            QuickApproveRow("amount null", amount = null),
            QuickApproveRow("amount zero", amount = 0.0),
            QuickApproveRow("amount negative", amount = -5.0),
            QuickApproveRow("merchant blank", merchant = "   "),
            QuickApproveRow("merchant Unknown", merchant = "Unknown"),
            QuickApproveRow("currency blank (S6-008)", currency = " "),
            QuickApproveRow("categoryId zero", suggestedCategoryId = 0L, knownCategoryIds = listOf(0L, 4L)),
            QuickApproveRow("categoryId negative", suggestedCategoryId = -1L, knownCategoryIds = listOf(-1L, 4L)),
            QuickApproveRow("category not in categories list", suggestedCategoryId = 4L, knownCategoryIds = listOf(9L))
        )

        for (row in rows) {
            every { categoryRepository.allCategories } returns
                flowOf(row.knownCategoryIds.map { category(it, "Cat$it") })
            val item = PendingReviewWithReceipt(
                review = mockk(relaxed = true) {
                    every { id } returns 80L
                    every { suggestedMerchant } returns row.merchant
                    every { suggestedAmount } returns row.amount
                    every { suggestedCurrency } returns row.currency
                },
                receipt = null
            )
            every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))

            viewModel = newViewModel(mockk())
            setAssistStates(
                mapOf(
                    80L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                        categorySuggestion = AiLoadState.Ready(
                            CategoryAssistSuggestion(row.suggestedCategoryId, "Cat${row.suggestedCategoryId}")
                        ),
                        dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(row.verdict))
                    )
                )
            )
            backgroundScope.launch { viewModel.categories.collect { } }
            advanceUntilIdle()

            if (row.expectOpen) {
                assertTrue("canOfferQuickApprove must be true for: ${row.name}", viewModel.canOfferQuickApprove(80L))
                viewModel.requestQuickApprovePreview(80L)
                advanceUntilIdle()
                assertNotNull("preview must open for: ${row.name}", viewModel.quickApprovePreview.value)
                continue
            }

            assertFalse("canOfferQuickApprove must be false for: ${row.name}", viewModel.canOfferQuickApprove(80L))
            viewModel.requestQuickApprovePreview(80L)
            advanceUntilIdle()
            assertNull("preview must stay closed for: ${row.name}", viewModel.quickApprovePreview.value)

            viewModel.confirmQuickApprove()
            advanceUntilIdle()
        }

        coVerify(exactly = 0) {
            reviewQueueRepository.approveReview(
                reviewId = any(),
                finalAmount = any(),
                finalMerchant = any(),
                finalCategoryId = any(),
                finalDate = any(),
                finalType = any(),
                finalLatitude = any(),
                finalLongitude = any(),
                finalAddress = any(),
                finalPlaceId = any()
            )
        }
    }

    private fun categoryArtifact(id: Long, reviewId: Long) =
        com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            id = id,
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = reviewId,
            targetKey = "pending_review:$reviewId",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

    private fun approveWithCategory4(reviewId: Long) = coEvery {
        reviewQueueRepository.approveReview(
            reviewId = reviewId,
            finalAmount = null,
            finalMerchant = null,
            finalCategoryId = 4L,
            finalDate = null,
            finalType = null,
            finalLatitude = null,
            finalLongitude = null,
            finalAddress = null,
            finalPlaceId = null
        )
    }

    @Test
    fun `confirmQuickApprove approves review with AI category`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, reviewQuickApproveEnabled = true))
        every { categoryRepository.allCategories } returns flowOf(listOf(category(4L, "Groceries")))
        val item = quickApprovableReview(72L)
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))
        approveWithCategory4(72L) returns Result.Success(20L)
        coEvery { aiArtifactRepository.getLatest("pending_review:72", AiCapability.CATEGORIZATION_FALLBACK) } returns categoryArtifact(21L, 72L)

        viewModel = newViewModel(mockk())

        setAssistStates(
            mapOf(
                72L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    categorySuggestion = AiLoadState.Ready(CategoryAssistSuggestion(4L, "Groceries")),
                    dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(DuplicateVerdict.LIKELY_DISTINCT))
                )
            )
        )

        backgroundScope.launch { viewModel.categories.collect { } }
        advanceUntilIdle()
        viewModel.requestQuickApprovePreview(72L)
        viewModel.confirmQuickApprove()
        advanceUntilIdle()

        coVerify {
            reviewQueueRepository.approveReview(
                reviewId = 72L,
                finalAmount = null,
                finalMerchant = null,
                finalCategoryId = 4L,
                finalDate = null,
                finalType = null,
                finalLatitude = null,
                finalLongitude = null,
                finalAddress = null,
                finalPlaceId = null
            )
        }
        coVerify { aiArtifactRepository.markApplied(21L) }
        verify { aiRuntimeDiagnostics.recordInteraction(type = "phase4_accept", message = any(), now = any()) }
    }

    @Test
    fun `confirmQuickApprove marks dedupe artifact applied when assist was used`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true, reviewQuickApproveEnabled = true))
        every { categoryRepository.allCategories } returns flowOf(listOf(category(4L, "Groceries")))
        val item = quickApprovableReview(73L)
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))
        approveWithCategory4(73L) returns Result.Success(20L)
        coEvery { aiArtifactRepository.getLatest("pending_review:73", AiCapability.CATEGORIZATION_FALLBACK) } returns categoryArtifact(31L, 73L)
        coEvery { aiArtifactRepository.getLatest("pending_review:73", AiCapability.DEDUPE_JUDGE) } returns com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            id = 32L,
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 73L,
            targetKey = "pending_review:73",
            capability = AiCapability.DEDUPE_JUDGE,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        viewModel = newViewModel(mockk())

        // S6-010: quick approve requires an explicit LIKELY_DISTINCT dedupe verdict.
        setAssistStates(
            mapOf(
                73L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    categorySuggestion = AiLoadState.Ready(CategoryAssistSuggestion(4L, "Groceries")),
                    dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(DuplicateVerdict.LIKELY_DISTINCT))
                )
            )
        )

        backgroundScope.launch { viewModel.categories.collect { } }
        advanceUntilIdle()
        viewModel.requestQuickApprovePreview(73L)
        viewModel.confirmQuickApprove()
        advanceUntilIdle()

        coVerify { aiArtifactRepository.markApplied(31L) }
        coVerify { aiArtifactRepository.markApplied(32L) }
    }

    @Test
    fun `confirmQuickApprove stops when toggle turns off`() = runTest {
        val settingsFlow = MutableStateFlow(AiSettings(aiEnabled = true, reviewQuickApproveEnabled = true))
        every { aiSettingsRepository.settings() } returns settingsFlow
        every { categoryRepository.allCategories } returns flowOf(listOf(category(4L, "Groceries")))
        val item = quickApprovableReview(74L)
        every { reviewQueueRepository.getAllPendingReviews() } returns MutableStateFlow(listOf(item))

        viewModel = newViewModel(mockk())

        setAssistStates(
            mapOf(
                74L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    categorySuggestion = AiLoadState.Ready(CategoryAssistSuggestion(4L, "Groceries")),
                    dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(DuplicateVerdict.LIKELY_DISTINCT))
                )
            )
        )

        backgroundScope.launch { viewModel.categories.collect { } }
        advanceUntilIdle()
        viewModel.requestQuickApprovePreview(74L)
        assertNotNull(viewModel.quickApprovePreview.value)

        settingsFlow.value = AiSettings(aiEnabled = true, reviewQuickApproveEnabled = false)
        advanceUntilIdle()

        assertNull(viewModel.quickApprovePreview.value)
        viewModel.confirmQuickApprove()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            reviewQueueRepository.approveReview(
                reviewId = any(),
                finalAmount = any(),
                finalMerchant = any(),
                finalCategoryId = any(),
                finalDate = any(),
                finalType = any(),
                finalLatitude = any(),
                finalLongitude = any(),
                finalAddress = any(),
                finalPlaceId = any()
            )
        }
    }

    // ============================================================================
    // DISMISS ARTIFACT
    // ============================================================================

    @Test
    fun `dismissCategoryAssist resets state and marks artifact dismissed`() = runTest {
        val artifact = com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            id = 8L,
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 80L,
            targetKey = "pending_review:80",
            capability = com.yourname.expensetracker.domain.ai.model.AiCapability.CATEGORIZATION_FALLBACK,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { aiArtifactRepository.getLatest("pending_review:80", com.yourname.expensetracker.domain.ai.model.AiCapability.CATEGORIZATION_FALLBACK) } returns artifact

        setAssistStates(
            mapOf(
                80L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    categorySuggestion = AiLoadState.Ready(CategoryAssistSuggestion(1L, "Groceries")),
                    categoryDiagnostics = "On-device - mlkit-genai-nano - gemini-nano"
                )
            )
        )

        viewModel.dismissCategoryAssist(80L)
        advanceUntilIdle()

        coVerify { aiArtifactRepository.markDismissed(8L) }
        assertEquals(AiLoadState.Idle, viewModel.reviewCaptureAssistStates.value[80L]?.categorySuggestion)
        assertNull(viewModel.reviewCaptureAssistStates.value[80L]?.categoryDiagnostics)
    }

    @Test
    fun `dismissDedupeAssist resets state and marks artifact dismissed`() = runTest {
        val artifact = com.yourname.expensetracker.domain.dto.AiArtifactRecord(
            id = 9L,
            targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
            targetId = 81L,
            targetKey = "pending_review:81",
            capability = com.yourname.expensetracker.domain.ai.model.AiCapability.DEDUPE_JUDGE,
            status = com.yourname.expensetracker.domain.ai.model.AiArtifactStatus.READY,
            mode = com.yourname.expensetracker.domain.ai.model.AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { aiArtifactRepository.getLatest("pending_review:81", com.yourname.expensetracker.domain.ai.model.AiCapability.DEDUPE_JUDGE) } returns artifact

        setAssistStates(
            mapOf(
                81L to com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState(
                    dedupeSuggestion = AiLoadState.Ready(DedupeJudgeSuggestion(DuplicateVerdict.UNCERTAIN)),
                    dedupeDiagnostics = "Cloud - google-ai-studio - gemini-2.5-flash"
                )
            )
        )

        viewModel.dismissDedupeAssist(81L)
        advanceUntilIdle()

        coVerify { aiArtifactRepository.markDismissed(9L) }
        assertEquals(AiLoadState.Idle, viewModel.reviewCaptureAssistStates.value[81L]?.dedupeSuggestion)
        assertNull(viewModel.reviewCaptureAssistStates.value[81L]?.dedupeDiagnostics)
    }
}
