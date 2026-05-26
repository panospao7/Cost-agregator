package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recurring_reminder_deliveries",
    foreignKeys = [
        ForeignKey(
            entity = RecurringOccurrence::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["occurrenceId", "reminderWindow"], unique = true),
        Index(value = ["status"]),
        Index(value = ["scheduledAt"]),
        Index(value = ["claimedAt"]),
        Index(value = ["occurrenceId"])
    ]
)
data class RecurringReminderDelivery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long,
    val reminderWindow: String,     // "DUE_DAY", "3_DAYS_BEFORE", "7_DAYS_BEFORE", "14_DAYS_BEFORE", "30_DAYS_BEFORE", "OVERDUE"
    val scheduledAt: Long,          // when the reminder should fire (epoch millis)
    val status: String,             // "SCHEDULED", "CLAIMED", "SENT", "DISMISSED", "SNOOZED", "CANCELLED", "FAILED_PERMISSION", "FAILED_TRANSIENT", "FAILED_FINAL"
    val lastSentAt: Long? = null,
    val dismissedAt: Long? = null,
    val snoozedUntil: Long? = null,
    val notificationId: Int? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Timestamp of when the delivery was claimed (set atomically by claimDelivery). */
    val claimedAt: Long? = null,
    /** Timestamp of the most recent delivery attempt. */
    val lastAttemptAt: Long? = null,
    /** Number of delivery attempts (incremented on each claim). */
    val attemptCount: Int = 0,
    /** Human-readable reason for a failed delivery attempt. */
    val failureReason: String? = null,
    /** Must be set to timeProvider.now() on every mutation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
)
