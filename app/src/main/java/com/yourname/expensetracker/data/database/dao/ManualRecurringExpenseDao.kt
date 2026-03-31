package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

/**
 * DAO for manual recurring expenses (subscriptions, bills, etc.)
 */
@Dao
interface ManualRecurringExpenseDao {
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isActive = 1 ORDER BY nextDate ASC")
    fun getAllActiveRecurringExpenses(): Flow<List<ManualRecurringExpense>>
    
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY createdAt DESC")
    suspend fun getAllManualRecurringExpenses(): List<ManualRecurringExpense>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE id = :id")
    suspend fun getById(id: Long): ManualRecurringExpense?
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isSubscription = 1 AND isActive = 1")
    suspend fun getAllActiveSubscriptions(): List<ManualRecurringExpense>
    
    @Insert
    suspend fun insert(expense: ManualRecurringExpense): Long
    
    @Update
    suspend fun update(expense: ManualRecurringExpense)
    
    @Delete
    suspend fun delete(expense: ManualRecurringExpense)
    
    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("UPDATE manual_recurring_expenses SET isActive = :isActive WHERE id = :id")
    suspend fun setActiveStatus(id: Long, isActive: Boolean)
    
    @Query("UPDATE manual_recurring_expenses SET nextDate = :nextDate WHERE id = :id")
    suspend fun updateNextDate(id: Long, nextDate: Long)
    
    @Query("SELECT COUNT(*) FROM manual_recurring_expenses WHERE isActive = 1")
    suspend fun getActiveCount(): Int
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE nextDate <= :date AND isActive = 1")
    suspend fun getExpensesDueBefore(date: Long): List<ManualRecurringExpense>
}