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
        Index("correctedCategoryId"),
        Index("packageName"),
        Index("wasApproved"),
        Index("wasRejected"),
        Index("originalMerchant")
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
    @ColumnInfo(defaultValue = "0") val wasRejected: Boolean = false,    // User said "this isn't a transaction"
    @ColumnInfo(defaultValue = "0") val wasApproved: Boolean = false,    // User confirmed it was correct
    val notificationTitle: String?,
    val notificationText: String?,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)
