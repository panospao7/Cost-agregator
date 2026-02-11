package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringExpenseDao {
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>>

    @Query("SELECT * FROM manual_recurring_expenses")
    suspend fun getAll(): List<ManualRecurringExpense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ManualRecurringExpense): Long

    @Update
    suspend fun update(expense: ManualRecurringExpense)

    @Delete
    suspend fun delete(expense: ManualRecurringExpense)
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?
    
    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}
