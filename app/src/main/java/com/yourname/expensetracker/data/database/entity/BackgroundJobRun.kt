package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent record of a background worker execution (job run).
 *
 * Each time a WorkManager worker executes, it inserts a [BackgroundJobRun] row
 * to track observability: when it started/finished, what it processed, and
 * whether it succeeded or needs retry.
 *
 * Status values:
 * - SCHEDULED: Worker was scheduled but has not started yet.
 * - RUNNING:  Worker is currently executing.
 * - SUCCESS:  Worker completed successfully.
 * - FAILED:   Worker failed with a permanent error.
 * - RETRY:    Worker hit a transient error and will be retried.
 */
@Entity(
    tableName = "background_job_runs",
    indices = [
        Index(value = ["workerName", "startedAt"]),
        Index(value = ["status"])
    ]
)
data class BackgroundJobRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Logical worker name, e.g. "data_retention", "location_backfill". */
    val workerName: String,
    /** Epoch millis when the run started. */
    val startedAt: Long,
    /** Epoch millis when the run finished (null while still running). */
    val finishedAt: Long? = null,
    /** Execution status: SCHEDULED, RUNNING, SUCCESS, FAILED, RETRY. */
    val status: String,
    /** Number of rows/documents scanned during this run. */
    val rowsScanned: Int = 0,
    /** Number of rows/documents updated during this run. */
    val rowsUpdated: Int = 0,
    /** Number of notifications sent during this run. */
    val notificationsSent: Int = 0,
    /** If retrying, the reason for the retry. */
    val retryReason: String? = null,
    /** If failed, the error message. */
    val errorMessage: String? = null
)
