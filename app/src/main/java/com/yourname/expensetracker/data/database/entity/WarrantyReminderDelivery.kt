package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable, claim-before-notify sent-state for [com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker].
 *
 * Replaces the previous SharedPreferences-based dedup (warranty_expiration_worker_prefs).
 * One row per (warrantyId, windowDays, expiryDate) reminder. The row is SENT only after
 * the notification delivery actually succeeds, so:
 *  - the same reminder is never re-sent across reboots / worker reschedules (durable dedup),
 *  - two concurrent runs cannot double-notify (atomic claim flips SCHEDULED→CLAIMED),
 *  - the state survives backup/restore (it lives in the Room DB, which is snapshotted whole).
 *
 * Modeled on [RecurringReminderDelivery] (the proven claim/markSent/recover-stale template).
 *
 * ## Status lifecycle
 * SCHEDULED → CLAIMED → SENT (terminal) | FAILED (retryable next run).
 *
 * A FAILED row is re-claimable on a subsequent run (transient delivery failure, e.g.
 * notifications temporarily disabled). A SENT row is terminal and never re-claimed.
 */
@Entity(
    tableName = "warranty_reminder_deliveries",
    foreignKeys = [
        ForeignKey(
            entity = Warranty::class,
            parentColumns = ["id"],
            childColumns = ["warrantyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Idempotency key: one delivery per warranty + window + expiry.
        Index(value = ["warrantyId", "windowDays", "expiryDate"], unique = true),
        // Room requires an index on the FK child column (warrantyId).
        Index(value = ["warrantyId"])
    ]
)
data class WarrantyReminderDelivery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warrantyId: Long,
    /** Reminder window in days (e.g. 7 or 30). */
    val windowDays: Int,
    /** The warranty's exclusive end (warrantyEndDate) at the time the reminder was seeded. */
    val expiryDate: Long,
    val status: String,                 // "SCHEDULED", "CLAIMED", "SENT", "FAILED"
    /** Timestamp of when the delivery was claimed (set atomically by [com.yourname.expensetracker.data.database.dao.WarrantyReminderDeliveryDao.claim]). */
    val claimedAt: Long? = null,
    /** Timestamp of the most recent delivery attempt. */
    val lastAttemptAt: Long? = null,
    /** Number of delivery attempts (incremented on each claim). */
    val attemptCount: Int = 0,
    /** Android notification id, persisted only once delivery actually succeeds. */
    val notificationId: Int? = null,
    /** Human-readable reason for a failed delivery attempt. */
    val failureReason: String? = null,
    /** Must be set to the worker's now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to the worker's now() on every mutation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
)
