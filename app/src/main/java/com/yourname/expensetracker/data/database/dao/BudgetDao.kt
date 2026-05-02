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
     * active overall budget.
     */
    @Transaction
    suspend fun insertAndActivateOverall(budget: Budget): Long {
        require(budget.isActive) {
            "insertAndActivateOverall requires an active budget"
        }
        // Deactivate every currently-active overall budget (id 0 matches none
        // during the deactivation pass; the real keepId is applied after insert).
        @Suppress("KotlinConstantConditions")
        deactivateAllActiveOverallBudgets()
        return insert(budget.copy(
            activeOverallKey = 1,
            activeCategoryKey = null
        ))
    }

    /**
     * Atomically inserts a new category budget and deactivates any previously
     * active budget for the same [Budget.categoryId].
     */
    @Transaction
    suspend fun insertAndActivateCategory(budget: Budget): Long {
        require(budget.isActive) {
            "insertAndActivateCategory requires an active budget"
        }
        val catId = requireNotNull(budget.categoryId) {
            "insertAndActivateCategory requires a non-null categoryId"
        }
        deactivateAllActiveCategoryBudgets(catId)
        return insert(budget.copy(
            activeOverallKey = null,
            activeCategoryKey = catId
        ))
    }

    /**
     * Updates a budget while enforcing the single-active-budget invariant for
     * the budget's target scope.
     */
    @Transaction
    suspend fun updateAndEnforceActiveScope(budget: Budget) {
        val existing = getById(budget.id)
        if (existing == null) {
            update(budget)
            return
        }

        if (budget.isActive) {
            when (val categoryId = budget.categoryId) {
                null -> deactivateOtherOverallBudgets(budget.id)
                else -> deactivateOtherCategoryBudgets(categoryId, budget.id)
            }
        }

        // Set materialized keys based on active state and scope
        val updatedBudget = if (budget.isActive) {
            when (budget.categoryId) {
                null -> budget.copy(activeOverallKey = 1, activeCategoryKey = null)
                else -> budget.copy(activeOverallKey = null, activeCategoryKey = budget.categoryId)
            }
        } else {
            budget.copy(activeOverallKey = null, activeCategoryKey = null)
        }

        update(updatedBudget)
    }

    /**
     * Toggles a budget's active state while enforcing the single-active-budget
     * invariant when activating. Also updates materialized keys.
     */
    @Transaction
    suspend fun setActiveAndEnforceScope(id: Long, isActive: Boolean) {
        if (isActive) {
            val budget = getById(id)
            when (budget?.categoryId) {
                null -> if (budget != null) deactivateOtherOverallBudgets(id)
                else -> deactivateOtherCategoryBudgets(budget.categoryId, id)
            }
        }

        setActive(id, isActive)
    }

    /**
     * Replaces all budgets while replaying active-budget enforcement for each
     * inserted row so restored snapshots cannot leave conflicting active rows.
     */
    @Transaction
    suspend fun replaceAllAndEnforceActiveScopes(budgets: List<Budget>) {
        deleteAll()
        budgets.forEach { budget ->
            when {
                !budget.isActive -> insert(budget.copy(activeOverallKey = null, activeCategoryKey = null))
                budget.categoryId == null -> insertAndActivateOverall(budget)
                else -> insertAndActivateCategory(budget)
            }
        }
    }

    /** Deactivate **all** active overall budgets (categoryId IS NULL), clearing their materialized keys. */
    @Query("""
        UPDATE budgets SET isActive = 0,
            activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId IS NULL AND isActive = 1
    """)
    suspend fun deactivateAllActiveOverallBudgets()

    /** Deactivate **all** active budgets for [categoryId], clearing their materialized keys. */
    @Query("""
        UPDATE budgets SET isActive = 0,
            activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId = :categoryId AND isActive = 1
    """)
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
     * Deactivate all active overall budgets except the one with [keepId],
     * clearing their materialized keys.
     * Used to enforce single-active-overall-budget invariant.
     */
    @Query("""
        UPDATE budgets SET isActive = 0,
            activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId IS NULL AND isActive = 1 AND id != :keepId
    """)
    suspend fun deactivateOtherOverallBudgets(keepId: Long)

    /**
     * Deactivate all active budgets for [categoryId] except the one with [keepId],
     * clearing their materialized keys.
     * Used to enforce single-active-category-budget invariant.
     */
    @Query("""
        UPDATE budgets SET isActive = 0,
            activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId = :categoryId AND isActive = 1 AND id != :keepId
    """)
    suspend fun deactivateOtherCategoryBudgets(categoryId: Long, keepId: Long)

    @Query("UPDATE budgets SET lastWarningNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateWarningNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastCriticalNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateCriticalNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastExceededNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateExceededNotification(id: Long, timestamp: Long)

    /** Set active state and materialized keys based on the budget's scope. */
    @Query("""
        UPDATE budgets SET
            isActive = :isActive,
            activeOverallKey = CASE WHEN :isActive = 1 AND categoryId IS NULL THEN 1 ELSE NULL END,
            activeCategoryKey = CASE WHEN :isActive = 1 AND categoryId IS NOT NULL THEN categoryId ELSE NULL END
        WHERE id = :id
    """)
    suspend fun setActive(id: Long, isActive: Boolean)

    /** Low-level: deactivate a budget and clear its materialized keys by id. */
    @Query("""
        UPDATE budgets SET isActive = 0,
            activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE id = :id
    """)
    suspend fun deactivateAndClearKeys(id: Long)

    /** Count active overall keys (should be 0 or 1). */
    @Query("SELECT COUNT(*) FROM budgets WHERE activeOverallKey IS NOT NULL")
    suspend fun countActiveOverallKeys(): Int

    /** Find active category keys that appear more than once (invariant violation). */
    @Query("""
        SELECT activeCategoryKey AS keyValue, COUNT(*) AS cnt
        FROM budgets WHERE activeCategoryKey IS NOT NULL
        GROUP BY activeCategoryKey HAVING cnt > 1
    """)
    suspend fun findDuplicateActiveCategoryKeys(): List<DuplicateKeyEntry>

    data class DuplicateKeyEntry(
        val keyValue: Long,
        val cnt: Int
    )

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}
