package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.GroupExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupExpenseDao {
    
    @Insert
    suspend fun insert(groupExpense: GroupExpense): Long
    
    @Update
    suspend fun update(groupExpense: GroupExpense)
    
    @Delete
    suspend fun delete(groupExpense: GroupExpense)
    
    @Query("SELECT * FROM group_expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: Long): Flow<List<GroupExpense>>
    
    @Query("SELECT * FROM group_expenses WHERE groupId = :groupId ORDER BY date DESC")
    suspend fun getExpensesForGroupOnce(groupId: Long): List<GroupExpense>

    @Query("SELECT * FROM group_expenses WHERE groupId IN (:groupIds) ORDER BY groupId, date DESC")
    suspend fun getExpensesForGroups(groupIds: List<Long>): List<GroupExpense>
    
    @Query("SELECT * FROM group_expenses WHERE expenseId = :expenseId LIMIT 1")
    suspend fun getGroupExpenseForExpense(expenseId: Long): GroupExpense?
    
    @Query("SELECT * FROM group_expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): GroupExpense?
    
    @Deprecated("Raw SUM without currency grouping. All expenses in a group share the same currency (group.defaultCurrency), but prefer currency-aware aggregates for safety.")
    @Query("""
        SELECT SUM(totalAmount) FROM group_expenses 
        WHERE groupId = :groupId AND paidById = :paidById
    """)
    suspend fun getTotalPaidByMember(groupId: Long, paidById: Long): Double?
    
    @Deprecated("Raw SUM without currency grouping. All expenses in a group share the same currency (group.defaultCurrency), but prefer currency-aware aggregates for safety.")
    @Query("""
        SELECT SUM(totalAmount) FROM group_expenses 
        WHERE groupId = :groupId
    """)
    suspend fun getTotalGroupExpenses(groupId: Long): Double?
    
    @Query("DELETE FROM group_expenses WHERE groupId = :groupId")
    suspend fun deleteAllForGroup(groupId: Long)
    
    @Query("SELECT COUNT(*) FROM group_expenses WHERE groupId = :groupId")
    suspend fun getExpenseCount(groupId: Long): Int

    @Query("SELECT COUNT(*) FROM group_expenses WHERE groupId = :groupId AND paidById = :memberId")
    suspend fun countExpensesPaidByMember(groupId: Long, memberId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM group_expenses
        WHERE groupId = :groupId
          AND splitType != 'EQUAL'
          AND customSplitsJson IS NOT NULL
          AND TRIM(customSplitsJson) != ''
          AND (
              REPLACE(customSplitsJson, ' ', '') LIKE :memberPrefixPattern
              OR REPLACE(customSplitsJson, ' ', '') LIKE :memberMiddlePattern
          )
        """
    )
    suspend fun countPotentialSplitReferences(
        groupId: Long,
        memberPrefixPattern: String,
        memberMiddlePattern: String
    ): Int
}
