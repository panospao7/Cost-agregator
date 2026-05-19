package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity to track email receipts from providers (Amazon, Uber, Apple, etc.)
 * Links to the scanned_receipts table for unified receipt processing.
 */
@Entity(
    tableName = "email_receipt_sources",
    foreignKeys = [
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["receiptId"]),
        Index(value = ["emailMessageId"], unique = true),
        Index(value = ["provider", "parsedAt"]),
        Index(value = ["parsedAt"]),
        Index(name = "index_email_receipt_fingerprint", value = ["fingerprint"]),
        Index(name = "index_email_receipt_sources_emailMessageIdHash", value = ["emailMessageIdHash"])
    ]
)
data class EmailReceiptSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val receiptId: Long, // FK to scanned_receipts

    /** Raw sender — null in restricted storage modes. */
    @ColumnInfo(defaultValue = "NULL")
    val emailSender: String? = null,

    /** Raw subject — null in restricted storage modes. */
    @ColumnInfo(defaultValue = "NULL")
    val emailSubject: String? = null,

    @ColumnInfo(defaultValue = "NULL")
    val emailMessageId: String? = null,

    /** HMAC hash of messageId — present in all modes for dedup. */
    @ColumnInfo(defaultValue = "NULL")
    val emailMessageIdHash: String? = null,

    /** SHA-256 hash of content fingerprint (merchant+amount+date). */
    @ColumnInfo(defaultValue = "NULL")
    val contentFingerprintHash: String? = null,

    val parsedAt: Long,

    val provider: String, // "amazon", "uber", "apple", "unknown"

    val confidence: Double,

    @ColumnInfo(defaultValue = "")
    val fingerprint: String = ""
)
