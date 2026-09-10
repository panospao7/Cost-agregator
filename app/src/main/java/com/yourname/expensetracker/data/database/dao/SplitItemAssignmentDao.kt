package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SplitItemAssignment
import kotlinx.coroutines.flow.Flow

@Dao
interface SplitItemAssignmentDao {
    @Query("SELECT * FROM split_item_assignments WHERE expenseId = :expenseId ORDER BY participantIndex")
    fun getAssignmentsForExpense(expenseId: Long): Flow<List<SplitItemAssignment>>
    
    @Query("SELECT * FROM split_item_assignments WHERE expenseId = :expenseId ORDER BY participantIndex")
    suspend fun getAssignmentsForExpenseSync(expenseId: Long): List<SplitItemAssignment>
    
    @Query("SELECT SUM(assignedAmount) FROM split_item_assignments WHERE expenseId = :expenseId")
    suspend fun getTotalAssignedAmount(expenseId: Long): Double?
    
    @Query("SELECT COUNT(*) FROM split_item_assignments WHERE expenseId = :expenseId AND isPaid = 1")
    suspend fun getPaidCount(expenseId: Long): Int
    
    @Insert
    suspend fun insertAssignment(assignment: SplitItemAssignment): Long
    
    @Insert
    suspend fun insertAssignments(assignments: List<SplitItemAssignment>): List<Long>
    
    @Update
    suspend fun updateAssignment(assignment: SplitItemAssignment)
    
    /**
     * @param timestamp Required current time in epoch-millis, supplied by the caller
     *   (e.g. [com.yourname.expensetracker.domain.util.TimeProvider.now]).
     */
    @Query("UPDATE split_item_assignments SET isPaid = 1, paidAt = :timestamp WHERE id = :assignmentId")
    suspend fun markAsPaid(assignmentId: Long, timestamp: Long)
    
    @Delete
    suspend fun deleteAssignment(assignment: SplitItemAssignment)
    
    @Query("DELETE FROM split_item_assignments WHERE expenseId = :expenseId")
    suspend fun deleteAllForExpense(expenseId: Long)
    
    @Query("""
        SELECT participantName, SUM(assignedAmount) as totalAmount, COUNT(*) as itemCount
        FROM split_item_assignments 
        WHERE expenseId = :expenseId 
        GROUP BY participantName 
        ORDER BY totalAmount DESC
    """)
    suspend fun getParticipantTotals(expenseId: Long): List<ParticipantTotal>
    
    data class ParticipantTotal(
        val participantName: String,
        val totalAmount: Double,
        val itemCount: Int
    )
}
