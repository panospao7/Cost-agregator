package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink

@Dao
interface ReceiptExpenseLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: ReceiptExpenseLink): Long

    @Query("SELECT * FROM receipt_expense_links WHERE receiptId = :receiptId")
    suspend fun getLinksForReceipt(receiptId: Long): List<ReceiptExpenseLink>

    @Query("SELECT * FROM receipt_expense_links WHERE expenseId = :expenseId")
    suspend fun getLinksForExpense(expenseId: Long): List<ReceiptExpenseLink>

    @Query("DELETE FROM receipt_expense_links WHERE receiptId = :receiptId AND expenseId = :expenseId")
    suspend fun unlink(receiptId: Long, expenseId: Long): Int

    @Query("DELETE FROM receipt_expense_links WHERE receiptId = :receiptId")
    suspend fun deleteAllLinksForReceipt(receiptId: Long)
}
