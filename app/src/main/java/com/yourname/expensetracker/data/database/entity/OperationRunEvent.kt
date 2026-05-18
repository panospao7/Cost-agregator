package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Individual event within an [OperationRun]. No hard FK so audit rows survive cleanup.
 */
@Entity(
    tableName = "operation_run_events",
    indices = [
        Index(value = ["operationRunId"]),
        Index(value = ["correlationId"]),
        Index(value = ["eventType"]),
        Index(value = ["occurredAt"])
    ]
)
data class OperationRunEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationRunId: Long? = null,
    val correlationId: String,
    val causationId: String? = null,
    val operationType: String,
    val stage: String,
    val eventType: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String? = null,
    val occurredAt: Long,
    val entityType: String? = null,
    val entityId: Long? = null,
    val metadataJson: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null
)
