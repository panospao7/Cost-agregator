package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pipeline_diagnostic_events",
    indices = [
        Index(value = ["pipeline", "stage"]),
        Index(value = ["timestamp"]),
        Index(value = ["correlationId"]),
        Index(value = ["reasonCode"]),
        Index(value = ["entityType", "entityId"])
    ]
)
data class PipelineDiagnosticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pipeline: String,
    val stage: String,
    val outcome: String,
    val packageName: String? = null,
    val sourceId: Long? = null,
    val dropReason: String? = null,
    val message: String? = null,
    val timestamp: Long,
    val entityType: String? = null,
    val entityId: Long? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val metadataJson: String? = null,
    val notificationKeyHash: String? = null,
    val confidence: Float? = null,
    val decisionSource: String? = null,
    val elapsedMs: Long? = null,
    // --- PR 2 additions ---
    val eventId: String? = null,
    val correlationId: String? = null,
    val causationId: String? = null,
    val severity: String? = null,
    val reasonCode: String? = null,
    val sourceType: String? = null,
    val sourceIdHash: String? = null,
    val isTerminal: Boolean? = null,
    val metadataSchemaVersion: Int = 1
)
