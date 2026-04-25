package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM group_members WHERE groupId IN (:groupIds) ORDER BY groupId, name")
    suspend fun getAllForGroups(groupIds: List<Long>): List<GroupMember>
    
    @Query("SELECT * FROM group_members WHERE id = :memberId LIMIT 1")
    suspend fun getById(memberId: Long): GroupMember?
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1 LIMIT 1")
    suspend fun getCurrentUser(groupId: Long): GroupMember?

    /**
     * Clears the current-user flag for every member in [groupId].
     * Used internally by [setCurrentUser] before promoting a new member.
     */
    @Query("UPDATE group_members SET isCurrentUser = 0 WHERE groupId = :groupId AND isCurrentUser = 1")
    suspend fun clearCurrentUser(groupId: Long)

    /**
     * Atomically designates [memberId] as the current user for [groupId].
     *
     * App-layer transaction logic enforces the single-current-user invariant by
     * clearing the previous current user before promoting the new one.
     *
     * Both [groupId] **and** [memberId] are checked: the promotion query matches
     * on `id = :memberId AND groupId = :groupId`, preventing a cross-group
     * `memberId` from silently mutating another group.
     *
     * @throws IllegalArgumentException if no row was updated — either [memberId]
     *   does not exist or it does not belong to [groupId].
     */
    @Transaction
    suspend fun setCurrentUser(groupId: Long, memberId: Long) {
        clearCurrentUser(groupId)
        val updated = markAsCurrentUser(groupId, memberId)
        if (updated == 0) {
            throw IllegalArgumentException(
                "Member $memberId does not exist in group $groupId"
            )
        }
    }

    /**
     * Low-level helper — callers should prefer [setCurrentUser] which clears the
     * previous current user first.
     *
     * Returns the number of rows updated (0 or 1). The query is group-scoped so
     * a mismatched [groupId]/[memberId] pair touches nothing.
     */
    @Query("UPDATE group_members SET isCurrentUser = 1 WHERE id = :memberId AND groupId = :groupId")
    suspend fun markAsCurrentUser(groupId: Long, memberId: Long): Int
    
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
