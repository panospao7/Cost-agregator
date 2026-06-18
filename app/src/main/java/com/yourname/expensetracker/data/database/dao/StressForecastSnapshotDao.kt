package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.StressForecastSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing and managing stress forecast snapshot records.
 */
@Dao
interface StressForecastSnapshotDao {
    
    /**
     * Insert a new stress forecast snapshot.
     */
    @Insert
    suspend fun insert(snapshot: StressForecastSnapshot): Long
    
    /**
     * Get the most recent stress forecast snapshots.
     */
    @Query("SELECT * FROM stress_forecast_snapshots ORDER BY computedAt DESC LIMIT :limit")
    suspend fun getRecentSnapshots(limit: Int = 30): List<StressForecastSnapshot>
    
    /**
     * Get stress forecast snapshots as a Flow for reactive updates.
     */
    @Query("SELECT * FROM stress_forecast_snapshots ORDER BY computedAt DESC LIMIT :limit")
    fun getRecentSnapshotsFlow(limit: Int = 30): Flow<List<StressForecastSnapshot>>
    
    /**
     * Get the most recent stress forecast snapshot.
     */
    @Query("SELECT * FROM stress_forecast_snapshots ORDER BY computedAt DESC LIMIT 1")
    suspend fun getMostRecent(): StressForecastSnapshot?
    
    /**
     * Get snapshots within a specific date range.
     */
    @Query("SELECT * FROM stress_forecast_snapshots WHERE computedAt >= :startTime AND computedAt <= :endTime ORDER BY computedAt DESC")
    suspend fun getSnapshotsBetween(startTime: Long, endTime: Long): List<StressForecastSnapshot>
    
    /**
     * Get trend of overall risk level over time.
     */
    @Query("SELECT overallRiskLevel FROM stress_forecast_snapshots WHERE computedAt >= :since ORDER BY computedAt ASC")
    suspend fun getRiskTrendSince(since: Long): List<String>
    
    /**
     * Get average probability of crunch over the last N snapshots.
     */
    @Query("SELECT AVG((days30ProbabilityOfCrunch + days60ProbabilityOfCrunch + days90ProbabilityOfCrunch) / 3.0) FROM stress_forecast_snapshots WHERE computedAt >= :since")
    suspend fun getAverageCrunchProbabilitySince(since: Long): Double?
    
    /**
     * Count snapshots by risk level.
     */
    @Query("SELECT COUNT(*) FROM stress_forecast_snapshots WHERE overallRiskLevel = :riskLevel AND computedAt >= :since")
    suspend fun countByRiskLevel(riskLevel: String, since: Long): Int
    
    /**
     * Delete old snapshots beyond a certain date.
     */
    @Query("DELETE FROM stress_forecast_snapshots WHERE computedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int
    
    /**
     * Count total snapshots.
     */
    @Query("SELECT COUNT(*) FROM stress_forecast_snapshots")
    suspend fun count(): Int
    
    /**
     * Get the oldest snapshot timestamp.
     */
    @Query("SELECT MIN(computedAt) FROM stress_forecast_snapshots")
    suspend fun getOldestSnapshotTime(): Long?
    
    /**
     * Delete all snapshots.
     */
    @Query("DELETE FROM stress_forecast_snapshots")
    suspend fun deleteAll()
    
    /**
     * Get snapshots with crunch risk (for alerts/notifications).
     */
    @Query("SELECT * FROM stress_forecast_snapshots WHERE overallRiskLevel IN ('ELEVATED', 'HIGH', 'CRITICAL') AND computedAt >= :since ORDER BY computedAt DESC LIMIT :limit")
    suspend fun getRiskySnapshots(since: Long, limit: Int = 10): List<StressForecastSnapshot>
    
    /**
     * Check if there are any critical risk snapshots since a given time.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM stress_forecast_snapshots WHERE overallRiskLevel = 'CRITICAL' AND computedAt >= :since LIMIT 1)")
    suspend fun hasCriticalRiskSince(since: Long): Boolean
}
