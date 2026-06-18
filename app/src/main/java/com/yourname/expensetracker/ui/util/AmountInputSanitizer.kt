package com.yourname.expensetracker.ui.util

/**
 * Sanitizes amount input for money fields.
 *
 * Rules:
 * - Only digits and one decimal separator (period) allowed
 * - Maximum 2 decimal places
 * - No leading zeros (except "0." prefix)
 * - Maximum reasonable length (10 integer digits)
 * - Empty/blank input returns empty string
 *
 * Usage:
 * ```kotlin
 * TextField(
 *     value = amount,
 *     onValueChange = { amount = AmountInputSanitizer.sanitize(it) }
 * )
 * ```
 */
object AmountInputSanitizer {

    private const val MAX_INTEGER_DIGITS = 10
    private const val MAX_FRACTION_DIGITS = 2

    /**
     * Sanitizes raw input into a valid money amount string.
     *
     * @param raw The raw user input
     * @param maxFractionDigits Maximum decimal places (default 2)
     * @return Sanitized string that can be parsed as a valid amount
     */
    fun sanitize(raw: String, maxFractionDigits: Int = MAX_FRACTION_DIGITS): String {
        if (raw.isBlank()) return ""

        val cleaned = raw.filter { it.isDigit() || it == '.' }
        if (cleaned.isEmpty()) return ""

        // S2-002: Leading decimal → prepend "0" so ".5" becomes "0.5"
        val normalized = if (cleaned.startsWith('.')) "0$cleaned" else cleaned

        val parts = normalized.split('.')

        val rawInteger = parts[0].take(MAX_INTEGER_DIGITS)
        val integerPart = if (rawInteger.all { it == '0' }) {
            if (rawInteger.isEmpty()) "" else "0"
        } else {
            rawInteger.trimStart('0').ifEmpty { "0" }
        }

        return if (parts.size > 1) {
            val fractionPart = parts[1].take(maxFractionDigits)
            "$integerPart.$fractionPart"
        } else {
            integerPart
        }
    }

    /**
     * Checks if the input is a valid amount (can be parsed to Double > 0).
     */
    fun isValid(input: String): Boolean {
        if (input.isBlank()) return false
        val amount = input.toDoubleOrNull() ?: return false
        return amount > 0
    }
}
