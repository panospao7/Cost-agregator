package com.yourname.expensetracker.data.database.entity

import androidx.room.*

@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    @ColumnInfo(defaultValue = "0") val totalNotifications: Long = 0,
    @ColumnInfo(defaultValue = "0") val acceptedAsExpense: Long = 0,
    @ColumnInfo(defaultValue = "0") val rejectedByUser: Long = 0,
    @ColumnInfo(defaultValue = "0") val autoRejected: Long = 0,
    @ColumnInfo(defaultValue = "0") val pendingReview: Long = 0,
    @ColumnInfo(defaultValue = "0") val duplicates: Long = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() {
            // Exclude auto-rejected notifications (promos, exchange-rate alerts, non-financial
            // content that the parser correctly returned null for) from the denominator.
            // Counting them would penalise high-volume sources like Revolut that send many
            // non-financial notifications, causing real purchases to score below AUTO_ACCEPT.
            val effectiveTotal = totalNotifications - autoRejected
            val valid = acceptedAsExpense + duplicates
            return if (effectiveTotal > 0)
                valid.toFloat() / effectiveTotal
            else 0f
        }

    val isLikelySpam: Boolean
        get() {
            val effectiveTotal = totalNotifications - autoRejected
            return effectiveTotal > 10 && trustScore < 0.05f
        }
}
