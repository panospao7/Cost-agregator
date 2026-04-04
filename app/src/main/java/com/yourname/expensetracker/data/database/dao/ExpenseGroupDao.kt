package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.model.ExpenseGroupWithDetails
import kotlinx.coroutines.flow.Flow

/**
 * DAO for expense group management
 * Follows dual API pattern: Flow for reactive UI, suspend for one-shot operations
 */
@Dao
interface ExpenseGroupDao {
    
    // CRUD operations
    @Insert
    suspend fun insert(group: ExpenseGroup): Long
    
    @Update
    suspend fun update(group: ExpenseGroup)
    
    @Delete
    suspend fun delete(group: ExpenseGroup)
    
    // Flow variants for reactive UI
    @Query("SELECT * FROM expense_groups ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ExpenseGroup>>
    
    @Query("SELECT * FROM expense_groups WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveFlow(): Flow<List<ExpenseGroup>>
    
    @Query("SELECT * FROM expense_groups WHERE id = :groupId LIMIT 1")
    fun getByIdFlow(groupId: Long): Flow<ExpenseGroup?>
    
    @Query("SELECT COUNT(*) FROM expense_groups WHERE isActive = 1")
    fun getActiveCountFlow(): Flow<Int>
    
    // One-shot variants for single operations
    @Query("SELECT * FROM expense_groups ORDER BY createdAt DESC")
    suspend fun getAll(): List<ExpenseGroup>
    
    @Query("SELECT * FROM expense_groups WHERE isActive = 1 ORDER BY createdAt DESC")
    suspend fun getActive(): List<ExpenseGroup>

    @Transaction
    @Query("SELECT * FROM expense_groups WHERE isActive = 1 ORDER BY createdAt DESC")
    suspend fun getActiveWithDetails(): List<ExpenseGroupWithDetails>
    
    @Query("SELECT * FROM expense_groups WHERE id = :groupId LIMIT 1")
    suspend fun getById(groupId: Long): ExpenseGroup?
    
    @Query("SELECT COUNT(*) FROM expense_groups WHERE isActive = 1")
    suspend fun getActiveCount(): Int
    
    // Status management
    @Query("UPDATE expense_groups SET isActive = 0 WHERE id = :groupId")
    suspend fun archiveGroup(groupId: Long)
    
    @Query("UPDATE expense_groups SET isActive = 1 WHERE id = :groupId")
    suspend fun restoreGroup(groupId: Long)
    
    @Query("UPDATE expense_groups SET isActive = :isActive WHERE id = :groupId")
    suspend fun setActiveStatus(groupId: Long, isActive: Boolean)
    
    // Atomic transaction operations
    @Transaction
    suspend fun insertGroupWithMembers(
        group: ExpenseGroup,
        memberDao: GroupMemberDao,
        members: List<GroupMember>
    ): Long {
        val groupId = insert(group)
        if (groupId <= 0) {
            throw IllegalStateException("Failed to create group")
        }
        
        val membersWithGroupId = members.map { it.copy(groupId = groupId) }
        val memberIds = memberDao.insertAll(membersWithGroupId)
        
        if (memberIds.any { it <= 0 }) {
            throw IllegalStateException("Failed to add some members")
        }
        
        return groupId
    }
    
    // Legacy methods - deprecated
    @Deprecated(
        message = "Use getAllFlow() instead",
        replaceWith = ReplaceWith("getAllFlow()"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM expense_groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<ExpenseGroup>>
    
    @Deprecated(
        message = "Use getActiveFlow() instead",
        replaceWith = ReplaceWith("getActiveFlow()"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM expense_groups WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveGroups(): Flow<List<ExpenseGroup>>
    
    @Deprecated(
        message = "Use getById() instead",
        replaceWith = ReplaceWith("getById(groupId)"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM expense_groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: Long): ExpenseGroup?
    
    @Deprecated(
        message = "Use setActiveStatus() instead",
        replaceWith = ReplaceWith("setActiveStatus(groupId, false)"),
        level = DeprecationLevel.WARNING
    )
    @Query("UPDATE expense_groups SET isActive = 0 WHERE id = :groupId")
    suspend fun archiveGroupLegacy(groupId: Long)
    
    @Deprecated(
        message = "Use setActiveStatus() instead",
        replaceWith = ReplaceWith("setActiveStatus(groupId, true)"),
        level = DeprecationLevel.WARNING
    )
    @Query("UPDATE expense_groups SET isActive = 1 WHERE id = :groupId")
    suspend fun restoreGroupLegacy(groupId: Long)
}
