package com.yourname.expensetracker.ui.common

import java.text.NumberFormat
import java.util.Locale

/**
 * centralized currency formatting logic to ensure consistency across the app.
 * Replaces hardcoded "%.2f" formatting.
 */
object CurrencyFormatter {
    
    private val defaultFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    private val decimalFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun format(amount: Double, currencyCode: String = "EUR"): String {
        return try {
             // For now, simple format, can be extended to use Currency class
             // If we just want the number format like 1,234.56
             decimalFormat.format(amount)
        } catch (e: Exception) {
            "%.2f".format(amount)
        }
    }
    
    fun formatWithSymbol(amount: Double, currencyCode: String = "EUR"): String {
        return try {
            val format = NumberFormat.getCurrencyInstance()
            format.currency = java.util.Currency.getInstance(currencyCode)
            format.format(amount)
        } catch (e: Exception) {
             val symbol = if (currencyCode == "EUR") "€" else currencyCode
             "${decimalFormat.format(amount)} $symbol"
        }
    }
}
