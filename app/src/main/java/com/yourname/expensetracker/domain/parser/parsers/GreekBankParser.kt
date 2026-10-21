package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern

/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications with transaction codes:
 * - Χ (Χρέωση) = Debit/Outgoing
 * - Π (Πίστωση) = Credit/Incoming
 */
class GreekBankParser(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner,
    private val homeCurrency: String
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "gr.nbg.mobilebanking",
        "mbanking.NBG",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )

    // Transaction type indicators (Greek bank codes)
    private val DEBIT_CODES = listOf("Χ", "ΧΡ", "ΧΡΕ", "ΧΡΕΩΣΗ", "DEBIT")
    private val CREDIT_CODES = listOf("Π", "ΠΙ", "ΠΙΣ", "ΠΙΣΤΩΣΗ", "CREDIT")

    /**
     * MONEY-02 hotfix H1: every pattern declares the ONLY capture group(s) that may carry the
     * amount, the currency token and the merchant/description. Free-text groups (merchant,
     * transfer tail) are never parsed as amounts, so digits in them ("SHOP 24", "14:32",
     * "****1234") cannot overwrite the real amount. Group indices are 1-based regex groups;
     * [NO_GROUP] means the pattern has no such group.
     */
    private class AmountPattern(
        val pattern: Pattern,
        val amountGroup: Int,
        val currencyGroup: Int = NO_GROUP,
        val merchantGroup: Int = NO_GROUP
    )

    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        // Also handles: "Πληρώσατε €7,50 από την κάρτα *1554 σε BOX FOOD APP"
        // group 1 = amount, group 2 = optional trailing currency token, group 3 = merchant
        // (the optional leading currency token before the amount stays non-capturing)
        AmountPattern(
            Pattern.compile(
                """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*${AMOUNT_SPACED}\s*((?:EUR|USD|GBP)(?![\p{L}\p{N}])|€)?\s*(?:απ[όο]\s+τ[ηι]ν?\s+κ[άα]ρτ[αά]\s*[*0-9]*\s*)?(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 1,
            currencyGroup = 2,
            merchantGroup = 3
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        // group 1 = currency symbol, group 2 = amount, group 3 = merchant
        AmountPattern(
            Pattern.compile(
                """([€$£])\s*${AMOUNT_SPACED}\s*(?:at|στ[οη]ν?|σε|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 2,
            currencyGroup = 1,
            merchantGroup = 3
        ),
        // "MERCHANT 12,50 EUR"
        // group 1 = amount, group 2 = optional currency (EUR|€), group 3 = merchant
        AmountPattern(
            Pattern.compile(
                """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)${AMOUNT_SPACED}\s*(EUR|€)?\s*[-–]\s*(.+)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 1,
            currencyGroup = 2,
            merchantGroup = 3
        )
    )

    // All deposit patterns have exactly one capture group: the amount (group 1).
    private val DEPOSIT_PATTERNS = listOf(
        // Greek deposit keywords
        AmountPattern(
            Pattern.compile("""(?:κατάθεση|πίστωση|μισθοδοσία|καταθέσεις|πιστώσεις|επιστροφή)[\p{L}]*\s*[€$£]?\s*${AMOUNT}""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            amountGroup = 1
        ),
        // English deposit keywords
        AmountPattern(
            Pattern.compile("""(?:deposit|credited|received|transfer\s*received|incoming)[\p{L}]*\s*[€$£]?\s*${AMOUNT}""", Pattern.CASE_INSENSITIVE),
            amountGroup = 1
        ),
        // Salary patterns
        AmountPattern(
            Pattern.compile("""(?:μισθ[όό]ς|salary|wages)[\p{L}]*\s*[€$£]?\s*${AMOUNT}""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            amountGroup = 1
        ),
        // Amount followed by deposit keywords
        AmountPattern(
            Pattern.compile("""${AMOUNT}\s*[€$£]?\s*(?:κατάθεση|πίστωση|deposit|credited)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            amountGroup = 1
        )
    )

    // Transfer patterns (specific to bank transfers, not purchases)
    private val TRANSFER_PATTERNS = listOf(
        // "Μεταφορά 100€ σε Λογαριασμό" (Transfer to account)
        // group 1 = amount, group 2 = optional currency token (EUR|USD|GBP followed by a non-letter/digit,
        // or €), group 3 = destination tail (merchant). The preposition alternatives are non-capturing.
        // This pattern and the transaction-code pattern below now also consume USD and GBP tokens
        // (previously only EUR and €), so "Μεταφορά 100,00 USD σε Μαρία" gives merchant "σε Μαρία"
        // instead of "USD σε Μαρία" (an improvement pinned by tests).
        AmountPattern(
            Pattern.compile(
                """(?:μεταφορ[άα]|μεταφορά|transfer)\s*(?:[€$£])?\s*${AMOUNT_SPACED}\s*(?:((?:EUR|USD|GBP)(?![\p{L}\p{N}])|€)|σε|to|στον?)?\s*(.+)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 1,
            currencyGroup = 2,
            merchantGroup = 3
        ),
        // Transaction code patterns: "Χ 50,00" or "Π 100,00"
        // group 1 = direction code letter (metadata, not amount), group 2 = amount,
        // group 3 = optional currency token, group 4 = tail (merchant)
        AmountPattern(
            Pattern.compile(
                """(?:^|\s)([ΧΠXDP])\s*[:\-\s]?\s*${AMOUNT}\s*((?:EUR|USD|GBP)(?![\p{L}\p{N}])|€)?\s*(.*)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 2,
            currencyGroup = 3,
            merchantGroup = 4
        ),
        // Format variations: "50,00 € Χρέωση" or "Πίστωση 100.00 EUR" (statement-style)
        // group 1 = amount; no merchant group
        AmountPattern(
            Pattern.compile(
                """${AMOUNT}\s*[€$£]?\s*(?:EUR|USD|GBP)?\s*(?:χρ[έε]ωση|π[ίι]στωση|ανάληψη|κατάθεση)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 1
        ),
        // "€12,50 στο MERCHANT" (amount first, then location - common Greek format)
        // group 1 = amount, group 2 = merchant
        AmountPattern(
            Pattern.compile(
                """[€$£]?\s*${AMOUNT}\s*(?:EUR|€)?\s*(?:στ[οη]ν?|σε|at)\s+(.+?)(?:\s*(?:με|with)|$)""",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            ),
            amountGroup = 1,
            merchantGroup = 2
        )
    )

 private val REJECT_PATTERNS = listOf(
 "otp", "κωδικός", "code",
 "ενεργοποί", "activate", "εγκρίθηκε η αίτηση",
 "προσφορά", "offer", "έκπτωση", "discount",
 "ενημέρωση", "update", "reminder"
 )

 private val BALANCE_STRIP_PATTERNS = listOf(
 Regex("""(?m)^\s*υπόλοιπ[οό]\s*:?\s*.*$""", RegexOption.IGNORE_CASE),
 Regex("""(?m)^\s*balance\s*:?\s*.*$""", RegexOption.IGNORE_CASE),
 Regex("""υπόλοιπ[οό]\s*:?\s*[€$£]?\s*\d+([.,]\d{1,2})?""", RegexOption.IGNORE_CASE),
 Regex("""balance\s*:?\s*[€$£]?\s*\d+([.,]\d{1,2})?""", RegexOption.IGNORE_CASE)
 )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        @Suppress("UNUSED_PARAMETER") subText: String?,
        @Suppress("UNUSED_PARAMETER") packageName: String
    ): ParsedTransaction? {
 val fields = listOfNotNull(title, text, bigText)

        for (rawField in fields) {
            val field = BALANCE_STRIP_PATTERNS.fold(rawField) { acc, regex -> regex.replace(acc, "") }.trim()
            if (field.isBlank()) continue

            val lowerField = field.lowercase()

            if (REJECT_PATTERNS.any { lowerField.contains(it) }) continue

            // Try deposit patterns first (they're usually incoming transfers/salary)
            for (spec in DEPOSIT_PATTERNS) {
                val matcher = spec.pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtractDeposit(matcher, spec, field)
                    if (result != null) return result
                }
            }

            // Then try purchase patterns
            for (spec in PURCHASE_PATTERNS) {
                val matcher = spec.pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtract(matcher, spec)
                    if (result != null) return result
                }
            }

            // Finally try explicit transfer patterns
            for (spec in TRANSFER_PATTERNS) {
                val matcher = spec.pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtractTransfer(matcher, spec, field)
                    if (result != null) return result
                }
            }
        }

        return null
    }

    private fun tryExtract(matcher: java.util.regex.Matcher, spec: AmountPattern): ParsedTransaction? {
        // MONEY-02 H1: the amount comes only from the pattern's designated amount group.
        val amount = extractAmount(matcher, spec.amountGroup) ?: return null
        if (amount < 0.01 || amount > 50000) return null

        // MONEY-02 H1: when the whole tail is a bare currency token ("Αγορά 12,50 USD") the
        // pattern's lazy tail captured it. It is the currency, never the merchant.
        val rawTail = groupOrNull(matcher, spec.merchantGroup)
        val tailCurrencyToken = currencyTokenOrNull(rawTail)
        val currency = resolveCurrency(extractCurrencyToken(matcher, spec.currencyGroup) ?: tailCurrencyToken)
        var merchant = "Unknown"
        if (rawTail != null && tailCurrencyToken == null && rawTail.length > 2) {
            merchant = merchantCleaner.clean(rawTail)
        }

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = ParsedTransactionType.PURCHASE,
            confidence = 0.92f
        )
    }

    private fun tryExtractDeposit(
        matcher: java.util.regex.Matcher,
        spec: AmountPattern,
        fullText: String
    ): ParsedTransaction? {
        val amount = extractAmount(matcher, spec.amountGroup) ?: return null
        if (amount < 0.01 || amount > 50000) return null

        val currency = resolveCurrency(extractCurrencyToken(matcher, spec.currencyGroup))

        // For deposits, merchant is usually the sender (bank/employer)
        val merchant = extractDepositSource(fullText)
        
        // Detect direction based on intent keywords.
        // IMPORTANT: prioritize incoming credit semantics over generic prepositions like "to/σε".
        val normalizedText = fullText.lowercase()
        val direction = when {
            normalizedText.contains(
                Regex(
                    """(?:credited|credit(?:ed)?|πίστωση|πιστώθηκε|received|incoming|deposit|κατάθεση|transfer\s*received)""",
                    RegexOption.IGNORE_CASE
                )
            ) -> ParsedTransferDirection.INCOMING

            normalizedText.contains(
                Regex(
                    """(?:sent\s+to|transfer\s+to|μεταφορ[άα]\s+σε|εστ[άα]λη\s+σε|στάλθηκε\s+σε)""",
                    RegexOption.IGNORE_CASE
                )
            ) -> ParsedTransferDirection.OUTGOING

            normalizedText.contains(" από ") || normalizedText.contains(" απο ") || normalizedText.contains(" from ") ->
                ParsedTransferDirection.INCOMING

            else -> detectGreekDirection(fullText)
        }
        
        // Determine if it's a transfer or deposit based on context
        val isTransfer = fullText.contains(Regex("""(?:μεταφορ[άα]|transfer|από\s+λογαρ)""", RegexOption.IGNORE_CASE))

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = if (isTransfer) ParsedTransactionType.TRANSFER else ParsedTransactionType.DEPOSIT,
            confidence = 0.90f,
            transferDirection = direction ?: ParsedTransferDirection.INCOMING,  // Default to incoming for deposits
            transferAccountName = direction?.let { 
                when (it) {
                    ParsedTransferDirection.INCOMING -> "From: $merchant"
                    ParsedTransferDirection.OUTGOING -> "To: $merchant"
                }
            }
        )
    }

    private fun tryExtractTransfer(
        matcher: java.util.regex.Matcher,
        spec: AmountPattern,
        fullText: String
    ): ParsedTransaction? {
        // MONEY-02 H1: amount only from the designated group; the single-letter bank code
        // group (Χ/Π) is direction metadata and is never read as merchant or amount.
        val amount = extractAmount(matcher, spec.amountGroup) ?: return null
        if (amount < 0.01 || amount > 50000) return null

        // A tail that is only a bare currency token ("Μεταφορά 100,00 USD", "Χ 50,00 $") is the
        // currency, never the destination name.
        val tail = groupOrNull(matcher, spec.merchantGroup)?.trim()
        val tailCurrencyToken = currencyTokenOrNull(tail)
        val currency = resolveCurrency(extractCurrencyToken(matcher, spec.currencyGroup) ?: tailCurrencyToken)
        var merchant = "Transfer"
        if (tail != null && tailCurrencyToken == null && tail.length > 2) {
            merchant = merchantCleaner.clean(tail)
        }

        // Matches from the last TRANSFER_PATTERN (index 3, bare "€amount στο MERCHANT")
        // lack an explicit transfer keyword (μεταφορά/transfer) and can overlap with
        // purchase patterns. Reduce confidence so downstream consumers can weigh this
        // result against a purchase match if one is available.
        val hasExplicitTransferKeyword = fullText.contains(Regex("""(?:μεταφορ[άα]|transfer)""", RegexOption.IGNORE_CASE))
        val confidence = if (hasExplicitTransferKeyword) 0.9f else 0.75f

        val direction = detectGreekDirection(fullText)
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = ParsedTransactionType.TRANSFER,
            confidence = confidence,
            transferDirection = direction,
            transferAccountName = direction?.let {
                when (it) {
                    ParsedTransferDirection.INCOMING -> "From: $merchant"
                    ParsedTransferDirection.OUTGOING -> "To: $merchant"
                }
            }
        )
    }

    private fun groupOrNull(matcher: java.util.regex.Matcher, group: Int): String? {
        if (group == NO_GROUP || group < 1 || group > matcher.groupCount()) return null
        return matcher.group(group)
    }

    /**
     * Parses the amount from the pattern's designated amount group only. A token that is
     * missing, has no digit, or contains any letter is rejected (fail closed -> null).
     */
    private fun extractAmount(matcher: java.util.regex.Matcher, group: Int): Double? {
        val token = groupOrNull(matcher, group)?.trim() ?: return null
        if (token.none { it.isDigit() }) return null
        if (token.any { it.isLetter() }) return null
        // F-H1-a: in Greek notifications a dot-only token of 3-digit groups ("1.500", "1.234.567")
        // is a thousands separator, not a decimal point. Scoped here so the shared
        // AmountUtils.parseAmount contract used by other parsers is untouched.
        if (GREEK_DOT_THOUSANDS.matches(token)) return token.replace(".", "").toDouble()
        return AmountUtils.parseAmount(token)
    }

    /** The trimmed text when it is exactly a recognised currency symbol/code, else null. */
    private fun currencyTokenOrNull(text: String?): String? {
        val token = text?.trim() ?: return null
        return if (CURRENCY_TOKEN.matches(token)) token else null
    }

    /** Currency token from the designated currency group; recognised symbol/code tokens only. */
    private fun extractCurrencyToken(matcher: java.util.regex.Matcher, group: Int): String? =
        currencyTokenOrNull(groupOrNull(matcher, group))

    /** Normalises a recognised currency token, or falls back to the home currency. */
    private fun resolveCurrency(token: String?): String =
        token?.let { currencyNormalizer.normalizeOrNull(it) } ?: homeCurrency

    private companion object {
        const val NO_GROUP = -1
        val CURRENCY_TOKEN = Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE)

        /** Dot-only grouped integer: 1-3 leading digits (no leading zero) then one or more ".ddd" groups. */
        val GREEK_DOT_THOUSANDS = Regex("""^[1-9]\d{0,2}(?:\.\d{3})+$""")

        /**
         * Amount tokens (MONEY-02 H1). Each pattern captures the amount as ONE group built from these:
         * - leading boundary `(?<![\d.,])`: the amount can never start in the middle of a longer
         *   number (stops "1.234,56" being read as "234,56" and "κάρτας 25,00" as "5,00");
         * - trailing guard `(?![.,]?\d)`: the amount can never end inside a longer number, so a
         *   lazy/optional capture after it cannot make the regex backtrack and give the amount's
         *   last digit to the tail ("Αγορά €20" must not become amount 2 + tail "0").
         * AMOUNT groups thousands with '.' or ','; AMOUNT_SPACED additionally accepts whitespace
         * grouping (kept only for the patterns that always accepted it).
         * The resulting string text is handed to AmountUtils.parseAmount unchanged.
         */
        const val AMOUNT_NUM = """\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
        const val AMOUNT_NUM_SPACED = """\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
        // Atomic (?>...) so a failing tail can never backtrack into the amount and shorten it.
        const val AMOUNT = """(?<![\d.,])(?>(${AMOUNT_NUM})(?![.,]?\d))"""
        const val AMOUNT_SPACED = """(?<![\d.,])(?>(${AMOUNT_NUM_SPACED})(?![.,]?\d))"""
    }
    
    /**
     * Detects transfer direction from Greek bank notification text.
     * Uses transaction codes and keywords.
     */
    private fun detectGreekDirection(text: String): ParsedTransferDirection? {
        val upperText = text.uppercase()
        
        // Check for debit codes (outgoing)
        if (DEBIT_CODES.any { code -> 
            upperText.contains(" $code ") || 
            upperText.startsWith("$code ") ||
            upperText.contains(Regex("""\b$code[\s:.-]"""))
        }) {
            return ParsedTransferDirection.OUTGOING
        }
        
        // Check for credit codes (incoming)
        if (CREDIT_CODES.any { code -> 
            upperText.contains(" $code ") || 
            upperText.startsWith("$code ") ||
            upperText.contains(Regex("""\b$code[\s:.-]"""))
        }) {
            return ParsedTransferDirection.INCOMING
        }
        
        // Check keywords
        return when {
            // Outgoing keywords
            text.contains(Regex("""(?:χρ[έε]ωση|χρεώθηκε|χρεωστικό|μεταφορά\s+σε|sent\s+to|transfer\s+to)""", RegexOption.IGNORE_CASE)) ->
                ParsedTransferDirection.OUTGOING
            
            // Incoming keywords
            text.contains(Regex("""(?:πίστωση|πιστώθηκε|πιστωτικό|μεταφορά\s+από|received\s+from|transfer\s+from)""", RegexOption.IGNORE_CASE)) ->
                ParsedTransferDirection.INCOMING
            
            else -> null
        }
    }

    private fun extractDepositSource(text: String): String {
        // Try to extract sender/source from deposit notification
        val patterns = listOf(
            // "από" (from) pattern
            Pattern.compile("""(?:απ[όο]|from)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,/\-()]{3,40})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
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
