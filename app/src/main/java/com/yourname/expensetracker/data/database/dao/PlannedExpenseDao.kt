package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedExpenseDao {
    @Query("SELECT * FROM planned_expenses ORDER BY date ASC")
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>>

    @Query("SELECT * FROM planned_expenses WHERE date >= :startMs AND date < :endMs")
    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>>

    /**
     * Inserts a planned expense.
     *
     * Uses [OnConflictStrategy.IGNORE] to prevent accidental overwrite of
     * existing rows. If a row with the same primary key already exists the
     * insert is silently skipped — callers should check the return value
     * (0 = skipped) and decide whether an explicit update is needed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlannedExpense(expense: PlannedExpense): Long

    @Delete
    suspend fun deletePlannedExpense(expense: PlannedExpense)

    @Query("DELETE FROM planned_expenses WHERE id = :id")
    suspend fun deletePlannedExpenseById(id: Long)

    @Query("SELECT * FROM planned_expenses WHERE sourceOccurrenceKey = :key LIMIT 1")
    suspend fun getBySourceOccurrenceKey(key: String): PlannedExpense?

    @Query("""
        UPDATE planned_expenses
        SET status = :status,
            updatedAt = :updatedAt,
            openSourceOccurrenceKey = CASE WHEN :status = 'PLANNED' THEN sourceOccurrenceKey ELSE NULL END
        WHERE id = :id
    """)
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("""
        UPDATE planned_expenses
        SET linkedActualExpenseId = :expenseId,
            status = 'FULFILLED',
            updatedAt = :updatedAt,
            openSourceOccurrenceKey = NULL
        WHERE id = :id
    """)
    suspend fun linkToActualExpense(id: Long, expenseId: Long, updatedAt: Long)

    /**
     * Refreshes the [openSourceOccurrenceKey] for the planned expense with [id].
     * Sets it to [sourceOccurrenceKey] when status is 'PLANNED', NULL otherwise.
     */
    @Query("""
        UPDATE planned_expenses
        SET openSourceOccurrenceKey =
            CASE WHEN status = 'PLANNED' THEN sourceOccurrenceKey ELSE NULL END
        WHERE id = :id
    """)
    suspend fun refreshOpenOccurrenceKey(id: Long)

    /**
     * P4-CURRENT-002: Atomically unlink an actual expense from a planned expense,
     * resetting status to PLANNED and clearing linkedActualExpenseId (NULL, not 0).
     */
    @Query("""
        UPDATE planned_expenses
        SET status = 'PLANNED',
            linkedActualExpenseId = NULL,
            openSourceOccurrenceKey = sourceOccurrenceKey,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun unlinkActualExpense(id: Long, updatedAt: Long): Int

    @Query("DELETE FROM planned_expenses WHERE sourceRecurringRuleId = :ruleId")
    suspend fun deleteByRecurringRuleId(ruleId: Long)

    @Query("""
        UPDATE planned_expenses
        SET status = 'CANCELLED',
            openSourceOccurrenceKey = NULL,
            updatedAt = :updatedAt
        WHERE sourceRecurringRuleId = :ruleId
          AND status = 'PLANNED'
    """)
    suspend fun cancelPlannedByRecurringRuleId(ruleId: Long, updatedAt: Long): Int

    /** Deletes open PLANNED rows for a rule (used during rule update regeneration). */
    @Query("DELETE FROM planned_expenses WHERE sourceRecurringRuleId = :ruleId AND status = 'PLANNED'")
    suspend fun deleteOpenPlannedByRecurringRuleId(ruleId: Long): Int

    /**
     * RP-04 A2: read side for rule-derived planned rows (invariant checks and
     * lifecycle tests). Read-only; no payload-sensitive columns materialized
     * beyond the entity's own planned-budget fields.
     */
    @Query("SELECT * FROM planned_expenses WHERE sourceRecurringRuleId = :ruleId")
    suspend fun getByRecurringRuleId(ruleId: Long): List<PlannedExpense>

    /**
     * RP-04 A2: targeted retirement of specific open PLANNED rows by their
     * occurrence keys (moved logical slots during rule update reconciliation).
     * Only rows with status='PLANNED' are affected; FULFILLED history and
     * linked actual expenses are never touched.
     */
    @Query("""
        DELETE FROM planned_expenses
        WHERE sourceOccurrenceKey IN (:keys) AND status = 'PLANNED'
    """)
    suspend fun deleteOpenPlannedBySourceKeys(keys: List<String>): Int

    /**
     * RP-04 A2: atomically refreshes a derived planned row when its logical
     * occurrence adopts the new rule snapshot (amount/currency/date/merchant/
     * category and — after a frequency change — the re-keyed occurrenceKey).
     * Preserves the materialized-key CHECK invariant: openSourceOccurrenceKey
     * is set to the new key only when the row is still PLANNED, else left NULL.
     * FULFILLED rows keep their snapshot: the WHERE clause must be narrowed by
     * the caller-verified open key (openSourceOccurrenceKey = :oldKey).
     */
    @Query("""
        UPDATE planned_expenses
        SET description = :description,
            amount = :amount,
            currency = :currency,
            date = :date,
            categoryId = :categoryId,
            merchantKey = :merchantKey,
            sourceOccurrenceKey = :newKey,
            openSourceOccurrenceKey = :newKey,
            updatedAt = :updatedAt
        WHERE sourceOccurrenceKey = :oldKey
          AND status = 'PLANNED'
          AND openSourceOccurrenceKey = :oldKey
    """)
    suspend fun updateDerivedSnapshotForKey(
        oldKey: String,
        newKey: String,
        description: String,
        amount: Double,
        currency: String,
        date: Long,
        categoryId: Long?,
        merchantKey: String?,
        updatedAt: Long
    ): Int

    /**
     * RP-04 A2: read side of the reconciler's pre-commit invariant check.
     * Returns open PLANNED rows projected from a rule (orphans and slot
     * coverage are validated against these rows inside the update transaction).
     */
    @Query("SELECT * FROM planned_expenses WHERE sourceRecurringRuleId = :ruleId AND status = 'PLANNED'")
    suspend fun getOpenPlannedByRecurringRuleId(ruleId: Long): List<PlannedExpense>

    /**
     * P4-CURRENT-003: Fulfill a planned expense by its occurrence key.
     * Marks it as FULFILLED when the occurrence transitions to PAID.
     * Now accepts an expenseId to preserve provenance of which actual expense fulfilled it.
     */
    @Query("""
        UPDATE planned_expenses
        SET status = 'FULFILLED',
            linkedActualExpenseId = :expenseId,
            openSourceOccurrenceKey = NULL,
            updatedAt = :updatedAt
        WHERE sourceOccurrenceKey = :occurrenceKey
          AND status = 'PLANNED'
    """)
    suspend fun fulfillByOccurrenceKey(occurrenceKey: String, expenseId: Long, updatedAt: Long): Int
}
