package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable event log recording every significant lifecycle transition
 * for a recurring occurrence.
 *
 * Each row captures a single event type together with timestamps and
 * optional before/after status snapshots so that the full history of
 * an occurrence can be reconstructed for auditing or debugging.
 *
 * @property id Auto-generated primary key.
 * @property occurrenceId The occurrence this event relates to (nullable for coordinator-level events).
 * @property eventType One of: "OCCURRENCE_GENERATED", "OCCURRENCE_PAID", "OCCURRENCE_SKIPPED",
 *                     "OCCURRENCE_CANCELLED", "REMINDER_SCHEDULED", "REMINDER_SENT",
 *                     "REMINDER_DISMISSED", "PLANNED_GENERATED", "DRIFT_DETECTED".
 * @property occurredAt Epoch millisecond timestamp of when the event occurred.
 * @property oldStatus Previous status of the occurrence (null for creates).
 * @property newStatus New status of the occurrence (null for informational events).
 * @property metadata JSON map for extra event-specific data.
 */
@Entity(
    tableName = "recurring_lifecycle_events",
    indices = [
        Index(value = ["occurrenceId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["eventType"])
    ]
)
data class RecurringLifecycleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceId: Long?,
    val eventType: String,
    val occurredAt: Long,
    val oldStatus: String?,
    val newStatus: String?,
    val metadata: String?
)
