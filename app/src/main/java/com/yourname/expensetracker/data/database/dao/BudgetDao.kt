package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(budget: Budget): Long

    @Update
    suspend fun update(budget: Budget)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): Budget?

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>

    @Query("SELECT COUNT(*) FROM budgets")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(budgets: List<Budget>)

    // ── Transactional active-budget switching helpers ─────────────────

    /**
     * Atomically inserts a new overall budget and deactivates any previously
     * active overall budget.  This is the **only** safe path for setting a
     * new active overall budget — callers must never rely on silent REPLACE
     * semantics, which would drop history/notification fields on the old row.
     */
    @Transaction
    suspend fun insertAndActivateOverall(budget: Budget): Long {
        // Deactivate every currently-active overall budget (id 0 matches none
        // during the deactivation pass; the real keepId is applied after insert).
        @Suppress("KotlinConstantConditions")
        deactivateAllActiveOverallBudgets()
        return insert(budget)
    }

    /**
     * Atomically inserts a new category budget and deactivates any previously
     * active budget for the same [Budget.categoryId].
     */
    @Transaction
    suspend fun insertAndActivateCategory(budget: Budget): Long {
        val catId = requireNotNull(budget.categoryId) {
            "insertAndActivateCategory requires a non-null categoryId"
        }
        deactivateAllActiveCategoryBudgets(catId)
        return insert(budget)
    }

    /** Deactivate **all** active overall budgets (categoryId IS NULL). */
    @Query("UPDATE budgets SET isActive = 0 WHERE categoryId IS NULL AND isActive = 1")
    suspend fun deactivateAllActiveOverallBudgets()

    /** Deactivate **all** active budgets for [categoryId]. */
    @Query("UPDATE budgets SET isActive = 0 WHERE categoryId = :categoryId AND isActive = 1")
    suspend fun deactivateAllActiveCategoryBudgets(categoryId: Long)

    @Query("SELECT * FROM budgets")
    fun getAllFlow(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    suspend fun getActiveBudgets(): List<Budget>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun getActiveBudgetsFlow(): Flow<List<Budget>>

    /**
     * Returns the single active overall budget (categoryId IS NULL).
     * Deterministic: ordered by id DESC so the most-recently-created row wins
     * when the partial unique index is not yet in place on legacy data.
     */
    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND isActive = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getOverallBudget(): Budget?

    /**
     * Returns the single active budget for [categoryId].
     * Deterministic: ordered by id DESC so the most-recently-created row wins.
     */
    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND isActive = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getByCategory(categoryId: Long): Budget?

    /**
     * Deactivate all active overall budgets except the one with [keepId].
     * Used to enforce single-active-overall-budget invariant.
     */
    @Query("UPDATE budgets SET isActive = 0 WHERE categoryId IS NULL AND isActive = 1 AND id != :keepId")
    suspend fun deactivateOtherOverallBudgets(keepId: Long)

    /**
     * Deactivate all active budgets for [categoryId] except the one with [keepId].
     * Used to enforce single-active-category-budget invariant.
     */
    @Query("UPDATE budgets SET isActive = 0 WHERE categoryId = :categoryId AND isActive = 1 AND id != :keepId")
    suspend fun deactivateOtherCategoryBudgets(categoryId: Long, keepId: Long)

    @Query("UPDATE budgets SET lastWarningNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateWarningNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastCriticalNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateCriticalNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastExceededNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateExceededNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}
