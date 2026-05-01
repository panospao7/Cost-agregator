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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlannedExpense(expense: PlannedExpense): Long

    @Delete
    suspend fun deletePlannedExpense(expense: PlannedExpense)

    @Query("DELETE FROM planned_expenses WHERE id = :id")
    suspend fun deletePlannedExpenseById(id: Long)

    @Query("SELECT * FROM planned_expenses WHERE sourceOccurrenceKey = :key LIMIT 1")
    suspend fun getBySourceOccurrenceKey(key: String): PlannedExpense?

    @Query("UPDATE planned_expenses SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE planned_expenses SET linkedActualExpenseId = :expenseId, status = 'FULFILLED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun linkToActualExpense(id: Long, expenseId: Long, updatedAt: Long)
}
