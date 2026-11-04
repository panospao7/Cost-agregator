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

    @Query("SELECT * FROM group_settlements WHERE groupId IN (:groupIds) ORDER BY createdAt DESC")
    suspend fun getSettlementsForGroups(groupIds: List<Long>): List<GroupSettlementEntity>

    @Query("DELETE FROM group_settlements WHERE id = :id")
    suspend fun deleteSettlement(id: Long)

    /**
     * DR-01 read-only cash-flow probe: counted (RECORDED/COMPLETED, same set as the group balance
     * policy) settlements in the half-open range [startDate, endDate) on [GroupSettlementEntity.createdAt]
     * where exactly one party is the current user. Settlements between two other members are never
     * returned. [CurrentUserSettlementRow.paidByCurrentUser] is true when the current user paid
     * (cash outflow) and false when the current user received (cash inflow).
     */
    @Query(
        """
        SELECT gs.id AS id, gs.amount AS amount, gs.currency AS currency,
               gs.createdAt AS createdAt, gs.linkedExpenseId AS linkedExpenseId,
               fm.isCurrentUser AS paidByCurrentUser
        FROM group_settlements gs
        INNER JOIN group_members fm ON fm.id = gs.fromMemberId
        INNER JOIN group_members tm ON tm.id = gs.toMemberId
        WHERE gs.status IN ('RECORDED', 'COMPLETED')
          AND gs.createdAt >= :startDate AND gs.createdAt < :endDate
          AND (fm.isCurrentUser = 1 OR tm.isCurrentUser = 1)
        """
    )
    suspend fun getCurrentUserSettlementsBetween(startDate: Long, endDate: Long): List<CurrentUserSettlementRow>
}

/** Read-only projection for [GroupSettlementDao.getCurrentUserSettlementsBetween]. */
data class CurrentUserSettlementRow(
    val id: Long,
    val amount: Double,
    val currency: String,
    val createdAt: Long,
    val linkedExpenseId: Long?,
    val paidByCurrentUser: Boolean
)
