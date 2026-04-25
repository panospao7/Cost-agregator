package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.DomainTransactionType

enum class DuplicateVerdict {
    LIKELY_DUPLICATE,
    LIKELY_DISTINCT,
    UNCERTAIN
}

data class SuggestedValue<T>(
    val value: T,
    val confidence: Float? = null,
    val rationale: String? = null
)

data class CategoryOption(
    val id: Long,
    val name: String,
    val cloudLabel: String = name
)

data class ReceiptAssistInput(
    val receiptId: Long,
    val rawOcrText: String,
    val imagePath: String?,
    val imageMimeType: String?,
    // NEW: Flag to indicate AI should use vision/image analysis mode
    val isImageAnalysisMode: Boolean = false,
    val redactBeforeCloud: Boolean = false,
    val parsedMerchant: String?,
    val parsedTotal: Double?,
    val parsedDate: Long?,
    val parsedTaxAmount: Double?,
    val currency: String,
    val lineItemsJson: String?,
    val currentTimeMs: Long
)

data class ReceiptAssistSuggestion(
    val merchant: SuggestedValue<String>? = null,
    val total: SuggestedValue<Double>? = null,
    val date: SuggestedValue<Long>? = null,
    val taxAmount: SuggestedValue<Double>? = null,
    val notes: List<String> = emptyList(),
    val usedImageInput: Boolean = false,
    val attemptDetails: List<ReceiptAssistAttemptDetail> = emptyList()
)

data class ReceiptAssistAttemptDetail(
    val attemptNumber: Int,
    val method: String,
    val success: Boolean,
    val confidence: Float? = null,
    val errorMessage: String? = null
)

sealed interface AiServiceResult<out T> {
    data class Success<T>(val value: T) : AiServiceResult<T>
    data class Failure(val error: AiServiceError) : AiServiceResult<Nothing>
}

sealed interface AiServiceError {
    data object Timeout : AiServiceError
    data object Offline : AiServiceError
    data class HttpError(val code: Int, val message: String? = null) : AiServiceError
    data object SslError : AiServiceError
    data class ParseError(val message: String? = null) : AiServiceError
    data class Disabled(val reason: String) : AiServiceError
    data class Unknown(val message: String? = null) : AiServiceError
}

sealed interface ReceiptAssistGenerationResult {
    data class Success(
        val suggestion: ReceiptAssistSuggestion,
        val fromCache: Boolean,
        val usedImageInput: Boolean = false
    ) : ReceiptAssistGenerationResult

    data class Disabled(val reason: String) : ReceiptAssistGenerationResult

    data class NotNeeded(val reason: String) : ReceiptAssistGenerationResult

    data class Error(val reason: String) : ReceiptAssistGenerationResult
}

data class MerchantTransactionHint(
    val merchant: String,
    val categoryName: String,
    val cloudMerchant: String = merchant,
    val cloudCategoryName: String = categoryName
)

data class CategorizationAssistInput(
    val targetType: AiTargetType,
    val targetId: Long,
    val merchant: String,
    val amount: Double?,
    val currency: String,
    val transactionType: DomainTransactionType,
    val date: Long?,
    val currentCategoryId: Long?,
    val deterministicMatchType: String?,
    val deterministicExplanation: String?,
    val candidateCategories: List<CategoryOption>,
    val supportingText: String? = null,
    val recentTransactionsWithSameMerchant: List<MerchantTransactionHint> = emptyList()
) {
    init {
        require(amount == null || (amount.isFinite() && amount > 0.0)) {
            "CategorizationAssistInput.amount must be finite and > 0 when provided"
        }
    }
}

data class CategoryAssistSuggestion(
    val categoryId: Long,
    val categoryName: String,
    val confidence: Float? = null,
    val rationale: String? = null,
    val alternativeCategoryIds: List<Long> = emptyList()
)

sealed interface CategoryAssistGenerationResult {
    data class Success(
        val suggestion: CategoryAssistSuggestion,
        val fromCache: Boolean
    ) : CategoryAssistGenerationResult

    data class Disabled(val reason: String) : CategoryAssistGenerationResult

    data class NotNeeded(val reason: String) : CategoryAssistGenerationResult

    data class Error(val reason: String) : CategoryAssistGenerationResult
}

data class DedupeCandidateSummary(
    val targetType: AiTargetType,
    val targetId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val sourceLabel: String,
    val textPreview: String? = null,
    val transactionType: String? = null
)

data class DedupeJudgeInput(
    val subject: DedupeCandidateSummary,
    val candidates: List<DedupeCandidateSummary>
)

data class DedupeJudgeSuggestion(
    val verdict: DuplicateVerdict,
    val matchedTargetType: AiTargetType? = null,
    val matchedTargetId: Long? = null,
    val confidence: Float? = null,
    val rationale: String? = null
)

sealed interface DedupeJudgeBuildResult {
    data class Ready(val input: DedupeJudgeInput) : DedupeJudgeBuildResult

    data class NotNeeded(val reason: String) : DedupeJudgeBuildResult
}

sealed interface DedupeJudgeGenerationResult {
    data class Success(
        val suggestion: DedupeJudgeSuggestion,
        val fromCache: Boolean
    ) : DedupeJudgeGenerationResult

    data class Disabled(val reason: String) : DedupeJudgeGenerationResult

    data class NotNeeded(val reason: String) : DedupeJudgeGenerationResult

    data class Error(val reason: String) : DedupeJudgeGenerationResult
}

data class ReviewCaptureAssistState(
    val receiptSuggestion: AiLoadState<ReceiptAssistSuggestion> = AiLoadState.Idle,
    val receiptDiagnostics: String? = null,
    val receiptMessage: String? = null,
    val categorySuggestion: AiLoadState<CategoryAssistSuggestion> = AiLoadState.Idle,
    val categoryDiagnostics: String? = null,
    val dedupeSuggestion: AiLoadState<DedupeJudgeSuggestion> = AiLoadState.Idle,
    val dedupeDiagnostics: String? = null
)

data class ReviewReceiptPrefill(
    val merchant: String? = null,
    val amount: Double? = null,
    val date: Long? = null
)
