package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.GroupMember
import kotlinx.coroutines.flow.Flow

/**
 * DAO for group member management
 * Follows dual API pattern: Flow for reactive UI, suspend for one-shot operations
 */
@Dao
interface GroupMemberDao {
    
    @Insert
    suspend fun insert(member: GroupMember): Long
    
    @Insert
    suspend fun insertAll(members: List<GroupMember>): List<Long>
    
    @Update
    suspend fun update(member: GroupMember)
    
    @Delete
    suspend fun delete(member: GroupMember)
    
    // Flow variants for reactive UI
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    fun getAllForGroupFlow(groupId: Long): Flow<List<GroupMember>>
    
    @Query("SELECT * FROM group_members WHERE id = :memberId LIMIT 1")
    fun getByIdFlow(memberId: Long): Flow<GroupMember?>
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1 LIMIT 1")
    fun getCurrentUserFlow(groupId: Long): Flow<GroupMember?>
    
    @Query("SELECT * FROM group_members")
    fun getAllFlow(): Flow<List<GroupMember>>
    
    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    fun getMemberCountFlow(groupId: Long): Flow<Int>
    
    // One-shot variants for single operations
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    suspend fun getAllForGroup(groupId: Long): List<GroupMember>
    
    @Query("SELECT * FROM group_members WHERE id = :memberId LIMIT 1")
    suspend fun getById(memberId: Long): GroupMember?
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1 LIMIT 1")
    suspend fun getCurrentUser(groupId: Long): GroupMember?
    
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: Long)
    
    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberCount(groupId: Long): Int
    
    // Legacy method - deprecated, use getAllForGroup instead
    @Deprecated(
        message = "Use getAllForGroup() instead",
        replaceWith = ReplaceWith("getAllForGroup(groupId)"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    suspend fun getMembersForGroupOnce(groupId: Long): List<GroupMember>
    
    // Legacy method - deprecated, use getAllFlow instead
    @Deprecated(
        message = "Use getAllFlow() instead",
        replaceWith = ReplaceWith("getAllFlow()"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM group_members")
    fun getAllMembers(): Flow<List<GroupMember>>
    
    // Legacy method - deprecated, use getAllForGroupFlow instead
    @Deprecated(
        message = "Use getAllForGroupFlow() instead",
        replaceWith = ReplaceWith("getAllForGroupFlow(groupId)"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    fun getMembersForGroup(groupId: Long): Flow<List<GroupMember>>
    
    // Legacy method - deprecated, use getById instead
    @Deprecated(
        message = "Use getById() instead",
        replaceWith = ReplaceWith("getById(memberId)"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM group_members WHERE id = :memberId LIMIT 1")
    suspend fun getMemberById(memberId: Long): GroupMember?
    
    // Legacy method - deprecated, use getCurrentUser instead
    @Deprecated(
        message = "Use getCurrentUser() instead",
        replaceWith = ReplaceWith("getCurrentUser(groupId)"),
        level = DeprecationLevel.WARNING
    )
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1 LIMIT 1")
    suspend fun getCurrentUserMember(groupId: Long): GroupMember?
}
