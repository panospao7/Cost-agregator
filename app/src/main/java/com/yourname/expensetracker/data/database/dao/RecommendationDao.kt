package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for dashboard follow-through recommendations.
 */
@Dao
interface RecommendationDao {
    
    /**
     * Get all active (non-archived, non-expired) recommendations for a user.
     * Limited to 5 most recent recommendations.
     */
    @Query("""
        SELECT * FROM recommendations
        WHERE userId = :userId 
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
        ORDER BY CASE priority
            WHEN 'HIGH' THEN 3
            WHEN 'MEDIUM' THEN 2
            ELSE 1
        END DESC,
        createdAt DESC,
        id ASC
        LIMIT 5
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun getActiveByUser(userId: String, nowMillis: Long): List<RecommendationEntity>

    /**
     * Get the full active recommendation set for a user without the UI-facing cap.
     * Used by repository cap enforcement to inspect and prune overflow rows.
     */
    @Query("""
        SELECT * FROM recommendations
        WHERE userId = :userId
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
        ORDER BY CASE priority
            WHEN 'HIGH' THEN 3
            WHEN 'MEDIUM' THEN 2
            ELSE 1
        END DESC,
        createdAt DESC,
        id ASC
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun getAllActiveByUser(userId: String, nowMillis: Long): List<RecommendationEntity>
    
    /**
     * Observe active recommendations for reactive UI updates.
     */
    @Query("""
        SELECT * FROM recommendations
        WHERE userId = :userId 
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
        ORDER BY CASE priority
            WHEN 'HIGH' THEN 3
            WHEN 'MEDIUM' THEN 2
            ELSE 1
        END DESC,
        createdAt DESC,
        id ASC
        LIMIT 5
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    fun observeActiveByUser(userId: String, nowMillis: Long): Flow<List<RecommendationEntity>>
    
    /**
     * Insert a new recommendation.
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recommendation: RecommendationEntity)
    
    /**
     * Insert multiple recommendations.
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(recommendations: List<RecommendationEntity>)
    
    /**
     * Update an existing recommendation.
     */
    @Update
    suspend fun update(recommendation: RecommendationEntity)
    
    /**
     * Archive a recommendation (user dismissed it).
     */
    @Query("""
        UPDATE recommendations 
        SET status = 'ARCHIVED', 
            dismissedAt = :nowMillis,
            updatedAt = :nowMillis
        WHERE id = :id
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun archive(id: String, nowMillis: Long)

    /**
     * Archive active recommendations outside the retained active set.
     */
    @Query("""
        UPDATE recommendations
        SET status = 'ARCHIVED',
            dismissedAt = :nowMillis,
            updatedAt = :nowMillis
        WHERE userId = :userId
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
          AND id NOT IN (:retainedIds)
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun archiveActiveOverflow(
        userId: String,
        retainedIds: List<String>,
        nowMillis: Long
    ): Int
    
    /**
     * Expire old recommendations in bulk.
     */
    @Query("""
        UPDATE recommendations
        SET status = 'EXPIRED',
            updatedAt = :nowMillis
        WHERE userId = :userId
          AND expiresAt < :beforeTimestamp
          AND status != 'EXPIRED'
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun expireOld(userId: String, beforeTimestamp: Long, nowMillis: Long)

    @Query("""
        UPDATE recommendations
        SET status = 'EXPIRED',
            updatedAt = :nowMillis
        WHERE userId = :userId
          AND status = 'ACTIVE'
    """)
    /**
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    suspend fun expireAllActiveByUser(userId: String, nowMillis: Long): Int
    
    /**
     * Clear all recommendations for a user (e.g., account switch).
     */
    @Query("DELETE FROM recommendations WHERE userId = :userId")
    suspend fun clearByUser(userId: String)
    
    /**
     * Get archived (dismissed) recommendations for a user.
     */
    @Query("""
        SELECT * FROM recommendations
        WHERE userId = :userId 
          AND status = 'ARCHIVED'
        ORDER BY dismissedAt DESC
        LIMIT :limit
    """)
    suspend fun getArchived(userId: String, limit: Int = 50): List<RecommendationEntity>
    
    /**
     * Get a specific recommendation by ID.
     */
    @Query("SELECT * FROM recommendations WHERE id = :id")
    suspend fun getById(id: String): RecommendationEntity?
    
    /**
     * Count active recommendations for a user.
     */
    /**
     * Count active recommendations for a user.
     *
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    @Query("""
        SELECT COUNT(*) FROM recommendations
        WHERE userId = :userId 
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
    """)
    suspend fun countActive(userId: String, nowMillis: Long): Int
    
    /**
     * Delete all expired recommendations (cleanup).
     *
     * @param nowMillis Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    @Query("DELETE FROM recommendations WHERE expiresAt < :nowMillis AND status = 'EXPIRED'")
    suspend fun deleteExpired(nowMillis: Long): Int
}
