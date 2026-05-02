package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ReturnStatus
import com.yourname.expensetracker.data.database.entity.ReturnWindow
import kotlinx.coroutines.flow.Flow

@Dao
interface ReturnWindowDao {
    @Query("SELECT * FROM return_windows ORDER BY returnDeadline ASC")
    fun getAllReturnWindows(): Flow<List<ReturnWindow>>

    @Query("SELECT * FROM return_windows WHERE status = :status ORDER BY returnDeadline ASC")
    fun getReturnWindowsByStatus(status: ReturnStatus): Flow<List<ReturnWindow>>

    @Query("SELECT * FROM return_windows WHERE returnDeadline > :currentTime AND status = 'RETURNABLE' ORDER BY returnDeadline ASC")
    fun getActiveReturnWindows(currentTime: Long): Flow<List<ReturnWindow>>

    @Query("SELECT * FROM return_windows WHERE returnDeadline >= :currentTime AND returnDeadline < :futureTime AND status = 'RETURNABLE' ORDER BY returnDeadline ASC")
    suspend fun getReturnWindowsExpiringSoon(
        futureTime: Long,
        currentTime: Long
    ): List<ReturnWindow>

    @Query("SELECT * FROM return_windows WHERE returnDeadline < :currentTime AND status = 'RETURNABLE'")
    suspend fun getRecentlyExpiredReturnWindows(currentTime: Long): List<ReturnWindow>

    @Query("SELECT * FROM return_windows WHERE receiptId = :receiptId")
    suspend fun getReturnWindowByReceiptId(receiptId: Long): ReturnWindow?

    @Query("SELECT * FROM return_windows WHERE expenseId = :expenseId")
    suspend fun getReturnWindowByExpenseId(expenseId: Long): ReturnWindow?

    @Insert
    suspend fun insertReturnWindow(returnWindow: ReturnWindow): Long

    @Update
    suspend fun updateReturnWindow(returnWindow: ReturnWindow)

    @Delete
    suspend fun deleteReturnWindow(returnWindow: ReturnWindow)

    @Query("DELETE FROM return_windows WHERE id = :returnWindowId")
    suspend fun deleteReturnWindowById(returnWindowId: Long)

    @Query("UPDATE return_windows SET expenseId = :expenseId, updatedAt = :updatedAt WHERE receiptId = :receiptId")
    suspend fun updateExpenseIdByReceiptId(
        receiptId: Long,
        expenseId: Long?,
        updatedAt: Long
    )

    @Query("UPDATE return_windows SET status = :status, returnedAt = :returnedAt, refundAmount = :refundAmount, updatedAt = :updatedAt WHERE id = :returnWindowId")
    suspend fun markAsReturned(
        returnWindowId: Long,
        status: ReturnStatus = ReturnStatus.RETURNED,
        returnedAt: Long,
        refundAmount: Double? = null,
        updatedAt: Long
    )

    @Query("UPDATE return_windows SET status = 'EXPIRED', updatedAt = :updatedAt WHERE returnDeadline < :currentTime AND status = 'RETURNABLE'")
    suspend fun markExpiredReturnWindows(currentTime: Long, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM return_windows WHERE status = 'RETURNABLE' AND returnDeadline > :currentTime")
    suspend fun getActiveReturnWindowCount(currentTime: Long): Int
}
