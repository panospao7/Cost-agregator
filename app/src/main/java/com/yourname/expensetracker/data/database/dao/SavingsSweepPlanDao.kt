package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Savings Sweep Plans.
 *
 * Provides operations to:
 * - Create and update sweep plans
 * - Query plans by month, status, or goal
 * - Clean up old/expired plans
 */
@Dao
interface SavingsSweepPlanDao {

    /**
     * Insert a new sweep plan.
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(plan: SavingsSweepPlan): Long

    /**
     * Insert multiple sweep plans (for multi-goal allocation).
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(plans: List<SavingsSweepPlan>): List<Long>

    /**
     * Update an existing sweep plan.
     */
    @Update
    suspend fun update(plan: SavingsSweepPlan)

    /**
     * Delete a sweep plan.
     */
    @Delete
    suspend fun delete(plan: SavingsSweepPlan)

    /**
     * Get all sweep plans for a specific month end.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE monthEnd = :monthEnd")
    suspend fun getPlansForMonth(monthEnd: Long): List<SavingsSweepPlan>

    /**
     * Get all sweep plans for a specific month end as Flow.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE monthEnd = :monthEnd")
    fun getPlansForMonthFlow(monthEnd: Long): Flow<List<SavingsSweepPlan>>

    /**
     * Get all pending sweep plans.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE status = 'PENDING' ORDER BY computedAt DESC")
    suspend fun getPendingPlans(): List<SavingsSweepPlan>

    /**
     * Get all pending sweep plans as Flow.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE status = 'PENDING' ORDER BY computedAt DESC")
    fun getPendingPlansFlow(): Flow<List<SavingsSweepPlan>>

    /**
     * Get sweep plans for a specific goal.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE goalId = :goalId ORDER BY computedAt DESC")
    suspend fun getPlansForGoal(goalId: Long): List<SavingsSweepPlan>

    /**
     * Get the most recent sweep plan for a specific month.
     */
    @Query("SELECT * FROM savings_sweep_plan WHERE monthEnd = :monthEnd ORDER BY computedAt DESC LIMIT 1")
    suspend fun getMostRecentForMonth(monthEnd: Long): SavingsSweepPlan?

    /**
     * Check if a sweep plan exists for a specific month.
     */
    @Query("SELECT COUNT(*) FROM savings_sweep_plan WHERE monthEnd = :monthEnd")
    suspend fun hasPlanForMonth(monthEnd: Long): Int

    /**
     * Update plan status.
     *
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE savings_sweep_plan SET status = :status, actionedAt = :timestamp WHERE id = :planId")
    suspend fun updateStatus(planId: Long, status: SweepPlanStatus, timestamp: Long = System.currentTimeMillis())

    /**
     * Accept a sweep plan.
     *
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE savings_sweep_plan SET status = 'ACCEPTED', actionedAt = :timestamp WHERE id = :planId")
    suspend fun acceptPlan(planId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Dismiss a sweep plan.
     *
     * @param timestamp Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE savings_sweep_plan SET status = 'DISMISSED', actionedAt = :timestamp WHERE id = :planId")
    suspend fun dismissPlan(planId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark plans as expired for months that have passed.
     *
     * @param currentTime Defaults to [System.currentTimeMillis] for backward compat;
     *   production callers should pass [com.yourname.expensetracker.domain.util.TimeProvider.now] explicitly.
     */
    @Query("UPDATE savings_sweep_plan SET status = 'EXPIRED' WHERE monthEnd < :currentTime AND status = 'PENDING'")
    suspend fun expireOldPlans(currentTime: Long = System.currentTimeMillis())

    /**
     * Delete old sweep plans (for cleanup).
     */
    @Query("DELETE FROM savings_sweep_plan WHERE computedAt < :olderThan")
    suspend fun deleteOldPlans(olderThan: Long)

    /**
     * Get all sweep plans.
     */
    @Query("SELECT * FROM savings_sweep_plan ORDER BY computedAt DESC")
    suspend fun getAllPlans(): List<SavingsSweepPlan>

    /**
     * Get all sweep plans as Flow.
     */
    @Query("SELECT * FROM savings_sweep_plan ORDER BY computedAt DESC")
    fun getAllPlansFlow(): Flow<List<SavingsSweepPlan>>

    /**
     * Delete all sweep plans.
     */
    @Query("DELETE FROM savings_sweep_plan")
    suspend fun deleteAll()

    /**
     * Get total allocated amount for a specific goal (accepted plans only).
     */
    @Query("SELECT COALESCE(SUM(allocatedAmount), 0.0) FROM savings_sweep_plan WHERE goalId = :goalId AND status = 'ACCEPTED'")
    suspend fun getTotalAllocatedForGoal(goalId: Long): Double
}
