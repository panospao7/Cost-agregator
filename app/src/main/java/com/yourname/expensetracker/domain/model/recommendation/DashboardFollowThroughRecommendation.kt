package com.yourname.expensetracker.domain.model.recommendation

import java.util.UUID

/**
 * Domain model for a dashboard follow-through recommendation.
 * 
 * Represents an actionable insight that users can tap to navigate to a filtered
 * transaction view. The AI generates only the [recommendationText] summary;
 * all navigation targets and filter criteria are determined by deterministic code.
 * 
 * @property id Unique identifier (UUID string)
 * @property userId User identifier for multi-user support
 * @property recommendationText Human-readable recommendation text (AI-generated)
 * @property navigationTarget Target screen identifier (e.g., "TRANSACTION_LIST", "BUDGET_DETAIL")
 * @property filterCriteria Serialized TransactionFilter JSON string for filtering
 * @property createdAt Epoch milliseconds when recommendation was created
 * @property updatedAt Epoch milliseconds when recommendation was last updated
 * @property dismissedAt Epoch milliseconds when user dismissed the recommendation (null if not dismissed)
 * @property expiresAt Epoch milliseconds when recommendation expires (createdAt + 7 days)
 * @property priority Display priority (HIGH, MEDIUM, LOW)
 * @property category Associated category identifier (e.g., "FOOD", "TRANSPORT")
 * @property sourceArtifactId Link to the originating ai_artifacts table row
 * @property status Current lifecycle status (ACTIVE, ARCHIVED, EXPIRED)
 */
data class DashboardFollowThroughRecommendation(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val recommendationText: String,
    val navigationTarget: String,
    val filterCriteria: String, // Serialized JSON of TransactionFilter
    val createdAt: Long,
    val updatedAt: Long,
    val dismissedAt: Long? = null,
    val expiresAt: Long,
    val priority: RecommendationPriority = RecommendationPriority.MEDIUM,
    val category: String = "GENERAL",
    val sourceArtifactId: String = "",
    val status: RecommendationStatus = RecommendationStatus.ACTIVE
) {
    companion object {
        /**
         * Check if this recommendation has expired based on current time.
         */
        fun isExpired(expiresAt: Long, nowMillis: Long): Boolean {
            return nowMillis >= expiresAt
        }
    }
    
    /**
     * Check if this recommendation is currently active (not dismissed, not expired).
     */
    fun isActive(nowMillis: Long): Boolean {
        return status == RecommendationStatus.ACTIVE && 
               dismissedAt == null && 
               !isExpired(expiresAt, nowMillis)
    }
}
