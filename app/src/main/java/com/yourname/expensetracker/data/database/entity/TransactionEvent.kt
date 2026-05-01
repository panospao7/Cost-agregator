package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable event log recording every significant lifecycle transition for an expense.
 *
 * Each row captures a single event (creation, update, delete, dedupe skip, etc.)
 * together with the actor, timestamps, and optional before/after snapshots so that
 * the full history of an expense can be reconstructed for auditing or debugging.
 *
 * @property id Auto-generated primary key.
 * @property expenseId The expense this event relates to (nullable for attempted-but-failed creates).
 * @property eventType The type of lifecycle event (from LifecycleEventType enum name).
 * @property source The source system/component that triggered this event (ExpenseSource name or "SYSTEM").
 * @property actor Who or what performed the action (e.g. "user", "system:backfill", "notification-auto-accept").
 * @property occurredAt Epoch millisecond timestamp of when the event occurred.
 * @property dedupeKey The deduplication key involved (for dedupe-related events).
 * @property duplicateExpenseId The ID of the existing expense that was matched as a duplicate.
 * @property beforeSnapshot JSON snapshot of the expense state before the change (null for creates).
 * @property afterSnapshot JSON snapshot of the expense state after the change (null for deletes).
 * @property metadata JSON map for extra event-specific data (e.g. validation errors, conflict details).
 * @property reason Optional human-readable explanation for the event.
 */
@Entity(
    tableName = "transaction_events",
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["source"]),
        Index(value = ["occurredAt"]),
        Index(value = ["eventType"])
    ]
)
data class TransactionEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val expenseId: Long?,
    val eventType: String,  // LifecycleEventType.name
    val source: String,      // ExpenseSource.name or "SYSTEM"
    val actor: String?,
    val occurredAt: Long,    // epoch millis
    val dedupeKey: String?,
    val duplicateExpenseId: Long?,
    val beforeSnapshot: String?,  // JSON snapshot before update/delete
    val afterSnapshot: String?,   // JSON snapshot after create/update
    val metadata: String?,        // JSON map for extra data
    val reason: String?
)
