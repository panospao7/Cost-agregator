package com.yourname.expensetracker.data.email.provider

import com.yourname.expensetracker.domain.util.AmountUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

/**
 * Data class representing parsed receipt data from email.
 */
data class ParsedEmailReceipt(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val items: List<ReceiptItem>,
    val orderNumber: String?,
    val confidence: Double
)

data class ReceiptItem(
    val description: String,
    val quantity: Int,
    val unitPrice: Double,
    val totalPrice: Double
)

/**
 * Controlled, payload-independent skip reasons for email parser outcomes.
 * Stable constants only — never raw payload or error text (privacy rules).
 */
enum class EmailParseSkipReason {
    /** Generic parse failure: no trusted receipt structure was found. */
    PARSE_FAILED,

    /** RP-18 18-A: no trusted currency signal (symbol, bounded ISO code, trusted domain). */
    CURRENCY_UNRESOLVED
}

/**
 * RP-18 18-A: typed outcome of an email receipt parse attempt. [EmailReceiptParser.parse]
 * stays null-returning for compatibility; [EmailReceiptParser.parseWithOutcome] carries
 * the controlled skip reason instead of an untyped failure.
 */
sealed class EmailParseOutcome {
    data class Parsed(val receipt: ParsedEmailReceipt) : EmailParseOutcome()
    data class Skipped(val reason: EmailParseSkipReason) : EmailParseOutcome()
}

/**
 * Interface for email receipt parsers.
 */
interface EmailReceiptParser {
    /**
     * Check if this parser can handle the given email.
     */
    fun canParse(sender: String, subject: String, body: String): Boolean

    /**
     * Parse the email body into structured receipt data.
     * Returns null if parsing fails or the parse is skipped.
     */
    fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt?

    /**
     * RP-18 18-A: parse with a typed outcome. The default bridges [parse];
     * provider parsers override it to report controlled skips
     * (e.g. [EmailParseSkipReason.CURRENCY_UNRESOLVED]) while [parse]
     * keeps its stable null-on-failure contract.
     */
    fun parseWithOutcome(emailBody: String, receivedAt: Long): EmailParseOutcome =
        parse(emailBody, receivedAt)
            ?.let { EmailParseOutcome.Parsed(it) }
            ?: EmailParseOutcome.Skipped(EmailParseSkipReason.PARSE_FAILED)
}

/**
 * Base class with common parsing utilities.
 */
abstract class BaseEmailParser : EmailReceiptParser {
    companion object {
        private val amountRegex = Regex(
            """(?:€|\$|£|EUR|USD|GBP)?\s*([+-]?(?:\d{1,3}(?:[.,\s\u00A0\u202F\u2007]\d{3})+|\d+[.,]\d{2}))\s*(?:€|\$|£|EUR|USD|GBP)?""",
            RegexOption.IGNORE_CASE
        )

        private val htmlBreakRegex = Regex("""(?is)<\s*br\s*/?>""")
        private val htmlBlockBoundaryRegex = Regex(
            """(?is)<\s*(?:/(?:p|div|section|article|header|footer|ul|ol|li|tr|table|h[1-6])|(?:li|tr|h[1-6]))\b[^>]*>"""
        )
        private val htmlCellRegex = Regex("""(?is)</?(?:td|th)\b[^>]*>""")
        private val htmlCommentRegex = Regex("""(?s)<!--.*?-->""")
        private val htmlScriptStyleRegex = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>""")
        private val htmlTagRegex = Regex("""(?s)<[^>]+>""")
        private val htmlEntityRegex = Regex("""&(#x[0-9A-Fa-f]+|#\d+|[A-Za-z][A-Za-z0-9]+);""")
        private val horizontalWhitespaceRegex = Regex("""[\t\u000B\f\u00A0\u202F\u2007 ]+""")
        private val extraBlankLinesRegex = Regex("""\n{3,}""")
        private val ordinalSuffixRegex = Regex("""(?i)(\d{1,2})(st|nd|rd|th)""")

        private val datePatterns = listOf(
            "MMMM dd, yyyy",
            "MMM dd, yyyy",
            "MMMM d, yyyy",
            "MMM d, yyyy",
            "MMMM dd yyyy",
            "MMM dd yyyy",
            "MMMM d yyyy",
            "MMM d yyyy",
            "dd MMMM yyyy",
            "d MMMM yyyy",
            "dd MMM yyyy",
            "d MMM yyyy",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "dd.MM.yyyy"
        )

        private val supportedDateLocales = listOf(
            Locale.US,
            Locale.UK,
            Locale.ENGLISH,
            Locale.GERMAN,
            Locale.FRANCE,
            Locale.FRENCH,
            Locale.ITALIAN,
            Locale("es"),
            Locale("pt"),
            Locale("nl"),
            Locale("el")
        )

        // P11-PR3 (NEW-P11-004): Pre-built formatter cache — avoids 176 allocations per parse call
        private val formatterCache: List<java.time.format.DateTimeFormatter> by lazy {
            supportedDateLocales.flatMap { locale ->
                datePatterns.map { pattern ->
                    java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
                }
            }
        }

        private val htmlEntities = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to '"'.toString(),
            "apos" to "'",
            "nbsp" to " ",
            "ensp" to " ",
            "emsp" to " ",
            "thinsp" to " ",
            "euro" to "€",
            "pound" to "£",
            "cent" to "¢",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
            "ndash" to "-",
            "mdash" to "-",
            "lsquo" to "'",
            "rsquo" to "'",
            "ldquo" to '"'.toString(),
            "rdquo" to '"'.toString(),
            "hellip" to "..."
        )

        // ── RP-18 18-A: shared total/currency/summary machinery ─────────────
        // Strict numeric grammar for a TOTAL amount: grouped thousands (with
        // optional cents) or a plain two-decimal number. Bare integers
        // ("Total items: 3") and VAT percentages ("19%") never match, so they
        // can never be selected as a total.
        private const val totalNumberGrammar =
            """(?:\d{1,3}(?:[.,\s\u00A0\u202F\u2007]\d{3})+(?:[.,]\d{2})?|\d+[.,]\d{2})"""

        private val totalAmountCandidateRegex = Regex(
            """[€$£]?\s*(${totalNumberGrammar})\s*(?:[€$£]|\b(?:USD|EUR|GBP)\b)?""",
            RegexOption.IGNORE_CASE
        )

        // Last-resort keyword lines: "total" not preceded by a letter or
        // hyphen, so "Subtotal"/"SUBTOTAL" can never match; deliberately no
        // trailing boundary so the "Totals:" recovery spelling also matches.
        private val totalKeywordLineRegex = Regex("""(?i)(?<![\p{L}-])total""")

        // RP-18 18-A: aggregate/summary rows ("3 items, total 45,90") are never line items.
        private val summaryRowRegex = Regex(
            """(?i)\b\d{1,4}\s+(?:items?|artikel|articles?|pieces?|pcs\.?|positions?|produkte|prodotti)\b"""
        )

        // RP-18 18-A: currency resolution — symbols first, then word-bounded
        // 3-letter ISO codes, then trusted domains. Bare two-letter tokens
        // ("US", "DE", "IT", …) are deliberately NOT signals: they
        // false-positive on the ordinary words "us", "de" and "it".
        private val currencySymbolToCode = listOf("€" to "EUR", "£" to "GBP", "$" to "USD")
        private val currencyIsoRegex = Regex("""\b(USD|EUR|GBP)\b""")
    }

