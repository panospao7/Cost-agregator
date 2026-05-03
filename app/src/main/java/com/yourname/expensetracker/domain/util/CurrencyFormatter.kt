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

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoney(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun format(amount: Double, currencyCode: String = DEFAULT_CURRENCY, showCents: Boolean = true): String {
        return currencyNumberFormat(currencyCode, showCents).format(amount)
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoneyCompact(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatCompact(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
        val symbol = getCurrencySymbol(currencyCode)
        return when {
            amount >= 1_000_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000_000)}M"
            amount >= 1_000 -> "$symbol${String.format(Locale.getDefault(), "%.1f", amount / 1_000)}K"
            else -> format(amount, currencyCode)
        }
    }

    @Deprecated("Unsafe: currencyCode defaults to EUR silently. Use formatMoneyWithSign(amount, currencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
    fun formatWithSign(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String {
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
        return format(amount, currencyCode, showCents)
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
            val resolvedCurrency = runCatching { Currency.getInstance(currencyCode) }
                .getOrElse { Currency.getInstance(DEFAULT_CURRENCY) }
            currency = resolvedCurrency
            // Use the currency's default fraction digits (e.g., EUR/USD → 2, JPY → 0, BHD → 3)
            val fractionDigits = if (showCents) resolvedCurrency.defaultFractionDigits else 0
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
    }
}

@Deprecated("Unsafe: currencyCode defaults to EUR silently. Use toCurrency(explicitCurrencyCode) with an explicit currency.", level = DeprecationLevel.WARNING)
fun Double.toCurrency(currencyCode: String = "EUR"): String = 
    CurrencyFormatter.format(this, currencyCode)
