package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityScore
import com.yourname.expensetracker.domain.ai.service.ReviewPriorityScorer
import javax.inject.Inject

/**
 * Use case for prioritizing review queue items.
 * 
 * This use case coordinates the priority scoring and sorting of review items
 * to present them in the most helpful order to users.
 * 
 * Sorting logic:
 * 1. High priority items first (score > 0.7)
 * 2. Then by time sensitivity (older items first)
 * 3. Finally by creation date (newest first)
 * 
 * The AI scorer runs asynchronously and doesn't block the UI.
 */
class PrioritizeReviewItemsUseCase @Inject constructor(
    private val scorer: ReviewPriorityScorer
) {
    /**
     * Execute prioritization on a list of pending reviews.
     * 
     * @param reviews List of reviews to prioritize
     * @return List sorted by priority (high priority first)
     */
    suspend fun execute(reviews: List<PendingReview>): List<PendingReview> {
        if (reviews.isEmpty()) return reviews
        
        // Score all reviews
        val scored = scorer.scoreReviews(reviews)
        
        // Create lookup map for scores
        val scoreMap = scored.associateBy { it.reviewId }
        
        // Sort reviews based on priority
        return reviews.sortedWith(
            compareByDescending<PendingReview> { review ->
                val score = scoreMap[review.id]?.priorityScore ?: 0.5f
                // Boost items with high priority
                if (score > 0.7f) score else score * 0.8f
            }.thenBy { review ->
                // Older items get higher priority
                review.createdAt
            }
        )
    }
    
    /**
     * Get priority scores for all reviews without sorting.
     * 
     * Use this when you need to display priority information
     * alongside each review item.
     * 
     * @param reviews List of reviews to score
     * @return Map of review ID to priority score
     */
    suspend fun getPriorityScores(reviews: List<PendingReview>): Map<Long, ReviewPriorityScore> {
        if (reviews.isEmpty()) return emptyMap()
        
        return scorer.scoreReviews(reviews).associateBy { it.reviewId }
    }
    
    /**
     * Quick base score calculation without AI.
     * 
     * Use this when AI is unavailable or for quick previews.
     * 
     * @param review The review to score
     * @return Base priority score (0.0-1.0)
     */
    fun quickScore(review: PendingReview): Float {
        return scorer.calculateBaseScore(review)
    }
}
