package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recurring_reminder_deliveries",
    indices = [
        Index(value = ["occurrenceId", "reminderWindow"]),
        Index(value = ["status"]),
        Index(value = ["scheduledAt"])
    ]
)
data class RecurringReminderDelivery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long,
    val reminderWindow: String,     // "DUE_DAY", "3_DAYS_BEFORE", "7_DAYS_BEFORE", "14_DAYS_BEFORE", "30_DAYS_BEFORE", "OVERDUE"
    val scheduledAt: Long,          // when the reminder should fire (epoch millis)
    val status: String,             // "SCHEDULED", "SENT", "DISMISSED", "SNOOZED", "FAILED"
    val lastSentAt: Long? = null,
    val dismissedAt: Long? = null,
    val snoozedUntil: Long? = null,
    val notificationId: Int? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)
