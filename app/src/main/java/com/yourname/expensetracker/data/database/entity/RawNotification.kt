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
        // The entity-level unique index is NOT declared here because SQLite
        // treats NULL != NULL, so the 4-column unique index cannot prevent
        // duplicate rows when title or text is NULL.  A partial unique index
        // (created in MIGRATION_73_74 / FRESH_INSTALL_CALLBACK) closes this
        // loophole at the DB level.  The old index
        // index_raw_notifications_packageName_timestamp_title_text is dropped
        // by the migration and replaced by two partial indexes.
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
