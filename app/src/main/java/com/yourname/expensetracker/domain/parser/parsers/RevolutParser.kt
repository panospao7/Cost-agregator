package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern

class RevolutParser : AppNotificationParser {

    override val supportedPackages = setOf("com.revolut.revolut")

    // Revolut notifications typically look like:
    // Title: "Paid €12.50" or "💳 €12.50 at SKLAVENITIS"
    // Text: "💳 €12.50 at SKLAVENITIS" or "You paid €5.00 to John"
    // Also: "Received €100.00 from John"
    // Also: "ATM withdrawal: €50.00"
    // Ignore: "Your exchange rate...", "Weekly report", "Special offer"

    private val PAID_PATTERN = Pattern.compile(
        """(?:paid|sent|💳)\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:at|to)\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val RECEIVED_PATTERN = Pattern.compile(
        """received\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val ATM_PATTERN = Pattern.compile(
        """(?:atm|withdrawal)[:\s]*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})""",
        Pattern.CASE_INSENSITIVE
    )

    // Patterns to REJECT (not transactions)
    private val REJECT_PATTERNS = listOf(
        "exchange rate", "weekly report", "special offer", "cashback",
        "refer a friend", "upgrade", "verify", "security", "pin",
        "top-up reminder", "price alert", "savings vault"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Check each field individually to avoid "doubling" content which confuses greedy regex
        val candidates = listOfNotNull(title, text, bigText)

        for (content in candidates) {
            val lower = content.lowercase()
            if (REJECT_PATTERNS.any { lower.contains(it) }) return null

            // Try paid/purchase pattern
            val paidMatcher = PAID_PATTERN.matcher(content)
            if (paidMatcher.find()) {
                val currency = normalizeCurrency(paidMatcher.group(1))
                val amount = paidMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
                val merchant = cleanMerchant(paidMatcher.group(3) ?: "Unknown")
                return ParsedTransaction(amount, currency, merchant, TransactionType.PURCHASE, 0.95f)
            }

            // Try received pattern
            val receivedMatcher = RECEIVED_PATTERN.matcher(content)
            if (receivedMatcher.find()) {
                val currency = normalizeCurrency(receivedMatcher.group(1))
                val amount = receivedMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
                val merchant = cleanMerchant(receivedMatcher.group(3) ?: "Unknown")
                return ParsedTransaction(amount, currency, merchant, TransactionType.DEPOSIT, 0.90f)
            }

            // Try ATM pattern
            val atmMatcher = ATM_PATTERN.matcher(content)
            if (atmMatcher.find()) {
                val currency = normalizeCurrency(atmMatcher.group(1))
                val amount = atmMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
                return ParsedTransaction(amount, currency, "ATM", TransactionType.WITHDRAWAL, 0.95f)
            }
        }

        return null
    }

    private fun cleanMerchant(raw: String): String {
        return raw.trim()
            .replace(Regex("[.!]$"), "")
            .take(40)
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
