package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseGroupDao {
    
    @Insert
    suspend fun insert(group: ExpenseGroup): Long
    
    @Update
    suspend fun update(group: ExpenseGroup)
    
    @Delete
    suspend fun delete(group: ExpenseGroup)
    
    @Query("SELECT * FROM expense_groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<ExpenseGroup>>
    
    @Query("SELECT * FROM expense_groups WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveGroups(): Flow<List<ExpenseGroup>>
    
    @Query("SELECT * FROM expense_groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: Long): ExpenseGroup?
    
    @Query("SELECT * FROM expense_groups WHERE id = :groupId LIMIT 1")
    fun getGroupByIdFlow(groupId: Long): Flow<ExpenseGroup?>
    
    @Query("UPDATE expense_groups SET isActive = 0 WHERE id = :groupId")
    suspend fun archiveGroup(groupId: Long)
    
    @Query("UPDATE expense_groups SET isActive = 1 WHERE id = :groupId")
    suspend fun restoreGroup(groupId: Long)
}
