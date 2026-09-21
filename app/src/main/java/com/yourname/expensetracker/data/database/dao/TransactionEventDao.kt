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

    /**
     * RP-14 P8-001 stage 1: null out beforeSnapshot/afterSnapshot for ALL event
     * types older than the snapshot cutoff — update/delete/create snapshots can
     * all carry merchant, notes, or other financial data. Set-based SQL update;
     * never materializes snapshot payloads into Kotlin. Idempotent: only events
     * that still hold at least one snapshot are updated, so re-runs report 0.
     * No schema change: both snapshot columns are already nullable.
     */
    @Query("""
        UPDATE transaction_events
        SET beforeSnapshot = NULL,
            afterSnapshot = NULL
        WHERE occurredAt < :beforeMs
          AND (beforeSnapshot IS NOT NULL OR afterSnapshot IS NOT NULL)
    """)
    suspend fun nullSnapshotsOlderThan(beforeMs: Long): Int

    /**
     * RP-14 P8-001 stage 2: hard-delete transaction-event rows older than the
     * row-retention cutoff. Runs AFTER [nullSnapshotsOlderThan] (numeric target
     * name prefixes `10_`/`20_` guarantee that order); the two operations are
     * independently idempotent count-only results.
     */
    @Query("DELETE FROM transaction_events WHERE occurredAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
