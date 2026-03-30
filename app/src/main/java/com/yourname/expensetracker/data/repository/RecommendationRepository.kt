package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecommendationDao
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.service.RecommendationDeduplicator
import kotlinx.coroutines.CoroutineDispatcher
import timber.log.Timber
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
 * 
 * **Phase 3A**: Deduplication - uses [RecommendationDeduplicator] to prevent
 * duplicate cards for the same merchant, category, or analysis target.
 */
@Singleton
class RecommendationRepository @Inject constructor(
    private val dao: RecommendationDao,
    private val deduplicator: RecommendationDeduplicator,
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
     * 
     * **Phase 3A**: Deduplication applied before priority sorting.
     * Ensures no duplicate merchant/category/filter combinations.
     * Also checks against existing active recommendations in DB to prevent duplicates.
     */
    suspend fun saveAll(recommendations: List<DashboardFollowThroughRecommendation>) {
        withContext(ioDispatcher) {
            if (recommendations.isEmpty()) return@withContext
            
            // Step 1: Deduplicate within the batch
            val uniqueNew = deduplicator.deduplicate(recommendations)
            
            // Step 2: Get existing active recommendations to check for duplicates across calls
            val userId = recommendations.firstOrNull()?.userId ?: return@withContext
            val existingActive = dao.getActiveByUser(userId)
            val existingSignatures = existingActive.map { computeSignature(it) }.toSet()
            
            // Step 3: Filter out any new recommendations that already exist in DB
            val trulyNew = uniqueNew.filter { rec ->
                val sig = deduplicator.computeSignature(rec)
                sig !in existingSignatures
            }
            
            if (trulyNew.isEmpty()) {
                Timber.d("RecommendationRepository: All recommendations already exist, skipping insert")
                return@withContext
            }
            
            // Step 4: Sort by priority (HIGH > MEDIUM > LOW) and take top 5
            val topRecommendations = trulyNew
                .sortedWith(compareByDescending<DashboardFollowThroughRecommendation> { it.priority.rank() })
                .take(MAX_ACTIVE_RECOMMENDATIONS)
            
            dao.insertAll(topRecommendations.map { it.toEntity() })
        }
    }
    
    /**
     * Compute signature for database entity (navTarget + category + filter criteria hash).
     */
    private fun computeSignature(entity: RecommendationEntity): String {
        val parts = mutableListOf<String>()
        parts.add(entity.navigationTarget)
        parts.add(entity.category)
        // Simple hash of filterCriteria for comparison
        parts.add(entity.filterCriteria.hashCode().toString())
        return parts.joinToString(":")
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
