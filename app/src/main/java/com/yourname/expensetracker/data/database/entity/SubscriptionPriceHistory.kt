package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks price history for subscription/recurring expenses.
 * Allows detecting price increases and calculating total price changes over time.
 */
@Entity(
    tableName = "subscription_price_history",
    foreignKeys = [
        ForeignKey(
            entity = ManualRecurringExpense::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["subscriptionId", "recordedAt"])]
)
data class SubscriptionPriceHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subscriptionId: Long, // Foreign key to ManualRecurringExpense
    val amount: Double,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    val recordedAt: Long = System.currentTimeMillis(),
    val changeReason: String? = null // e.g., "Annual increase", "Plan upgrade", "Promotional rate ended"
)
