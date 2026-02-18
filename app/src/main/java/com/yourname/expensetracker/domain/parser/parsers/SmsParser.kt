package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern

/**
 * Handles SMS from banking apps (Google Messages, Samsung Messages, etc.)
 * These are forwarded notifications from SMS — needs very careful filtering
 * because messaging apps send ALL messages.
 */
import com.yourname.expensetracker.domain.util.AmountUtils
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
        Pattern.compile(
            """(\d+[.,]\d{2})\s*(EUR|€|USD|\$|GBP|£)|(EUR|€|USD|\$|GBP|£)\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val TRANSACTION_KEYWORDS = listOf(
        "αγορ", "πληρωμ", "χρέωσ", "συναλλαγ",
        "purchase", "payment", "charged", "debit",
        "agora", "pliromi", "plirwmi", "hreosi", "xreosi", "synallagi"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
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


        // Must contain transaction keywords
        val hasKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        if (!hasKeyword) return null

        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null

        val amountStr = (matcher.group(1) ?: matcher.group(4)) ?: return null
        val amount = AmountUtils.parseAmount(amountStr) ?: return null
        val currency = currencyNormalizer.normalize(matcher.group(2) ?: matcher.group(3))

        if (amount < 0.10 || amount > 50000) return null

        // Try to extract merchant from the SMS body
        val merchant = extractMerchantFromSms(body)

        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.85f
        )
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
