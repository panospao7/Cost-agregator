package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity

@Dao
interface GroupSettlementDao {
    @Insert
    suspend fun insert(settlement: GroupSettlementEntity): Long

    @Query("SELECT * FROM group_settlements WHERE groupId = :groupId ORDER BY createdAt DESC")
    suspend fun getSettlementsForGroup(groupId: Long): List<GroupSettlementEntity>

    @Query("DELETE FROM group_settlements WHERE id = :id")
    suspend fun deleteSettlement(id: Long)
}
