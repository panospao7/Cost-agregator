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
    
    /**
     * R18: read-only link probe used by the transaction lifecycle coordinator to
     * block delete / money edits of an expense that is linked to a shared group.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM group_expenses WHERE expenseId = :expenseId)")
    suspend fun existsForExpense(expenseId: Long): Boolean

    /**
     * DR-01 read-only probe: ids of personal expenses in the half-open date range
     * [startDate, endDate) that are linked to a group expense whose payer is NOT the current
     * user. Those rows are the user's share of a bill someone else paid, so they are not a cash
     * outflow for the user (settling up is the cash event). Payer-is-current-user links and
     * unlinked expenses are never returned.
     */
    @Query(
        """
        SELECT ge.expenseId FROM group_expenses ge
        INNER JOIN group_members gm ON gm.id = ge.paidById
        INNER JOIN expenses e ON e.id = ge.expenseId
        WHERE ge.expenseId IS NOT NULL
          AND gm.isCurrentUser = 0
          AND e.date >= :startDate AND e.date < :endDate
        """
    )
    suspend fun getNonPayerLinkedExpenseIdsBetween(startDate: Long, endDate: Long): List<Long>

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

    /** Looks up a group expense by its idempotency key. Returns null if not found. */
    @Query("SELECT * FROM group_expenses WHERE groupId = :groupId AND idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(groupId: Long, idempotencyKey: String): GroupExpense?
}
