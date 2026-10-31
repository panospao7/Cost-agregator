package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.util.AmountUtils

/**
 * Amount and currency-token helpers for text statements. Built on [AmountUtils]; every function
 * is pure and returns null instead of throwing on malformed input.
 */
object StatementAmountParser {
    /** Mirrors the ParsedTransaction amount invariant (amount <= 1_000_000). */
    const val MAX_AMOUNT: Double = 1_000_000.0

    /** Unicode minus sign (U+2212), normalised to '-' before parsing. */
    private const val UNICODE_MINUS_CODE = 0x2212

    private val SIGNED_TOKEN = Regex("""^[-+]?\d[\d.,]*$""")
    private val CURRENCY_MARKERS = Regex("""[€£$]|\b(?:EUR|USD|GBP)\b""", RegexOption.IGNORE_CASE)

    // Regex escapes (not literal characters): no-break space A0, narrow no-break space 202F, figure space 2007.
    private val WHITESPACE = Regex("""[\s\xA0\x{202F}\x{2007}]+""")
    private val CURRENCY_AMOUNT = Regex("""(-)?\s*([€£$])\s*(-)?(\d[\d.,]*\d|\d)""")
    private val HEADER_CODE = Regex("""(?<![A-Za-z])(EUR|USD|GBP)(?![A-Za-z])""")

    /** A currency-marked amount found inside a line; [value] is signed. */
    data class CurrencyAmount(
        val value: Double,
        val currency: String,
        val start: Int,
        val end: Int
    )

    /**
     * Parses a signed statement token such as "1.234,56", "-8,63", "€1,234.56" or "-€3.00".
     * Returns null for anything that is not a plain amount.
     */
    fun parse(token: String): Double? {
        val compact = WHITESPACE.replace(
            CURRENCY_MARKERS.replace(token, "").replace(Char(UNICODE_MINUS_CODE), '-'),
            ""
        )
        if (!SIGNED_TOKEN.matches(compact)) return null
        val value = AmountUtils.parseStatementAmount(compact.removePrefix("+")) ?: return null
        return value.takeIf { it.isFinite() }
    }

    /** Finds every symbol-marked amount ("€20.00", "-€3.00", "$3.30") in reading order. */
    fun findCurrencyAmounts(text: String): List<CurrencyAmount> =
        CURRENCY_AMOUNT.findAll(text).mapNotNull { match ->
            val currency = symbolCurrency(match.groupValues[2].first()) ?: return@mapNotNull null
            val negative = match.groupValues[1].isNotEmpty() || match.groupValues[3].isNotEmpty()
            val magnitude = parse(match.groupValues[4]) ?: return@mapNotNull null
            CurrencyAmount(
                value = if (negative) -kotlin.math.abs(magnitude) else magnitude,
                currency = currency,
                start = match.range.first,
                end = match.range.last + 1
            )
        }.toList()

    fun symbolCurrency(symbol: Char): String? = when (symbol) {
        '€' -> "EUR"
        '£' -> "GBP"
        '$' -> "USD"
        else -> null
    }

    /**
     * The one currency a statement header names, or null when it names none or several.
     * Considers currency symbols and the codes EUR/USD/GBP.
     */
    fun singleHeaderCurrency(lines: List<String>): String? {
        val found = mutableSetOf<String>()
        for (line in lines) {
            for (char in line) symbolCurrency(char)?.let { found.add(it) }
            HEADER_CODE.findAll(line).forEach { found.add(it.groupValues[1]) }
            if (found.size > 1) return null
        }
        return found.singleOrNull()
    }
}
