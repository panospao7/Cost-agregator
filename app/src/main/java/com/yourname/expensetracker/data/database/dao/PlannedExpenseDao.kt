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

    /**
     * P4-CURRENT-003: Fulfill a planned expense by its occurrence key.
     * Marks it as FULFILLED when the occurrence transitions to PAID.
     */
    @Query("""
        UPDATE planned_expenses
        SET status = 'FULFILLED',
            openSourceOccurrenceKey = NULL,
            updatedAt = :updatedAt
        WHERE sourceOccurrenceKey = :occurrenceKey
          AND status = 'PLANNED'
    """)
    suspend fun fulfillByOccurrenceKey(occurrenceKey: String, updatedAt: Long): Int
}
