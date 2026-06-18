package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent record of a background worker execution.
 *
 * Status values: RUNNING, SUCCESS, SKIPPED, RETRY, FAILED, CANCELLED, STALE_ABORTED
 * Use [statusReason] for typed skip/cancel reason instead of encoding it in status.
 */
@Entity(
    tableName = "background_job_runs",
    indices = [
        Index(value = ["workerName", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["correlationId"])
    ]
)
data class BackgroundJobRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** RUNNING, SUCCESS, SKIPPED, RETRY, FAILED, CANCELLED, STALE_ABORTED */
    val status: String,
    val rowsScanned: Int = 0,
    val rowsUpdated: Int = 0,
    val notificationsSent: Int = 0,
    val retryReason: String? = null,
    val errorMessage: String? = null,
    /** Typed reason code (DiagnosticReasonCode name) for SKIPPED/CANCELLED status. */
    val statusReason: String? = null,
    // --- PR 2 additions ---
    val correlationId: String? = null,
    val cancellationReason: String? = null,
    val metadataJson: String? = null,
    val errorClass: String? = null
)
