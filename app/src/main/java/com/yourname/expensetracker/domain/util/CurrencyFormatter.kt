package com.yourname.expensetracker.domain.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Utility for formatting currency amounts consistently across the app.
 * Supports multiple currencies, compact notation, and signed amounts.
 */
object CurrencyFormatter {
    // M09 OPEN: CurrencyFormatter uses locale-sensitive String.format which can
    // produce different decimal separators (1.234,56 vs 1,234.56) depending on device locale.
    // For UI display, this is desired. For machine-readable output (CSV, API), use
    // String.format(Locale.US, "%.2f", amount) to enforce period-as-decimal.
    private const val DEFAULT_CURRENCY = "EUR"

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoney(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun format(amount: Double, currencyCode: String = DEFAULT_CURRENCY, showCents: Boolean = true): String {
        return formatExplicit(amount, currencyCode, showCents)
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoneyCompact(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatCompact(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        require(amount.isFinite()) { "Cannot format non-finite amount: $amount" }
        val symbol = getCurrencySymbol(currencyCode)
        return when {
            amount >= 1_000_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000_000)}M"
            amount >= 1_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000)}K"
            else -> format(amount, currencyCode)
        }
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoneyWithSign(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatWithSign(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        require(amount.isFinite()) { "Cannot format non-finite amount: $amount" }
        val absolute = format(kotlin.math.abs(amount), currencyCode)
        return when {
            amount < 0 -> "-$absolute"
            amount > 0 -> "+$absolute"
            else -> absolute
        }
    }

    // --- Non-deprecated replacements (require explicit currency) ---

    /**
     * Format a money amount with explicit currency.
     * This is the safe replacement for the deprecated [format] overload.
     */
    fun formatMoney(amount: Double, currencyCode: String, showCents: Boolean = true): String {
        return formatExplicit(amount, currencyCode, showCents)
    }

    /**
     * Format a money amount in compact notation (e.g., "€1.5K", "€2.3M") with explicit currency.
     * Safe replacement for the deprecated [formatCompact] overload.
     */
    fun formatMoneyCompact(amount: Double, currencyCode: String): String {
        return formatCompact(amount, currencyCode)
    }

    /**
     * Format a money amount with explicit sign (+/-) and explicit currency.
     * Safe replacement for the deprecated [formatWithSign] overload.
     */
    fun formatMoneyWithSign(amount: Double, currencyCode: String): String {
        return formatWithSign(amount, currencyCode)
    }

    /**
     * Format a monetary amount for machine-readable export (CSV, IIF, API).
     *
     * Always uses [Locale.US] to enforce period-as-decimal (e.g. "1234.56").
     * Rejects non-finite amounts (NaN, Infinity) with [IllegalArgumentException].
     */
    fun formatForExport(amount: Double): String {
        require(amount.isFinite()) { "Cannot export non-finite monetary amount: $amount" }
        return String.format(Locale.US, "%.2f", amount)
    }

    fun getCurrencySymbol(currencyCode: String): String {
        return try {
            Currency.getInstance(currencyCode).getSymbol(Locale.getDefault())
        } catch (e: IllegalArgumentException) {
            currencyCode // Return raw code instead of EUR symbol
        }
    }

    private fun formatExplicit(amount: Double, currencyCode: String, showCents: Boolean): String {
        require(amount.isFinite()) { "Cannot format non-finite amount: $amount" }
        return try {
            val format = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                val resolvedCurrency = Currency.getInstance(currencyCode)
                currency = resolvedCurrency
                val fractionDigits = if (showCents) resolvedCurrency.defaultFractionDigits else 0
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
            }
            format.format(amount)
        } catch (e: IllegalArgumentException) {
            // Invalid currency code — show raw amount with raw code instead of EUR symbol
            val fractionDigits = if (showCents) 2 else 0
            String.format(Locale.getDefault(), "%.${fractionDigits}f %s", amount, currencyCode)
        }
    }
}

@Deprecated("Unsafe: currencyCode defaults to EUR silently. Use toCurrency(explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
fun Double.toCurrency(currencyCode: String = "EUR"): String = 
    CurrencyFormatter.formatMoney(this, currencyCode)
