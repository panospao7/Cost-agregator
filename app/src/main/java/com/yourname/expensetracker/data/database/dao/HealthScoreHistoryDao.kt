package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.HealthScoreHistory
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing and managing health score history records.
 */
@Dao
interface HealthScoreHistoryDao {
    
    /**
     * Insert a new health score history record.
     */
    @Insert
    suspend fun insert(history: HealthScoreHistory): Long

    /**
     * Update an existing health score history record.
     */
    @Update
    suspend fun update(history: HealthScoreHistory)
    
    /**
     * Get the most recent health score records, ordered by calculation time descending.
     */
    @Query("SELECT * FROM health_score_history ORDER BY calculatedAt DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int = 30): List<HealthScoreHistory>
    
    /**
     * Get health score history as a Flow for reactive updates.
     */
    @Query("SELECT * FROM health_score_history ORDER BY calculatedAt DESC LIMIT :limit")
    fun getRecentHistoryFlow(limit: Int = 30): Flow<List<HealthScoreHistory>>
    
    /**
     * Get the most recent health score calculation.
     */
    @Query("SELECT * FROM health_score_history ORDER BY calculatedAt DESC LIMIT 1")
    suspend fun getMostRecent(): HealthScoreHistory?

    @Query("SELECT * FROM health_score_history WHERE NOT (periodStart = :periodStart AND periodEnd = :periodEnd) AND periodEnd <= :periodStart ORDER BY periodEnd DESC, calculatedAt DESC LIMIT 1")
    suspend fun getMostRecentBefore(periodStart: Long, periodEnd: Long): HealthScoreHistory?
    
    /**
     * Get health score history for a specific time period.
     */
    @Query("SELECT * FROM health_score_history WHERE periodStart = :periodStart AND periodEnd = :periodEnd ORDER BY calculatedAt DESC")
    suspend fun getHistoryForPeriod(periodStart: Long, periodEnd: Long): List<HealthScoreHistory>
    
    /**
     * Get the average overall score over the last N days.
     */
    @Query("SELECT AVG(overallScore) FROM health_score_history WHERE calculatedAt >= :since")
    suspend fun getAverageScoreSince(since: Long): Double?
    
    /**
     * Get history records within a specific date range.
     */
    @Query("SELECT * FROM health_score_history WHERE calculatedAt >= :startTime AND calculatedAt <= :endTime ORDER BY calculatedAt DESC")
    suspend fun getHistoryBetween(startTime: Long, endTime: Long): List<HealthScoreHistory>
    
    /**
     * Delete old history records beyond a certain date.
     */
    @Query("DELETE FROM health_score_history WHERE calculatedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int
    
    /**
     * Count total history records.
     */
    @Query("SELECT COUNT(*) FROM health_score_history")
    suspend fun count(): Int
    
    /**
     * Get the oldest record timestamp.
     */
    @Query("SELECT MIN(calculatedAt) FROM health_score_history")
    suspend fun getOldestRecordTime(): Long?
    
    /**
     * Delete all history records.
     */
    @Query("DELETE FROM health_score_history")
    suspend fun deleteAll()
}
