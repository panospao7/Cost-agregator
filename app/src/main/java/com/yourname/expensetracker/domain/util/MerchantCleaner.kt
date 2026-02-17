package com.yourname.expensetracker.domain.util

import java.util.Locale
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

        return candidate
            .replace(Regex("""\s{2,}"""), " ") // Standardize whitespace
            .replace(Regex("""[.!;]$"""), "") // Remove trailing punctuation
            .trim()
            .take(40)
            .let { if (it.isEmpty()) "Unknown" else it }
    }
}
