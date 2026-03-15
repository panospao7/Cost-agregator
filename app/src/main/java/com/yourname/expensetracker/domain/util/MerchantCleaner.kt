package com.yourname.expensetracker.domain.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized utility for cleaning and normalizing merchant names.
 * Consolidates logic from various parsers to ensure consistency.
 */
@Singleton
class MerchantCleaner @Inject constructor() {

    private val timeRegex = Regex("""\s\d{1,2}:\d{2}(?::\d{2})?.*$""")
    private val dateRegex = Regex("""\s\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?.*$""")
    private val dateWithDotsRegex = Regex("""\s\d{1,4}\.\d{1,2}\.\d{1,4}.*$""")
    private val cardInfoRegex = Regex("""\s*(?:(?:Mastercard|Visa|Amex|card|κάρτα|•|·|-)+\s*)+\*?\.?\d+.*$""", RegexOption.IGNORE_CASE)
    private val stopWords = listOf(
        "confirmed", "successful", "completed", "declined", "pending",
        "ολοκληρώθηκε", "επιτυχής", "with card", "με κάρτα", "από την κάρτα", "στις", "at", "on", "to"
    )

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown"

        var candidate = raw.trim()
            .replace('\u00A0', ' ') // Non-breaking space
            .replace(timeRegex, "")
            .replace(dateRegex, "")
            .replace(dateWithDotsRegex, "") // Dates like 2024.03.15 or 15.03.2024
            .replace(cardInfoRegex, "")

        // Remove stop words from the end
        for (stop in stopWords) {
            val idx = candidate.indexOf(" $stop", ignoreCase = true)
            if (idx != -1) candidate = candidate.substring(0, idx)
            
            // Check if it's the very start (e.g. "at Starbucks")
            if (candidate.startsWith("$stop ", ignoreCase = true)) {
                candidate = candidate.substring(stop.length + 1)
            }
        }

        var result = candidate
            .replace(Regex("""\s{2,}"""), " ") // Standardize whitespace
            .replace(Regex("""[.!;?!,:]+$"""), "") // Remove trailing punctuation (one or more)
            .trim()
            .take(AppConstants.Parser.MAX_MERCHANT_LENGTH)
        // Strip emojis and other symbols that can cause display/parsing issues
        result = result.replace(Regex("""[\p{So}]+"""), "").trim() // Strip emojis and symbol-other
        return when {
            result.isEmpty() -> "Unknown"
            stopWords.any { result.equals(it, ignoreCase = true) } -> "Unknown"
            else -> result
        }
    }
}
