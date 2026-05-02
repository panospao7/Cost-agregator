package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a raw notification captured from the Android
 * notification listener service.
 *
 * Each notification is captured with its package name, title, text, and big text
 * for downstream parsing into [Expense] records. Deduplication is enforced via
 * a unique index on [dedupeFingerprint] (a deterministic hash of the notification
 * content). Notifications outside the capture window (too old) are periodically
 * pruned by [DataRetentionWorker].
 *
 * ## Deduplication
 * - [dedupeFingerprint] is a materialized SHA-256 hash of package+title+text+timestamp.
 * - The UNIQUE index on [dedupeFingerprint] prevents duplicate expense creation
 *   when the same notification is re-dispatched by the system.
 *
 * ## Indexes
 * - [packageName], [timestamp] — filtered queries by source app + time range.
 * - [isRelevant] — quick filtering for the review queue.
 * - Multi-column covering index on [packageName], [timestamp], [title], [text], [bigText]
 *   for batch dedup scans.
 */
@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]),
        Index(value = ["isRelevant"]),
        // Room's schema contract only declares the non-unique covering index
        // below. SQLite treats NULL != NULL, so any stronger dedup guarantees
        // must be handled outside the Room-exported schema contract.
        Index(value = ["packageName", "timestamp", "title", "text", "bigText"]),
        // Materialized dedupe fingerprint for deterministic dedup (unique when non-null).
        Index(value = ["dedupeFingerprint"], unique = true)
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
    val parseResult: String? = null,    // JSON of parsed data or error message

    // Raw data retention: epoch ms when raw content was purged, null = not yet purged
    val rawContentPurgedAt: Long? = null,

    /** Deterministic dedupe fingerprint (SHA-256 of packageName|title|text|bigText|timestamp).
     *  NULL for legacy rows; must be computed for new rows. */
    val dedupeFingerprint: String? = null
)
