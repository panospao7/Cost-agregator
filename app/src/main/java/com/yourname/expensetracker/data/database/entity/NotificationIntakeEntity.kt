package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable intake table that guarantees a notification accepted by the capture
 * gate is represented before expensive parser/AI/pipeline work begins.
 *
 * If the service or process dies between capture and persistence, the intake
 * row survives and can be resumed by a recovery worker on next app start.
 */
@Entity(
    tableName = "notification_intake",
    indices = [
        Index(value = ["dedupeFingerprint"], unique = true),
        Index(value = ["status", "nextAttemptAt"]),
        Index(value = ["status", "updatedAt"]),
        Index(value = ["correlationId"]),
        Index(value = ["packageName", "postTime"])
    ]
)
data class NotificationIntakeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Source app package name. */
    val packageName: String,
    /** Human-readable app name (may be null if unresolvable). */
    val appName: String?,
    /** Hashed notification key for safe tracing. */
    val notificationKeyHash: String?,
    /** Original notification postTime from StatusBarNotification. */
    val postTime: Long,
    /** When this intake row was captured (millis since epoch). */
    val capturedAt: Long,
    /** Source: "LISTENER" or "REFRESH". */
    val source: String,
    /** End-to-end correlation ID. */
    val correlationId: String,

    /** Canonical content fingerprint for deduplication. */
    val dedupeFingerprint: String,
    /** SHA-256 of combinedBody for extra safety. */
    val contentHash: String?,

    /** Processing payload — may be null/redacted per privacy policy. */
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?,

    /** Raw storage mode at time of capture. */
    val rawStorageMode: String,
    /** Whether intake payload is present (RAW, METADATA_ONLY, NONE). */
    val payloadMode: String,
    /** When raw payload fields were purged (null = not purged). */
    val rawPayloadPurgedAt: Long? = null,

    /** Current processing status (see NotificationIntakeStatus). */
    val status: String,
    /** Number of processing attempts so far. */
    val attempts: Int = 0,
    /** Maximum allowed attempts before final failure. */
    val maxAttempts: Int = 5,
    /** Earliest time for next retry attempt (null = immediate). */
    val nextAttemptAt: Long? = null,
    /** When this row was claimed for processing (null = unclaimed). */
    val lockedAt: Long? = null,
    /** Worker ID that claimed this row. */
    val lockedBy: String? = null,
    /** Timestamp of last processing attempt. */
    val lastAttemptAt: Long? = null,
    /** When terminal status was reached. */
    val terminalAt: Long? = null,

    /** Result references — set when terminal. */
    val rawNotificationId: Long? = null,
    val expenseId: Long? = null,
    val pendingReviewId: Long? = null,

    /** Last failure info (safe metadata only). */
    val lastFailureCode: String? = null,
    val lastFailureMessageHash: String? = null,
    /** Final outcome class name for debugging. */
    val finalOutcome: String? = null,

    val createdAt: Long,
    val updatedAt: Long
)
