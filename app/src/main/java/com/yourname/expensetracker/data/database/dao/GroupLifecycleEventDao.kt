package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.GroupLifecycleEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupLifecycleEventDao {
    @Insert
    suspend fun insert(event: GroupLifecycleEventEntity): Long

    @Query("SELECT * FROM group_lifecycle_events WHERE groupId = :groupId ORDER BY createdAt DESC")
    suspend fun getEventsForGroup(groupId: Long): List<GroupLifecycleEventEntity>

    @Query("SELECT * FROM group_lifecycle_events WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeEventsForGroup(groupId: Long): Flow<List<GroupLifecycleEventEntity>>

    @Query("DELETE FROM group_lifecycle_events WHERE groupId = :groupId")
    suspend fun deleteEventsForGroup(groupId: Long)
}
