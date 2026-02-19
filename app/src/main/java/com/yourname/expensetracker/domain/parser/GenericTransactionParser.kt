package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.AppConstants
import java.util.regex.Pattern

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
class GenericTransactionParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {
    // Strong signals that this is a REAL transaction notification
    private val strongTransactionSignals by lazy {
        listOf(
            // English patterns that strongly indicate actual transactions
            Pattern.compile("""(?:you\s+)?paid\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""payment\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""charged?\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:debit|deducted)\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transaction\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek patterns - using UNICODE_CASE and \p{L} for Greek letters
            Pattern.compile("""(?:πληρω|χρεω|αγορ[αά])[\p{L}]*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:στ[οη]|at)\s""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Greeklish patterns
            Pattern.compile("""(?:pliromi|xreosi|hreosi|agora)\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:sto|se|stin?)\s""", Pattern.CASE_INSENSITIVE),
        )
    }

    // Deposit/Income signals - HIGH PRIORITY (checked first)
    private val depositSignals by lazy {
        listOf(
            // English deposit patterns
            Pattern.compile("""(?:deposit|credited|received|incoming|transfer\s*received)[\p{L}]*\s*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek deposit patterns
            Pattern.compile("""(?:κατάθεση|πίστωση|μισθοδοσία|επιστροφή)[\p{L}]*\s*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Salary patterns
            Pattern.compile("""(?:salary|wages|μισθ[όό]ς)[\p{L}]*\s*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        )
    }

    // NEGATIVE signals — if present, this is NOT a transaction
    // Using Regex to enforce word boundaries for English words to avoid "Coffee" matching "offer"
    private val negativeSignalsPattern by lazy {
        Pattern.compile(
            """\b(offer|discount|save\s+up\s+to|earn|free|up\s+to|starting\s+from|balance|otp|verification|code|unsubscribe|opt\s+out|sale|%\s+off|promo|your\s+order|tracking|shipped|delivered|reminder|rate\s+us|review|survey)\b|""" +
            """(προσφορά|έκπτωση|εξοικονομ|κέρδισε|δωρεάν|έως|από|υπόλοιπο|κωδικός|υπενθύμιση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private val amountPattern by lazy {
        com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX
    }

    private val MERCHANT_PREFIXES = listOf(" at ", " to ", " σε ", " στον ", " στην ", " στο ", " για ", " sto ", " ston ", " stin ", " se ")

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        // 1. Check negative signals first
        if (negativeSignalsPattern.matcher(lowerFull).find()) return null

        // 2. Check for DEPOSIT signals FIRST (high priority)
        val hasDepositSignal = depositSignals.any { it.matcher(lowerFull).find() }

        // 3. If not deposit, require at least one STRONG transaction signal
        if (!hasDepositSignal) {
            val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
            if (!hasStrongSignal) return null
        }

        // 4. Extract amount
        val amountResult = extractAmount(fullText) ?: return null

        // 5. Sanity check amount
        if (amountResult.first < AppConstants.Parser.MIN_VALID_AMOUNT || amountResult.first > AppConstants.Parser.MAX_GENERIC_PARSER_AMOUNT) return null

        // 6. Extract merchant
        val merchant = extractMerchant(fullText, title)

        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = if (hasDepositSignal) TransactionType.DEPOSIT else TransactionType.PURCHASE,
            confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION // LOGIC-004
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val currency = matcher.group(1) ?: matcher.group(3) ?: "€"
            val amountStr = matcher.group(2) ?: return null
            val amount = AmountUtils.parseAmount(amountStr) ?: return null
            return Pair(amount, currencyNormalizer.normalize(currency))
        }
        return null
    }

    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return merchantCleaner.clean(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return merchantCleaner.clean(title)
        }
        return "Unknown"
    }

    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }
}
