package com.yourname.expensetracker.domain.model.recommendation

/**
 * Lifecycle status for dashboard follow-through recommendations.
 */
enum class RecommendationStatus {
    /**
     * Active recommendation, visible to the user.
     */
    ACTIVE,
    
    /**
     * User dismissed the recommendation - archived for analytics but not shown.
     */
    ARCHIVED,
    
    /**
     * Recommendation has expired (past TTL of 7 days).
     */
    EXPIRED
}
