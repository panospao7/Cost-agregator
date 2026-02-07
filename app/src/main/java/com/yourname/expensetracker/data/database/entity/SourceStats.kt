package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Int = 0,
    val acceptedAsExpense: Int = 0,
    val rejectedByUser: Int = 0,
    val autoRejected: Int = 0,
    val pendingReview: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() = if (totalNotifications > 0)
            acceptedAsExpense.toFloat() / totalNotifications
        else 0f

    val isLikelySpam: Boolean
        get() = totalNotifications > 10 && trustScore < 0.05f
}
