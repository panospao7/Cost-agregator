package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a subscription candidate detected from notification transaction parsing.
 * Stores detection confidence and metadata to allow user confirmation/activation.
 */
@Entity(
    tableName = "subscription_candidates",
    indices = [
        Index(value = ["canonicalMerchant"], unique = false),
        Index(value = ["isConverted"]),
        Index(value = ["confidence"])
    ]
)
data class SubscriptionCandidate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Original merchant name from transactions */
    val merchant: String,
    
    /** Normalized/canonical merchant name */
    val canonicalMerchant: String,
    
    /** Average transaction amount */
    val averageAmount: Double,
    
    /** Currency code (e.g., EUR, USD) */
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    
    /** Detected recurrence interval: "weekly", "monthly", "yearly", etc. */
    val detectedInterval: String,
    
    /** Detection confidence score (0.0 to 1.0) */
    val confidence: Double,
    
    /** Number of transactions used for detection */
    val transactionCount: Int,
    
    /** Timestamp of first detected transaction */
    val firstSeen: Long,
    
    /** Timestamp of most recent detected transaction */
    val lastSeen: Long,
    
    /** Estimated annual cost based on average amount and interval */
    val estimatedAnnualCost: Double,
    
    /** Whether this candidate has been converted to an active subscription */
    @ColumnInfo(defaultValue = "0") val isConverted: Boolean = false,
    
    /** ID of the ManualRecurringExpense if converted */
    val convertedSubscriptionId: Long? = null,
    
    /** User action: "accepted", "rejected", "pending" */
    @ColumnInfo(defaultValue = "pending") val userAction: String = "pending",
    
    /** When the candidate was first detected */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** When the candidate was last updated */
    val updatedAt: Long = System.currentTimeMillis()
)
