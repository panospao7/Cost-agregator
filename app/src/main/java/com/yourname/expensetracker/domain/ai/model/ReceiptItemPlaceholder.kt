package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot

/**
 * Single definition of a "placeholder" receipt item: a line the cloud call did not send, kept so
 * the stored item count still matches the receipt. A placeholder has no suggested category,
 * confidence 0, a blank rationale and no alternatives.
 *
 * A real model answer with confidence 0 and a blank rationale but WITH a category (or
 * alternatives) is not a placeholder.
 */
object ReceiptItemPlaceholder {

    const val PLACEHOLDER_RATIONALE = ""

    /** Core rule; every other overload delegates here. */
    fun isPlaceholder(
        hasSuggestedCategory: Boolean,
        confidence: Float,
        rationale: String?,
        hasAlternatives: Boolean
    ): Boolean = !hasSuggestedCategory &&
        confidence == 0f &&
        rationale.isNullOrBlank() &&
        !hasAlternatives

    fun isPlaceholder(item: CategorizedReceiptItem): Boolean = isPlaceholder(
        hasSuggestedCategory = item.suggestedCategory != null,
        confidence = item.confidence,
        rationale = item.rationale,
        hasAlternatives = item.alternatives.isNotEmpty()
    )

    fun isPlaceholder(item: ReceiptItemCategorizationSnapshot): Boolean = isPlaceholder(
        hasSuggestedCategory = item.suggestedCategoryId != null || !item.suggestedCategoryName.isNullOrBlank(),
        confidence = item.confidence,
        rationale = item.aiRationale,
        hasAlternatives = hasAlternativesJson(item.alternativeCategoriesJson)
    )

    /** Persisted alternatives are a JSON array; null, blank and "[]" mean none. */
    private fun hasAlternativesJson(json: String?): Boolean {
        val trimmed = json?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed != "[]"
    }
}
