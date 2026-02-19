package com.yourname.expensetracker.ui.common

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized currency formatting logic to ensure consistency across the app.
 * Replaces hardcoded "%.2f" formatting.
 */
object CurrencyFormatter {

    private val defaultFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    private val decimalFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    // Thread-safe cache for currency-specific formatters
    private val currencyFormatCache = ConcurrentHashMap<String, NumberFormat>()

    fun format(amount: Double, currencyCode: String = "EUR"): String {
        return try {
            decimalFormat.format(amount)
        } catch (e: Exception) {
            "%.2f".format(amount)
        }
    }

    fun formatWithSymbol(amount: Double, currencyCode: String = "EUR"): String {
        return try {
            val format = currencyFormatCache.getOrPut(currencyCode) {
                NumberFormat.getCurrencyInstance().apply {
                    currency = Currency.getInstance(currencyCode)
                }
            }
            format.format(amount)
        } catch (e: Exception) {
            val symbol = if (currencyCode == "EUR") "€" else currencyCode
            "${decimalFormat.format(amount)} $symbol"
        }
    }
}
