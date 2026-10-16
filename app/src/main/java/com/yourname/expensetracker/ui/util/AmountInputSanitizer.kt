package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.UserAmountResult
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Sanitizes amount input for money fields.
 *
 * Rules:
 * - Only ASCII digits, '.' and ',' are kept; digit runs are never joined across a separator
 *   (repeated separators collapse to one, e.g. "1,,5" -> "1,5")
 * - A leading separator becomes "0," / "0." (".5" -> "0.5")
 * - No leading zeros (except a single "0")
 * - At most 2 digits after the locale decimal separator, 3 after the other separator
 *   (the 3 allows thousands groups such as "1.234" / "1,234" to be typed)
 * - Maximum 10 integer digits
 * - Empty/blank input returns empty string
 * - Separators come from the current default locale at call time
 *
 * Validity is decided by [AmountUtils.parseUserAmount], the same function the save path uses.
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
    private const val MAX_GROUP_DIGITS = 3

    /**
     * Sanitizes raw input into a money amount string that keeps the user's separators.
     *
     * @param raw The raw user input
     * @param maxFractionDigits Maximum decimal places after the locale decimal separator (default 2)
     * @return Sanitized string that [isValid] / [AmountUtils.parseUserAmount] can evaluate
     */
    fun sanitize(raw: String, maxFractionDigits: Int = MAX_FRACTION_DIGITS): String {
        if (raw.isBlank()) return ""

        val cleaned = raw.filter { it in '0'..'9' || it == '.' || it == ',' }
        if (cleaned.isEmpty()) return ""

        // Leading separator -> prepend "0" so ".5" becomes "0.5" and ",5" becomes "0,5".
        val normalized = if (cleaned[0] == '.' || cleaned[0] == ',') "0$cleaned" else cleaned

        val localeDecimal = if (currentSymbols().decimalSeparator == ',') ',' else '.'

        // Split into digit runs separated by single separators (repeated separators collapse).
        val runs = mutableListOf(StringBuilder())
        val separators = mutableListOf<Char>()
        for (c in normalized) {
            if (c in '0'..'9') {
                runs.last().append(c)
            } else if (runs.last().isNotEmpty()) {
                separators.add(c)
                runs.add(StringBuilder())
            }
        }

        val rawFirst = runs[0].toString()
        val first = if (rawFirst.all { it == '0' }) "0" else rawFirst.trimStart('0')
        var integerBudget = MAX_INTEGER_DIGITS
        val firstPart = first.take(integerBudget)
        integerBudget -= firstPart.length
        val out = StringBuilder(firstPart)

        val lastIndex = runs.lastIndex
        if (lastIndex == 0) return out.toString()

        val lastLimit = if (separators.last() == localeDecimal) {
            maxFractionDigits.coerceAtLeast(0)
        } else {
            MAX_GROUP_DIGITS
        }
        for (i in 1..lastIndex) {
            val run = runs[i].toString()
            if (i == lastIndex) {
                out.append(separators[i - 1]).append(run.take(lastLimit))
            } else {
                val part = run.take(MAX_GROUP_DIGITS).take(integerBudget)
                if (part.isEmpty()) break
                integerBudget -= part.length
                out.append(separators[i - 1]).append(part)
            }
        }
        return out.toString()
    }

    /**
     * Single entry point for parsing a user-typed amount, using the separators of the current
     * default locale at call time. Validation and save paths must both use this (or [parseOrNull])
     * so they cannot disagree.
     */
    fun parse(input: String, maxFractionDigits: Int = MAX_FRACTION_DIGITS): UserAmountResult {
        val symbols = currentSymbols()
        return AmountUtils.parseUserAmount(
            text = input,
            decimalSep = symbols.decimalSeparator,
            groupingSep = symbols.groupingSeparator,
            maxFractionDigits = maxFractionDigits
        )
    }

    /**
     * Parsed amount, or null when the input is empty, ambiguous or invalid. The value is NOT
     * checked for positivity; callers keep their own range checks.
     */
    fun parseOrNull(input: String, maxFractionDigits: Int = MAX_FRACTION_DIGITS): Double? =
        (parse(input, maxFractionDigits) as? UserAmountResult.Valid)?.value

    /**
     * Checks if the input is a valid amount (parses to Double > 0).
     * Ambiguous and invalid input are both rejected.
     */
    fun isValid(input: String, maxFractionDigits: Int = MAX_FRACTION_DIGITS): Boolean {
        if (input.isBlank()) return false
        val value = parseOrNull(input, maxFractionDigits) ?: return false
        return value > 0
    }

    private fun currentSymbols(): DecimalFormatSymbols =
        DecimalFormatSymbols.getInstance(Locale.getDefault())
}
