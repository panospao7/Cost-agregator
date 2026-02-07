package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.regex.Pattern

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
class GenericTransactionParser {

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
        Pattern.compile(
            """([€$£])\s*(\d+(?:[.,]\d{2})?)|(\d+(?:[.,]\d{2})?)\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
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

        // 2. Require at least one STRONG transaction signal
        val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
        if (!hasStrongSignal) return null

        // 3. Extract amount
        val amountResult = extractAmount(fullText) ?: return null

        // 4. Sanity check amount
        if (amountResult.first < 0.10 || amountResult.first > 25000) return null

        // 5. Extract merchant
        val merchant = extractMerchant(fullText, title)

        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.60f // Lower confidence for generic parser
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val currency = matcher.group(1) ?: matcher.group(4) ?: "€"
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            return Pair(amount, normalizeCurrency(currency))
        }
        return null
    }

    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return cleanMerchant(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return cleanMerchant(title)
        }
        return "Unknown"
    }

    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }

    private fun cleanMerchant(raw: String): String {
        val stopWords = listOf("confirmed", "successful", "completed", "declined",
            "ολοκληρώθηκε", "επιτυχής", ".", "!", "with card", "με κάρτα")
        var candidate = raw
        for (stop in stopWords) {
            val idx = candidate.indexOf(stop, ignoreCase = true)
            if (idx != -1) candidate = candidate.substring(0, idx)
        }
        return candidate.trim().take(40).trim()
    }

    private fun normalizeCurrency(raw: String): String {
        return when (raw.uppercase().trim()) {
            "€", "EUR", "ΕΥΡΩ" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}
