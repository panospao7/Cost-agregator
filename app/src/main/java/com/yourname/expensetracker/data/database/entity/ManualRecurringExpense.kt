package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.RecurrenceFrequency

@Entity(tableName = "manual_recurring_expenses")
data class ManualRecurringExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    val currency: String = "EUR",
    val frequency: RecurrenceFrequency,
    val nextDate: Long,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    
    // Subscription-specific fields (added in migration 39→40)
    val isSubscription: Boolean = true, // Default to true for backwards compatibility
    val subscriptionCategory: String? = null, // e.g., "Streaming", "Software", "Fitness", "News"
    val usageTargetPerMonth: Int? = null, // Expected usage count per month
    val cancellationUrl: String? = null, // URL for easy cancellation
    val isActive: Boolean = true // Whether user is still subscribed
)
