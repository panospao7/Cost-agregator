package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

// TODO (I04): Add InvestmentTransaction table for multi-lot support.
// Schema: id, holdingId, type (BUY/SELL/DIVIDEND), quantity, pricePerUnit, totalAmount, currency, fee, date
// Migration: new table + index on (holdingId, date)

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
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    val exchange: String? = null, // NYSE, NASDAQ, BINANCE, etc.
    
    // Purchase details
    val purchasePrice: Double,
    val quantity: Double,
    val purchaseDate: Long,
    @ColumnInfo(defaultValue = "0.0") val purchaseFees: Double = 0.0,
    
    // Current tracking
    val currentPrice: Double = purchasePrice,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val lastUpdated: Long = 0L,
    
    // Notes and categorization
    val category: String? = null,    // "Tech", "Crypto", "Blue Chip"
    val notes: String? = null,
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true,
    
    // Target/alert settings
    val targetPrice: Double? = null,
    val stopLossPrice: Double? = null,
    
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L

    // TODO (I07): Add updatedAt field and enforce both createdAt and updatedAt
    // are set to timeProvider.now() in all creation paths.
) {
    @get:Ignore
    val purchasePriceMoneyAmount: MoneyAmount get() = MoneyAmount(purchasePrice, CurrencyCode(currency))

    @get:Ignore
    val purchaseFeesMoneyAmount: MoneyAmount get() = MoneyAmount(purchaseFees, CurrencyCode(currency))

    @get:Ignore
    val currentPriceMoneyAmount: MoneyAmount get() = MoneyAmount(currentPrice, CurrencyCode(currency))

    @get:Ignore
    val targetPriceMoneyAmount: MoneyAmount? get() = targetPrice?.let { MoneyAmount(it, CurrencyCode(currency)) }

    @get:Ignore
    val stopLossPriceMoneyAmount: MoneyAmount? get() = stopLossPrice?.let { MoneyAmount(it, CurrencyCode(currency)) }
}

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
