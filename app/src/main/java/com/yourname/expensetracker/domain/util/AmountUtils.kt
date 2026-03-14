package com.yourname.expensetracker.domain.util

import timber.log.Timber
import java.util.Locale

/**
 * AmountUtils - Utility for parsing and validating monetary amounts.
 * 
 * ## Parsing Rules
 * 
 * ### Supported Formats:
 * - European: 1.234,56 (dot as thousands sep, comma as decimal)
 * - US: 1,234.56 (comma as thousands sep, dot as decimal)
 * - Plain: 1234.56 (no thousands separator)
 * 
 * ### Special Cases:
 * - Negative amounts: Leading "-" or "−" or parentheses "(100)"
 * - Currency prefix: "€100" or "E100"
 * - Spacing: "1 234.56" or "1,234.56"
 * 
 * ### Validation:
 * - Rejects inconsistent thousands separators (e.g., "1.234,567.89")
 * - Maximum amount: 1,000,000.00
 * 
 * ### Examples:
 * ```
 * "€ 1.234,56"  -> 1234.56
 * "$99.99"      -> 99.99
 * "(50.00)"     -> -50.00
 * "1,234.56"    -> 1234.56
 * "1.234,56"    -> 1234.56
 * ```
 */
object AmountUtils {
    private const val TAG = "AmountUtils"
    private val NON_DIGIT_REGEX = Regex("""[^0-9.\-,]""")
    private val SUPPORTED_MINUS_SIGNS = setOf('-', '−', '‑', '–', '—')

    fun parseAmount(amountStr: String): Double? {
        if (amountStr.isBlank()) return null
        if (amountStr.contains('/')) return null
        val hasOtherSymbol = amountStr
            .codePoints()
            .anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        if (hasOtherSymbol) return null
        
        var cleaned = amountStr.trim()

        var isNegative = false
        
        if (cleaned.startsWith("E") || cleaned.startsWith("e")) {
            val rest = cleaned.substring(1)
            if (rest.isNotEmpty() && rest[0].isDigit()) {
                cleaned = rest
            }
        }

        if (cleaned.isNotEmpty() && SUPPORTED_MINUS_SIGNS.contains(cleaned.first())) {
            isNegative = true
            cleaned = cleaned.substring(1)
        }
        
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            isNegative = true
            cleaned = cleaned.substring(1, cleaned.length - 1)
        }

        cleaned = cleaned.replace(Regex("""\s+"""), "")
        
        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")
        
        val result = when {
            hasComma && hasDot -> {
                val lastComma = cleaned.lastIndexOf(",")
                val lastDot = cleaned.lastIndexOf(".")
                if (lastComma > lastDot) {
                    cleaned.replace(".", "").replace(",", ".")
                } else {
                    cleaned.replace(",", "")
                }
            }
            hasComma -> {
                val parts = cleaned.split(",")
                when {
                    parts.size == 2 && parts[1].length <= 2 -> {
                        cleaned.replace(",", ".")
                    }
                    parts.size >= 2 && parts.drop(1).all { it.isNotEmpty() && it.all { c -> c.isDigit() } } -> {
                        // Check for inconsistent grouping (e.g., "1,23,456" has 1, 23, 456 - mixed 1-digit and 3-digit groups)
                        val groupSizes = parts.drop(1).map { it.length }
                        if (groupSizes.toSet().size > 1) {
                            Timber.w("Ambiguous amount format (inconsistent grouping): $amountStr")
                            return@parseAmount null
                        }
                        cleaned.replace(",", "")
                    }
                    else -> {
                        Timber.w("Ambiguous amount format: $amountStr")
                        return@parseAmount null
                    }
                }
            }
            else -> cleaned
        }
        
        val finalCleaned = result.replace(NON_DIGIT_REGEX, "")
        if (finalCleaned.isBlank()) return null
        
        return try {
            val value = finalCleaned.toDoubleOrNull() ?: return null
            if (isNegative) -value else value
        } catch (e: Exception) {
            Timber.w("Failed to parse amount")
            null
        }
    }

    fun isValidAmount(amount: Double, max: Double = 1_000_000.0): Boolean {
        return amount > 0 && amount <= max
    }

    fun formatAmount(amount: Double, currency: String = "€"): String {
        return String.format(Locale.US, "%s%.2f", currency, amount)
    }
}
