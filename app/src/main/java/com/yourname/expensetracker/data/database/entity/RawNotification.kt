package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]),
        Index(value = ["isRelevant"]),
        // Room's schema contract only declares the non-unique covering index
        // below. SQLite treats NULL != NULL, so any stronger dedup guarantees
        // must be handled outside the Room-exported schema contract.
        Index(value = ["packageName", "timestamp", "title", "text"])
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Source app
    val packageName: String,
    val appName: String?,
    
    // Notification content
    val title: String?,
    val text: String?,
    val bigText: String? = null,          // Expanded notification
    val subText: String? = null,
    
    // Raw extras as JSON string for debugging
    val extrasJson: String? = null,
    
    // Metadata
    val timestamp: Long,           // When notification was posted
    val capturedAt: Long,          // When we captured it
    
    // Processing status
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null    // JSON of parsed data or error message
)
