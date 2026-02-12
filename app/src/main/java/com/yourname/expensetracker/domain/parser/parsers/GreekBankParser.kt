package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications.
 */
class GreekBankParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "gr.nbg.mobilebanking",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )

    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:EUR|€|USD|GBP)?\s*(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})\s*(?:at|στ[οη]ν?|σε|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "MERCHANT 12,50 EUR"
        Pattern.compile(
            """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)(\d+[.,]\d{2})\s*(EUR|€)?\s*[-–]\s*(.+)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Patterns to REJECT
    private val REJECT_PATTERNS = listOf(
        "υπόλοιπο", "balance", "otp", "κωδικός", "code",
        "ενεργοποί", "activate", "εγκρίθηκε η αίτηση",
        "προσφορά", "offer", "έκπτωση", "discount",
        "ενημέρωση", "update", "reminder"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        // Quick reject
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null

        for (pattern in PURCHASE_PATTERNS) {
            val matcher = pattern.matcher(fullText)
            if (matcher.find()) {
                // Groups vary by pattern but we try to extract amount and merchant
                return tryExtract(matcher, fullText)
            }
        }

        return null
    }

    private fun tryExtract(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        // Try to find the amount (could be in group 1 or 2)
        var amountStr: String? = null
        var currency = "EUR"
        var merchant = "Unknown"

        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i) ?: continue
            if (group.matches(Regex("""\d+[.,]\d{2}"""))) {
                amountStr = group
            } else if (group.matches(Regex("""[€$£]|EUR|USD|GBP""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            } else if (group.length > 2) {
                merchant = merchantCleaner.clean(group)
            }
        }

        val amount = amountStr?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (amount < 0.01 || amount > 50000) return null

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.92f
        )
    }
}
