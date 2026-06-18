package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory LRU cache for dashboard recommendations with TTL support.
 * 
 * Provides fast access to frequently accessed recommendations while
 * falling back to the repository for cache misses.
 * 
 * Features:
 * - LRU eviction when cache exceeds limit
 * - 7-day TTL check on access
 * - Thread-safe operations
 */
@Singleton
class RecommendationCacheService @Inject constructor(
    private val repository: RecommendationRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val CACHE_SIZE = 50
        private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }
    
    private val mutex = Mutex()
    
    // LRU cache: true = access-order, false = insertion-order
    private val cache = object : LinkedHashMap<String, CacheEntry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean {
            return size > CACHE_SIZE
        }
    }
    
    private data class CacheEntry(
        val recommendation: DashboardFollowThroughRecommendation,
        val cachedAt: Long
    ) {
        fun isExpired(nowMillis: Long): Boolean {
            return (nowMillis - cachedAt) > TTL_MILLIS || DashboardFollowThroughRecommendation.isExpired(recommendation.expiresAt, nowMillis)
        }
    }
    
    /**
     * Get a recommendation by ID, checking cache first.
     */
    suspend fun getById(id: String): DashboardFollowThroughRecommendation? {
        return withContext(ioDispatcher) {
            val nowMillis = timeProvider.now()
            
            // Check cache
            mutex.withLock {
                val entry = cache[id]
                if (entry != null && !entry.isExpired(nowMillis)) {
                    return@withContext entry.recommendation
                } else if (entry != null) {
                    // Remove expired entry
                    cache.remove(id)
                }
            }
            
            // Cache miss or expired - fetch from repository
            val recommendation = repository.getById(id)
            
            // Update cache
            if (recommendation != null && recommendation.isActive(nowMillis)) {
                mutex.withLock {
                    cache[id] = CacheEntry(recommendation, timeProvider.now())
                }
            }
            
            recommendation
        }
    }
    
    /**
     * Put a recommendation into the cache.
     */
    suspend fun put(recommendation: DashboardFollowThroughRecommendation) {
        withContext(ioDispatcher) {
            mutex.withLock {
                cache[recommendation.id] = CacheEntry(recommendation, timeProvider.now())
            }
        }
    }
    
    /**
     * Put multiple recommendations into the cache.
     */
    suspend fun putAll(recommendations: List<DashboardFollowThroughRecommendation>) {
        withContext(ioDispatcher) {
            val now = timeProvider.now()
            mutex.withLock {
                recommendations.forEach { recommendation ->
                    cache[recommendation.id] = CacheEntry(recommendation, now)
                }
            }
        }
    }
    
    /**
     * Remove a recommendation from the cache.
     */
    suspend fun remove(id: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                cache.remove(id)
            }
        }
    }
    
    /**
     * Clear the entire cache.
     */
    suspend fun clear() {
        withContext(ioDispatcher) {
            mutex.withLock {
                cache.clear()
            }
        }
    }
    
    /**
     * Clear all cached recommendations for a specific user.
     */
    suspend fun clearForUser(userId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val keysToRemove = cache.entries
                    .filter { it.value.recommendation.userId == userId }
                    .map { it.key }
                
                keysToRemove.forEach { cache.remove(it) }
            }
        }
    }
    
    /**
     * Evict expired entries from the cache.
     */
    suspend fun evictExpired() {
        withContext(ioDispatcher) {
            val nowMillis = timeProvider.now()
            
            mutex.withLock {
                val keysToRemove = cache.entries
                    .filter { it.value.isExpired(nowMillis) }
                    .map { it.key }
                
                keysToRemove.forEach { cache.remove(it) }
            }
        }
    }
    
    /**
     * Get cache statistics for debugging.
     */
    suspend fun getStats(): CacheStats {
        return withContext(ioDispatcher) {
            mutex.withLock {
                CacheStats(
                    size = cache.size,
                    maxSize = CACHE_SIZE
                )
            }
        }
    }
    
    data class CacheStats(
        val size: Int,
        val maxSize: Int
    )
}
