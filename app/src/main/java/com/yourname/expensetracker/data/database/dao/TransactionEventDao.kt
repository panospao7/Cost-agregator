package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.TransactionEvent

@Dao
interface TransactionEventDao {

    @Insert
    suspend fun insert(event: TransactionEvent): Long

    @Query("SELECT * FROM transaction_events WHERE expenseId = :expenseId ORDER BY occurredAt DESC")
    suspend fun getEventsForExpense(expenseId: Long): List<TransactionEvent>
}
