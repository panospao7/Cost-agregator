package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable event log recording every significant lifecycle transition for a receipt.
 *
 * Each row captures a single event (capture, validation, OCR, parsing, etc.)
 * together with the actor, timestamps, and status transitions so that the full
 * history of a receipt can be reconstructed for auditing or debugging.
 *
 * @property id Auto-generated primary key.
 * @property receiptId The receipt this event relates to (nullable for events before receipt creation).
 * @property sourceType The source type of the receipt at the time of the event.
 * @property documentType The document type of the receipt at the time of the event.
 * @property eventType The type of lifecycle event (e.g. "CAPTURED", "OCR_COMPLETED", "EXPENSE_CREATED").
 * @property occurredAt Epoch millisecond timestamp of when the event occurred.
 * @property oldStatus The processing status before the transition.
 * @property newStatus The processing status after the transition.
 * @property actor Who or what performed the action (e.g. "user", "system:ocr", "system:backfill").
 * @property message Optional human-readable description of the event.
 * @property metadata JSON map for extra event-specific data.
 * @property errorDetails Error details if the event represents a failure.
 */
@Entity(
    tableName = "receipt_events",
    indices = [
        Index(value = ["receiptId"]),
        Index(value = ["sourceType"]),
        Index(value = ["documentType"]),
        Index(value = ["occurredAt"]),
        Index(value = ["eventType"])
    ]
)
data class ReceiptEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long?,
    val sourceType: String,
    val documentType: String,
    val eventType: String,
    val occurredAt: Long,
    val oldStatus: String?,
    val newStatus: String?,
    val actor: String?,
    val message: String?,
    val metadata: String?,
    val errorDetails: String?
)
