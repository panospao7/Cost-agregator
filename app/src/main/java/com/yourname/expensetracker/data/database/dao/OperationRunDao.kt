package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.OperationRun

@Dao
interface OperationRunDao {

    @Insert
    suspend fun insert(run: OperationRun): Long

    @Update
    suspend fun update(run: OperationRun)

    @Query("SELECT * FROM operation_runs WHERE id = :id")
    suspend fun getById(id: Long): OperationRun?

    @Query("SELECT * FROM operation_runs WHERE correlationId = :correlationId")
    suspend fun getByCorrelationId(correlationId: String): OperationRun?

    @Query("SELECT * FROM operation_runs WHERE operationType = :type ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(type: String, limit: Int = 20): List<OperationRun>

    @Query("SELECT * FROM operation_runs WHERE status = 'RUNNING' AND startedAt < :staleThresholdMs")
    suspend fun getStaleRunning(staleThresholdMs: Long): List<OperationRun>

    /** Persist counter increments immediately — prevents loss on process death. */
    @Query("""
        UPDATE operation_runs
        SET rowsProcessed = rowsProcessed + :processed,
            rowsSucceeded = rowsSucceeded + :succeeded,
            rowsFailed    = rowsFailed    + :failed,
            rowsSkipped   = rowsSkipped   + :skipped,
            warningCount  = warningCount  + :warnings,
            errorCount    = errorCount    + :errors
        WHERE id = :id AND status = 'RUNNING'
    """)
    suspend fun incrementCounters(
        id: Long,
        processed: Int,
        succeeded: Int,
        failed: Int,
        skipped: Int,
        warnings: Int,
        errors: Int
    )

    /** Idempotent finalization — only first terminal state wins. Returns rows updated. */
    @Query("""
        UPDATE operation_runs
        SET status = :status, finishedAt = :finishedAt, errorSummary = :errorSummary
        WHERE id = :id AND status = 'RUNNING'
    """)
    suspend fun finalizeIfRunning(
        id: Long,
        status: String,
        finishedAt: Long,
        errorSummary: String?
    ): Int
}
