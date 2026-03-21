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
        createdAt DESC
        LIMIT 5
    """)
    suspend fun getActiveByUser(userId: String, nowMillis: Long = System.currentTimeMillis()): List<RecommendationEntity>
    
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
        createdAt DESC
        LIMIT 5
    """)
    fun observeActiveByUser(userId: String, nowMillis: Long = System.currentTimeMillis()): Flow<List<RecommendationEntity>>
    
    /**
     * Insert a new recommendation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recommendation: RecommendationEntity)
    
    /**
     * Insert multiple recommendations.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
    suspend fun archive(id: String, nowMillis: Long = System.currentTimeMillis())
    
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
    suspend fun expireOld(userId: String, beforeTimestamp: Long, nowMillis: Long = System.currentTimeMillis())
    
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
    @Query("""
        SELECT COUNT(*) FROM recommendations
        WHERE userId = :userId 
          AND status = 'ACTIVE'
          AND dismissedAt IS NULL
          AND expiresAt > :nowMillis
    """)
    suspend fun countActive(userId: String, nowMillis: Long = System.currentTimeMillis()): Int
    
    /**
     * Delete all expired recommendations (cleanup).
     */
    @Query("DELETE FROM recommendations WHERE expiresAt < :nowMillis AND status = 'EXPIRED'")
    suspend fun deleteExpired(nowMillis: Long = System.currentTimeMillis()): Int
}
