package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "split_templates",
    indices = [
        Index(value = ["isDefault"])
    ]
)
data class SplitTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "2") val totalSplits: Int = 2,
    @ColumnInfo(defaultValue = "PERCENTAGE") val splitType: SplitType = SplitType.PERCENTAGE,
    val shares: String, // JSON array of SplitShare objects
    val description: String? = null,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val useCount: Int = 0
) {
    enum class SplitType {
        EQUAL,
        PERCENTAGE,
        CUSTOM_AMOUNT,
        UNEQUAL
    }
}

data class SplitShare(
    val participantIndex: Int,
    val participantName: String,
    val percentage: Double? = null,
    val amount: Double? = null,
    val color: String? = null
)
