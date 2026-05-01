package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.ReceiptEvent

@Dao
interface ReceiptEventDao {

    @Insert
    suspend fun insert(event: ReceiptEvent): Long

    @Query("SELECT * FROM receipt_events WHERE receiptId = :receiptId ORDER BY occurredAt DESC")
    suspend fun getEventsForReceipt(receiptId: Long): List<ReceiptEvent>
}
