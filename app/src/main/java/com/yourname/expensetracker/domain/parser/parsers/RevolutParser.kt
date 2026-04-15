package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CommonPatterns
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Parser for Revolut app notifications.
 * 
 * Supports:
 * - Purchases: "Paid €12.50 at SKLAVENITIS"
 * - Transfers (outgoing): "You paid €5.00 to John"
 * - Deposits (incoming): "Received €100.00 from John"
 * - ATM withdrawals: "ATM withdrawal: €50.00"
 * - Revolut-to-Revolut transfers
 */
class RevolutParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf("com.revolut.revolut")

    // Revolut notifications typically look like:
    // Title: "Paid €12.50" or "💳 €12.50 at SKLAVENITIS"
    // Text: "💳 €12.50 at SKLAVENITIS" or "You paid €5.00 to John"
    // Also: "Received €100.00 from John"
    // Also: "ATM withdrawal: €50.00"
    // Ignore: "Your exchange rate...", "Weekly report", "Special offer"

    // Shared grouped-amount fragment supports thousands-separated amounts (e.g. 1,234.56 / 1.234,56)
    private val AMT = CommonPatterns.GROUPED_AMOUNT_TOKEN

    private val PAID_AT_PATTERN = Pattern.compile(
        """(?:paid|sent|💳)\s*([€$£]|EUR|USD|GBP)?\s*($AMT)\s*at\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    
    private val PAID_TO_PATTERN = Pattern.compile(
        """(?:you\s+)?(?:paid|sent)\s*([€$£]|EUR|USD|GBP)?\s*($AMT)\s*to\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val RECEIVED_PATTERN = Pattern.compile(
        """(?:received|added)\s*([€$£]|EUR|USD|GBP)?\s*($AMT)\s*(?:from\s+(.+))?""",
        Pattern.CASE_INSENSITIVE
    )

    private val ATM_PATTERN = Pattern.compile(
        """(?:atm|withdrawal)[:\s]*([€$£]|EUR|USD|GBP)?\s*($AMT)""",
        Pattern.CASE_INSENSITIVE
    )

    // Patterns to REJECT (not transactions)
    private val REJECT_PATTERNS = listOf(
        "exchange rate", "weekly report", "special offer", "cashback",
        "refer a friend", "upgrade", "verify", "security", "pin",
        "top-up reminder", "price alert", "savings vault", "subscription",
        "card delivery", "statements", "settings"
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

            // Try patterns in order of specificity
            val paidAtMatcher = PAID_AT_PATTERN.matcher(content)
            val paidToMatcher = PAID_TO_PATTERN.matcher(content)
            val receivedMatcher = RECEIVED_PATTERN.matcher(content)
            val atmMatcher = ATM_PATTERN.matcher(content)

            when {
                // 1. Purchase at merchant: "Paid €12.50 at SKLAVENITIS"
                paidAtMatcher.find() -> {
                    val amount = paidAtMatcher.group(2)?.let { AmountUtils.parseAmount(it) } ?: continue
                    val currency = currencyNormalizer.normalize(paidAtMatcher.group(1))
                    val merchant = merchantCleaner.clean(paidAtMatcher.group(3))

                    return ParsedTransaction(
                        amount = amount, 
                        currency = currency, 
                        merchant = merchant, 
                        type = ParsedTransactionType.PURCHASE, 
                        confidence = 0.95f
                    )
                }
                
                // 2. Transfer to person: "You paid €5.00 to John" or "Sent €10 to Mary"
                paidToMatcher.find() -> {
                    val amount = paidToMatcher.group(2)?.let { AmountUtils.parseAmount(it) } ?: continue
                    val currency = currencyNormalizer.normalize(paidToMatcher.group(1))
                    val recipient = merchantCleaner.clean(paidToMatcher.group(3))

                    return ParsedTransaction(
                        amount = amount, 
                        currency = currency, 
                        merchant = recipient, 
                        type = ParsedTransactionType.TRANSFER, 
                        confidence = 0.92f,
                        transferDirection = ParsedTransferDirection.OUTGOING,
                        transferAccountName = "To: $recipient"
                    )
                }
                
                // 3. Received money: "Received €100.00 from John" or "Added €50"
                receivedMatcher.find() -> {
                    val amount = receivedMatcher.group(2)?.let { AmountUtils.parseAmount(it) } ?: continue
                    val currency = currencyNormalizer.normalize(receivedMatcher.group(1))
                    val sender = receivedMatcher.group(3)?.let { merchantCleaner.clean(it) }
                    
                    // Determine if it's a transfer from someone or just an add-money
                    val isFromPerson = sender != null && sender.isNotBlank()

                    return ParsedTransaction(
                        amount = amount, 
                        currency = currency, 
                        merchant = sender ?: "Revolut", 
                        type = if (isFromPerson) ParsedTransactionType.TRANSFER else ParsedTransactionType.DEPOSIT, 
                        confidence = if (isFromPerson) 0.92f else 0.88f,
                        transferDirection = ParsedTransferDirection.INCOMING,
                        transferAccountName = sender?.let { "From: $it" }
                    )
                }
                
                // 4. ATM withdrawal: "ATM withdrawal: €50.00"
                atmMatcher.find() -> {
                    val amount = atmMatcher.group(2)?.let { AmountUtils.parseAmount(it) } ?: continue
                    val currency = currencyNormalizer.normalize(atmMatcher.group(1))
                    return ParsedTransaction(
                        amount = amount, 
                        currency = currency, 
                        merchant = "ATM Withdrawal", 
                        type = ParsedTransactionType.WITHDRAWAL, 
                        confidence = 0.95f
                    )
                }
            }
        }

        return null
    }
}
