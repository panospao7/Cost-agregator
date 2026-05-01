package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecommendationDao
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.util.TimeProvider
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
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    companion object {
        private const val MAX_ACTIVE_RECOMMENDATIONS = 5
    }
    
    /**
     * Get active recommendations for a user as a Flow.
     */
    fun observeActiveForUser(userId: String): Flow<List<DashboardFollowThroughRecommendation>> {
        val nowMillis = timeProvider.now()
        return dao.observeActiveByUser(userId, nowMillis).map { entities ->
            entities.map { it.toDomain() }
        }
    }
    
    /**
     * Get active recommendations for a user (one-shot).
     */
    suspend fun getActiveForUser(userId: String): List<DashboardFollowThroughRecommendation> {
        return withContext(ioDispatcher) {
            dao.getActiveByUser(userId, timeProvider.now()).map { it.toDomain() }
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
            
            val userId = recommendations.firstOrNull()?.userId ?: return@withContext
            val nowMillis = timeProvider.now()

            // Step 1: Deduplicate within the batch
            val uniqueNew = deduplicator.deduplicate(recommendations)

            // Step 2: Get the full existing active set to check for duplicates and prune overflow
            val existingActive = dao.getAllActiveByUser(userId, nowMillis)
            val existingSignatures = existingActive.map { computeSignature(it) }.toSet()

            // Step 3: Filter out any new recommendations that already exist in DB
            val trulyNew = uniqueNew.filter { rec ->
                val sig = deduplicator.computeSignature(rec)
                sig !in existingSignatures
            }

            // Step 4: Merge existing + new, then keep only the global top 5 ACTIVE rows.
            val mergedRanked = (existingActive + trulyNew.map { it.toEntity() })
                .sortedWith(activeRecommendationComparator())

            val retained = mergedRanked.take(MAX_ACTIVE_RECOMMENDATIONS)
            val retainedIds = retained.map { it.id }
            val retainedIdSet = retainedIds.toSet()

            val retainedNew = trulyNew.filter { it.id in retainedIdSet }
            if (retainedNew.isNotEmpty()) {
                dao.insertAll(retainedNew.map { it.toEntity() })
            }

            val existingOverflowIds = existingActive
                .asSequence()
                .map { it.id }
                .filterNot { it in retainedIdSet }
                .toList()

            if (existingOverflowIds.isNotEmpty()) {
                dao.archiveActiveOverflow(userId, retainedIds, nowMillis)
                Timber.d(
                    "RecommendationRepository: Archived %d overflow active recommendations for user %s",
                    existingOverflowIds.size,
                    userId
                )
            }

            if (trulyNew.isEmpty()) {
                Timber.d("RecommendationRepository: All recommendations already exist, pruned active set if needed")
            }
        }
    }
    
    /**
     * Compute signature for database entity (navTarget + filter criteria hash).
     */
    private fun computeSignature(entity: RecommendationEntity): String {
        return deduplicator.computeSignature(entity.toDomain())
    }

    private fun activeRecommendationComparator(): Comparator<RecommendationEntity> {
        return compareByDescending<RecommendationEntity> { it.priority.rank }
            .thenByDescending { it.createdAt }
            .thenBy { it.id }
    }
    
    /**
     * Dismiss (archive) a recommendation by ID.
     */
    suspend fun dismiss(recommendationId: String) {
        withContext(ioDispatcher) {
            dao.archive(recommendationId, timeProvider.now())
        }
    }
    
    /**
     * Expire all old recommendations for a user.
     */
    suspend fun expireOld(userId: String, beforeTimestamp: Long = timeProvider.now()) {
        withContext(ioDispatcher) {
            dao.expireOld(userId, beforeTimestamp)
        }
    }

    /**
     * Expire old recommendations for a user.
     * Alias kept to match follow-through infrastructure contract.
     */
    suspend fun expireAll(userId: String, beforeTimestamp: Long = timeProvider.now()) {
        withContext(ioDispatcher) {
            dao.expireOld(userId, beforeTimestamp)
            dao.expireAllActiveByUser(userId, timeProvider.now())
        }
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
            dao.countActive(userId, timeProvider.now())
        }
    }
    
    /**
     * Clean up expired recommendations (for background worker).
     */
    suspend fun cleanupExpired(): Int {
        return withContext(ioDispatcher) {
            dao.deleteExpired(timeProvider.now())
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
}
