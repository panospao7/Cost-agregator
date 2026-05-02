package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores exchange rates for currency conversion.
 * Rates are stored relative to a base currency (EUR by default).
 *
 * ## Known limitation — unique index prevents historical rates
 *
 * The unique index on `(fromCurrency, toCurrency)` means only one rate can
 * exist per currency pair at any time. When rates are refreshed, old rates
 * are **overwritten**. This means historical reports always use the *latest*
 * rate rather than the rate at the time of the transaction.
 *
 * The `validDate` and `lastUpdated` columns exist on the row but are NOT
 * part of the unique index, so they cannot differentiate historical entries.
 *
 * ### Future fix
 * To support historical accuracy the unique index would need to be changed
 * to `(fromCurrency, toCurrency, validDate)` so that multiple rates can
 * coexist for different dates.
 */
@Entity(
    tableName = "exchange_rates",
    indices = [
        Index(value = ["fromCurrency", "toCurrency"], unique = true),
        Index(value = ["fromCurrency", "toCurrency", "validDate"]),
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
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val lastUpdated: Long = 0L,
    @ColumnInfo(defaultValue = "manual") val source: String = "manual",  // "manual", "api", "cached"
    @ColumnInfo(defaultValue = "0") val validDate: Long = 0L
)
