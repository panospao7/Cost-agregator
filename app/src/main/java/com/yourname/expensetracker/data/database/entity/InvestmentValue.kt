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
        // DB-8: CASCADE on InvestmentValue.investmentId → Investment(id)
        // Safe: Value snapshots are child records of an investment; deleting the investment cleans up its history.
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
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val timestamp: Long = 0L,
    val totalValue: Double,      // price * quantity
    val dayChange: Double? = null,
    val dayChangePercent: Double? = null
)
