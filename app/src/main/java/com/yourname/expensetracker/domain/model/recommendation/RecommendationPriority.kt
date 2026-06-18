package com.yourname.expensetracker.domain.model.recommendation

/**
 * Priority levels for dashboard follow-through recommendations.
 * Determines display order and urgency indicators in the UI.
 */
enum class RecommendationSeverity {
    HIGH,
    MEDIUM,
    LOW
}

enum class RecommendationPriority(
    val rank: Int,
    val severity: RecommendationSeverity
) {
    /**
     * High priority recommendations (e.g., budget overruns, anomalies, critical insights).
     */
    HIGH(rank = 3, severity = RecommendationSeverity.HIGH),
    
    /**
     * Medium priority recommendations (e.g., category trends, recurring patterns).
     */
    MEDIUM(rank = 2, severity = RecommendationSeverity.MEDIUM),
    
    /**
     * Low priority recommendations (e.g., informational insights, minor trends).
     */
    LOW(rank = 1, severity = RecommendationSeverity.LOW)
}
