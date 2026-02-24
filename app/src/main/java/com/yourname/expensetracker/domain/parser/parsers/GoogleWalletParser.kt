package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern

import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

class GoogleWalletParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    override val supportedPackages = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user"
    )

    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£E])\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*([€$£E]|EUR|USD|GBP)|([€$£E]\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val atPattern by lazy {
        Pattern.compile("""(?:at|to)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]+)""", Pattern.CASE_INSENSITIVE)
    }

    // Things that are NOT transactions
    private val REJECT_PATTERNS = listOf(
        "add a card", "set up", "tap to pay", "loyalty", "offer",
        "reward", "cashback available", "nearby", "suggest"
    )

    // Deposit keywords for Google Pay
    private val DEPOSIT_KEYWORDS = listOf(
        "received", "credited", "deposit", "incoming transfer",
        "sent to you", "paid you", "money received"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Fix encoding issues: € symbol sometimes becomes E before digits
        var fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        fullText = fullText.replace(Regex("""E(\d)""")) { "€${it.groupValues[1]}" }
        val lowerFull = fullText.lowercase()

        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null

        // Determine if this is a deposit or purchase
        val isDeposit = DEPOSIT_KEYWORDS.any { lowerFull.contains(it) }

        // Extract amount from anywhere in the notification
        val amount = extractAmount(fullText) ?: return null

        // Extract merchant: usually the title IS the merchant, or text contains "at MERCHANT"
        val merchant = extractMerchant(title, text, bigText, isDeposit)

        return ParsedTransaction(
            amount = amount.first,
            currency = amount.second,
            merchant = merchant,
            type = if (isDeposit) TransactionType.DEPOSIT else TransactionType.PURCHASE,
            confidence = 0.90f
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            // Group 1: currency prefix with space (€ 8.00)
            // Group 2: amount after space
            // Group 3: amount before currency
            // Group 4: currency suffix  
            // Group 5: currency + amount no space (€8.00 or E8.00)
            val prefixCurrency = matcher.group(1) ?: matcher.group(4) ?: (matcher.group(5)?.firstOrNull()?.toString())
            val amountStr = (matcher.group(2) ?: matcher.group(3) ?: matcher.group(5)?.drop(1)) ?: return null
            val amount = AmountUtils.parseAmount(amountStr) ?: return null
            // Filter unrealistic amounts
            if (amount < 0.01 || amount > 50000) return null
            return Pair(amount, currencyNormalizer.normalize(prefixCurrency))
        }
        return null
    }

    private fun extractMerchant(title: String?, text: String?, bigText: String?, isDeposit: Boolean): String {
        // For deposits, extract sender instead of merchant
        if (isDeposit) {
            val combinedText = listOfNotNull(title, text, bigText).joinToString(" ")
            // Look for "from SENDER" pattern
            val fromPattern = Pattern.compile("""(?:from|απ[όο])\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{3,30})""", Pattern.CASE_INSENSITIVE)
            val fromMatcher = fromPattern.matcher(combinedText)
            if (fromMatcher.find()) {
                return merchantCleaner.clean(fromMatcher.group(1))
            }
            // Default to "Google Pay" for deposits
            return "Google Pay"
        }

        // Check for "at MERCHANT" pattern in text
        val combinedText = listOfNotNull(text, bigText).joinToString(" ")
        val atMatcher = atPattern.matcher(combinedText)
        if (atMatcher.find()) {
            return merchantCleaner.clean(atMatcher.group(1))
        }

        // Title might be the merchant if it doesn't contain amount/payment keywords
        if (!title.isNullOrBlank()) {
            val lowerTitle = title.lowercase()
            val isAmount = amountPattern.matcher(title).find()
            val isKeyword = listOf("payment", "purchase", "paid", "transaction", "google wallet", "wallet").any { lowerTitle.contains(it) }
            
            if (!isAmount && !isKeyword) {
                return merchantCleaner.clean(title)
            }
        }

        return "Unknown"
    }
}
