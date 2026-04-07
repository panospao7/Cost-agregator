package com.yourname.expensetracker.domain.dto

/**
 * Domain-level snapshot of a pending review item containing only the fields
 * consumed by [com.yourname.expensetracker.domain.ai.model.ReviewPriorityFactors].
 *
 * This replaces the direct dependency on the Room entity
 * [com.yourname.expensetracker.data.database.entity.PendingReview] in domain model code.
 *
 * Mappers in the data / adapter layer should convert [PendingReview] entities to
 * [ReviewPriorityInput] before passing them into domain scoring helpers.
 */
data class ReviewPriorityInput(
    val reviewId: Long,
    val confidence: Float,
    val suggestedMerchant: String,
    val suggestedCategoryId: Long?,
    val suggestedAmount: Double,
    val createdAt: Long
)
