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

    /**
     * RP-14 P8-003 (`40_receipt_events` target, D14 cutoff 90 days): hard-delete
     * receipt events older than the cutoff by occurredAt. Count-only,
     * idempotent — re-runs with the same cutoff report 0.
     */
    @Query("DELETE FROM receipt_events WHERE occurredAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
