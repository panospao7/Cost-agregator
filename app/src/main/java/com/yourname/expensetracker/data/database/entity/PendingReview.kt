package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["status"])
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val confidence: Float,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING"      // PENDING, APPROVED, REJECTED, MODIFIED
)
