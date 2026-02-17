package com.yourname.expensetracker.data.database.entity

import androidx.room.*

@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Long = 0,
    val acceptedAsExpense: Long = 0,
    val rejectedByUser: Long = 0,
    val autoRejected: Long = 0,
    val pendingReview: Long = 0,
    val duplicates: Long = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() {
            val valid = acceptedAsExpense + duplicates
            return if (totalNotifications > 0)
                valid.toFloat() / totalNotifications
            else 0f
        }

    val isLikelySpam: Boolean
        get() = totalNotifications > 10 && trustScore < 0.05f
}
