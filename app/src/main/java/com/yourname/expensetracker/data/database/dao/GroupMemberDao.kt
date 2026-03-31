package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.GroupMember
import kotlinx.coroutines.flow.Flow

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
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    fun getMembersForGroup(groupId: Long): Flow<List<GroupMember>>
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name")
    suspend fun getMembersForGroupOnce(groupId: Long): List<GroupMember>
    
    @Query("SELECT * FROM group_members WHERE id = :memberId LIMIT 1")
    suspend fun getMemberById(memberId: Long): GroupMember?
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1 LIMIT 1")
    suspend fun getCurrentUserMember(groupId: Long): GroupMember?
    
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: Long)
    
    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberCount(groupId: Long): Int

    @Query("SELECT * FROM group_members")
    fun getAllMembers(): Flow<List<GroupMember>>
}
