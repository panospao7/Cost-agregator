package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "privacy_audit_events",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["capability"]),
        Index(value = ["caller"])
    ]
)
data class PrivacyAuditEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val capability: String,
    val decision: String,  // "ALLOWED" or "DENIED"
    val reason: String?,
    val context: String?,  // JSON string
    val timestampMs: Long,
    val caller: String?
)
