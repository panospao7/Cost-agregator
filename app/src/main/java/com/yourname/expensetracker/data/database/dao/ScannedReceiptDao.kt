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

    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?

    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int

    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()

    @Query("UPDATE scanned_receipts SET expenseId = :expenseId WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)
}
