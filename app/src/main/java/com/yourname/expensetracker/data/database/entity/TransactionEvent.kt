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
 * ## DB-8: CASCADE audit — TransactionEvent
 * This entity has **no foreign key declarations** — [expenseId] is nullable and
 * unconstrained at the DB level. No cascade-delete risk exists. This is intentional:
 * events must survive expense deletion so the audit trail is never lost. If an FK
 * were added, it should use `onDelete = ForeignKey.SET_NULL` to preserve the event
 * when the parent expense is deleted.
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
 *
 * ## AID-9: Future ai_audit_log table
 *
 * The `transaction_events` table serves as the general-purpose lifecycle audit log
 * for expenses.  For AI-specific audit events (`AI_AUTO_ACCEPT`, `AI_WARRANTY_CREATED`)
 * the current implementation writes into `transaction_events` / `receipt_events` with
 * event-type prefixes.
 *
 * If review/rollback requirements grow (e.g. listing all AI actions in a dedicated UI,
 * reverting auto-accepted expenses in bulk), consider creating a dedicated
 * `ai_audit_log` table with the following columns:
 *
 * | Column              | Type    | Description                                                  |
 * |---------------------|---------|--------------------------------------------------------------|
 * | id                  | LONG    | Auto-generated primary key                                   |
 * | confidence          | DOUBLE  | The AI confidence score that drove the auto-action           |
 * | routingDecision     | STRING  | The routing decision from the confidence router              |
 * | rawPayloadReference | STRING  | FK or reference to the raw notification / receipt ID         |
 * | createdExpenseId    | LONG?   | FK to the expense that was auto-created (nullable)           |
 * | createdAt           | LONG    | Epoch millisecond timestamp of when the AI action occurred   |
 * | eventType           | STRING  | Discriminator: "AI_AUTO_ACCEPT", "AI_WARRANTY_CREATED", etc. |
 * | metadata            | STRING  | JSON blob for extra AI-specific context                      |
 *
 * This dedicated table would allow efficient queries for "all AI actions" without
 * filtering by event-type prefix across the general-purpose event tables.
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
