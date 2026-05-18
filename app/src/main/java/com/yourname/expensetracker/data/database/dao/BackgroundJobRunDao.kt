package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun

/**
 * DAO for persisting and querying [BackgroundJobRun] records.
 *
 * Each worker execution should:
 * 1. [insert] a RUNNING record at the start.
 * 2. [update] the record with final status, row counts, and finishedAt on completion.
 */
@Dao
interface BackgroundJobRunDao {

    /**
     * Insert a new job run record. Returns the auto-generated primary key.
     */
    @Insert
    suspend fun insert(run: BackgroundJobRun): Long

    /**
     * Update an existing job run record (e.g. to set finishedAt and final status).
     */
    @Update
    suspend fun update(run: BackgroundJobRun)

    /**
     * Fetch the most recent [limit] job runs for a given worker, ordered by
     * startedAt descending.
     */
    @Query(
        """
        SELECT * FROM background_job_runs
        WHERE workerName = :workerName
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecent(workerName: String, limit: Int = 20): List<BackgroundJobRun>

    /**
     * Fetch all job runs with RUNNING status that started before [staleThresholdMs].
     * These are runs that may have been abandoned (e.g. process killed).
     */
    @Query(
        """
        SELECT * FROM background_job_runs
        WHERE status = 'RUNNING' AND startedAt < :staleThresholdMs
        ORDER BY startedAt ASC
        """
    )
    suspend fun getStaleRunningRuns(staleThresholdMs: Long): List<BackgroundJobRun>

    @Query("SELECT * FROM background_job_runs WHERE correlationId = :correlationId ORDER BY startedAt ASC")
    suspend fun getByCorrelationId(correlationId: String): List<BackgroundJobRun>
}
