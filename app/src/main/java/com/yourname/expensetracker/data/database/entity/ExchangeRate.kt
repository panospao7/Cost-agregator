package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores exchange rates for currency conversion.
 * Rates are stored relative to a base currency (EUR by default).
 */
@Entity(
    tableName = "exchange_rates",
    indices = [
        Index(value = ["fromCurrency", "toCurrency"], unique = true),
        Index(value = ["lastUpdated"]),
        Index(value = ["toCurrency"])
    ]
)
data class ExchangeRate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fromCurrency: String,      // Source currency code (e.g., "USD")
    val toCurrency: String,        // Target currency code (e.g., "EUR")
    val rate: Double,              // Exchange rate (how much 1 unit of fromCurrency is worth in toCurrency)
    val lastUpdated: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "manual") val source: String = "manual"  // "manual", "api", "cached"
)
