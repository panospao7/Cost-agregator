package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores exchange rates for currency conversion.
 * Rates are stored relative to a base currency (EUR by default).
 *
 * Historical rates are supported via the unique index on
 * `(fromCurrency, toCurrency, validDate)`, which allows multiple rates
 * to coexist for the same currency pair on different dates.  This means
 * a report for a past date can use the rate that was valid on that date,
 * rather than always using the latest rate.
 *
 * The `validDate` column represents the date (epoch milliseconds) for which
 * this rate is valid.  When rates are refreshed via API, a new row is
 * inserted with the current date rather than overwriting the previous row.
 */
@Entity(
    tableName = "exchange_rates",
    indices = [
        Index(value = ["fromCurrency", "toCurrency", "validDate"], unique = true),
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
