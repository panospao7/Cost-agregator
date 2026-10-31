package com.yourname.expensetracker.domain.quickadd

import java.math.BigDecimal
import java.util.Locale

/**
 * Amount rules of the quick-add parser (spec 4.5, 4.6).
 *
 * [QuickAddAmountRules.canonicalize] is the SINGLE OWNER of the amount canonicalisation rule. It is
 * a pure, separately callable function: other callers (the clipboard parser) call it instead of
 * re-implementing the rule. It deliberately does not call `AmountUtils.parseAmount` (which logs)
 * and does not call the UI-layer `AmountInputSanitizer`. Tests assert that its output round-trips
 * through both (INV-5).
 *
 * Rounding: none. More than 2 fraction digits is flagged, never rounded or truncated.
 */
object QuickAddAmountRules {

    private const val MAX_AMOUNT = 1_000_000L

    /** Minus-class: ASCII hyphen-minus, U+2212, U+2011, U+2013, U+2014, U+FE63, U+FF0D. */
    private val MINUS_CLASS: Set<Char> =
        intArrayOf(0x002D, 0x2212, 0x2011, 0x2013, 0x2014, 0xFE63, 0xFF0D).map { it.toChar() }.toSet()
    private val SHAPE = Regex("^[0-9]([0-9.,]*[0-9])?$")
    private val LIMIT = BigDecimal(MAX_AMOUNT)

    /** Appended to the core of a poisoned token so it can never pass the amount shape gate (U+FFFF). */
    private val POISON: String = 0xFFFF.toChar().toString()

    /**
     * Canonicalises one amount core: the text of a money token with its currency markers removed.
     *
     * Result:
     * - `Value(AmountText)`: text matching `^\d{1,7}\.\d{2}$`, value in [0.01, 1000000.00];
     * - `Flagged(reason)`: AMOUNT_NOT_POSITIVE, AMOUNT_UNCLEAR, AMOUNT_AMBIGUOUS_SEPARATOR or
     *   AMOUNT_OVER_LIMIT;
     * - `Absent`: [core] is empty.
     *
     * Rules, in order (spec 4.5 step 5):
     * R0 leading minus-class char; R1 shape `^[0-9]([0-9.,]*[0-9])?$`; R2 leading zero followed by a
     * digit; R3 exactly one separator followed by exactly three digits (ambiguous, never guessed);
     * R4 grouping that does not parse; R5 more than 2 fraction digits; R6 value not positive;
     * R7 value above 1,000,000.
     *
     * Interpretation of separators mirrors the app's lenient amount reader for the shapes that
     * survive R0-R3: with both separators the last one is the decimal mark and the other must form
     * strict 3-digit groups; a single comma followed by 1-2 digits is a decimal; repeated commas
     * must be strict 3-digit groups; a dot is always a decimal point.
     */
    fun canonicalize(core: String): FieldParse<AmountText> {
        if (core.isEmpty()) return FieldParse.Absent
        if (core[0] in MINUS_CLASS) return FieldParse.Flagged(FlagReason.AMOUNT_NOT_POSITIVE)
        if (!SHAPE.matches(core)) return FieldParse.Flagged(FlagReason.AMOUNT_UNCLEAR)
        if (core.length >= 2 && core[0] == '0' && core[1] in '0'..'9') {
            return FieldParse.Flagged(FlagReason.AMOUNT_UNCLEAR)
        }

        val separatorCount = core.count { it == '.' || it == ',' }
        if (separatorCount == 1) {
            val separatorIndex = core.indexOfFirst { it == '.' || it == ',' }
            if (core.length - separatorIndex - 1 == 3) {
                return FieldParse.Flagged(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR)
            }
        }

        val value = interpret(core) ?: return FieldParse.Flagged(FlagReason.AMOUNT_UNCLEAR)
        if (value.scale() > 2) return FieldParse.Flagged(FlagReason.AMOUNT_UNCLEAR)
        if (value.signum() <= 0) return FieldParse.Flagged(FlagReason.AMOUNT_NOT_POSITIVE)
        if (value.compareTo(LIMIT) > 0) return FieldParse.Flagged(FlagReason.AMOUNT_OVER_LIMIT)
        return FieldParse.Value(AmountText(value.setScale(2).toPlainString()))
    }

