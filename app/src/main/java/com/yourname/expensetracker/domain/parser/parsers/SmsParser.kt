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
        Pattern.compile(
            """($amt)\s*(EUR|€|USD|\$|GBP|£)|(EUR|€|USD|\$|GBP|£)\s*($amt)""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val TRANSACTION_KEYWORDS = listOf(
        // Purchase keywords
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
        val hasPurchaseKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        val hasDepositKeyword = DEPOSIT_KEYWORDS.any { lowerBody.contains(it) }
        val hasTransferKeyword = TRANSFER_KEYWORDS.any { lowerBody.contains(it) }
        
        if (!hasPurchaseKeyword && !hasDepositKeyword && !hasTransferKeyword) return null

        // Determine transaction type precedence: transfer > deposit > purchase
        val transactionType = when {
            hasTransferKeyword -> ParsedTransactionType.TRANSFER
            hasDepositKeyword && !hasPurchaseKeyword -> ParsedTransactionType.DEPOSIT
            else -> ParsedTransactionType.PURCHASE
        }

        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null

        val amountStr = (matcher.group(1) ?: matcher.group(4)) ?: return null
        val amount = AmountUtils.parseAmount(amountStr) ?: return null
        val currency = currencyNormalizer.normalize(matcher.group(2) ?: matcher.group(3))

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
            confidence = 0.85f,
            transferDirection = direction,
            transferAccountName = accountName?.let { 
                when (direction) {
                    ParsedTransferDirection.INCOMING -> "From: $it"
                    ParsedTransferDirection.OUTGOING -> "To: $it"
                    else -> null
                }
            }
        )
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
