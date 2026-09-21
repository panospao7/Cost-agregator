package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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

    // ── RP-14 P8-003: operation-run retention ─────────────────────────────────

    /** Child/parent counts reported by [purgeTerminalRunsWithEvents]. */
    data class OperationRunPurgeCounts(
        val childEventsDeleted: Int,
        val parentRunsDeleted: Int
    )

    /**
     * RP-14 P8-003: ONE atomic transaction — delete the child
     * `operation_run_events` of terminal runs whose `finishedAt` is before the
     * cutoff, then the parent runs. The schema declares no FK/cascade, so the
     * deletion order is explicit; the transaction prevents
     * child-success/parent-failure partial state. RUNNING rows are NEVER
     * purged (only `status != 'RUNNING'` rows with a past `finishedAt` match).
     * Set-based SQL; no run identifiers are materialized into Kotlin.
     */
    @Transaction
    suspend fun purgeTerminalRunsWithEvents(cutoffMs: Long): OperationRunPurgeCounts {
        val children = deleteEventsForTerminalRunsOlderThan(cutoffMs)
        val parents = deleteTerminalRunsOlderThan(cutoffMs)
        return OperationRunPurgeCounts(
            childEventsDeleted = children,
            parentRunsDeleted = parents
        )
    }

    @Query("""
        DELETE FROM operation_run_events
        WHERE operationRunId IN (
            SELECT id FROM operation_runs
            WHERE status != 'RUNNING'
              AND finishedAt IS NOT NULL
              AND finishedAt < :cutoffMs
        )
    """)
    suspend fun deleteEventsForTerminalRunsOlderThan(cutoffMs: Long): Int

    @Query("""
        DELETE FROM operation_runs
        WHERE status != 'RUNNING'
          AND finishedAt IS NOT NULL
          AND finishedAt < :cutoffMs
    """)
    suspend fun deleteTerminalRunsOlderThan(cutoffMs: Long): Int
}
