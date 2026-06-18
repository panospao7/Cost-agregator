package com.yourname.expensetracker.domain.notification.money

import com.yourname.expensetracker.domain.currency.CurrencyResolution
import com.yourname.expensetracker.domain.currency.MoneySignal
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects monetary amounts and currencies from notification text.
 * Handles prefix/suffix notation, decimal commas, ambiguous symbols,
 * and home-currency-based disambiguation.
 */
@Singleton
class NotificationMoneySignalDetector @Inject constructor(
    private val userCurrencyProvider: UserCurrencyProvider
) {
    // Supported currencies with ISO codes and symbols
    private val currencies = listOf(
        CurrencyDef("EUR", "€", "EUR", "EURO"),
        CurrencyDef("USD", "$", "USD", "US$"),
        CurrencyDef("GBP", "£", "GBP"),
        CurrencyDef("CHF", "CHF", "Fr", "SFr"),
        CurrencyDef("PLN", "PLN", "zł", "zl"),
        CurrencyDef("RON", "RON", "lei", "leu"),
        CurrencyDef("TRY", "TRY", "₺", "TL"),
        CurrencyDef("CAD", "CAD", "C$", "CA$"),
        CurrencyDef("AUD", "AUD", "A$", "AU$"),
        CurrencyDef("JPY", "JPY", "¥"),
        CurrencyDef("SEK", "SEK", "kr"),
        CurrencyDef("NOK", "NOK", "kr"),
        CurrencyDef("DKK", "DKK", "kr"),
        CurrencyDef("HUF", "HUF", "Ft"),
        CurrencyDef("CZK", "CZK", "Kč", "Kc")
    )

    suspend fun bestTransactionAmount(
        text: String,
        homeCurrency: String? = null
    ): MoneySignal? {
        // Try explicit ISO codes first (e.g. "12.30 EUR", "EUR 12.30")
        for (currency in currencies) {
            val regex = Regex(
                """(\d[\d.,\s]*)\s*(${currency.isoCodes.joinToString("|") { Regex.escape(it) }})\b|\b(${currency.isoCodes.joinToString("|") { Regex.escape(it) }})\s*(\d[\d.,\s]*)""",
                RegexOption.IGNORE_CASE
            )
            val match = regex.find(text) ?: continue
            val amountStr = (match.groupValues[1].ifEmpty { match.groupValues[4] })
                .replace(Regex("""\s+"""), "")
                .replace(",", ".")
            val amount = cleanAmount(amountStr) ?: continue
            if (amount <= 0.01) continue

            return MoneySignal(
                raw = match.value.trim(),
                amount = amount,
                currencyCode = currency.code,
                currencyCandidates = setOf(currency.code),
                resolution = CurrencyResolution.EXPLICIT_ISO_CODE,
                confidence = 0.95f,
                ambiguous = false
            )
        }

        // Try unambiguous symbols (€, £, ¥, ₺)
        for (currency in currencies.filter { it.unambiguousSymbol }) {
            val symbols = currency.symbols.map { Regex.escape(it) }.joinToString("|")
            val regex = Regex("""($symbols)\s*(\d[\d.,\s]*)|\b(\d[\d.,\s]*)\s*($symbols)""")
            val match = regex.find(text) ?: continue
            val amountStr = (match.groupValues[2].ifEmpty { match.groupValues[3] })
                .replace(Regex("""\s+"""), "")
                .replace(",", ".")
            val amount = cleanAmount(amountStr) ?: continue
            if (amount <= 0.01) continue

            return MoneySignal(
                raw = match.value.trim(),
                amount = amount,
                currencyCode = currency.code,
                currencyCandidates = setOf(currency.code),
                resolution = CurrencyResolution.EXPLICIT_UNAMBIGUOUS_SYMBOL,
                confidence = 0.90f,
                ambiguous = false
            )
        }

        // Try ambiguous $ (USD/CAD/AUD)
        val dollarMatch = Regex("""(\$)\s*(\d[\d.,\s]*)|\b(\d[\d.,\s]*)\s*(\$)""").find(text)
        if (dollarMatch != null) {
            val amountStr = (dollarMatch.groupValues[2].ifEmpty { dollarMatch.groupValues[3] })
                .replace(Regex("""\s+"""), "").replace(",", ".")
            val amount = cleanAmount(amountStr)
            if (amount != null && amount > 0.01) {
                val candidates = setOf("USD", "CAD", "AUD")
                val resolved = if (homeCurrency in candidates) homeCurrency!! else null
                return MoneySignal(
                    raw = dollarMatch.value.trim(),
                    amount = amount,
                    currencyCode = resolved,
                    currencyCandidates = candidates,
                    resolution = if (resolved != null) CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME
                                 else CurrencyResolution.AMBIGUOUS_UNRESOLVED,
                    confidence = if (resolved != null) 0.70f else 0.50f,
                    ambiguous = resolved == null
                )
            }
        }

        // Try ambiguous kr (SEK/NOK/DKK)
        val krMatch = Regex("""(\d[\d.,\s]*)\s*(kr)\b""", RegexOption.IGNORE_CASE).find(text)
        if (krMatch != null) {
            val amountStr = krMatch.groupValues[1].replace(Regex("""\s+"""), "").replace(",", ".")
            val amount = cleanAmount(amountStr)
            if (amount != null && amount > 0.01) {
                val candidates = setOf("SEK", "NOK", "DKK")
                val resolved = if (homeCurrency in candidates) homeCurrency!! else null
                return MoneySignal(
                    raw = krMatch.value.trim(),
                    amount = amount,
                    currencyCode = resolved,
                    currencyCandidates = candidates,
                    resolution = if (resolved != null) CurrencyResolution.AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME
                                 else CurrencyResolution.AMBIGUOUS_UNRESOLVED,
                    confidence = if (resolved != null) 0.70f else 0.45f,
                    ambiguous = resolved == null
                )
            }
        }

        return null
    }

    private fun cleanAmount(raw: String): Double? {
        // Remove thousands separators, normalize decimal
        val cleaned = raw.replace(Regex("""[^0-9.,]"""), "")
        // Detect comma-as-decimal (e.g. "12,30") vs comma-as-thousands (e.g. "1,234.56")
        val hasDot = cleaned.contains(".")
        val hasComma = cleaned.contains(",")
        return try {
            when {
                hasDot && hasComma -> cleaned.replace(",", "").toDouble() // 1,234.56
                hasComma && cleaned.lastIndexOf(',') == cleaned.length - 3 -> cleaned.replace(",", ".").toDouble() // 12,30
                hasComma -> cleaned.replace(",", "").toDouble() // 1234,00
                else -> cleaned.toDouble()
            }
        } catch (e: NumberFormatException) { null }
    }

    private class CurrencyDef(
        val code: String,
        vararg val symbols: String
    ) {
        val isoCodes = symbols.toList()
        val unambiguousSymbol: Boolean get() = symbols.none { it in setOf("$", "kr") }
    }
}
