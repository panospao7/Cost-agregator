package com.yourname.expensetracker.domain.util

import java.util.Currency
import java.util.Locale

/**
 * Utility for formatting currency amounts consistently across the app.
 * Supports multiple currencies, compact notation, and signed amounts.
 */
object CurrencyFormatter {
    private const val DEFAULT_CURRENCY = "EUR"
    private val DEFAULT_SYMBOL = "€"

    fun format(amount: Double, currencyCode: String = DEFAULT_CURRENCY, showCents: Boolean = true): String {
        val symbol = getCurrencySymbol(currencyCode)
        return if (showCents) {
            "$symbol${String.format(Locale.US, "%.2f", amount)}"
        } else {
            "$symbol${String.format(Locale.US, "%.0f", amount)}"
        }
    }

    fun formatCompact(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        val symbol = getCurrencySymbol(currencyCode)
        return when {
            amount >= 1_000_000 -> "$symbol${String.format(Locale.US, "%.1f", amount / 1_000_000)}M"
            amount >= 1_000 -> "$symbol${String.format(Locale.US, "%.1f", amount / 1_000)}K"
            else -> format(amount, currencyCode)
        }
    }

    fun formatWithSign(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        val symbol = getCurrencySymbol(currencyCode)
        val formatted = String.format(Locale.US, "%.2f", kotlin.math.abs(amount))
        return if (amount < 0) {
            "-$symbol$formatted"
        } else {
            "+$symbol$formatted"
        }
    }

    private fun getCurrencySymbol(currencyCode: String): String {
        return try {
            Currency.getInstance(currencyCode).symbol
        } catch (e: Exception) {
            DEFAULT_SYMBOL
        }
    }
}

fun Double.toCurrency(currencyCode: String = "EUR"): String = 
    CurrencyFormatter.format(this, currencyCode)
