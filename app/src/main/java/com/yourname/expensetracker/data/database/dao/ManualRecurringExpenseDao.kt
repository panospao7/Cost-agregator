package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

/**
 * DAO for manual recurring expenses (subscriptions, bills, etc.)
 * This is the primary DAO for all recurring expense operations.
 * RecurringExpenseDao is deprecated - use this instead.
 *
 * TODO (P4-CURRENT-013): Direct DAO mutation surface is public. Add a static
 * guard or internal visibility modifier so that only RecurringLifecycleCoordinator
 * (and tests) can call mutating methods (insert, update, delete, setActiveStatus,
 * updateNextDate). This prevents bypassing lifecycle event tracking.
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
    
    /**
     * REC-15: Exact merchant name lookup (legacy).
     *
     * Performs a case-sensitive exact match on the raw merchant display name.
     * Prefer [RecurringExpenseRepository.getByMerchantFuzzy] which normalizes
     * both the query and stored names via [MerchantKeyGenerator] for fuzzy
     * matching (handles minor variations like "McDonald's" vs "Mc Donalds").
     *
     * @see com.yourname.expensetracker.data.repository.RecurringExpenseRepository.getByMerchantFuzzy
     */
    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?
    
    // CRUD operations
    @Insert(onConflict = OnConflictStrategy.ABORT)
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