package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeGenerationResult
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.toDisplayText
import com.yourname.expensetracker.domain.ai.model.toDiagnosticsOrNull
import com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState
import com.yourname.expensetracker.domain.ai.model.ReviewReceiptPrefill
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.receipt.lifecycle.BankStatementResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import timber.log.Timber
// ...
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject

/** UI-layer wrapper surfaced per review card. */
data class ReviewExplanationUi(
    val headline: String,
    val body: String,
    val caution: String? = null,
    val isAi: Boolean = true,
    val diagnostics: String? = null
)

data class ReviewQuickApprovePreview(
    val reviewId: Long,
    val merchant: String,
    val amount: Double,
    val categoryId: Long,
    val categoryName: String,
    val diagnostics: List<String>
)

sealed interface ReviewEvent {
    data class ConsumePrefilledCategorySuggestion(val reviewId: Long) : ReviewEvent
    data class ConsumePrefilledReceiptSuggestion(val reviewId: Long) : ReviewEvent
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: com.yourname.expensetracker.data.repository.ReceiptRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val debugDataStorage: com.yourname.expensetracker.ui.screens.debug.DebugDataStorage,
    val geocodingService: com.yourname.expensetracker.domain.location.GeocodingService,
    private val explainPendingReviewUseCase: ExplainPendingReviewUseCase,
    private val suggestCategoryFallbackUseCase: SuggestCategoryFallbackUseCase,
    private val suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase,
    private val judgePendingReviewDuplicateUseCase: JudgePendingReviewDuplicateUseCase,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val receiptLifecycleCoordinator: ReceiptLifecycleCoordinator
) : ViewModel() {
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    /** S6-005: Emits reviewId when approveReviewWithEdits succeeds — screen closes dialog on this. */
    private val _editApproveSuccess = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val editApproveSuccess: kotlinx.coroutines.flow.SharedFlow<Long> = _editApproveSuccess.asSharedFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    val batchProgress = _batchProgress.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing = _isBatchProcessing.asStateFlow()
    
    private val _debugData = MutableStateFlow<com.yourname.expensetracker.ui.screens.debug.DebugData?>(null)
    val debugData = _debugData.asStateFlow()

    // ── AI explanation state ──────────────────────────────────────────────────
    // Map of reviewId → AiLoadState<ReviewExplanationUi>; updated atomically.
    private val _aiExplanationStates =
        MutableStateFlow<Map<Long, AiLoadState<ReviewExplanationUi>>>(emptyMap())
    val aiExplanationStates: StateFlow<Map<Long, AiLoadState<ReviewExplanationUi>>> =
        _aiExplanationStates.asStateFlow()

    private val _reviewCaptureAssistStates =
        MutableStateFlow<Map<Long, ReviewCaptureAssistState>>(emptyMap())
    val reviewCaptureAssistStates: StateFlow<Map<Long, ReviewCaptureAssistState>> =
        _reviewCaptureAssistStates.asStateFlow()

    private val _prefilledCategorySuggestions = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val prefilledCategorySuggestions: StateFlow<Map<Long, Long>> =
        _prefilledCategorySuggestions.asStateFlow()

    private val _prefilledReceiptSuggestions = MutableStateFlow<Map<Long, ReviewReceiptPrefill>>(emptyMap())
    val prefilledReceiptSuggestions: StateFlow<Map<Long, ReviewReceiptPrefill>> =
        _prefilledReceiptSuggestions.asStateFlow()

    private val _quickApprovePreview = MutableStateFlow<ReviewQuickApprovePreview?>(null)
    val quickApprovePreview: StateFlow<ReviewQuickApprovePreview?> =
        _quickApprovePreview.asStateFlow()

    val reviewQuickApproveEnabled: StateFlow<Boolean> = aiSettingsRepository.settings()
        .map { it.aiEnabled && it.reviewQuickApproveEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Tracks review IDs that already have an in-flight coroutine, preventing duplicates. */
    private val _inFlightExplanations = mutableSetOf<Long>()

    /** S6-003: Per-review mutation guard — prevents double-approve/reject. */
    private val _inFlightMutations = mutableSetOf<Long>()

    /** S6-012: Per-review AI assist in-flight guard — key is (reviewId, assistType). */
    private val _inFlightAssist = mutableSetOf<Pair<Long, String>>()

    /** S6-003: Attempt to begin a mutation. Returns false if already in-flight. */
    private fun beginMutation(reviewId: Long): Boolean {
        return _inFlightMutations.add(reviewId)
    }

    /** S6-003: Always call in finally block. */
    private fun endMutation(reviewId: Long) {
        _inFlightMutations.remove(reviewId)
    }

    init {
        // Recover any reviews stuck in PROCESSING state from prior process death
        viewModelScope.launch {
            val recovered = reviewQueueRepository.recoverStuckReviews()
            if (recovered > 0) Timber.w("Recovered %d stuck PROCESSING reviews", recovered)
        }
        // Load saved debug data on startup
        viewModelScope.launch {
            _debugData.value = debugDataStorage.load()
        }
        // Initialise AI explanation states based on current settings
        viewModelScope.launch {
            val settings = aiSettingsRepository.settings().first()
            if (!settings.aiEnabled || !settings.reviewExplanationEnabled) {
                // Pre-set a sentinel so the UI knows AI is globally disabled
                // (individual per-review states default to Idle when absent from map)
                Timber.d("ReviewViewModel: AI explanation disabled in settings.")
            }
        }
        viewModelScope.launch {
            reviewQuickApproveEnabled.collect { enabled ->
                if (!enabled) {
                    _quickApprovePreview.value = null
                }
            }
        }
    }

    private var batchJob: Job? = null

    // ── AI explanation API ────────────────────────────────────────────────────

    /**
     * Triggers on-demand AI explanation for [reviewId].
     *
     * Guards:
     * - Skips if already in-flight for this ID.
     * - Sets [AiLoadState.Loading] immediately so the UI reflects the request.
     * - On completion, reads the artifact from the repository and maps it to
     *   [ReviewExplanationUi], then sets [AiLoadState.Ready] or [AiLoadState.Error].
     * - If AI is disabled in settings, sets [AiLoadState.Disabled] and returns.
     */
    fun loadAiExplanation(reviewId: Long) {
        if (_inFlightExplanations.contains(reviewId)) return

        viewModelScope.launch {
            // Settings gate — check before touching UI state
            val settings = aiSettingsRepository.settings().first()
            if (!settings.aiEnabled || !settings.reviewExplanationEnabled) {
                _aiExplanationStates.update { it + (reviewId to AiLoadState.Disabled) }
                return@launch
            }

            // Mark in-flight and show loading
            _inFlightExplanations.add(reviewId)
            _aiExplanationStates.update { it + (reviewId to AiLoadState.Loading) }

            try {
                // Load the review entity
                val review = reviewQueueRepository.getReviewById(reviewId)
                if (review == null) {
                    _aiExplanationStates.update {
                        it + (reviewId to AiLoadState.Error("Review not found"))
                    }
                    return@launch
                }

                // Run the use case (writes artifact to DB)
                explainPendingReviewUseCase(review)

                // Read the resulting artifact and map to UI model
                val targetKey = "pending_review:$reviewId"
                val artifact = aiArtifactRepository.getLatest(
                    targetKey,
                    com.yourname.expensetracker.domain.ai.model.AiCapability.REVIEW_EXPLANATION
                )

                val newState: AiLoadState<ReviewExplanationUi> = when {
                    artifact == null -> AiLoadState.Error("No artifact produced")
                    artifact.status == AiArtifactStatus.READY &&
                            artifact.summaryText != null -> {
                        AiLoadState.Ready(
                            ReviewExplanationUi(
                                headline = artifact.summaryText,
                                body     = artifact.explanationText ?: "",
                                isAi     = true,
                                diagnostics = artifact.toDiagnosticsOrNull()?.toDisplayText()
                            )
                        )
                    }
                    else -> AiLoadState.Error(
                        artifact.errorMessage ?: "Generation failed"
                    )
                }

                _aiExplanationStates.update { it + (reviewId to newState) }

            } catch (e: Exception) {
                Timber.e(e, "loadAiExplanation: unexpected error for review $reviewId")
                _aiExplanationStates.update {
                    it + (reviewId to AiLoadState.Error(e.message ?: "Unexpected error"))
                }
            } finally {
                _inFlightExplanations.remove(reviewId)
            }
        }
    }

    val pendingReviews: StateFlow<List<PendingReviewWithReceipt>> = reviewQueueRepository
        .getAllPendingReviews()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** S6-026: Typed loadable state for the review queue. */
    val reviewQueueLoadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<PendingReviewWithReceipt>>
        get() = when {
            pendingReviews.value.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(
                com.yourname.expensetracker.domain.model.UiText.DynamicString("All caught up!")
            )
            else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(pendingReviews.value)
        }

    val pendingCount: StateFlow<Int> = reviewQueueRepository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveReview(reviewId: Long) {
        if (!beginMutation(reviewId)) return // S6-003: idempotency guard
        viewModelScope.launch {
            try {
                val result = reviewQueueRepository.approveReview(reviewId)
                handleResult(result, "Failed to approve")
            } finally {
                endMutation(reviewId)
            }
        }
    }

    private fun handleResult(result: Result<Long>, prefix: String) {
        when (result) {
            is Result.Success -> { /* Handled by UI observing DB change */ }
            is Result.Duplicate -> _errorMessage.value = "Duplicate transaction detected"
            is Result.Error -> _errorMessage.value = "$prefix: ${result.message}"
            Result.Loading -> { /* No-op or show loading */ }
        }
    }


    fun rejectReview(reviewId: Long) {
        if (!beginMutation(reviewId)) return // S6-003: idempotency guard
        viewModelScope.launch {
            try {
                reviewQueueRepository.rejectReview(reviewId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to reject review $reviewId")
                _errorMessage.value = "Failed to reject review: ${e.message ?: "Unknown error"}"
            } finally {
                endMutation(reviewId)
            }
        }
    }

    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?,
        finalDate: Long?,
        finalType: TransactionType?,
        applyToAll: Boolean = false,
        approveAllPending: Boolean = false,
        finalLatitude: Double? = null,
        finalLongitude: Double? = null,
        finalAddress: String? = null,
        finalPlaceId: String? = null
    ) {
        viewModelScope.launch {
            if (!beginMutation(reviewId)) return@launch // S6-003: idempotency guard
            try {
                // S6-006: Fetch original review BEFORE approval so bulk logic has correct merchant
                val originalReview = reviewQueueRepository.getReviewById(reviewId)

                val result = reviewQueueRepository.approveReview(
                    reviewId = reviewId,
                    finalAmount = finalAmount,
                    finalMerchant = finalMerchant,
                    finalCategoryId = finalCategoryId,
                    finalDate = finalDate,
                    finalType = finalType,
                    finalLatitude = finalLatitude,
                    finalLongitude = finalLongitude,
                    finalAddress = finalAddress,
                    finalPlaceId = finalPlaceId
                )
                handleResult(result, "Failed to approve edits")
                if (result !is Result.Success) return@launch

                // S6-005: Emit success so screen can close dialog
                _editApproveSuccess.tryEmit(reviewId)

                if (applyToAll && (finalCategoryId != null || finalMerchant != null)) {
                    try {
                        val originalMerchant = originalReview?.suggestedMerchant
                        val merchantName = finalMerchant ?: originalMerchant
                        val categoryId = finalCategoryId
                        
                        if (merchantName != null && categoryId != null) {
                            expenseRepository.updateExpenseCategoryBulk(merchantName, categoryId)
                            reviewQueueRepository.updatePendingReviewCategoryBulk(merchantName, categoryId)
                        }

                        if (finalMerchant != null && originalMerchant != null && finalMerchant != originalMerchant) {
                            expenseRepository.updateExpenseMerchantBulk(originalMerchant, finalMerchant)
                            reviewQueueRepository.updatePendingReviewMerchantBulk(originalMerchant, finalMerchant)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to apply bulk category update")
                    }
                }

                if (approveAllPending) {
                    try {
                        val originalMerchant = originalReview?.suggestedMerchant
                        val searchMerchant = finalMerchant ?: originalMerchant
                    if (searchMerchant != null) {
                        val identicalPending = reviewQueueRepository.getPendingReviewsByMerchant(searchMerchant)
                        for (pending in identicalPending) {
                            if (pending.id != reviewId) {
                                 reviewQueueRepository.approveReview(
                                     reviewId = pending.id,
                                     finalAmount = null, // Keep original amounts for identical transactions
                                     finalMerchant = finalMerchant,
                                     finalCategoryId = finalCategoryId,
                                     finalDate = finalDate,
                                     finalType = finalType,
                                     finalLatitude = finalLatitude,
                                     finalLongitude = finalLongitude,
                                     finalAddress = finalAddress,
                                     finalPlaceId = finalPlaceId
                                 )
                             }
                         }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to apply bulk approval")
                }
            }
            } finally {
                endMutation(reviewId)
            }
        }
    }


    fun clearError() {
        _errorMessage.value = null
    }

    fun requestCategoryAssist(reviewId: Long, force: Boolean = false) {
        // S6-012: In-flight guard — prevent duplicate AI calls
        val key = reviewId to "category"
        if (!_inFlightAssist.add(key)) return
        viewModelScope.launch {
            try {
                val item = reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)
                    ?: return@launch updateCategoryAssistState(reviewId, AiLoadState.Error("Review not found"))

                updateCategoryAssistState(reviewId, AiLoadState.Loading)

                when (val result = suggestCategoryFallbackUseCase(item, force = force)) {
                    is CategoryAssistGenerationResult.Success -> {
                        val diagnostics = latestArtifactDiagnostics(
                            targetKey = "pending_review:$reviewId",
                            capability = AiCapability.CATEGORIZATION_FALLBACK
                        )
                        updateCategoryAssistState(reviewId = reviewId, state = AiLoadState.Ready(result.suggestion), diagnostics = diagnostics)
                    }
                    is CategoryAssistGenerationResult.Disabled -> updateCategoryAssistState(reviewId, AiLoadState.Disabled, diagnostics = null)
                    is CategoryAssistGenerationResult.NotNeeded -> updateCategoryAssistState(reviewId, AiLoadState.Error("Not needed"), diagnostics = null)
                    is CategoryAssistGenerationResult.Error -> {
                        val diagnostics = latestArtifactDiagnostics(targetKey = "pending_review:$reviewId", capability = AiCapability.CATEGORIZATION_FALLBACK, expectedStatus = AiArtifactStatus.FAILED)
                        updateCategoryAssistState(reviewId, AiLoadState.Error(result.reason), diagnostics = diagnostics)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Category assist failed for review $reviewId")
                updateCategoryAssistState(reviewId, AiLoadState.Error(e.message ?: "AI assist failed"))
            } finally {
                _inFlightAssist.remove(key)
            }
        }
    }

    fun requestReceiptAssist(reviewId: Long, force: Boolean = false) {
        val key = reviewId to "receipt"
        if (!_inFlightAssist.add(key)) return // S6-012: in-flight guard
        viewModelScope.launch {
            try {
        val item = reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)
                ?: return@launch updateReceiptAssistState(
                    reviewId,
                    AiLoadState.Error("Receipt review not found")
                )

            val receipt = item.receipt
            if (receipt == null) {
                updateReceiptAssistState(
                    reviewId = reviewId,
                    state = AiLoadState.Error("No scanned receipt is attached to this review."),
                    diagnostics = null,
                    message = null
                )
                return@launch
            }

            updateReceiptAssistState(reviewId, AiLoadState.Loading)

            when (val result = suggestReceiptExtractionUseCase(receipt.id, force = force)) {
                is ReceiptAssistGenerationResult.Success -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:${receipt.id}",
                        capability = AiCapability.RECEIPT_EXTRACTION
                    )
                    val message = when {
                        result.fromCache -> "Showing cached AI receipt suggestions."
                        result.usedImageInput -> "Image-aware AI cross-checked the receipt photo and OCR text."
                        else -> "AI suggested receipt fields you can apply to this review."
                    }
                    updateReceiptAssistState(
                        reviewId = reviewId,
                        state = AiLoadState.Ready(result.suggestion),
                        diagnostics = diagnostics,
                        message = message
                    )
                }
                is ReceiptAssistGenerationResult.Disabled -> {
                    updateReceiptAssistState(
                        reviewId = reviewId,
                        state = AiLoadState.Disabled,
                        diagnostics = null,
                        message = result.reason
                    )
                }
                is ReceiptAssistGenerationResult.NotNeeded -> {
                    updateReceiptAssistState(
                        reviewId = reviewId,
                        state = AiLoadState.Idle,
                        diagnostics = null,
                        message = result.reason
                    )
                }
                is ReceiptAssistGenerationResult.Error -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "scanned_receipt:${receipt.id}",
                        capability = AiCapability.RECEIPT_EXTRACTION,
                        expectedStatus = AiArtifactStatus.FAILED
                    )
                    updateReceiptAssistState(
                        reviewId = reviewId,
                        state = AiLoadState.Error(result.reason),
                        diagnostics = diagnostics,
                        message = result.reason
                    )
                }
            }
            } catch (e: Exception) {
                Timber.e(e, "Receipt assist failed for review $reviewId")
                updateReceiptAssistState(reviewId, AiLoadState.Error(e.message ?: "AI assist failed"), diagnostics = null, message = null)
            } finally {
                _inFlightAssist.remove(key)
            }
        }
    }

    fun requestDedupeAssist(reviewId: Long, force: Boolean = false) {
        val key = reviewId to "dedupe"
        if (!_inFlightAssist.add(key)) return // S6-012: in-flight guard
        viewModelScope.launch {
            try {
            val item = reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)
                ?: return@launch updateDedupeAssistState(
                    reviewId,
                    AiLoadState.Error("Review not found")
                )

            updateDedupeAssistState(reviewId, AiLoadState.Loading)

            when (val result = judgePendingReviewDuplicateUseCase(item, force = force)) {
                is DedupeJudgeGenerationResult.Success -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "pending_review:$reviewId",
                        capability = AiCapability.DEDUPE_JUDGE
                    )
                    updateDedupeAssistState(
                        reviewId = reviewId,
                        state = AiLoadState.Ready(result.suggestion),
                        diagnostics = diagnostics
                    )
                }
                is DedupeJudgeGenerationResult.Disabled -> {
                    updateDedupeAssistState(reviewId, AiLoadState.Disabled, diagnostics = null)
                }
                is DedupeJudgeGenerationResult.NotNeeded -> {
                    updateDedupeAssistState(reviewId, AiLoadState.Error(result.reason), diagnostics = null)
                }
                is DedupeJudgeGenerationResult.Error -> {
                    val diagnostics = latestArtifactDiagnostics(
                        targetKey = "pending_review:$reviewId",
                        capability = AiCapability.DEDUPE_JUDGE,
                        expectedStatus = AiArtifactStatus.FAILED
                    )
                    updateDedupeAssistState(reviewId, AiLoadState.Error(result.reason), diagnostics = diagnostics)
                }
            }
            } catch (e: Exception) {
                Timber.e(e, "Dedupe assist failed for review $reviewId")
                updateDedupeAssistState(reviewId, AiLoadState.Error(e.message ?: "AI assist failed"), diagnostics = null)
            } finally {
                _inFlightAssist.remove(key)
            }
        }
    }

    fun applyCategorySuggestion(reviewId: Long) {
        val suggestion = (_reviewCaptureAssistStates.value[reviewId]?.categorySuggestion as? AiLoadState.Ready)?.value
            ?: return
        _prefilledCategorySuggestions.update { it + (reviewId to suggestion.categoryId) }
        viewModelScope.launch {
            markCategoryArtifactApplied(reviewId)
        }
    }

    /** S6-013: Which field(s) to apply from receipt suggestion. */
    enum class ReceiptApplyField { MERCHANT, AMOUNT, DATE, ALL }

    fun applyReceiptSuggestion(reviewId: Long, field: ReceiptApplyField = ReceiptApplyField.ALL) {
        val suggestion = (_reviewCaptureAssistStates.value[reviewId]?.receiptSuggestion as? AiLoadState.Ready)?.value
            ?: return
        val full = suggestion.toPrefill()
        val prefill = when (field) {
            ReceiptApplyField.MERCHANT -> ReviewReceiptPrefill(merchant = full.merchant)
            ReceiptApplyField.AMOUNT   -> ReviewReceiptPrefill(amount = full.amount)
            ReceiptApplyField.DATE     -> ReviewReceiptPrefill(date = full.date)
            ReceiptApplyField.ALL      -> full
        }
        _prefilledReceiptSuggestions.update { current -> current + (reviewId to prefill) }
        viewModelScope.launch {
            val receiptId = reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)?.receipt?.id
            if (receiptId != null) markReceiptArtifactApplied(receiptId)
        }
    }

    fun canOfferQuickApprove(reviewId: Long): Boolean {
        if (!reviewQuickApproveEnabled.value) return false
        val state = _reviewCaptureAssistStates.value[reviewId] ?: return false
        val categoryReady = state.categorySuggestion as? AiLoadState.Ready ?: return false
        if (categoryReady.value.categoryId <= 0L) return false
        // S6-010: Require explicit LIKELY_DISTINCT — no dedupe state = no quick approve
        val dedupeReady = state.dedupeSuggestion as? AiLoadState.Ready ?: return false
        return dedupeReady.value.verdict == com.yourname.expensetracker.domain.ai.model.DuplicateVerdict.LIKELY_DISTINCT
    }

    fun requestQuickApprovePreview(reviewId: Long) {
        if (!canOfferQuickApprove(reviewId)) return
        val state = _reviewCaptureAssistStates.value[reviewId] ?: return
        val categorySuggestion = (state.categorySuggestion as? AiLoadState.Ready)?.value ?: return
        val cachedItem = pendingReviews.value.firstOrNull { it.review.id == reviewId }
        if (cachedItem != null) {
            showQuickApprovePreview(reviewId, cachedItem, state, categorySuggestion)
            return
        }

        viewModelScope.launch {
            val item = reviewQueueRepository.getAllPendingReviews().first().firstOrNull { it.review.id == reviewId }
                ?: reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)
                ?: return@launch
            showQuickApprovePreview(reviewId, item, state, categorySuggestion)
        }
    }

    fun dismissQuickApprovePreview() {
        _quickApprovePreview.value = null
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_dismiss",
            message = "review quick approve preview dismissed"
        )
    }

    fun confirmQuickApprove() {
        val preview = _quickApprovePreview.value ?: return
        if (!reviewQuickApproveEnabled.value) {
            _quickApprovePreview.value = null
            _errorMessage.value = "Review quick approve is turned off."
            return
        }
        // S6-011: Don't clear preview yet — keep it open until we know the result
        if (!beginMutation(preview.reviewId)) return

        viewModelScope.launch {
            try {
                val result = reviewQueueRepository.approveReview(
                    reviewId = preview.reviewId,
                    finalCategoryId = preview.categoryId
                )
                if (result is Result.Success) {
                    // Only clear preview on success
                    _quickApprovePreview.value = null
                }
                handleResult(result, "Failed to quick approve")
                if (result is Result.Success) {
                    markQuickApproveArtifactsApplied(preview.reviewId)
                }
                aiRuntimeDiagnostics.recordInteraction(
                    type = "phase4_accept",
                    message = "review quick approve confirmed for ${preview.reviewId}"
                )
            } catch (e: Exception) {
                Timber.e(e, "Quick approve failed for ${preview.reviewId}")
                _errorMessage.value = "Quick approve failed: ${e.message}"
            } finally {
                endMutation(preview.reviewId)
            }
        }
    }

    fun onEvent(event: ReviewEvent) {
        when (event) {
            is ReviewEvent.ConsumePrefilledCategorySuggestion -> {
                consumePrefilledCategorySuggestion(event.reviewId)
            }
            is ReviewEvent.ConsumePrefilledReceiptSuggestion -> {
                consumePrefilledReceiptSuggestion(event.reviewId)
            }
        }
    }

    private fun consumePrefilledCategorySuggestion(reviewId: Long): Long? {
        val value = _prefilledCategorySuggestions.value[reviewId]
        _prefilledCategorySuggestions.update { it - reviewId }
        return value
    }

    private fun consumePrefilledReceiptSuggestion(reviewId: Long): ReviewReceiptPrefill? {
        val value = _prefilledReceiptSuggestions.value[reviewId]
        _prefilledReceiptSuggestions.update { it - reviewId }
        return value
    }

    fun dismissCategoryAssist(reviewId: Long) {
        dismissArtifact(
            reviewId = reviewId,
            capability = com.yourname.expensetracker.domain.ai.model.AiCapability.CATEGORIZATION_FALLBACK,
            clear = { current -> current.copy(categorySuggestion = AiLoadState.Idle, categoryDiagnostics = null) }
        )
    }

    fun dismissReceiptAssist(reviewId: Long) {
        viewModelScope.launch {
            val receiptId = reviewQueueRepository.getPendingReviewWithReceiptById(reviewId)?.receipt?.id
            if (receiptId != null) {
                aiArtifactRepository.getLatest("scanned_receipt:$receiptId", AiCapability.RECEIPT_EXTRACTION)
                    ?.let { artifact ->
                        aiArtifactRepository.markDismissed(artifact.id)
                    }
            }
            _reviewCaptureAssistStates.update { current ->
                val existing = current[reviewId] ?: ReviewCaptureAssistState()
                current + (reviewId to existing.copy(
                    receiptSuggestion = AiLoadState.Idle,
                    receiptDiagnostics = null,
                    receiptMessage = null
                ))
            }
        }
    }

    fun dismissDedupeAssist(reviewId: Long) {
        dismissArtifact(
            reviewId = reviewId,
            capability = com.yourname.expensetracker.domain.ai.model.AiCapability.DEDUPE_JUDGE,
            clear = { current -> current.copy(dedupeSuggestion = AiLoadState.Idle, dedupeDiagnostics = null) }
        )
    }

    fun approveAll() {
        if (_isBatchProcessing.value) return
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, 1)
                val results = reviewQueueRepository.approveAllReview()
                _batchProgress.value = Pair(1, 1)
                // S6-007: Report per-item failures
                val successCount = results.count { it.second is Result.Success }
                val duplicateCount = results.count { it.second is Result.Duplicate }
                val errorCount = results.count { it.second is Result.Error }
                _errorMessage.value = when {
                    errorCount > 0 || duplicateCount > 0 ->
                        "Approved $successCount, skipped $duplicateCount duplicates, $errorCount failed."
                    else -> "Approved all $successCount pending reviews."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve all: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    fun rejectAll() {
        viewModelScope.launch {
            try {
                reviewQueueRepository.rejectAllReviews()
                _errorMessage.value = "All pending reviews cleared."
            } catch (e: Exception) {
                _errorMessage.value = "Failed to clear all: ${e.message}"
            }
        }
    }

    fun processBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        batchJob?.cancel() // Cancel previous if any
        batchJob = viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, uris.size)
                
                val result = receiptRepository.processBatch(uris) { current, total ->
                    _batchProgress.value = Pair(current, total)
                }
                
                if (result.failureCount > 0) {
                    val firstError = result.errors.firstOrNull()?.let { 
                        if (it.length > 60) it.take(57) + "..." else it 
                    }
                    _errorMessage.value = "Processed ${result.successCount} ok. ${result.failureCount} failed: $firstError"
                } else {
                    _errorMessage.value = "Successfully processed all ${result.successCount} receipts!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Batch failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    fun cancelBatchProcessing() {
        batchJob?.cancel()
        _isBatchProcessing.value = false
        _batchProgress.value = null
        _errorMessage.value = "Batch processing cancelled."
    }

    fun processStatement(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true // Reuse batch loading state
                _batchProgress.value = Pair(0, 1)
                
                when (val result = receiptLifecycleCoordinator.processBankStatement(uri)) {
                    is Result.Success<*> -> {
                        val bankResult = result.data as BankStatementResult
                        _errorMessage.value = "Imported ${bankResult.transactionsFound} transactions from statement! " +
                            "(${bankResult.reviewsCreated} reviews created, ${bankResult.duplicatesSkipped} duplicates skipped)"
                        // Save debug data for the debug viewer
                        bankResult.debugData?.let { data ->
                            _debugData.value = data
                            debugDataStorage.save(data)
                        }
                    }
                    is Result.Error -> {
                        _errorMessage.value = "Failed to parse statement: ${result.message ?: result.exception?.message ?: "Unknown error"}"
                    }
                    is Result.Duplicate -> {
                        _errorMessage.value = "Statement already processed (duplicate)"
                    }
                    Result.Loading -> { /* no-op */ }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    suspend fun getDebugExportData(): String {
        return receiptRepository.exportParserDebugData()
    }

    suspend fun getReceiptDebugInfo(receiptId: Long): String {
        return receiptRepository.debugReceipt(receiptId)
    }

    fun clearScannedData() {
        viewModelScope.launch {
            receiptRepository.clearAllScannedReceipts()
            _errorMessage.value = "All scanned debug data cleared."
        }
    }
    
    fun clearDebugData() {
        viewModelScope.launch {
            debugDataStorage.clear()
        }
        _debugData.value = null
        _errorMessage.value = "Debug data cleared."
    }

    private fun updateCategoryAssistState(
        reviewId: Long,
        state: AiLoadState<CategoryAssistSuggestion>,
        diagnostics: String? = null
    ) {
        _reviewCaptureAssistStates.update { current ->
            val existing = current[reviewId] ?: ReviewCaptureAssistState()
            current + (reviewId to existing.copy(categorySuggestion = state, categoryDiagnostics = diagnostics))
        }
    }

    private fun updateReceiptAssistState(
        reviewId: Long,
        state: AiLoadState<ReceiptAssistSuggestion>,
        diagnostics: String? = null,
        message: String? = null
    ) {
        _reviewCaptureAssistStates.update { current ->
            val existing = current[reviewId] ?: ReviewCaptureAssistState()
            current + (reviewId to existing.copy(
                receiptSuggestion = state,
                receiptDiagnostics = diagnostics,
                receiptMessage = message
            ))
        }
    }

    private fun updateDedupeAssistState(
        reviewId: Long,
        state: AiLoadState<DedupeJudgeSuggestion>,
        diagnostics: String? = null
    ) {
        _reviewCaptureAssistStates.update { current ->
            val existing = current[reviewId] ?: ReviewCaptureAssistState()
            current + (reviewId to existing.copy(dedupeSuggestion = state, dedupeDiagnostics = diagnostics))
        }
    }

    private fun dismissArtifact(
        reviewId: Long,
        capability: com.yourname.expensetracker.domain.ai.model.AiCapability,
        clear: (ReviewCaptureAssistState) -> ReviewCaptureAssistState
    ) {
        viewModelScope.launch {
            aiArtifactRepository.getLatest("pending_review:$reviewId", capability)?.let { artifact ->
                aiArtifactRepository.markDismissed(artifact.id)
            }
            _reviewCaptureAssistStates.update { current ->
                val existing = current[reviewId] ?: ReviewCaptureAssistState()
                current + (reviewId to clear(existing))
            }
        }
    }

    private suspend fun latestArtifactDiagnostics(
        targetKey: String,
        capability: AiCapability,
        expectedStatus: AiArtifactStatus? = null
    ): String? {
        val artifact = aiArtifactRepository.getLatest(targetKey, capability) ?: return null
        if (expectedStatus != null && artifact.status != expectedStatus) return null
        return artifact.toDiagnosticsOrNull()?.toDisplayText()
    }

    private suspend fun markCategoryArtifactApplied(reviewId: Long) {
        markArtifactApplied(reviewId, AiCapability.CATEGORIZATION_FALLBACK)
    }

    private suspend fun markReceiptArtifactApplied(receiptId: Long) {
        aiArtifactRepository.getLatest("scanned_receipt:$receiptId", AiCapability.RECEIPT_EXTRACTION)
            ?.let { artifact ->
                aiArtifactRepository.markApplied(artifact.id)
            }
    }

    private suspend fun markQuickApproveArtifactsApplied(reviewId: Long) {
        markArtifactApplied(reviewId, AiCapability.CATEGORIZATION_FALLBACK)
        val dedupeReady = _reviewCaptureAssistStates.value[reviewId]?.dedupeSuggestion as? AiLoadState.Ready
        if (dedupeReady != null) {
            markArtifactApplied(reviewId, AiCapability.DEDUPE_JUDGE)
        }
    }

    private suspend fun markArtifactApplied(reviewId: Long, capability: AiCapability) {
        aiArtifactRepository.getLatest("pending_review:$reviewId", capability)
            ?.let { artifact ->
                aiArtifactRepository.markApplied(artifact.id)
            }
    }

    private fun showQuickApprovePreview(
        reviewId: Long,
        item: PendingReviewWithReceipt,
        state: ReviewCaptureAssistState,
        categorySuggestion: CategoryAssistSuggestion
    ) {
        _quickApprovePreview.value = ReviewQuickApprovePreview(
            reviewId = reviewId,
            merchant = item.review.suggestedMerchant,
            amount = item.review.suggestedAmount ?: 0.0,
            categoryId = categorySuggestion.categoryId,
            categoryName = categorySuggestion.categoryName,
            diagnostics = listOfNotNull(state.categoryDiagnostics, state.dedupeDiagnostics)
        )
        aiRuntimeDiagnostics.recordInteraction(
            type = "phase4_preview",
            message = "review quick approve preview opened for $reviewId"
        )
    }
}

private fun ReceiptAssistSuggestion.toPrefill(): ReviewReceiptPrefill {
    return ReviewReceiptPrefill(
        merchant = merchant?.value?.takeIf { it.isNotBlank() },
        amount = total?.value,
        date = date?.value
    )
}