    protected fun extractAmount(text: String): Double? {
        return amountRegex.findAll(cleanHtml(text)).firstNotNullOfOrNull { match ->
            parseLocalizedAmount(match.groupValues[1])
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // RP-18 18-A: total-label hierarchy, strict total grammar, summary-row
    // exclusion and currency resolution shared by all provider parsers.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * RP-18 18-A: line-anchored, specific-first total extraction.
     *
     * Hierarchy: each label in [specificLabels] (most specific first) → the
     * bare "total" label LAST → any line containing a word-bounded "total"
     * (never "Subtotal"). A generic "Total" match therefore cannot win over a
     * specific labelled total ("Grand Total", "Order Total", "Total amount", …),
     * and mid-line words like "Subtotal" or value-less "Total items: 3" lines
     * can never be selected.
     */
    protected fun extractTotalAmount(text: String, specificLabels: List<String>): Double? {
        for (label in specificLabels) {
            extractLabeledLineAmount(text, label)?.let { return it }
        }
        extractLabeledLineAmount(text, "total")?.let { return it }
        return extractKeywordLineTotal(text)
    }

    /**
     * RP-18 18-A: strict parse of the remainder of a line starting with
     * [label] (case-insensitive, line-anchored, optional colon).
     */
    protected fun extractLabeledLineAmount(text: String, label: String): Double? {
        val labelPattern = label.trim()
            .split(Regex("""\s+"""))
            .joinToString(separator = """\s+""") { word -> Regex.escape(word) }
        val lineRegex = Regex(
            """^[ \t]*${labelPattern}\b[ \t]*:?[ \t]*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        for (match in lineRegex.findAll(text)) {
            extractStrictAmount(match.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun extractKeywordLineTotal(text: String): Double? {
        for (line in text.lineSequence()) {
            if (!totalKeywordLineRegex.containsMatchIn(line)) continue
            extractStrictAmount(line)?.let { return it }
        }
        return null
    }

    /**
     * RP-18 18-A: first strict amount candidate in [text] that is neither a
     * VAT percentage ("19%") nor part of a quantity×unit-price expression
     * ("2 x 5,00 €", "2 @ $5.00").
     */
    private fun extractStrictAmount(text: String): Double? {
        for (match in totalAmountCandidateRegex.findAll(text)) {
            if (isUnitPriceOrPercentContext(text, match.range)) continue
            parseLocalizedAmount(match.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun isUnitPriceOrPercentContext(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first)
        val after = text.substring(range.last + 1)

        // VAT percentage directly after the number: "19%" / "19,00 %"
        if (after.startsWith("%")) return true

        // Unit price preceded by a quantity marker: "2 x 5,00" / "2 @ $5.00"
        val beforeTrimmed = before.trimEnd { it.isWhitespace() || it == '€' || it == '$' || it == '£' }
        if (beforeTrimmed.isNotEmpty()) {
            val marker = beforeTrimmed.last()
            if (isQuantityPriceMarker(marker)) {
                val quantity = beforeTrimmed.dropLast(1).trimEnd()
                if (quantity.isNotEmpty() && quantity.last().isDigit()) return true
            }
        }

        // Quantity marker followed by a number after the amount: "45,90 x 2"
        val afterTrimmed = after.trimStart { it.isWhitespace() || it == '€' || it == '$' || it == '£' }
        if (afterTrimmed.isNotEmpty()) {
            val marker = afterTrimmed.first()
            if (isQuantityPriceMarker(marker)) {
                val quantity = afterTrimmed.drop(1).trimStart()
                if (quantity.isNotEmpty() && quantity.first().isDigit()) return true
            }
        }

        return false
    }

    private fun isQuantityPriceMarker(char: Char): Boolean =
        char == 'x' || char == 'X' || char == '×' || char == '@' || char == '*'

    /**
     * RP-18 18-A: resolve a currency code from [text] — currency symbols,
     * then word-bounded 3-letter ISO codes, then [trustedDomainCodes] domains.
     * Multiple symbols resolve by fixed priority (EUR, GBP, USD); conflicting
     * ISO codes fail closed. Returns null when no trusted signal exists
     * (the caller must skip with [EmailParseSkipReason.CURRENCY_UNRESOLVED]) —
     * no default currency is ever guessed.
     */
    protected fun detectCurrencyCode(
        text: String,
        trustedDomainCodes: Map<String, String> = emptyMap()
    ): String? {
        val upper = text.uppercase(Locale.US)
        for ((symbol, code) in currencySymbolToCode) {
            if (upper.contains(symbol)) return code
        }
        val isoMatches = currencyIsoRegex.findAll(upper).map { it.groupValues[1] }.toSet()
        if (isoMatches.size == 1) return isoMatches.first()
        for ((domain, code) in trustedDomainCodes) {
            if (containsBoundedToken(upper, domain.uppercase(Locale.US))) return code
        }
        return null
    }

    /** RP-18 18-A: aggregate/summary rows ("3 items, total 45,90") are never line items. */
    protected fun isSummaryRow(text: String): Boolean {
        return summaryRowRegex.containsMatchIn(text)
    }

    protected fun parseDate(dateStr: String): Long? {
        return parseLocalizedDate(dateStr)
    }

    protected fun parseLocalizedAmount(amountText: String): Double? {
        val normalized = decodeHtmlEntities(amountText)
            .replace(horizontalWhitespaceRegex, " ")
            .replace(Regex("""(?i)\b(?:eur|usd|gbp)\b"""), " ")
            .replace(Regex("""[^\d,().\-−‑–—\s\u00A0\u202F\u2007]"""), " ")
            .trim()

        if (normalized.isEmpty()) {
            return null
        }

        return AmountUtils.parseAmount(normalized)
    }

    protected fun parseLocalizedDate(dateText: String): Long? {
        val normalized = decodeHtmlEntities(dateText)
            .replace(ordinalSuffixRegex, "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (normalized.isEmpty()) {
            return null
        }

        for (formatter in formatterCache) {
            try {
                val parsed = formatter.parse(normalized)
                val localDate = LocalDate.from(parsed)
                return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    protected fun cleanHtml(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(htmlCommentRegex, " ")
            .replace(htmlScriptStyleRegex, " ")
            .replace(htmlBreakRegex, "\n")
            .replace(htmlBlockBoundaryRegex, "\n")
            .replace(htmlCellRegex, " ")
            .replace(htmlTagRegex, " ")
            .let(::decodeHtmlEntities)
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace("\u2007", " ")
            .replace(horizontalWhitespaceRegex, " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(extraBlankLinesRegex, "\n\n")
            .trim()
    }

    protected fun containsBoundedToken(text: String, token: String): Boolean {
        if (token.isEmpty()) return false
        val escapedToken = Pattern.quote(token)
        val regex = Regex("""(?<![\p{L}\p{N}])$escapedToken(?![\p{L}\p{N}])""")
        return regex.containsMatchIn(text)
    }

    private fun decodeHtmlEntities(text: String): String {
        return htmlEntityRegex.replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) -> {
                    entity.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                }

                entity.startsWith("#") -> {
                    entity.substring(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                }

                else -> htmlEntities[entity.lowercase()] ?: match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int): String? {
        if (!Character.isValidCodePoint(codePoint)) {
            return null
        }

        return try {
            String(Character.toChars(codePoint))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
