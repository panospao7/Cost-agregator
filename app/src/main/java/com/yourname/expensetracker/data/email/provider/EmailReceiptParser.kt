package com.yourname.expensetracker.data.email.provider

import com.yourname.expensetracker.domain.util.AmountUtils
import java.text.ParsePosition
import java.text.SimpleDateFormat
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
 * Interface for email receipt parsers.
 */
interface EmailReceiptParser {
    /**
     * Check if this parser can handle the given email.
     */
    fun canParse(sender: String, subject: String, body: String): Boolean

    /**
     * Parse the email body into structured receipt data.
     * Returns null if parsing fails.
     */
    fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt?
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
    }

    protected fun extractAmount(text: String): Double? {
        return amountRegex.findAll(cleanHtml(text)).firstNotNullOfOrNull { match ->
            parseLocalizedAmount(match.groupValues[1])
        }
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

        for (locale in supportedDateLocales) {
            for (pattern in datePatterns) {
                val formatter = SimpleDateFormat(pattern, locale).apply {
                    isLenient = false
                }

                val position = ParsePosition(0)
                val parsed = formatter.parse(normalized, position)
                if (parsed != null && position.index == normalized.length) {
                    return parsed.time
                }
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
