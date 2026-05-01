package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links receipts to expenses, supporting both automatic matching and manual linking.
 *
 * This join table allows many-to-many relationships between receipts and expenses,
 * tracking the confidence of the match, whether it is the primary link, and the
 * source that created the link.
 *
 * @property id Auto-generated primary key.
 * @property receiptId The receipt being linked.
 * @property expenseId The expense being linked.
 * @property linkType The type of link (e.g. "AUTO_MATCHED", "MANUAL", "SPLIT", "MERGE").
 * @property confidence Confidence score of the match (0.0 to 1.0).
 * @property source The source system/component that created the link.
 * @property createdAt Epoch millisecond timestamp of when the link was created.
 * @property createdBy Who or what created the link.
 * @property isPrimary Whether this is the primary link for the receipt.
 * @property metadata JSON map for extra link-specific data.
 */
@Entity(
    tableName = "receipt_expense_links",
    indices = [
        Index(value = ["receiptId"]),
        Index(value = ["expenseId"]),
        Index(value = ["receiptId", "expenseId"], unique = true),
        Index(value = ["linkType"]),
        Index(value = ["createdAt"])
    ]
)
data class ReceiptExpenseLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val expenseId: Long,
    val linkType: String,
    val confidence: Float?,
    val source: String,
    val createdAt: Long,
    val createdBy: String?,
    val isPrimary: Boolean = true,
    val metadata: String? = null
)
