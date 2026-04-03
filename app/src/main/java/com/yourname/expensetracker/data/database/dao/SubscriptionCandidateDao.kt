package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing detected subscription candidates from notifications.
 */
@Dao
interface SubscriptionCandidateDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candidate: SubscriptionCandidate): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<SubscriptionCandidate>): List<Long>
    
    @Update
    suspend fun update(candidate: SubscriptionCandidate)
    
    @Delete
    suspend fun delete(candidate: SubscriptionCandidate)
    
    @Query("DELETE FROM subscription_candidates WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("SELECT * FROM subscription_candidates WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionCandidate?
    
    @Query("SELECT * FROM subscription_candidates WHERE canonicalMerchant = :canonicalMerchant LIMIT 1")
    suspend fun getByCanonicalMerchant(canonicalMerchant: String): SubscriptionCandidate?
    
    /**
     * Get all pending (not yet converted or rejected) candidates ordered by confidence.
     */
    @Query("""
        SELECT * FROM subscription_candidates 
        WHERE isConverted = 0 AND userAction = 'pending' 
        ORDER BY confidence DESC, lastSeen DESC
    """)
    fun getPendingCandidatesFlow(): Flow<List<SubscriptionCandidate>>
    
    /**
     * Get all pending candidates as a one-shot query.
     */
    @Query("""
        SELECT * FROM subscription_candidates 
        WHERE isConverted = 0 AND userAction = 'pending' 
        ORDER BY confidence DESC, lastSeen DESC
    """)
    suspend fun getPendingCandidates(): List<SubscriptionCandidate>
    
    /**
     * Get high-confidence candidates above a threshold.
     */
    @Query("""
        SELECT * FROM subscription_candidates 
        WHERE isConverted = 0 AND userAction = 'pending' AND confidence >= :minConfidence
        ORDER BY confidence DESC
    """)
    suspend fun getHighConfidenceCandidates(minConfidence: Double = 0.7): List<SubscriptionCandidate>
    
    /**
     * Mark a candidate as converted to an active subscription.
     */
    @Query("""
        UPDATE subscription_candidates 
        SET isConverted = 1, 
            convertedSubscriptionId = :subscriptionId, 
            userAction = 'accepted',
            updatedAt = :timestamp
        WHERE id = :candidateId
    """)
    suspend fun markAsConverted(candidateId: Long, subscriptionId: Long, timestamp: Long = System.currentTimeMillis())
    
    /**
     * Mark a candidate as rejected by the user.
     */
    @Query("""
        UPDATE subscription_candidates 
        SET userAction = 'rejected', 
            updatedAt = :timestamp
        WHERE id = :candidateId
    """)
    suspend fun markAsRejected(candidateId: Long, timestamp: Long = System.currentTimeMillis())
    
    /**
     * Get count of pending candidates.
     */
    @Query("SELECT COUNT(*) FROM subscription_candidates WHERE isConverted = 0 AND userAction = 'pending'")
    suspend fun getPendingCount(): Int
    
    /**
     * Get count of pending candidates as Flow for UI observation.
     */
    @Query("SELECT COUNT(*) FROM subscription_candidates WHERE isConverted = 0 AND userAction = 'pending'")
    fun getPendingCountFlow(): Flow<Int>
    
    /**
     * Delete old rejected candidates (cleanup).
     */
    @Query("""
        DELETE FROM subscription_candidates 
        WHERE userAction = 'rejected' AND updatedAt < :cutoffTime
    """)
    suspend fun deleteOldRejected(cutoffTime: Long)
    
    /**
     * Get all candidates for a specific merchant.
     */
    @Query("SELECT * FROM subscription_candidates WHERE canonicalMerchant = :canonicalMerchant")
    suspend fun getAllForMerchant(canonicalMerchant: String): List<SubscriptionCandidate>
    
    /**
     * Check if a merchant already has a pending candidate.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM subscription_candidates 
            WHERE canonicalMerchant = :canonicalMerchant 
            AND isConverted = 0 
            AND userAction = 'pending'
        )
    """)
    suspend fun hasPendingCandidate(canonicalMerchant: String): Boolean
}
