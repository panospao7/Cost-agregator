package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles invalidation of stale or expired recommendations.
 * 
 * Called when:
 * - Transactions are added/modified (trigger new recommendations)
 * - User switches accounts (clear all for old user)
 * - App lifecycle events (cleanup expired)
 * - Manual refresh requested
 */
@Singleton
class RecommendationInvalidator @Inject constructor(
    private val repository: RecommendationRepository,
    private val stateManager: RecommendationStateManager,
    private val cacheService: RecommendationCacheService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    
    /**
     * Invalidate all recommendations for a user and trigger a refresh.
     * 
     * Use this when:
     * - Major data changes occur (new transactions, category changes)
     * - User explicitly requests a refresh
     */
    suspend fun invalidateAllForUser(userId: String) {
        withContext(ioDispatcher) {
            try {
                // Clear cache
                cacheService.clearForUser(userId)
                
                // Expire old recommendations in database
                repository.expireOld(userId)
                
                // Trigger state refresh
                stateManager.refreshForUser(userId)
            } catch (e: Exception) {
                // Log error but don't throw - invalidation is best-effort
            }
        }
    }
    
    /**
     * Remove only stale/expired recommendations for a user.
     * 
     * Use this for periodic cleanup without triggering a full refresh.
     */
    suspend fun invalidateStale(userId: String) {
        withContext(ioDispatcher) {
            try {
                // Expire old recommendations
                repository.expireOld(userId)
                
                // Evict expired from cache
                cacheService.evictExpired()
                
                // Trigger state refresh to remove expired from UI
                stateManager.refreshForUser(userId)
            } catch (e: Exception) {
                // Log error but don't throw
            }
        }
    }
    
    /**
     * Clear all recommendations for a user (e.g., account switch).
     */
    suspend fun clearForUser(userId: String) {
        withContext(ioDispatcher) {
            try {
                // Clear from database
                repository.clearForUser(userId)
                
                // Clear from cache
                cacheService.clearForUser(userId)
                
                // Clear from state
                stateManager.clearForUser(userId)
            } catch (e: Exception) {
                // Log error but don't throw
            }
        }
    }
    
    /**
     * Global cleanup of expired recommendations across all users.
     * 
     * Use this in a background worker or on app startup.
     */
    suspend fun cleanupExpired(): Int {
        return withContext(ioDispatcher) {
            try {
                // Evict from cache
                cacheService.evictExpired()
                
                // Delete from database
                repository.cleanupExpired()
            } catch (e: Exception) {
                // Log error
                0
            }
        }
    }
    
    /**
     * Trigger a state refresh without clearing cache or database.
     * 
     * Use this when recommendations might have changed but cache is still valid.
     */
    fun refreshStateForUser(userId: String) {
        stateManager.refreshForUser(userId)
    }
}
