package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.data.database.entity.PendingReview

/**
 * Result of AI priority scoring for a review item.
 * 
 * @property reviewId The ID of the pending review item
 * @property priorityScore Score from 0.0-1.0 (higher = more urgent/important)
 * @property urgencyReason Optional explanation of why this item is high priority
 * @property estimatedApprovalTime Optional estimate of time needed to approve (seconds)
 * @property factors Breakdown of scoring factors
 */
data class ReviewPriorityScore(
    val reviewId: Long,
    val priorityScore: Float,
    val urgencyReason: String?,
    val estimatedApprovalTime: Int?,
    val factors: ReviewPriorityFactors
)

/**
 * Factors that contribute to priority scoring.
 * 
 * Each factor is a score from 0.0-1.0 that contributes to the overall priority.
 * Higher values generally indicate more need for user attention.
 * 
 * @property confidenceLevel Inverse of parser confidence (low confidence = higher priority)
 * @property duplicateRisk Risk this is a duplicate (high = needs verification)
 * @property merchantClarity How clear the merchant is (low = needs user input)
 * @property timeSensitivity How time-sensitive (older = higher priority)
 * @property categoryClarity How clear the category is (unknown = higher priority)
 * @property amountSignificance Significance of amount (very high amounts = higher priority)
 * @property historicalPattern User's historical approval patterns
 */
data class ReviewPriorityFactors(
    val confidenceLevel: Float,
    val duplicateRisk: Float,
    val merchantClarity: Float,
    val timeSensitivity: Float,
    val categoryClarity: Float,
    val amountSignificance: Float,
    val historicalPattern: Float
) {
    companion object {
        /**
         * Creates factors from a PendingReview item using deterministic calculations.
         * This is used as the base score before AI enhancement.
         */
        fun fromReview(review: PendingReview): ReviewPriorityFactors {
            return ReviewPriorityFactors(
                confidenceLevel = (1.0f - review.confidence).coerceIn(0f, 1f),
                duplicateRisk = 0.5f, // Will be calculated by dedupe engine
                merchantClarity = if (review.suggestedMerchant == "Unknown") 0.2f else 0.8f,
                timeSensitivity = calculateTimeSensitivity(review.createdAt),
                categoryClarity = if (review.suggestedCategoryId == null) 0.3f else 0.9f,
                amountSignificance = calculateAmountSignificance(review.suggestedAmount),
                historicalPattern = 0.5f // Default, will be enhanced by AI
            )
        }
        
        private fun calculateTimeSensitivity(createdAt: Long): Float {
            val ageHours = (System.currentTimeMillis() - createdAt) / (1000 * 60 * 60)
            return when {
                ageHours < 1 -> 0.2f // Very recent
                ageHours < 24 -> 0.4f // Today
                ageHours < 72 -> 0.6f // Within 3 days
                ageHours < 168 -> 0.8f // Within week
                else -> 1.0f // Old
            }
        }
        
        private fun calculateAmountSignificance(amount: Double): Float {
            return when {
                amount < 10.0 -> 0.2f // Small amount
                amount < 50.0 -> 0.4f // Medium amount
                amount < 100.0 -> 0.6f // Larger amount
                amount < 500.0 -> 0.8f // Significant amount
                else -> 1.0f // Very large amount
            }
        }
    }
}
