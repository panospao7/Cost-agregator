package com.yourname.expensetracker.domain.util

import android.util.Log

object AmountUtils {
    private const val TAG = "AmountUtils"
    private val NON_DIGIT_REGEX = Regex("""[^0-9.\-]""")

    fun parseAmount(amountStr: String): Double? {
        if (amountStr.isBlank()) return null
        
        // Check if this looks like European format (e.g., "1.602,57")
        // European: has comma as last decimal separator, no dot after comma
        // US: has dot as last decimal separator
        val hasComma = amountStr.contains(",")
        val hasDot = amountStr.contains(".")
        
        val result = when {
            hasComma && hasDot -> {
                val lastComma = amountStr.lastIndexOf(",")
                val lastDot = amountStr.lastIndexOf(".")
                if (lastComma > lastDot) {
                    // European format: 1.602,57 -> 1602.57
                    amountStr.replace(".", "").replace(",", ".")
                } else {
                    // US format with thousands separator: 1,602.57 -> 1602.57
                    amountStr.replace(",", "")
                }
            }
            hasComma -> {
                // Could be European decimal (57,00) or US thousands (1,602)
                // If comma is followed by exactly 2 digits at end, likely European decimal
                val parts = amountStr.split(",")
                if (parts.size == 2 && parts[1].length <= 2) {
                    amountStr.replace(",", ".")
                } else {
                    // Treat as US thousands separator
                    amountStr.replace(",", "")
                }
            }
            else -> amountStr
        }
        
        val cleaned = result.replace(NON_DIGIT_REGEX, "")
        
        return try {
            cleaned.toDoubleOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse amount: $amountStr")
            null
        }
    }

    fun isValidAmount(amount: Double, max: Double = 1_000_000.0): Boolean {
        return amount > 0 && amount <= max
    }

    fun formatAmount(amount: Double, currency: String = "€"): String {
        return "$currency%.2f".format(amount)
    }
}
