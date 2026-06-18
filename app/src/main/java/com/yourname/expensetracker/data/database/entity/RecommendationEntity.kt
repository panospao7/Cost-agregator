package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus

/**
 * Room entity for persisting dashboard follow-through recommendations.
 * 
 * Indices:
 * - userId + status + expiresAt: Fast lookup of active recommendations per user
 * - sourceArtifactId: Link to originating AI artifact
 * - createdAt: Chronological ordering
 * - expiresAt: TTL expiry cleanup
 */
@Entity(
    tableName = "recommendations",
    indices = [
        Index(value = ["userId", "status", "expiresAt"]),
        Index(value = ["sourceArtifactId"]),
        Index(value = ["createdAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class RecommendationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val recommendationText: String,
    val navigationTarget: String,
    val filterCriteria: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dismissedAt: Long? = null,
    val expiresAt: Long,
    val priority: RecommendationPriority,
    val category: String,
    val sourceArtifactId: String,
    val status: RecommendationStatus
)
