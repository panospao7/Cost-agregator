package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentEvent
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentRecommendation
import com.yourname.expensetracker.data.database.entity.RecommendationStatus
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Budget Adjustment Recommendations and Events.
 */
@Dao
interface BudgetAdjustmentDao {
    
    // ==================== RECOMMENDATIONS ====================
    
    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecommendation(recommendation: BudgetAdjustmentRecommendation): Long
    
    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecommendations(recommendations: List<BudgetAdjustmentRecommendation>): List<Long>
    
    @Update
    suspend fun updateRecommendation(recommendation: BudgetAdjustmentRecommendation)
    
    @Delete
    suspend fun deleteRecommendation(recommendation: BudgetAdjustmentRecommendation)
    
    @Query("SELECT * FROM budget_adjustment_recommendations WHERE id = :id")
    suspend fun getRecommendationById(id: Long): BudgetAdjustmentRecommendation?
    
    @Query("SELECT * FROM budget_adjustment_recommendations WHERE budgetId = :budgetId ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getLatestRecommendationForBudget(budgetId: Long): BudgetAdjustmentRecommendation?
    
    @Query("SELECT * FROM budget_adjustment_recommendations WHERE status = :status ORDER BY generatedAt DESC")
    fun getRecommendationsByStatus(status: RecommendationStatus): Flow<List<BudgetAdjustmentRecommendation>>
    
    @Query("SELECT * FROM budget_adjustment_recommendations WHERE status = 'PENDING' ORDER BY confidence DESC, generatedAt DESC")
    fun getPendingRecommendations(): Flow<List<BudgetAdjustmentRecommendation>>
    
    @Query("SELECT * FROM budget_adjustment_recommendations ORDER BY generatedAt DESC")
    fun getAllRecommendations(): Flow<List<BudgetAdjustmentRecommendation>>
    
    @Query("SELECT * FROM budget_adjustment_recommendations WHERE generatedAt > :since ORDER BY confidence DESC")
    suspend fun getRecentRecommendations(since: Long): List<BudgetAdjustmentRecommendation>
    
    /**
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE budget_adjustment_recommendations SET status = 'APPLIED', appliedAt = :timestamp WHERE id = :id")
    suspend fun markRecommendationApplied(id: Long, timestamp: Long = System.currentTimeMillis())
    
    /**
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE budget_adjustment_recommendations SET status = 'DISMISSED', dismissedAt = :timestamp WHERE id = :id")
    suspend fun markRecommendationDismissed(id: Long, timestamp: Long = System.currentTimeMillis())
    
    /**
     * @param now Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE budget_adjustment_recommendations SET status = 'EXPIRED' WHERE status = 'PENDING' AND expiresAt < :now")
    suspend fun expireOldRecommendations(now: Long = System.currentTimeMillis())
    
    @Query("DELETE FROM budget_adjustment_recommendations WHERE status = 'EXPIRED' AND generatedAt < :before")
    suspend fun deleteExpiredRecommendations(before: Long)
    
    @Query("DELETE FROM budget_adjustment_recommendations WHERE status = 'DISMISSED' AND dismissedAt < :before")
    suspend fun deleteOldDismissedRecommendations(before: Long)
    
    @Query("SELECT COUNT(*) FROM budget_adjustment_recommendations WHERE status = 'PENDING'")
    suspend fun getPendingRecommendationsCount(): Int
    
    @Query("SELECT EXISTS(SELECT 1 FROM budget_adjustment_recommendations WHERE budgetId = :budgetId AND status = 'PENDING' LIMIT 1)")
    suspend fun hasPendingRecommendationForBudget(budgetId: Long): Boolean
    
    // ==================== EVENTS ====================
    
    @Insert
    suspend fun insertEvent(event: BudgetAdjustmentEvent): Long
    
    @Insert
    suspend fun insertEvents(events: List<BudgetAdjustmentEvent>): List<Long>
    
    @Query("SELECT * FROM budget_adjustment_events WHERE id = :id")
    suspend fun getEventById(id: Long): BudgetAdjustmentEvent?
    
    @Query("SELECT * FROM budget_adjustment_events WHERE budgetId = :budgetId ORDER BY appliedAt DESC")
    fun getEventsForBudget(budgetId: Long): Flow<List<BudgetAdjustmentEvent>>
    
    @Query("SELECT * FROM budget_adjustment_events ORDER BY appliedAt DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 50): List<BudgetAdjustmentEvent>
    
    @Query("SELECT * FROM budget_adjustment_events WHERE appliedAt > :since ORDER BY appliedAt DESC")
    suspend fun getEventsSince(since: Long): List<BudgetAdjustmentEvent>
    
    @Query("SELECT COUNT(*) FROM budget_adjustment_events WHERE budgetId = :budgetId")
    suspend fun getEventCountForBudget(budgetId: Long): Int
    
    @Query("SELECT AVG(ABS(delta)) FROM budget_adjustment_events WHERE budgetId = :budgetId AND appliedAt > :since")
    suspend fun getAverageAdjustmentForBudget(budgetId: Long, since: Long): Double?
    
    @Delete
    suspend fun deleteEvent(event: BudgetAdjustmentEvent)
    
    @Query("DELETE FROM budget_adjustment_events WHERE appliedAt < :before")
    suspend fun deleteOldEvents(before: Long)
    
    // ==================== ANALYTICS ====================
    
    @Query("""
        SELECT 
            COUNT(*) as totalCount,
            SUM(CASE WHEN status = 'APPLIED' THEN 1 ELSE 0 END) as appliedCount,
            SUM(CASE WHEN status = 'DISMISSED' THEN 1 ELSE 0 END) as dismissedCount,
            SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END) as expiredCount,
            AVG(CASE WHEN status = 'APPLIED' THEN confidence ELSE NULL END) as avgAppliedConfidence
        FROM budget_adjustment_recommendations
        WHERE generatedAt > :since
    """)
    suspend fun getRecommendationStats(since: Long): RecommendationStats
    
    @Query("""
        SELECT 
            budgetId,
            COUNT(*) as adjustmentCount,
            AVG(ABS(delta)) as avgAdjustmentAmount,
            MAX(appliedAt) as lastAdjustmentAt
        FROM budget_adjustment_events
        WHERE appliedAt > :since
        GROUP BY budgetId
    """)
    suspend fun getAdjustmentFrequencyByBudget(since: Long): List<BudgetAdjustmentFrequency>
}

/**
 * Statistics for recommendation effectiveness.
 */
data class RecommendationStats(
    val totalCount: Int,
    val appliedCount: Int,
    val dismissedCount: Int,
    val expiredCount: Int,
    val avgAppliedConfidence: Double?
)

/**
 * Adjustment frequency per budget.
 */
data class BudgetAdjustmentFrequency(
    val budgetId: Long,
    val adjustmentCount: Int,
    val avgAdjustmentAmount: Double,
    val lastAdjustmentAt: Long
)
