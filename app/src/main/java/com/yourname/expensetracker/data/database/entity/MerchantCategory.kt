package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["normalizedCanonicalName"])]
)
data class MerchantCategory(
    @PrimaryKey
    val merchantPattern: String,
    val categoryId: Long,
    @ColumnInfo(defaultValue = "1.0") val confidence: Float = 1.0f,
    @ColumnInfo(defaultValue = "1") val timesUsed: Int = 1,
    val normalizedCanonicalName: String? = null
)
