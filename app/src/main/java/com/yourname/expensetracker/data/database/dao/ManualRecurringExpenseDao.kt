package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

/**
 * DAO for manual recurring expenses (subscriptions, bills, etc.)
 * This is the primary DAO for all recurring expense operations.
 * RecurringExpenseDao is deprecated - use this instead.
 */
@Dao
interface ManualRecurringExpenseDao {
    
    // Flow variants for reactive UI
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isActive = 1 ORDER BY nextDate ASC")
    fun getAllActiveFlow(): Flow<List<ManualRecurringExpense>>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isSubscription = 1 AND isActive = 1 ORDER BY nextDate ASC")
    fun getAllActiveSubscriptionsFlow(): Flow<List<ManualRecurringExpense>>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<ManualRecurringExpense?>
    
    // One-shot variants for single operations
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY createdAt DESC")
    suspend fun getAll(): List<ManualRecurringExpense>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isActive = 1 ORDER BY nextDate ASC")
    suspend fun getAllActive(): List<ManualRecurringExpense>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE isSubscription = 1 AND isActive = 1 ORDER BY nextDate ASC")
    suspend fun getAllActiveSubscriptions(): List<ManualRecurringExpense>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE id = :id")
    suspend fun getById(id: Long): ManualRecurringExpense?
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?
    
    // CRUD operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ManualRecurringExpense): Long
    
    @Update
    suspend fun update(expense: ManualRecurringExpense)
    
    @Delete
    suspend fun delete(expense: ManualRecurringExpense)
    
    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    // Status management
    @Query("UPDATE manual_recurring_expenses SET isActive = :isActive WHERE id = :id")
    suspend fun setActiveStatus(id: Long, isActive: Boolean)
    
    @Query("UPDATE manual_recurring_expenses SET nextDate = :nextDate WHERE id = :id")
    suspend fun updateNextDate(id: Long, nextDate: Long)
    
    // Statistics and queries
    @Query("SELECT COUNT(*) FROM manual_recurring_expenses WHERE isActive = 1")
    suspend fun getActiveCount(): Int
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE nextDate <= :date AND isActive = 1")
    suspend fun getExpensesDueBefore(date: Long): List<ManualRecurringExpense>
    
    @Query("SELECT * FROM manual_recurring_expenses WHERE nextDate <= :date AND isActive = 1")
    fun getExpensesDueBeforeFlow(date: Long): Flow<List<ManualRecurringExpense>>
}