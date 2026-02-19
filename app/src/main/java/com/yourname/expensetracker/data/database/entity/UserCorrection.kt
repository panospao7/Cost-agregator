package com.yourname.expensetracker.data.database.entity

import androidx.room.*

@Entity(
    tableName = "user_corrections",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["originalCategoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["correctedCategoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("originalCategoryId"),
        Index("correctedCategoryId")
    ]
)
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val originalMerchant: String,
    val correctedMerchant: String?,
    val originalAmount: Double,
    val correctedAmount: Double?,
    val originalCategoryId: Long?,
    val correctedCategoryId: Long?,
    val originalType: String?,           // TransactionType name (e.g., "PURCHASE")
    val correctedType: String?,          // User corrected TransactionType
    val wasRejected: Boolean = false,    // User said "this isn't a transaction"
    val wasApproved: Boolean = false,    // User confirmed it was correct
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis()
)
