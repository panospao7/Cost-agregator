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
        WHERE outcome IN ('FAILED_RETRYABLE','FAILED_FINAL','CANCELLED')
        ORDER BY occurredAt DESC LIMIT :limit
    """)
    suspend fun getRecentFailures(limit: Int = 50): List<OperationRunEvent>
}
