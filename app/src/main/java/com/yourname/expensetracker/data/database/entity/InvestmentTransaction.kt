package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * I04: Multi-lot investment transaction tracking.
 *
 * Records individual buy/sell/dividend events for an investment holding,
 * enabling accurate cost-basis calculation, realised P&L, and dividend
 * tracking across multiple lots.
 *
 * The table is indexed on (holdingId, date) for efficient per-holding
 * transaction history queries.
 */
@Entity(tableName = "investment_transactions",
    indices = [Index("holdingId"), Index("date")])
data class InvestmentTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val holdingId: Long,
    val type: String, // BUY, SELL, DIVIDEND
    val quantity: Double,
    val pricePerUnit: Double,
    val totalAmount: Double,
    val currency: String = "EUR",
    val fee: Double = 0.0,
    val date: Long
)
