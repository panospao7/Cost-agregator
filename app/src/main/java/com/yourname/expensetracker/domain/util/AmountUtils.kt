package com.yourname.expensetracker.domain.util

import timber.log.Timber

object AmountUtils {
    private const val TAG = "AmountUtils"
    private val NON_DIGIT_REGEX = Regex("""[^0-9.\-]""")

    fun parseAmount(amountStr: String): Double? {
        if (amountStr.isBlank()) return null
        
        var cleaned = amountStr

        // Handle E-prefix (e.g., "E0,13" -> "0,13")
        if (cleaned.startsWith("E") || cleaned.startsWith("e")) {
            val rest = cleaned.substring(1)
            if (rest.isNotEmpty() && rest[0].isDigit()) {
                cleaned = rest
            }
        }

        // Remove all spaces
        cleaned = cleaned.replace(" ", "")
        
        // Check if this looks like European format (e.g., "1.602,57")
        // European: has comma as last decimal separator, no dot after comma
        // US: has dot as last decimal separator
        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")
        
        val result = when {
            hasComma && hasDot -> {
                val lastComma = cleaned.lastIndexOf(",")
                val lastDot = cleaned.lastIndexOf(".")
                if (lastComma > lastDot) {
                    // European format: 1.602,57 -> 1602.57
                    cleaned.replace(".", "").replace(",", ".")
                } else {
                    // US format with thousands separator: 1,602.57 -> 1602.57
                    cleaned.replace(",", "")
                }
            }
            hasComma -> {
                // Could be European decimal (57,00) or US thousands (1,602)
                // If comma is followed by exactly 2 digits at end, likely European decimal
                val parts = cleaned.split(",")
                if (parts.size == 2 && parts[1].length <= 2) {
                    cleaned.replace(",", ".")
                } else {
                    // Treat as US thousands separator
                    cleaned.replace(",", "")
                }
            }
            else -> cleaned
        }
        
        val finalCleaned = result.replace(NON_DIGIT_REGEX, "")
        
        return try {
            finalCleaned.toDoubleOrNull()
        } catch (e: Exception) {
            Timber.w("Failed to parse amount")
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
