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
}
