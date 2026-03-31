package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an investment (stock, crypto, bond, etc.).
 */
@Entity(
    tableName = "investments",
    indices = [
        Index(value = ["type"]),
        Index(value = ["symbol"]),
        Index(value = ["isActive"])
    ]
)
data class Investment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,              // Full name (e.g., "Apple Inc.")
    val symbol: String,          // Ticker (e.g., "AAPL", "BTC")
    val type: InvestmentType,     // STOCK, CRYPTO, BOND, ETF, etc.
    val currency: String = "EUR",
    val exchange: String? = null, // NYSE, NASDAQ, BINANCE, etc.
    
    // Purchase details
    val purchasePrice: Double,
    val quantity: Double,
    val purchaseDate: Long,
    val purchaseFees: Double = 0.0,
    
    // Current tracking
    val currentPrice: Double = purchasePrice,
    val lastUpdated: Long = System.currentTimeMillis(),
    
    // Notes and categorization
    val category: String? = null,    // "Tech", "Crypto", "Blue Chip"
    val notes: String? = null,
    val isActive: Boolean = true,
    
    // Target/alert settings
    val targetPrice: Double? = null,
    val stopLossPrice: Double? = null,
    
    val createdAt: Long = System.currentTimeMillis()
)

enum class InvestmentType {
    STOCK,
    CRYPTO,
    BOND,
    ETF,
    MUTUAL_FUND,
    COMMODITY,
    FOREX,
    OTHER
}
