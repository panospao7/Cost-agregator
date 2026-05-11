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

    @Query("SELECT * FROM transaction_events ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 50): List<TransactionEvent>

    @Query("SELECT * FROM transaction_events WHERE eventType = :type ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getEventsByType(type: String, limit: Int = 50): List<TransactionEvent>

    @Query("SELECT * FROM transaction_events WHERE source = :source ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getEventsBySource(source: String, limit: Int = 50): List<TransactionEvent>

    @Query("SELECT * FROM transaction_events WHERE occurredAt BETWEEN :startMs AND :endMs ORDER BY occurredAt DESC")
    suspend fun getEventsBetween(startMs: Long, endMs: Long): List<TransactionEvent>

    @Query("SELECT COUNT(*) FROM transaction_events WHERE eventType = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT COUNT(*) FROM transaction_events WHERE eventType = :type AND occurredAt >= :sinceMs")
    suspend fun countByTypeSince(type: String, sinceMs: Long): Int

    @Query("DELETE FROM transaction_events WHERE occurredAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
