package com.yourname.expensetracker.domain.model.recommendation

/**
 * Priority levels for dashboard follow-through recommendations.
 * Determines display order and urgency indicators in the UI.
 */
enum class RecommendationPriority {
    /**
     * High priority recommendations (e.g., budget overruns, anomalies, critical insights).
     */
    HIGH,
    
    /**
     * Medium priority recommendations (e.g., category trends, recurring patterns).
     */
    MEDIUM,
    
    /**
     * Low priority recommendations (e.g., informational insights, minor trends).
     */
    LOW
}
