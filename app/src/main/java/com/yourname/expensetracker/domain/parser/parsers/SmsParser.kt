package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import java.util.regex.Pattern

/**
 * Handles SMS from banking apps (Google Messages, Samsung Messages, etc.)
 * These are forwarded notifications from SMS — needs very careful filtering
 * because messaging apps send ALL messages.
 */
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CommonPatterns
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

class SmsParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )

    // Known bank SMS sender IDs
    private val BANK_SENDERS = setOf(
        "nbg", "alpha", "eurobank", "piraeus", "winbank",
        "revolut", "paypal", "visa", "mastercard",
        "ethniki", "εθνική", "αλφα", "πειραιώς"
    )

    private val amountPattern by lazy {
        val amt = CommonPatterns.GROUPED_AMOUNT_TOKEN
        // Word-like currencies are boundary-checked so "ΕΥΡΩΠΑΙΚΗ" / "EUROPE"-style words are not
        // read as a currency. The amount may follow a single separator ("SHOP,12.50") but not a
        // digit-separator pair ("15.11" / "15,11").
        val cur = """EURO(?!\p{L})|EUR|ΕΥΡΩ(?!\p{L})|ευρώ(?!\p{L})|€|USD|\$|GBP|£"""
        Pattern.compile(
            """(?<!\d)(?<!\d[.,])($amt)(?!\d)\s*($cur)|($cur)\s*(?<!\d)(?<!\d[.,])($amt)(?!\d)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    // Matched as whole tokens (case-insensitive via lowercased text; accented and unaccented
    // Greek forms are both listed). Abbreviations carry no trailing dot. The dot is a clause
    // separator in isAdjacentToKeyword, so a dot directly after an abbreviation token ("Bal.",
    // "Υπόλ.") is collapsed first via abbreviationDotRegex (prefix path); the trailing path
    // already cuts at the dot, leaving the bare token.
    private val abbreviationDotRegex =
        Regex("""(?<![\p{L}\p{N}])(bal|avl|avail|υπόλ|υπολ)\.""")

    private val BALANCE_KEYWORDS = listOf(
        "balance", "available", "avail", "avl", "bal", "remaining", "limit", "saldo",
        "υπόλοιπο", "υπολοιπο", "υπόλ", "υπολ", "διαθέσιμο", "διαθεσιμο",
        "ypoloipo", "ipolipo", "diathesimo"
    )

    private val PURCHASE_PREFERENCE_KEYWORDS = listOf(
        "purchase", "spent", "payment", "paid", "charged", "debit",
        "αγορά", "αγορα", "χρέωση", "χρεωση", "πληρωμή", "πληρωμη",
        "συναλλαγή", "συναλλαγη",
        "agora", "xreosi", "hreosi", "pliromi", "plirwmi", "synallagi",
        "compra", "pago", "cargo"
    )

    // Trailing-keyword form ("EUR 500.00 balance"), built from BALANCE_KEYWORDS (longest first).
    private val trailingBalanceRegex by lazy {
        val alternation = BALANCE_KEYWORDS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("""^\s*(?:in|of|for|:)?\s*(?:avail(?:able)?\s+|διαθέσιμο\s+|διαθεσιμο\s+|diathesimo\s+)?(?:$alternation)(?![\p{L}\p{N}])""")
    }

    private val TRANSACTION_KEYWORDS = listOf(
        // Purchase keywords. "paid"/"spent" are intentionally absent (they match "prepaid" and
        // "paid by" in deposits). The unaccented stem "χρεωσ" is intentionally absent too: it
        // matches "χρεωστική" (debit card); all-caps "ΧΡΕΩΣΗ" is covered by a token-bounded
        // check in parse().
        "αγορ", "πληρωμ", "χρέωσ", "συναλλαγ",
        "purchase", "payment", "charged", "debit",
        "agora", "pliromi", "plirwmi", "hreosi", "xreosi", "synallagi"
    )

    private val DEPOSIT_KEYWORDS = listOf(
        // Greek deposit keywords
        "κατάθεση", "πίστωση", "μισθοδοσία", "καταθέσεις", "πιστώσεις",
        // English deposit keywords
        "deposit", "credited", "received", "incoming", "transfer received",
        // Greek salary/income
        "μισθός", " salary", "wages", "επιστροφή", "refund"
    )

    private val TRANSFER_KEYWORDS = listOf(
        // English transfer keywords
        "transfer", "transferred", "sent to",
        // Greek/Greeklish transfer keywords
        "μεταφορ", "εμβασ", "metafor", "embasma"
    )
    
    // Direction detection patterns
    private val INCOMING_PATTERNS = listOf(
        "received from", "credited by", "deposit from", "transfer from",
        "sent to you", "credited to", "incoming transfer",
        "ελήφθη από", "πιστώθηκε από", "κατάθεση από", "μεταφορά από"
    )
    
    private val OUTGOING_PATTERNS = listOf(
        "sent to", "transferred to", "payment to", "withdrawal to",
        "paid to", "outgoing transfer", "transfer to",
        "απεστάλη σε", "μεταφορά σε", "πληρωμή σε", "ανάληψη"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        @Suppress("UNUSED_PARAMETER") subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Fix (BUG-011): Handle null title by looking for sender in body or skipping if body looks like bank SMS
        val sender = title?.lowercase() ?: ""
        val body = listOfNotNull(text, bigText).joinToString(" ")
        val lowerBody = body.lowercase()
        
        // Sender check - either from title or start of body (e.g., "From: NBG")
        val isBankSms = BANK_SENDERS.any { 
            sender.contains(it) || lowerBody.startsWith(it) || lowerBody.contains("from: $it")
        }
        
        if (!isBankSms) return null


        // Must contain transaction keywords (purchase, deposit OR transfer)
        val hasPurchaseKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) } ||
            containsKeywordToken(lowerBody, listOf("χρεωση"))
        val hasDepositKeyword = DEPOSIT_KEYWORDS.any { lowerBody.contains(it) }
        val hasTransferKeyword = TRANSFER_KEYWORDS.any { lowerBody.contains(it) }
        
        if (!hasPurchaseKeyword && !hasDepositKeyword && !hasTransferKeyword) return null

        // Determine transaction type precedence: transfer > deposit > purchase
        val transactionType = when {
            hasTransferKeyword -> ParsedTransactionType.TRANSFER
            hasDepositKeyword && !hasPurchaseKeyword -> ParsedTransactionType.DEPOSIT
            else -> ParsedTransactionType.PURCHASE
        }

        // Extract amount (rejecting balance-adjacent numbers and preferring purchase-adjacent ones)
        val extracted = extractBestAmount(body) ?: return null
        val amount = extracted.amount
        val currency = extracted.currency

        if (amount < 0.10 || amount > 50000) return null

        // Try to extract merchant from the SMS body
        val merchant = extractMerchantFromSms(body)
        
        // Detect transfer direction for deposits/transfers
        val direction = detectSmsDirection(lowerBody, transactionType)
        val accountName = extractAccountNameFromSms(body)

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = transactionType,
            confidence = if (extracted.isAmbiguous) AMBIGUOUS_AMOUNT_CONFIDENCE else CLEAN_AMOUNT_CONFIDENCE,
            transferDirection = direction,
            transferAccountName = accountName?.let { 
                when (direction) {
                    ParsedTransferDirection.INCOMING -> "From: $it"
                    ParsedTransferDirection.OUTGOING -> "To: $it"
                    else -> null
                }
            },
            isAmountAmbiguous = extracted.isAmbiguous
        )
    }

    private data class AmountCandidate(
        val amount: Double,
        val currency: String,
        val start: Int,
        val end: Int
    )

    private data class ExtractedAmount(
        val amount: Double,
        val currency: String,
        val isAmbiguous: Boolean
    )

    private fun extractBestAmount(body: String): ExtractedAmount? {
        val candidates = mutableListOf<AmountCandidate>()
        val matcher = amountPattern.matcher(body)
        while (matcher.find()) {
            val amountStr = matcher.group(1) ?: matcher.group(4) ?: continue
            val amount = AmountUtils.parseAmount(amountStr) ?: continue
            val rawCurrency = matcher.group(2) ?: matcher.group(3)
            val normalizedCurrency = if (
                rawCurrency?.equals("ΕΥΡΩ", ignoreCase = true) == true ||
                rawCurrency?.equals("ευρώ", ignoreCase = true) == true
            ) {
                "EUR"
            } else {
                currencyNormalizer.normalize(rawCurrency)
            }
            candidates.add(AmountCandidate(amount, normalizedCurrency, matcher.start(), matcher.end()))
        }

        // Distinct (amount, currency), first occurrence kept: text duplicated into bigText
        // ("text + bigText") must stay a single clean amount.
        val distinct = candidates.distinctBy { it.amount to it.currency }
        val hadMultipleCandidates = distinct.size > 1
        val nonBalance = distinct.filterNot {
            isAdjacentToKeyword(body, it.start, it.end, BALANCE_KEYWORDS, allowTrailingKeyword = true)
        }
        if (nonBalance.isEmpty()) {
            // Every candidate looks balance-adjacent. Keep a lone candidate only when a purchase or
            // deposit keyword precedes it ("Purchase at BAL HARBOUR EUR 25.00"), and route it to review.
            val only = distinct.singleOrNull() ?: return null
            return if (hasPurchaseOrDepositContext(body, only)) {
                ExtractedAmount(only.amount, only.currency, isAmbiguous = true)
            } else {
                null
            }
        }
        if (nonBalance.size == 1) {
            val single = nonBalance.single()
            return ExtractedAmount(single.amount, single.currency, isAmbiguous = hadMultipleCandidates)
        }

        val purchaseAdjacent = nonBalance.filter { isAdjacentToKeyword(body, it.start, it.end, PURCHASE_PREFERENCE_KEYWORDS) }
        if (purchaseAdjacent.size == 1) {
            val chosen = purchaseAdjacent.single()
            return ExtractedAmount(chosen.amount, chosen.currency, isAmbiguous = true)
        }

        // Ambiguous: multiple plausible currency amounts remain. Take the FIRST one (the
        // transaction amount precedes the balance in bank SMS); ConfidenceRouter demotes the
        // ambiguous result to review.
        val pool = if (purchaseAdjacent.isNotEmpty()) purchaseAdjacent else nonBalance
        val chosen = pool.first()
        return ExtractedAmount(chosen.amount, chosen.currency, isAmbiguous = hadMultipleCandidates)
    }

    // Prefix-clause keyword context for a lone balance-adjacent candidate. A balance keyword attached
    // directly to the amount ("Payment info: balance EUR 500.00") outranks a farther purchase
    // keyword; a balance-like word followed by other words ("BAL HARBOUR EUR 25.00") does not.
    private fun hasPurchaseOrDepositContext(text: String, candidate: AmountCandidate): Boolean {
        val clause = prefixClauseOf(text, candidate.start)
        if (Regex("""\d""").containsMatchIn(clause)) return false
        val keywords = PURCHASE_PREFERENCE_KEYWORDS + DEPOSIT_KEYWORDS.map { it.trim() }
        if (!containsKeywordToken(clause, keywords)) return false
        val balanceAlternation = BALANCE_KEYWORDS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val directBalance = Regex("""(?<![\p{L}\p{N}])(?:$balanceAlternation)[\s:=]*$""")
        return !directBalance.containsMatchIn(clause)
    }

    private fun prefixClauseOf(text: String, matchStart: Int): String {
        val prefixStart = (matchStart - KEYWORD_ADJACENCY_WINDOW).coerceAtLeast(0)
        val prefix = text.substring(prefixStart, matchStart).lowercase()
        return prefix.replace(abbreviationDotRegex, "$1")
            .substringAfterLast('.').substringAfterLast(';').substringAfterLast(',')
            .substringAfterLast('\n').substringAfterLast('(').substringAfterLast('-')
    }

    private fun isAdjacentToKeyword(
        text: String,
        matchStart: Int,
        matchEnd: Int,
        keywords: List<String>,
        allowTrailingKeyword: Boolean = false
    ): Boolean {
        val lastClause = prefixClauseOf(text, matchStart)
        if (!Regex("""\d""").containsMatchIn(lastClause) && containsKeywordToken(lastClause, keywords)) {
            return true
        }

        if (allowTrailingKeyword) {
            val suffixEnd = (matchEnd + KEYWORD_ADJACENCY_WINDOW).coerceAtMost(text.length)
            val suffix = text.substring(matchEnd, suffixEnd).lowercase()
            val nextClause = suffix.substringBefore('.').substringBefore(';').substringBefore(',')
                .substringBefore('\n').substringBefore(')').substringBefore('-')
            // A digit in the next clause means the trailing keyword belongs to the NEXT amount
            // ("Purchase EUR 12.50 Balance EUR 500.00").
            if (!Regex("""\d""").containsMatchIn(nextClause) && trailingBalanceRegex.containsMatchIn(nextClause)) {
                return true
            }
        }
        return false
    }

    private fun containsKeywordToken(clause: String, keywords: List<String>): Boolean {
        return keywords.any { kw ->
            Regex("""(?<![\p{L}\p{N}])${Regex.escape(kw)}(?![\p{L}\p{N}])""").containsMatchIn(clause)
        }
    }

    private companion object {
        private const val CLEAN_AMOUNT_CONFIDENCE = 0.85f
        // Equal to the clean confidence: ConfidenceRouter caps ambiguous amounts below
        // AUTO_ACCEPT so multi-amount SMS land in review without a parser-side floor/penalty.
        private const val AMBIGUOUS_AMOUNT_CONFIDENCE = 0.85f
        private const val KEYWORD_ADJACENCY_WINDOW = 35
    }
    
    /**
     * Detects transfer direction from SMS text.
     * Returns null for ambiguous cases (tie/no evidence) to avoid biasing unknown transfers.
     */
    private fun detectSmsDirection(text: String, transactionType: ParsedTransactionType): ParsedTransferDirection? {
        if (transactionType != ParsedTransactionType.DEPOSIT && 
            transactionType != ParsedTransactionType.TRANSFER) {
            return null
        }
        
        val incomingScore = INCOMING_PATTERNS.count { text.contains(it) }
        val outgoingScore = OUTGOING_PATTERNS.count { text.contains(it) }
        
        return when {
            incomingScore > outgoingScore -> ParsedTransferDirection.INCOMING
            outgoingScore > incomingScore -> ParsedTransferDirection.OUTGOING
            // Ambiguous: no evidence or tie — return null instead of defaulting
            else -> null
        }
    }
    
    /**
     * Extracts account/counterparty name from SMS.
     */
    private fun extractAccountNameFromSms(body: String): String? {
        val patterns = listOf(
            Pattern.compile("""(?:from|από)[:\s]+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:to|σε)[:\s]+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(body)
            if (matcher.find()) {
                return matcher.group(1)?.trim()?.let { merchantCleaner.clean(it) }
            }
        }
        return null
    }

    private val merchantPatterns by lazy {
        listOf(
            // Logic: look for STO/AT/etc, then capture until common delimiters like "στις", "on", "at", or date-like patterns
            // Use non-greedy match to stop at the first delimiter.
            Pattern.compile("""(?:στ[οη]ν?|at|sto|stin?|ston?|se|sta)\s+(.+?)(?:\s+(?:στις|on|at|stis|athens|at-|\d{1,2}[/.-])|$)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
    }

    private fun extractMerchantFromSms(body: String): String {
        for (p in merchantPatterns) {
            val m = p.matcher(body)
            if (m.find()) {
                val raw = m.group(1) ?: continue
                return merchantCleaner.clean(raw)
            }
        }
        return "Unknown"
    }
}
