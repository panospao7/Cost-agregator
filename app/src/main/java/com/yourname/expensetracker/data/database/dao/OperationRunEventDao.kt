package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.OperationRunEvent

@Dao
interface OperationRunEventDao {

    @Insert
    suspend fun insert(event: OperationRunEvent): Long

    @Query("SELECT * FROM operation_run_events WHERE operationRunId = :runId ORDER BY occurredAt ASC")
    suspend fun getByRunId(runId: Long): List<OperationRunEvent>

    @Query("SELECT * FROM operation_run_events WHERE correlationId = :correlationId ORDER BY occurredAt ASC")
    suspend fun getByCorrelationId(correlationId: String): List<OperationRunEvent>

    @Query("""
        SELECT * FROM operation_run_events
        WHERE outcome IN (
            'FAILED_RETRYABLE','FAILED_FINAL','CANCELLED',
            'BLOCKED','DROPPED','SIDE_EFFECT_FAILED'
        )
        OR severity IN ('WARNING','ERROR','CRITICAL')
        ORDER BY occurredAt DESC LIMIT :limit
    """)
    suspend fun getRecentFailures(limit: Int = 50): List<OperationRunEvent>

    @Query("SELECT COUNT(*) FROM operation_run_events WHERE eventId = :eventId")
    suspend fun existsByEventId(eventId: String): Int

    /**
     * RP-14 P8-003: delete ORPHAN events older than the cutoff by occurredAt.
     * "Orphan" = null parent run id OR no matching parent run row (the schema
     * declares no FK, so dangling references can exist). Runs separately from
     * [OperationRunDao.purgeTerminalRunsWithEvents]. Set-based SQL; never
     * materializes event payloads into Kotlin.
     */
    @Query("""
        DELETE FROM operation_run_events
        WHERE occurredAt < :beforeMs
          AND (
            operationRunId IS NULL
            OR operationRunId NOT IN (SELECT id FROM operation_runs)
          )
    """)
    suspend fun deleteOrphanEventsOlderThan(beforeMs: Long): Int
}
