package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import com.yourname.expensetracker.domain.receipt.ReceiptParser

/**
 * Input data for AI receipt item categorization.
 */
data class ReceiptItemCategorizationInput(
    val receiptId: Long,
    val merchant: String,
    val lineItems: List<ReceiptParser.LineItem>,
    val userCategories: List<CategoryRef>,
    val cloudCategoryOptions: List<CloudCategoryOption> = emptyList(),
    val totalTax: Double?,
    val currency: String,
    val redactBeforeCloud: Boolean = false
)

data class CloudCategoryOption(
    val categoryId: Long,
    val cloudName: String
)

/**
 * A single categorized receipt item with AI suggestions.
 */
data class CategorizedReceiptItem(
    val itemDescription: String,
    val amount: Double,
    val suggestedCategory: CategorySuggestion?,
    val confidence: Float,
    val rationale: String,
    val alternatives: List<CategorySuggestion>,
    val needsReview: Boolean
)

/**
 * Category suggestion with confidence score.
 */
data class CategorySuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val confidence: Float,
    val isNewCategorySuggestion: Boolean = false
)

/**
 * Result of AI receipt item categorization.
 */
data class ReceiptItemCategorizationResult(
    val items: List<CategorizedReceiptItem>,
    val totalConfidence: Float,
    val needsReview: Boolean,
    val suggestedNewCategories: List<String>,
    val taxDistribution: Map<Long, Double> // categoryId -> tax amount
)

/**
 * Sealed interface for categorization result states.
 */
sealed interface CategorizationResult {
    data class Success(val result: ReceiptItemCategorizationResult) : CategorizationResult
    data class AlreadyAnalyzed(val items: List<ReceiptItemCategorizationSnapshot>) : CategorizationResult
    data object Disabled : CategorizationResult
    data object Error : CategorizationResult
}

/**
 * AI artifact payload for receipt item categorization.
 */
data class ReceiptItemCategorizationPayload(
    val items: List<CategorizedReceiptItemPayload>,
    val suggestedNewCategories: List<String>,
    val taxDistribution: Map<String, Double> // categoryName -> tax amount (serialized)
)

data class CategorizedReceiptItemPayload(
    val description: String,
    val amount: Double,
    val categoryName: String,
    val categoryId: Long?,
    val confidence: Float,
    val rationale: String,
    val alternatives: List<CategoryAlternativePayload>,
    val isNewCategorySuggestion: Boolean
)

data class CategoryAlternativePayload(
    val categoryName: String,
    val categoryId: Long?,
    val confidence: Float
)
