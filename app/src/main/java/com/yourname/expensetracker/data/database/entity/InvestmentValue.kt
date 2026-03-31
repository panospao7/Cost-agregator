package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Historical value snapshots for investments.
 */
@Entity(
    tableName = "investment_values",
    foreignKeys = [
        ForeignKey(
            entity = Investment::class,
            parentColumns = ["id"],
            childColumns = ["investmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["investmentId", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class InvestmentValue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val investmentId: Long,
    val price: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val totalValue: Double,      // price * quantity
    val dayChange: Double? = null,
    val dayChangePercent: Double? = null
)
