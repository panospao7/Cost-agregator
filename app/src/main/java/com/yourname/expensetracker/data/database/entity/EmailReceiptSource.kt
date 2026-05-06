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
        // DB-8: CASCADE on EmailReceiptSource.receiptId → ScannedReceipt(id)
        // Safe: Email source records are child data of a scanned receipt; no value in keeping orphaned records.
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
        Index(name = "index_email_receipt_fingerprint", value = ["fingerprint"])
    ]
)
data class EmailReceiptSource(
    @PrimaryKey(autoGenerate = true) 
    val id: Long = 0,
    
    val receiptId: Long, // FK to scanned_receipts
    
    val emailSender: String,
    
    val emailSubject: String,
    
    @ColumnInfo(defaultValue = "NULL")
    val emailMessageId: String? = null,
    
    val parsedAt: Long,
    
    val provider: String, // "amazon", "uber", "apple", "unknown"
    
    val confidence: Double,
    
    // Fingerprint for deduplication: merchant_lowercase + amount + date
    @ColumnInfo(defaultValue = "") 
    val fingerprint: String = ""
)