    private fun interpret(core: String): BigDecimal? {
        val hasComma = core.contains(',')
        val hasDot = core.contains('.')
        return when {
            hasComma && hasDot -> {
                val decimalMark = if (core.lastIndexOf(',') > core.lastIndexOf('.')) ',' else '.'
                val groupMark = if (decimalMark == ',') '.' else ','
                val parts = core.split(decimalMark)
                if (parts.size != 2 || parts[1].isEmpty()) return null
                if (!hasStrictGrouping(parts[0], groupMark)) return null
                toDecimal(parts[0].replace(groupMark.toString(), "") + "." + parts[1])
            }
            hasComma -> {
                val parts = core.split(',')
                when {
                    parts.size == 2 && parts[1].length <= 2 -> toDecimal(parts[0] + "." + parts[1])
                    parts.size >= 2 -> {
                        val groups = parts.drop(1)
                        val first = parts.first()
                        if (first.length !in 1..3 || groups.any { it.length != 3 }) return null
                        toDecimal(parts.joinToString(""))
                    }
                    else -> null
                }
            }
            else -> toDecimal(core)
        }
    }

    private fun hasStrictGrouping(integerPart: String, separator: Char): Boolean {
        val groups = integerPart.split(separator)
        if (groups.size < 2) return false
        val first = groups.first()
        if (first.length !in 1..3 || !first.all { it in '0'..'9' }) return false
        return groups.drop(1).all { it.length == 3 && it.all { c -> c in '0'..'9' } }
    }

    private fun toDecimal(text: String): BigDecimal? =
        try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            null
        }

    // ------------------------------------------------------------------ number-like tokens

    /** A money-shaped token after its attached currency markers were split off. */
    internal class NumberLike(val core: String, val markers: List<Set<String>>)

    /**
     * Splits attached currency markers off a folded token. Returns null when the remainder is not
     * number-like (it must contain an ASCII digit and no letter), so "7eleven", "a1" and "4k" stay
     * text. Marker stripping is preferred over keeping the marker characters in the core.
     *
     * A single leading minus-class character is skipped before the prefix marker is matched and is
     * kept in the core: "-$5" is the USD marker plus the core "-5", so R0 still fires on the core
     * while the foreign-currency decision (which has priority) sees the marker.
     *
     * [poisoned] marks a token whose combining marks were folded away from a non-letter: such a core
     * can never pass the amount shape gate.
     */
    internal fun splitNumberLike(foldedToken: String, poisoned: Boolean): NumberLike? {
        if (foldedToken.none { it in '0'..'9' }) return null
        val markers = QuickAddVocabulary.MARKERS_LONGEST_FIRST

        val minus = if (foldedToken[0] in MINUS_CLASS) foldedToken.substring(0, 1) else ""
        val body = foldedToken.substring(minus.length)

        val prefixes: List<QuickAddVocabulary.Marker?> =
            markers.filter { body.startsWith(it.key) } + listOf(null)
        for (prefix in prefixes) {
            val afterPrefix = if (prefix == null) body else body.substring(prefix.key.length)
            val suffixes: List<QuickAddVocabulary.Marker?> =
                markers.filter { afterPrefix.endsWith(it.key) } + listOf(null)
            for (suffix in suffixes) {
                val unsigned =
                    if (suffix == null) afterPrefix else afterPrefix.substring(0, afterPrefix.length - suffix.key.length)
                val core = minus + unsigned
                if (isNumberLikeCore(core)) {
                    val attached = listOfNotNull(prefix?.codes, suffix?.codes)
                    return NumberLike(if (poisoned) core + POISON else core, attached)
                }
            }
        }
        return null
    }

    private fun isNumberLikeCore(core: String): Boolean =
        core.any { it in '0'..'9' } && core.none { Character.isLetter(it) }

    // ---------------------------------------------------------------------- decision

    /** True when [codes] is not exactly the home currency (INV-4). */
    internal fun isForeign(codes: Set<String>, homeCurrency: String): Boolean =
        codes != setOf(homeCurrency.trim().uppercase(Locale.ROOT))

    /**
     * Decides the amount field (spec 4.5 steps 1-5).
     *
     * @param foreign a foreign currency marker appears anywhere in the line
     * @param quantity any quantity token is present
     * @param candidateCores the cores of all money candidates, in line order
     */
    internal fun decide(
        foreign: Boolean,
        quantity: Boolean,
        candidateCores: List<String>
    ): FieldParse<AmountText> = when {
        foreign -> FieldParse.Flagged(FlagReason.AMOUNT_FOREIGN_CURRENCY)
        quantity -> FieldParse.Flagged(FlagReason.AMOUNT_QUANTITY)
        candidateCores.size >= 2 -> FieldParse.Flagged(FlagReason.AMOUNT_MULTIPLE_NUMBERS)
        candidateCores.isEmpty() -> FieldParse.Absent
        else -> canonicalize(candidateCores.single())
    }
}
