package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks a single execution of a batch/multi-step operation (backup, restore,
 * export, import, bank sync, email batch, etc.).
 *
 * Status values: RUNNING, SUCCESS, PARTIAL_SUCCESS, SKIPPED, FAILED_RETRYABLE,
 *                FAILED_FINAL, CANCELLED, STALE_ABORTED
 */
@Entity(
    tableName = "operation_runs",
    indices = [
        Index(value = ["operationType", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["correlationId"], unique = true)
    ]
)
data class OperationRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val operationType: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val actor: String? = null,
    val rowsTotal: Int? = null,
    val rowsProcessed: Int = 0,
    val rowsSucceeded: Int = 0,
    val rowsFailed: Int = 0,
    val rowsSkipped: Int = 0,
    val warningCount: Int = 0,
    val errorCount: Int = 0,
    val metadataJson: String? = null,
    val errorSummary: String? = null
)
