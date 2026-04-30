package com.yourname.expensetracker.domain.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.ReplaceWith

/**
 * centralized utility for normalizing currency strings.
 * Converts symbols (€, $, £) to ISO 4217 codes (EUR, USD, GBP).
 * Defaults to "EUR" if unknown.
 */
@Singleton
class CurrencyNormalizer @Inject constructor() {

    @Deprecated(
        message = "Silently defaults to EUR for unknown currencies. Use normalizeOrNull() and handle null explicitly.",
        replaceWith = ReplaceWith("normalizeOrNull(raw) ?: <explicit fallback>")
    )
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "EUR"

        val cleaned = raw.trim().uppercase(Locale.ROOT)

        return when (cleaned) {
            "€", "EUR", "EURO", "E" -> "EUR"
            "$", "USD", "DOLLAR" -> "USD"
            "£", "GBP", "POUND" -> "GBP"
            "₹", "INR", "RUPEE", "RUPEES" -> "INR"
            "CHF", "FRANC" -> "CHF"
            "¥", "JPY", "YEN" -> "JPY"
            else -> {
                // If it looks like a valid 3-letter code, keep it, otherwise default
                if (cleaned.length == 3 && cleaned.all { it.isLetter() }) {
                    cleaned
                } else {
                    "EUR"
                }
            }
        }
    }

    /**
     * Normalizes a raw currency string to ISO 4217 code.
     * Returns null if the raw value is null, blank, or unrecognized.
     * Prefer this over [normalize] to avoid silent EUR defaults.
     */
    fun normalizeOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val cleaned = raw.trim().uppercase(java.util.Locale.ROOT)

        return when (cleaned) {
            "€", "EUR", "EURO", "E" -> "EUR"
            "$", "USD", "DOLLAR" -> "USD"
            "£", "GBP", "POUND" -> "GBP"
            "₹", "INR", "RUPEE", "RUPEES" -> "INR"
            "CHF", "FRANC" -> "CHF"
            "¥", "JPY", "YEN" -> "JPY"
            else -> {
                if (cleaned.length == 3 && cleaned.all { it.isLetter() }) {
                    cleaned
                } else {
                    null
                }
            }
        }
    }
}
