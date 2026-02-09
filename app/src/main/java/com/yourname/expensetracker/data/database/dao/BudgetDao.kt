package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long

    @Update
    suspend fun update(budget: Budget)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): Budget?

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>

    @Query("SELECT * FROM budgets")
    fun getAllFlow(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    suspend fun getActiveBudgets(): List<Budget>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun getActiveBudgetsFlow(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND isActive = 1 LIMIT 1")
    suspend fun getOverallBudget(): Budget?

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND isActive = 1 LIMIT 1")
    suspend fun getByCategory(categoryId: Long): Budget?

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
