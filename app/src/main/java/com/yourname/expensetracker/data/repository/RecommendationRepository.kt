package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecommendationDao
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for dashboard follow-through recommendations.
 * 
 * Wraps the DAO and provides domain model conversion, enforcing
 * business rules like the 5-recommendation limit.
 */
@Singleton
class RecommendationRepository @Inject constructor(
    private val dao: RecommendationDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    companion object {
        private const val MAX_ACTIVE_RECOMMENDATIONS = 5
    }
    
    /**
     * Get active recommendations for a user as a Flow.
     */
    fun observeActiveForUser(userId: String): Flow<List<DashboardFollowThroughRecommendation>> {
        return dao.observeActiveByUser(userId).map { entities ->
            entities.map { it.toDomain() }
        }
    }
    
    /**
     * Get active recommendations for a user (one-shot).
     */
    suspend fun getActiveForUser(userId: String): List<DashboardFollowThroughRecommendation> {
        return withContext(ioDispatcher) {
            dao.getActiveByUser(userId).map { it.toDomain() }
        }
    }
    
    /**
     * Save a single recommendation.
     */
    suspend fun save(recommendation: DashboardFollowThroughRecommendation) {
        withContext(ioDispatcher) {
            dao.insert(recommendation.toEntity())
        }
    }
    
    /**
     * Save multiple recommendations, enforcing the max limit.
     * If there are more than 5, only the top 5 by priority are saved.
     */
    suspend fun saveAll(recommendations: List<DashboardFollowThroughRecommendation>) {
        withContext(ioDispatcher) {
            // Sort by priority (HIGH > MEDIUM > LOW) and take top 5
            val topRecommendations = recommendations
                .sortedWith(compareByDescending<DashboardFollowThroughRecommendation> { it.priority.rank() })
                .take(MAX_ACTIVE_RECOMMENDATIONS)
            
            dao.insertAll(topRecommendations.map { it.toEntity() })
        }
    }
    
    /**
     * Dismiss (archive) a recommendation by ID.
     */
    suspend fun dismiss(recommendationId: String) {
        withContext(ioDispatcher) {
            dao.archive(recommendationId)
        }
    }
    
    /**
     * Expire all old recommendations for a user.
     */
    suspend fun expireOld(userId: String, beforeTimestamp: Long = System.currentTimeMillis()) {
        withContext(ioDispatcher) {
            dao.expireOld(userId, beforeTimestamp)
        }
    }

    /**
     * Expire old recommendations for a user.
     * Alias kept to match follow-through infrastructure contract.
     */
    suspend fun expireAll(userId: String, beforeTimestamp: Long = System.currentTimeMillis()) {
        expireOld(userId, beforeTimestamp)
    }
    
    /**
     * Clear all recommendations for a user (account switch scenario).
     */
    suspend fun clearForUser(userId: String) {
        withContext(ioDispatcher) {
            dao.clearByUser(userId)
        }
    }
    
    /**
     * Get archived (dismissed) recommendations.
     */
    suspend fun getArchivedForUser(userId: String, limit: Int = 50): List<DashboardFollowThroughRecommendation> {
        return withContext(ioDispatcher) {
            dao.getArchived(userId, limit).map { it.toDomain() }
        }
    }
    
    /**
     * Get a specific recommendation by ID.
     */
    suspend fun getById(id: String): DashboardFollowThroughRecommendation? {
        return withContext(ioDispatcher) {
            dao.getById(id)?.toDomain()
        }
    }
    
    /**
     * Count active recommendations for a user.
     */
    suspend fun countActive(userId: String): Int {
        return withContext(ioDispatcher) {
            dao.countActive(userId)
        }
    }
    
    /**
     * Clean up expired recommendations (for background worker).
     */
    suspend fun cleanupExpired(): Int {
        return withContext(ioDispatcher) {
            dao.deleteExpired()
        }
    }
    
    // Mapping functions
    
    private fun RecommendationEntity.toDomain(): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = navigationTarget,
            filterCriteria = filterCriteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dismissedAt = dismissedAt,
            expiresAt = expiresAt,
            priority = priority,
            category = category,
            sourceArtifactId = sourceArtifactId,
            status = status
        )
    }
    
    private fun DashboardFollowThroughRecommendation.toEntity(): RecommendationEntity {
        return RecommendationEntity(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = navigationTarget,
            filterCriteria = filterCriteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dismissedAt = dismissedAt,
            expiresAt = expiresAt,
            priority = priority,
            category = category,
            sourceArtifactId = sourceArtifactId,
            status = status
        )
    }

    private fun RecommendationPriority.rank(): Int = when (this) {
        RecommendationPriority.HIGH -> 3
        RecommendationPriority.MEDIUM -> 2
        RecommendationPriority.LOW -> 1
    }
}
