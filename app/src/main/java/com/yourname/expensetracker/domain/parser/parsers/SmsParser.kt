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
class SmsParser : AppNotificationParser {

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
        // For SMS, the title is usually the sender
        val sender = title?.lowercase() ?: return null
        val body = listOfNotNull(text, bigText).joinToString(" ")
        val lowerBody = body.lowercase()

        // Only process if sender looks like a bank
        val isBankSms = BANK_SENDERS.any { sender.contains(it) }
        if (!isBankSms) return null

        // Must contain transaction keywords
        val hasKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        if (!hasKeyword) return null

        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null

        val amountStr = (matcher.group(1) ?: matcher.group(4))?.replace(",", ".") ?: return null
        val amount = amountStr.toDoubleOrNull() ?: return null
        val currency = normalizeCurrency(matcher.group(2) ?: matcher.group(3))

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
            Pattern.compile("""(?:στ[οη]ν?|at|sto|stin?|ston?|se|sta)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
    }

    private fun extractMerchantFromSms(body: String): String {
        for (p in merchantPatterns) {
            val m = p.matcher(body)
            if (m.find()) {
                val raw = m.group(1) ?: continue
                return cleanMerchant(raw)
            }
        }
        return "Unknown"
    }

    private fun cleanMerchant(raw: String): String {
        return raw.trim()
            .replace(Regex("""\s\d{2}:\d{2}.*$"""), "") // Remove time like 12:30
            .replace(Regex("""\s\d{1,2}/\d{1,2}.*$"""), "") // Remove date like 12/05
            .replace(Regex("""\s(?:στις|at|on)\s+\d.*$""", RegexOption.IGNORE_CASE), "") // Remove "at 12..." or "στις 12..."
            .replace(Regex("""[.!;]$"""), "") // Remove trailing punctuation
            .take(30)
            .trim()
    }

    private fun normalizeCurrency(raw: String?): String {
        return when (raw?.uppercase()?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}
