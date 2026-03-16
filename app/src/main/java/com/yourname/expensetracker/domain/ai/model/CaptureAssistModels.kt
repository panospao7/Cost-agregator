package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.data.database.entity.TransactionType

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
    val name: String
)

data class ReceiptAssistInput(
    val receiptId: Long,
    val rawOcrText: String,
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
    val notes: List<String> = emptyList()
)

sealed interface ReceiptAssistGenerationResult {
    data class Success(
        val suggestion: ReceiptAssistSuggestion,
        val fromCache: Boolean
    ) : ReceiptAssistGenerationResult

    data class Disabled(val reason: String) : ReceiptAssistGenerationResult

    data class NotNeeded(val reason: String) : ReceiptAssistGenerationResult

    data class Error(val reason: String) : ReceiptAssistGenerationResult
}

data class CategorizationAssistInput(
    val targetType: AiTargetType,
    val targetId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val transactionType: TransactionType,
    val date: Long?,
    val currentCategoryId: Long?,
    val deterministicMatchType: String?,
    val deterministicExplanation: String?,
    val candidateCategories: List<CategoryOption>,
    val supportingText: String? = null
)

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
    val textPreview: String? = null
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
    val categorySuggestion: AiLoadState<CategoryAssistSuggestion> = AiLoadState.Idle,
    val categoryDiagnostics: String? = null,
    val dedupeSuggestion: AiLoadState<DedupeJudgeSuggestion> = AiLoadState.Idle,
    val dedupeDiagnostics: String? = null
)
