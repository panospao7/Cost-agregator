package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityFactors
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityScore

/**
 * Interface for AI-powered review queue priority scoring.
 * 
 * This service analyzes pending review items and assigns priority scores
to help users focus on the most important or urgent items first.
 * 
 * The scoring considers:
 * - Parser confidence levels
 * - Duplicate detection risk
 * - Merchant clarity
 * - Time sensitivity
 * - Historical user patterns
 * - AI semantic analysis of notification text
 * 
 * Implementation can be on-device (privacy-preserving) or cloud-based.
 */
interface ReviewPriorityScorer {
    /**
     * Score multiple review items in batch.
     * 
     * This method should be called when loading the review queue to sort
     * items by priority.
     * 
     * @param reviews List of pending reviews to score
     * @return List of scores in same order as input
     */
    suspend fun scoreReviews(reviews: List<PendingReview>): List<ReviewPriorityScore>
    
    /**
     * Score a single review item.
     * 
     * Use this when a new review is added to the queue and needs
     * immediate scoring.
     * 
     * @param review The pending review to score
     * @return Priority score with all factors
     */
    suspend fun scoreSingle(review: PendingReview): ReviewPriorityScore
    
    /**
     * Calculate base priority score using deterministic factors.
     * 
     * This doesn't use AI - it's fast and always available.
     * Used as fallback when AI is disabled or unavailable.
     * 
     * @param review The pending review
     * @return Base priority score (0.0-1.0)
     */
    fun calculateBaseScore(review: PendingReview): Float
}
