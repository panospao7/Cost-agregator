package com.yourname.expensetracker.domain.util

import timber.log.Timber
import java.math.BigDecimal
import java.util.Locale

/** Result of [AmountUtils.parseUserAmount]. Ambiguous input is rejected, never guessed. */
sealed interface UserAmountResult {
    data class Valid(val value: Double) : UserAmountResult
    object Empty : UserAmountResult
    object Ambiguous : UserAmountResult
    object Invalid : UserAmountResult
}

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
    private const val MAX_USER_INTEGER_DIGITS = 10
    private val STATEMENT_WHITESPACE_REGEX = Regex("""[\s   　]+""")
    private val STATEMENT_NUMBER_REGEX = Regex("""[0-9]+(?:[.,][0-9]+)*""")
    private val STATEMENT_LEADING_ZERO_FRACTION_REGEX = Regex("""0[.,][0-9]{3}""")

    fun parseAmount(amountStr: String): Double? {
        if (amountStr.isBlank()) return null
        if (amountStr.contains('/')) return null
        val hasOtherSymbol = amountStr
            .codePoints()
            .anyMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        if (hasOtherSymbol) return null
        
        // Grouping validation operates on numeric text, not its supported prefix.
        var cleaned = amountStr.trim().replace(Regex("^[€$£¥]\\s*"), "")

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

        val firstDigitIdx = cleaned.indexOfFirst { it.isDigit() }
        if (!isNegative && firstDigitIdx > 0) {
            val prefix = cleaned.substring(0, firstDigitIdx)
            if (prefix.any { SUPPORTED_MINUS_SIGNS.contains(it) }) {
                isNegative = true
            }
        }
        
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            isNegative = true
            cleaned = cleaned.substring(1, cleaned.length - 1)
        }

        // Normalize all Unicode whitespace (including nbsp U+00A0, narrow nbsp U+202F) to empty
        cleaned = cleaned.replace(Regex("""[\s\u00A0\u202F\u2007\u3000]+"""), "")
        
        val hasComma = cleaned.contains(",")
        val hasDot = cleaned.contains(".")
        
        val result = when {
            hasComma && hasDot -> {
                val lastComma = cleaned.lastIndexOf(",")
                val lastDot = cleaned.lastIndexOf(".")
                if (lastComma > lastDot) {
                    val decimalParts = cleaned.split(",")
                    if (decimalParts.size != 2 || decimalParts[1].isEmpty()) {
                        Timber.w("AMOUNT_FORMAT_INVALID")
                        return@parseAmount null
                    }
                    if (!hasStrictThousandsGrouping(decimalParts[0], separator = '.')) {
                        Timber.w("AMOUNT_GROUPING_INVALID")
                        return@parseAmount null
                    }
                    cleaned.replace(".", "").replace(",", ".")
                } else {
                    val decimalParts = cleaned.split(".")
                    if (decimalParts.size != 2 || decimalParts[1].isEmpty()) {
                        Timber.w("AMOUNT_FORMAT_INVALID")
                        return@parseAmount null
                    }
                    if (!hasStrictThousandsGrouping(decimalParts[0], separator = ',')) {
                        Timber.w("AMOUNT_GROUPING_INVALID")
                        return@parseAmount null
                    }
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
                        val firstGroup = parts.first()
                        val groupingParts = parts.drop(1)
                        if (
                            firstGroup.isEmpty() ||
                            firstGroup.length !in 1..3 ||
                            !firstGroup.all { it.isDigit() } ||
                            groupingParts.any { it.length != 3 }
                        ) {
                            Timber.w("AMOUNT_GROUPING_INVALID")
                            return@parseAmount null
                        }
                        cleaned.replace(",", "")
                    }
                    else -> {
                        Timber.w("AMOUNT_FORMAT_INVALID")
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w("AMOUNT_PARSE_FAILED")
            null
        }
    }

    fun isValidAmount(amount: Double, max: Double = 1_000_000.0): Boolean {
        return amount > 0 && amount <= max
    }

    fun formatAmount(amount: Double, currency: String = "€"): String {
        return String.format(Locale.US, "%s%.2f", currency, amount)
    }

    /**
     * Strict parser for amounts typed by the user in a money field.
     *
     * Unlike [parseAmount] (machine/OCR text, unchanged) this never guesses and never
     * silently drops a separator:
     * 1. Both '.' and ',' present: the last one is the decimal separator; every earlier
     *    separator must be the other character forming strict 3-digit groups, else Invalid.
     * 2. One separator kind repeated: must be strict 3-digit groups (integer), else Invalid.
     * 3. One separator followed by 1..maxFractionDigits digits is a decimal in every locale.
     * 4. One separator followed by exactly 3 digits is grouping only when it is
     *    [groupingSep]; otherwise Ambiguous (rejected, no guess).
     * 5. More than [maxFractionDigits] fraction digits (other than the grouping case) or more
     *    than 10 integer digits is Invalid.
     * 6. Callers map Ambiguous to their existing invalid-amount error.
     *
     * Only ASCII digits, '.' and ',' are accepted. No sign, currency or whitespace inside.
     */
    fun parseUserAmount(
        text: String,
        decimalSep: Char,
        groupingSep: Char,
        maxFractionDigits: Int = 2
    ): UserAmountResult {
        val input = text.trim()
        if (input.isEmpty()) return UserAmountResult.Empty
        if (maxFractionDigits < 0 || decimalSep == groupingSep) return UserAmountResult.Invalid
        if (input.any { it !in '0'..'9' && it != '.' && it != ',' }) return UserAmountResult.Invalid
        if (input.none { it in '0'..'9' }) return UserAmountResult.Invalid

        val dotCount = input.count { it == '.' }
        val commaCount = input.count { it == ',' }
        val separatorCount = dotCount + commaCount

        val integerDigits: String
        val fractionDigits: String
        when {
            dotCount > 0 && commaCount > 0 -> {
                val decimalIndex = input.lastIndexOfAny(charArrayOf('.', ','))
                val decimalChar = input[decimalIndex]
                val groupChar = if (decimalChar == '.') ',' else '.'
                val integerText = input.substring(0, decimalIndex)
                if (integerText.contains(decimalChar)) return UserAmountResult.Invalid
                if (!hasStrictUserGrouping(integerText, groupChar)) return UserAmountResult.Invalid
                integerDigits = integerText.replace(groupChar.toString(), "")
                fractionDigits = input.substring(decimalIndex + 1)
            }
            separatorCount >= 2 -> {
                val sep = if (dotCount > 0) '.' else ','
                if (!hasStrictUserGrouping(input, sep)) return UserAmountResult.Invalid
                integerDigits = input.replace(sep.toString(), "")
                fractionDigits = ""
            }
            separatorCount == 1 -> {
                val sepIndex = input.indexOfFirst { it == '.' || it == ',' }
                val sep = input[sepIndex]
                val integerText = input.substring(0, sepIndex)
                val fractionText = input.substring(sepIndex + 1)
                when {
                    fractionText.length <= maxFractionDigits -> {
                        integerDigits = integerText
                        fractionDigits = fractionText
                    }
                    fractionText.length == 3 -> {
                        if (sep != groupingSep) return UserAmountResult.Ambiguous
                        if (!hasStrictUserGrouping(input, sep)) return UserAmountResult.Invalid
                        integerDigits = integerText
                        fractionDigits = fractionText
                    }
                    else -> return UserAmountResult.Invalid
                }
            }
            else -> {
                integerDigits = input
                fractionDigits = ""
            }
        }

        // The grouping interpretation of rule 4 keeps the three digits in the integer part.
        val groupingInterpretation = separatorCount == 1 &&
            fractionDigits.length == 3 && fractionDigits.length > maxFractionDigits
        val wholeDigits = if (groupingInterpretation) integerDigits + fractionDigits else integerDigits
        val fractionForValue = if (groupingInterpretation) "" else fractionDigits

        if (wholeDigits.trimStart('0').length > MAX_USER_INTEGER_DIGITS) return UserAmountResult.Invalid
        val normalized = buildString {
            append(wholeDigits.ifEmpty { "0" })
            if (fractionForValue.isNotEmpty()) append('.').append(fractionForValue)
        }
        val decimal = try {
            BigDecimal(normalized)
        } catch (e: NumberFormatException) {
            return UserAmountResult.Invalid
        }
        if (decimal.scale() > maxFractionDigits) return UserAmountResult.Invalid
        val value = decimal.toDouble()
        if (!value.isFinite()) return UserAmountResult.Invalid
        return UserAmountResult.Valid(value)
    }

    /**
     * Statement amount token (sign + digits + optional separators) from a bank statement row.
     * The statement path only accepts 2-decimal currencies (EUR/USD/GBP), so a single separator
     * kind followed by exactly 3 trailing digits is thousands grouping ("1,234" and "1.234" are
     * 1234; "1,234,567" is 1234567). A leading-zero token with one separator and exactly 3
     * digits ("0,123" / "0.123") can never be grouping, so BOTH separators read it as the
     * decimal 0.123 (consistent; the old comma form silently became 123). Everything else is
     * delegated to [parseAmount] unchanged and returns the signed value exactly as it would.
     */
    fun parseStatementAmount(token: String): Double? {
        val compact = token.replace(STATEMENT_WHITESPACE_REGEX, "")
        val numeric = STATEMENT_NUMBER_REGEX.find(compact)?.value
        if (numeric != null && STATEMENT_LEADING_ZERO_FRACTION_REGEX.matches(numeric)) {
            return parseAmount(compact.replaceFirst(numeric, numeric.replace(',', '.')))
        }
        if (numeric != null) {
            val separators = numeric.filter { it == '.' || it == ',' }.toSet()
            if (separators.size == 1) {
                val sep = separators.first()
                if (hasStrictUserGrouping(numeric, sep)) {
                    return parseAmount(compact.replaceFirst(numeric, numeric.replace(sep.toString(), "")))
                }
            }
        }
        return parseAmount(token)
    }

    private fun hasStrictUserGrouping(text: String, separator: Char): Boolean {
        if (text.startsWith('0')) return false
        return hasStrictThousandsGrouping(text, separator)
    }

    private fun hasStrictThousandsGrouping(rawIntegerPart: String, separator: Char): Boolean {
        val groups = rawIntegerPart.split(separator)
        if (groups.size < 2) return false
        val firstGroup = groups.first()
        val remainingGroups = groups.drop(1)
        if (firstGroup.isEmpty() || firstGroup.length !in 1..3 || !firstGroup.all { it.isDigit() }) {
            return false
        }
        return remainingGroups.all { group -> group.length == 3 && group.all { it.isDigit() } }
    }
}
