package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pipeline_diagnostic_events",
    indices = [
        Index(value = ["pipeline", "stage"]),
        Index(value = ["timestamp"])
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
    val timestamp: Long
)