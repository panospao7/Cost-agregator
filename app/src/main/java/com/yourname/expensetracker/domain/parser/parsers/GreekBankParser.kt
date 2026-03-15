package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications with transaction codes:
 * - Χ (Χρέωση) = Debit/Outgoing
 * - Π (Πίστωση) = Credit/Incoming
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

    // Transaction type indicators (Greek bank codes)
    private val DEBIT_CODES = listOf("Χ", "ΧΡ", "ΧΡΕ", "ΧΡΕΩΣΗ", "DEBIT")
    private val CREDIT_CODES = listOf("Π", "ΠΙ", "ΠΙΣ", "ΠΙΣΤΩΣΗ", "CREDIT")

    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT" or "Πληρωμή €6.30 σε..."
        // Also handles: "Πληρώσατε €7,50 από την κάρτα *1554 σε BOX FOOD APP"
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρ[ώω]σ?(?:ατε|μ[ήη])?|payment|purchase)\s+(?:[€$£]|EUR|USD|GBP)?\s*(\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(?:EUR|€|USD|GBP)?\s*(?:απ[όο]\s+τ[ηι]ν?\s+κ[άα]ρτ[αά]\s*[*0-9]*\s*)?(?:στ[οη]ν?|σε|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        Pattern.compile(
            """([€$£])\s*(\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(?:at|στ[οη]ν?|σε|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "MERCHANT 12,50 EUR"
        Pattern.compile(
            """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)(\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(EUR|€)?\s*[-–]\s*(.+)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    private val DEPOSIT_PATTERNS = listOf(
        // Greek deposit keywords
        Pattern.compile("""(?:κατάθεση|πίστωση|μισθοδοσία|καταθέσεις|πιστώσεις|επιστροφή)[\p{L}]*\s*[€$£]?\s*(\d+(?:[.,]\d{1,2})?)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        // English deposit keywords
        Pattern.compile("""(?:deposit|credited|received|transfer\s*received|incoming)[\p{L}]*\s*[€$£]?\s*(\d+(?:[.,]\d{1,2})?)""", Pattern.CASE_INSENSITIVE),
        // Salary patterns
        Pattern.compile("""(?:μισθ[όό]ς|salary|wages)[\p{L}]*\s*[€$£]?\s*(\d+(?:[.,]\d{1,2})?)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        // Amount followed by deposit keywords
        Pattern.compile("""(\d+(?:[.,]\d{1,2})?)\s*[€$£]?\s*(?:κατάθεση|πίστωση|deposit|credited)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    )
    
    // Transfer patterns (specific to bank transfers, not purchases)
    private val TRANSFER_PATTERNS = listOf(
        // "Μεταφορά 100€ σε Λογαριασμό" (Transfer to account)
        Pattern.compile(
            """(?:μεταφορ[άα]|μεταφορά|transfer)\s*(?:[€$£])?\s*(\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(?:EUR|€|σε|to|στον?)?\s*(.+)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Transaction code patterns: "Χ 50,00" or "Π 100,00"
        Pattern.compile(
            """(?:^|\s)([ΧΠXDP])\s*[:\-\s]?\s*(\d+(?:[.,]\d{1,2})?)\s*(?:EUR|€)?\s*(.*)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Format variations: "50,00 € Χρέωση" or "Πίστωση 100.00 EUR" (statement-style)
        Pattern.compile(
            """(\d+(?:[.,]\d{1,2})?)\s*[€$£]?\s*(?:EUR|USD|GBP)?\s*(?:χρ[έε]ωση|π[ίι]στωση|ανάληψη|κατάθεση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // "€12,50 στο MERCHANT" (amount first, then location - common Greek format)
        Pattern.compile(
            """[€$£]?\s*(\d+(?:[.,]\d{1,2})?)\s*(?:EUR|€)?\s*(?:στ[οη]ν?|σε|at)\s+(.+?)(?:\s*(?:με|with)|$)""",
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

            // Finally try explicit transfer patterns
            for (pattern in TRANSFER_PATTERNS) {
                val matcher = pattern.matcher(field)
                if (matcher.find()) {
                    val result = tryExtractTransfer(matcher, field)
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
            if (looksLikeAmountToken(group)) {
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
            
            if (looksLikeAmountToken(group)) {
                amountStr = group
            } else if (group.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
            }
        }

        val amount = amountStr?.let { AmountUtils.parseAmount(it) } ?: return null
        if (amount < 0.01 || amount > 50000) return null

        // For deposits, merchant is usually the sender (bank/employer)
        val merchant = extractDepositSource(fullText)
        
        // Detect direction based on keywords
        val normalizedText = fullText.lowercase()
        val direction = when {
            normalizedText.contains(" σε ") || normalizedText.contains(" προς ") || normalizedText.contains(" to ") ->
                TransferDirection.OUTGOING
            normalizedText.contains(" από ") || normalizedText.contains(" απο ") || normalizedText.contains(" from ") ->
                TransferDirection.INCOMING
            else -> detectGreekDirection(fullText)
        }
        
        // Determine if it's a transfer or deposit based on context
        val isTransfer = fullText.contains(Regex("""(?:μεταφορ[άα]|transfer|από\s+λογαρ)""", RegexOption.IGNORE_CASE))

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = if (isTransfer) TransactionType.TRANSFER else TransactionType.DEPOSIT,
            confidence = 0.90f,
            transferDirection = direction ?: TransferDirection.INCOMING,  // Default to incoming for deposits
            transferAccountName = direction?.let { 
                when (it) {
                    TransferDirection.INCOMING -> "From: $merchant"
                    TransferDirection.OUTGOING -> "To: $merchant"
                }
            }
        )
    }

    private fun tryExtractTransfer(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        var amountStr: String? = null
        var currency = "EUR"
        var merchant = "Transfer"

        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i)?.trim().orEmpty()
            if (group.isEmpty()) continue

            if (looksLikeAmountToken(group)) {
                amountStr = group
                continue
            }

            if (group.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
                currency = currencyNormalizer.normalize(group)
                continue
            }

            // Single-letter bank code group (Χ/Π) is direction metadata, not merchant.
            if (group.length == 1 && (group.equals("Χ", true) || group.equals("Π", true) || group.equals("D", true) || group.equals("C", true))) {
                continue
            }

            if (group.length > 2) {
                merchant = merchantCleaner.clean(group)
            }
        }

        val amount = amountStr?.let { AmountUtils.parseAmount(it) } ?: return null
        if (amount < 0.01 || amount > 50000) return null

        val direction = detectGreekDirection(fullText)
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.TRANSFER,
            confidence = 0.9f,
            transferDirection = direction,
            transferAccountName = direction?.let {
                when (it) {
                    TransferDirection.INCOMING -> "From: $merchant"
                    TransferDirection.OUTGOING -> "To: $merchant"
                }
            }
        )
    }

    private fun looksLikeAmountToken(value: String): Boolean {
        val token = value.trim()
        if (token.none { it.isDigit() }) return false
        return AmountUtils.parseAmount(token) != null
    }
    
    /**
     * Detects transfer direction from Greek bank notification text.
     * Uses transaction codes and keywords.
     */
    private fun detectGreekDirection(text: String): TransferDirection? {
        val upperText = text.uppercase()
        
        // Check for debit codes (outgoing)
        if (DEBIT_CODES.any { code -> 
            upperText.contains(" $code ") || 
            upperText.startsWith("$code ") ||
            upperText.contains(Regex("""\b$code[\s:.-]"""))
        }) {
            return TransferDirection.OUTGOING
        }
        
        // Check for credit codes (incoming)
        if (CREDIT_CODES.any { code -> 
            upperText.contains(" $code ") || 
            upperText.startsWith("$code ") ||
            upperText.contains(Regex("""\b$code[\s:.-]"""))
        }) {
            return TransferDirection.INCOMING
        }
        
        // Check keywords
        return when {
            // Outgoing keywords
            text.contains(Regex("""(?:χρ[έε]ωση|χρεώθηκε|χρεωστικό|μεταφορά\s+σε|sent\s+to|transfer\s+to)""", RegexOption.IGNORE_CASE)) ->
                TransferDirection.OUTGOING
            
            // Incoming keywords
            text.contains(Regex("""(?:πίστωση|πιστώθηκε|πιστωτικό|μεταφορά\s+από|received\s+from|transfer\s+from)""", RegexOption.IGNORE_CASE)) ->
                TransferDirection.INCOMING
            
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
