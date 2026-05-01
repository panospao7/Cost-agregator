package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks usage patterns for subscriptions to determine value.
 * Users can log when they use a subscription service.
 */
@Entity(
    tableName = "subscription_usage",
    foreignKeys = [
        ForeignKey(
            entity = ManualRecurringExpense::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["subscriptionId", "usedAt"])]
)
data class SubscriptionUsage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subscriptionId: Long, // Foreign key to ManualRecurringExpense
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val usedAt: Long = 0L,
    val usageDurationMinutes: Int? = null, // Optional: how long they used it
    val usageType: String? = null // e.g., "watched_movie", " listened_music", "played_game"
)
