package com.yourname.expensetracker.domain.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Utility for formatting currency amounts consistently across the app.
 * Supports multiple currencies, compact notation, and signed amounts.
 */
object CurrencyFormatter {
    private const val DEFAULT_CURRENCY = "EUR"
    private val DEFAULT_SYMBOL = "€"

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use format(amount, explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun format(amount: Double, currencyCode: String = DEFAULT_CURRENCY, showCents: Boolean = true): String {
        return currencyNumberFormat(currencyCode, showCents).format(amount)
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatCompact(amount, explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatCompact(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        val symbol = getCurrencySymbol(currencyCode)
        return when {
            amount >= 1_000_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000_000)}M"
            amount >= 1_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000)}K"
            else -> format(amount, currencyCode)
        }
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatWithSign(amount, explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatWithSign(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        val absolute = format(kotlin.math.abs(amount), currencyCode)
        return when {
            amount < 0 -> "-$absolute"
            amount > 0 -> "+$absolute"
            else -> absolute
        }
    }

    fun formatForExport(amount: Double, locale: Locale = Locale.getDefault()): String {
        val safeAmount = if (amount.isFinite()) amount else 0.0
        return String.format(Locale.US, "%.2f", safeAmount)
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use getCurrencySymbol(explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun getCurrencySymbol(currencyCode: String = DEFAULT_CURRENCY): String {
        return try {
            Currency.getInstance(currencyCode).getSymbol(Locale.getDefault())
        } catch (e: Exception) {
            DEFAULT_SYMBOL
        }
    }

    private fun currencyNumberFormat(currencyCode: String, showCents: Boolean): NumberFormat {
        return NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            currency = runCatching { Currency.getInstance(currencyCode) }
                .getOrElse { Currency.getInstance(DEFAULT_CURRENCY) }
            minimumFractionDigits = if (showCents) 2 else 0
            maximumFractionDigits = if (showCents) 2 else 0
        }
    }
}

@Deprecated("Unsafe: currencyCode defaults to EUR silently. Use toCurrency(explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
fun Double.toCurrency(currencyCode: String = "EUR"): String = 
    CurrencyFormatter.format(this, currencyCode)
