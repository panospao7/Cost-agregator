package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ScannedReceipt): Long

    @Update
    suspend fun update(receipt: ScannedReceipt)

    @Delete
    suspend fun delete(receipt: ScannedReceipt)

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScannedReceipt>>

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getReceiptsPaged(limit: Int, offset: Int): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?

    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int

    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()

    @Query("UPDATE scanned_receipts SET expenseId = :expenseId, matchStatus = 'AUTO_MATCHED' WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)

    @Query("UPDATE scanned_receipts SET itemCategorizationStatus = :status WHERE id = :receiptId")
    suspend fun updateCategorizationStatus(receiptId: Long, status: String)

    @Query("SELECT * FROM scanned_receipts WHERE matchStatus = 'UNMATCHED' ORDER BY createdAt DESC")
    suspend fun getUnmatchedReceipts(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE matchStatus = 'SUGGESTED' ORDER BY createdAt DESC")
    suspend fun getReceiptsWithSuggestions(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE createdAt >= :since ORDER BY createdAt DESC")
    suspend fun getRecentReceipts(since: Long): List<ScannedReceipt>
}
