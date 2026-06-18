package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_canonicals",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["searchKey"], unique = true),
        Index(value = ["categoryId"])
    ]
)
data class MerchantCanonical(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val normalizedName: String, // e.g., "McDonald's"
    val searchKey: String,      // e.g., "mcdonalds" (stripped)
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "0") val totalOccurrences: Int = 0,
    @Deprecated(
        message = "totalSpent is a raw Double that sums mixed-currency amounts without conversion. " +
            "Do not use for financial decisions. Use expense-based computation with MoneyAggregate instead. " +
            "Kept for backward compatibility; will be replaced with per-currency buckets in a future schema migration.",
        level = DeprecationLevel.WARNING
    )
    @ColumnInfo(defaultValue = "0.0") val totalSpent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val isVerified: Boolean = false,
    val logoUrl: String? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L
)
