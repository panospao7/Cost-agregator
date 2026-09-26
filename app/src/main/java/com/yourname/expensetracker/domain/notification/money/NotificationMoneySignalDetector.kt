package com.yourname.expensetracker.domain.notification.money

import com.yourname.expensetracker.domain.currency.CurrencyResolution
import com.yourname.expensetracker.domain.currency.MoneySignal
import com.yourname.expensetracker.domain.currency.SupportedCurrency
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
    // ISO tokens and aliases are intentionally separate. Shared bare symbols
    // must never enter the explicit-code pass.
    private val currencies = listOf(
        CurrencyDef("EUR", aliases = listOf("€", "EURO")),
        CurrencyDef("USD", aliases = listOf("US$")),
        CurrencyDef("GBP", aliases = listOf("£")),
        CurrencyDef("CHF", aliases = listOf("Fr", "SFr")),
        CurrencyDef("PLN", aliases = listOf("zł", "zl")),
        CurrencyDef("RON", aliases = listOf("lei", "leu")),
        CurrencyDef("TRY", aliases = listOf("₺", "TL")),
        CurrencyDef("CAD", aliases = listOf("C$", "CA$")),
        CurrencyDef("AUD", aliases = listOf("A$", "AU$")),
        CurrencyDef("JPY", aliases = listOf("¥")),
        CurrencyDef("SEK"),
        CurrencyDef("NOK"),
        CurrencyDef("DKK"),
        CurrencyDef("HUF", aliases = listOf("Ft")),
        CurrencyDef("CZK", aliases = listOf("Kč", "Kc"))
    )

    suspend fun bestTransactionAmount(
        text: String,
        homeCurrency: String? = null
    ): MoneySignal? {
        val normalizedHomeCurrency = homeCurrency
            ?.let(SupportedCurrency::fromCode)
            ?.code

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
        for (currency in currencies.filter { it.aliases.isNotEmpty() }) {
            val prefixSymbols = currency.aliases.joinToString("|") { aliasPattern(it, isPrefix = true) }
            val suffixSymbols = currency.aliases.joinToString("|") { aliasPattern(it, isPrefix = false) }
            val regex = Regex(
                """($prefixSymbols)\s*(\d[\d.,\s]*)|\b(\d[\d.,\s]*)\s*($suffixSymbols)""",
                RegexOption.IGNORE_CASE
            )
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
                val resolved = normalizedHomeCurrency?.takeIf { it in candidates }
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
                val resolved = normalizedHomeCurrency?.takeIf { it in candidates }
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

    private fun aliasPattern(alias: String, isPrefix: Boolean): String {
        val escaped = Regex.escape(alias)
        // Guard only the outer edge: the amount may touch the inner edge (Fr42 / 42Fr).
        // The adjacent amount pattern already prevents matching word fragments there.
        return when {
            isPrefix && alias.firstOrNull()?.isLetterOrDigit() == true ->
                """(?<![\p{L}\p{N}])$escaped"""
            !isPrefix && alias.lastOrNull()?.isLetterOrDigit() == true ->
                """$escaped(?![\p{L}\p{N}])"""
            else -> escaped
        }
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
        val aliases: List<String> = emptyList()
    ) {
        val isoCodes: List<String> = listOf(code)
    }
}
