package com.yourname.expensetracker.data.database.entity

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
    val totalSplits: Int = 2,
    val splitType: SplitType = SplitType.PERCENTAGE,
    val shares: String, // JSON array of SplitShare objects
    val description: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 0
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
