@file:JvmName("TestFixtures")

package com.yourname.expensetracker.testfixtures

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foundation extension helpers for scenario testing.
 *
 * Provides fluent extensions for creating [MoneyAmount], [CurrencyCode],
 * and epoch-millisecond date values in test code.
 */

// ============================================================================
// Money Extension Properties
// ============================================================================

/** Create a [MoneyAmount] in EUR from a [Double] value. */
val Double.eur: MoneyAmount
    get() = MoneyAmount(this, CurrencyCode.EUR)

/** Create a [MoneyAmount] in USD from a [Double] value. */
val Double.usd: MoneyAmount
    get() = MoneyAmount(this, CurrencyCode("USD"))

/** Create a [MoneyAmount] in GBP from a [Double] value. */
val Double.gbp: MoneyAmount
    get() = MoneyAmount(this, CurrencyCode("GBP"))

// ============================================================================
// Date Helper Functions
// ============================================================================

/**
 * Converts the given calendar fields to epoch milliseconds in UTC.
 *
 * @param year  Calendar year (e.g. 2026)
 * @param month Calendar month (1 = January, 12 = December)
 * @param day   Day of month (1-based)
 * @param hour  Hour of day (0-23), default 0
 * @param minute Minute of hour (0-59), default 0
 * @return Epoch milliseconds
 */
fun dateMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
    return LocalDateTime.of(year, month, day, hour, minute)
        .atZone(ZoneId.of("UTC"))
        .toInstant()
        .toEpochMilli()
}

/**
 * Converts the given date (without time) to epoch milliseconds in UTC.
 *
 * Delegates to [dateMs] with hour=0, minute=0.
 */
fun dateMs(year: Int, month: Int, day: Int): Long = dateMs(year, month, day, 0, 0)

// ============================================================================
// Money Helper
// ============================================================================

/**
 * Creates a [MoneyAmount] from a numeric amount and a currency code string.
 *
 * @param amount   The monetary value
 * @param currency ISO 4217 currency code (default "EUR")
 */
fun money(amount: Double, currency: String = "EUR"): MoneyAmount =
    MoneyAmount(amount, CurrencyCode(currency))

// ============================================================================
// Standard Categories Constant
// ============================================================================

/** Standard category names used consistently across scenario tests. */
val STANDARD_CATEGORIES: List<String> = listOf(
    "Food & Dining",
    "Transportation",
    "Shopping",
    "Bills & Utilities",
    "Entertainment"
)

// ============================================================================
// Debug Extension
// ============================================================================

private val readableDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Formats an epoch-millisecond timestamp as a human-readable UTC date-time string.
 *
 * Example: `1672531200000.asReadableDate` → `"2023-01-01 00:00"`
 */
val Long.asReadableDate: String
    get() = java.time.Instant.ofEpochMilli(this)
        .atZone(ZoneId.of("UTC"))
        .toLocalDateTime()
        .format(readableDateFormatter)
