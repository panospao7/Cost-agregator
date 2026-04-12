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
    @ColumnInfo(defaultValue = "0.0") val totalSpent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val isVerified: Boolean = false,
    val logoUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
