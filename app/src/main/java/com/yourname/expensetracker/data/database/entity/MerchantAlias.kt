package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [
        ForeignKey(
            entity = MerchantCanonical::class,
            parentColumns = ["id"],
            childColumns = ["canonicalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rawName"], unique = true),
        Index(value = ["normalizedKey"], unique = true),
        Index(value = ["canonicalId"])
    ]
)
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawName: String,        // e.g., "MCDONALD'S #1234"
    val normalizedKey: String,   // e.g., "mcdonalds1234"
    val canonicalId: Long,
    @ColumnInfo(defaultValue = "1") val occurrenceCount: Int = 1,
    @ColumnInfo(defaultValue = "0") val isUserDefined: Boolean = false,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val lastUsedAt: Long = 0L
)
