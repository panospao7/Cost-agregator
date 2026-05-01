package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.RecurrenceFrequency

@Entity(
    tableName = "manual_recurring_expenses",
    indices = [
        Index(value = ["isActive", "nextDate"]),
        Index(value = ["isSubscription", "isActive", "nextDate"]),
        Index(value = ["merchant"])
    ]
)
data class ManualRecurringExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    val frequency: RecurrenceFrequency,
    val nextDate: Long,
    val note: String? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    
    // Subscription-specific fields (added in migration 39→40)
    @ColumnInfo(defaultValue = "0") val isSubscription: Boolean = false, // B4: default false; only true when explicitly a subscription
    val subscriptionCategory: String? = null, // e.g., "Streaming", "Software", "Fitness", "News"
    val usageTargetPerMonth: Int? = null, // Expected usage count per month
    val cancellationUrl: String? = null, // URL for easy cancellation
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true // Whether user is still subscribed
)
