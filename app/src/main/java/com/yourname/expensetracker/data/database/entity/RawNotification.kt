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
 *
 * ## DB-1: Stale partial indexes (CLEANED UP in MIGRATION_73_74 — VERIFIED)
 * Older migrations created several partial unique indexes to close SQLite's
 * NULL!=NULL dedup loophole:
 *   - `index_raw_notifications_dedup_nonnull` (when all 4 columns non-null)
 *   - `index_raw_notifications_dedup_both_null` (when title+text both null)
 *   - `index_raw_notifications_dedup_title_null` (when only title is null)
 *   - `index_raw_notifications_dedup_text_null` (when only text is null)
 *
 * These were **dropped** in migration 73→74 (lines 4372-4375 of AppDatabase.kt)
 * via `DROP INDEX IF EXISTS`. The cleanup is complete and confirmed. If any of
 * these indexes are detected on an existing database (e.g., long-hop migration
 * from a very old schema that skipped migration 73), they are safe to drop
 * manually — they serve no purpose alongside the materialized [dedupeFingerprint]
 * column (added in migration 64→65) which provides deterministic dedup without
 * partial indexes.
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
    // TODO P1-CURRENT-015: isProcessed is never set to true anywhere in the codebase.
    // Either update it after pipeline processing or remove the field in a migration.
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null,    // JSON of parsed data or error message

    // Raw data retention: epoch ms when raw content was purged, null = not yet purged
    val rawContentPurgedAt: Long? = null,

    /** Deterministic dedupe fingerprint (SHA-256 of packageName|title|text|bigText|timestamp).
     *  NULL for legacy rows; must be computed for new rows. */
    val dedupeFingerprint: String? = null
)
