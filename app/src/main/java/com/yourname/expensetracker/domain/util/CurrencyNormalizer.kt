package com.yourname.expensetracker.domain.util

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * centralized utility for normalizing currency strings.
 * Converts symbols (€, $, £) to ISO 4217 codes (EUR, USD, GBP).
 * Defaults to "EUR" if unknown.
 */
@Singleton
class CurrencyNormalizer @Inject constructor() {

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return "EUR"

        val cleaned = raw.trim().uppercase(Locale.getDefault())

        return when (cleaned) {
            "€", "EUR", "EURO", "E" -> "EUR"
            "$", "USD", "DOLLAR" -> "USD"
            "£", "GBP", "POUND" -> "GBP"
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
}
