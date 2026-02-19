package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.AmountUtils
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
        "mbanking.NBG",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )

    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        // Also handles: "Πληρώσατε €7,50 από την κάρτα *1554 σε BOX FOOD APP"
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:EUR|€|USD|GBP)?\s*(?:απ[όο]\s+τ[ηι]ν?\s+κ[άα]ρτ[αά]\s*[*0-9]*\s*)?(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
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

    private val DEPOSIT_PATTERNS = listOf(
        // Greek deposit keywords
        Pattern.compile("""(?:κατάθεση|πίστωση|μισθοδοσία|καταθέσεις|πιστώσεις|επιστροφή)[\p{L}]*\s*[€$£]?\s*(\d+[.,]\d{2})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        // English deposit keywords
        Pattern.compile("""(?:deposit|credited|received|transfer\s*received|incoming)[\p{L}]*\s*[€$£]?\s*(\d+[.,]\d{2})""", Pattern.CASE_INSENSITIVE),
        // Salary patterns
        Pattern.compile("""(?:μισθ[όό]ς|salary|wages)[\p{L}]*\s*[€$£]?\s*(\d+[.,]\d{2})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        // Amount followed by deposit keywords
        Pattern.compile("""(\d+[.,]\d{2})\s*[€$£]?\s*(?:κατάθεση|πίστωση|deposit|credited)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
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
        @Suppress("UNUSED_PARAMETER") subText: String?,
        @Suppress("UNUSED_PARAMETER") packageName: String
    ): ParsedTransaction? {
        val fields = listOfNotNull(title, text, bigText)
        
        for (field in fields) {
            val lowerField = field.lowercase()
            
            // Quick reject for this specific field
            if (REJECT_PATTERNS.any { lowerField.contains(it) }) continue

            // Try deposit patterns first (they're usually incoming transfers/salary)
            for (pattern in DEPOSIT_PATTERNS) {
                val matcher = pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtractDeposit(matcher, field)
                    if (result != null) return result
                }
            }

            // Then try purchase patterns
            for (pattern in PURCHASE_PATTERNS) {
                val matcher = pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtract(matcher, field)
                    if (result != null) return result
                }
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
            
            // Fix (BUG-010): Use more specific currency check to avoid partial merchant matches
            if (group.matches(Regex("""^\d+[.,]\d{2}$"""))) {
                amountStr = group
            } else if (group.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            } else if (group.length > 2 && merchant == "Unknown") {
                merchant = merchantCleaner.clean(group)
            }
        }


        val amount = amountStr?.let { AmountUtils.parseAmount(it) } ?: return null
        if (amount < 0.01 || amount > 50000) return null

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.92f
        )
    }

    private fun tryExtractDeposit(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        var amountStr: String? = null
        var currency = "EUR"

        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i) ?: continue
            
            if (group.matches(Regex("""^\d+[.,]\d{2}$"""))) {
                amountStr = group
            } else if (group.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            }
        }

        val amount = amountStr?.let { AmountUtils.parseAmount(it) } ?: return null
        if (amount < 0.01 || amount > 50000) return null

        // For deposits, merchant is usually the sender (bank/employer)
        val merchant = extractDepositSource(fullText)

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.DEPOSIT,
            confidence = 0.90f
        )
    }

    private fun extractDepositSource(text: String): String {
        // Try to extract sender/source from deposit notification
        val patterns = listOf(
            // "από" (from) pattern
            Pattern.compile("""(?:απ[όο]|from)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{3,30})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // "σε λογαριασμό" pattern (to account)
            Pattern.compile("""(?:σ[εά])\s+λογαριασμ[όό]\s*(?:[\u002A\u0030-\u0039]+)?\s*(.*)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val source = matcher.group(1) ?: continue
                if (source.length > 2) {
                    return merchantCleaner.clean(source)
                }
            }
        }

        // Default source for deposits
        return "Deposit"
    }
}
