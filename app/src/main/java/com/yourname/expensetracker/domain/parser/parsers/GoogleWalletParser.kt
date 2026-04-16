package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import com.yourname.expensetracker.domain.parser.TransferDirectionDetector
import java.util.regex.Pattern

import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject

class GoogleWalletParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) : AppNotificationParser {

    private val directionDetector = TransferDirectionDetector()

    override val supportedPackages = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user"
    )

    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£₹E]|EUR|USD|GBP|INR)\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*([€$£₹E]|EUR|USD|GBP|INR)""",
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

    // Non-P2P money-in wording that should remain deposits.
    private val DEPOSIT_KEYWORDS = listOf(
        "credited", "deposit", "top up", "top-up", "added money"
    )

    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Note: € symbol sometimes becomes E in notifications, and INR may appear
        // as either ₹ or INR. amountPattern handles both forms directly.
        // CurrencyNormalizer maps symbols/codes to ISO values. No global text
        // replacement needed, as it can corrupt merchant names.
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()

        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null

        // Extract amount from anywhere in the notification
        val amount = extractAmount(fullText) ?: return null

        val transferDirection = detectP2pTransferDirection(title, text, bigText, lowerFull)
        val isTransfer = transferDirection != null
        val isDeposit = !isTransfer && DEPOSIT_KEYWORDS.any { lowerFull.contains(it) }

        // Extract merchant: usually the title IS the merchant, or text contains "at MERCHANT"
        val merchant = when {
            isTransfer -> extractTransferCounterparty(title, text, bigText, transferDirection!!)
            else -> extractMerchant(title, text, bigText, isDeposit)
        }

        return ParsedTransaction(
            amount = amount.first,
            currency = amount.second,
            merchant = merchant,
            type = when {
                isTransfer -> ParsedTransactionType.TRANSFER
                isDeposit -> ParsedTransactionType.DEPOSIT
                else -> ParsedTransactionType.PURCHASE
            },
            confidence = 0.90f,
            transferDirection = transferDirection,
            transferAccountName = if (isTransfer) {
                when (transferDirection) {
                    ParsedTransferDirection.INCOMING -> "From: $merchant"
                    ParsedTransferDirection.OUTGOING -> "To: $merchant"
                }
            } else {
                null
            }
        )
    }

    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            // Group 1: currency prefix with/without space (€8.00, INR 8.00)
            // Group 2: amount after space
            // Group 3: amount before currency
            // Group 4: currency suffix
            val prefixCurrency = matcher.group(1) ?: matcher.group(4)
            val amountStr = matcher.group(2) ?: matcher.group(3) ?: return null
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

    private fun detectP2pTransferDirection(
        title: String?,
        text: String?,
        bigText: String?,
        lowerFull: String
    ): ParsedTransferDirection? {
        val explicitDirection = when {
            Regex("""\b(?:received|receive)\b.*\bfrom\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                Regex("""\b(?:sent|paid)\s+you\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                lowerFull.contains("sent to you") ||
                lowerFull.contains("money received") -> ParsedTransferDirection.INCOMING

            Regex("""\b(?:sent|send|paid)\b.*\bto\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                Regex("""\btransfer(?:red)?\b.*\bto\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) -> ParsedTransferDirection.OUTGOING

            else -> null
        }

        return explicitDirection ?: directionDetector.detectDirection(
            title = title,
            text = text,
            bigText = bigText,
            transactionType = ParsedTransactionType.TRANSFER
        )?.takeIf {
            lowerFull.contains("transfer") ||
                lowerFull.contains("sent") ||
                lowerFull.contains("received") ||
                lowerFull.contains("paid you")
        }
    }

    private fun extractTransferCounterparty(
        title: String?,
        text: String?,
        bigText: String?,
        direction: ParsedTransferDirection
    ): String {
        val combinedText = listOfNotNull(title, text, bigText).joinToString(" ")

        val detectorName = directionDetector.extractAccountName(title, text, bigText)
            ?.let { merchantCleaner.clean(it) }
            ?.takeIf { it.isNotBlank() }
        if (detectorName != null) {
            return detectorName
        }

        val patterns = when (direction) {
            ParsedTransferDirection.INCOMING -> listOf(
                Pattern.compile("""(?:from|paid by)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{3,30})""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{3,30})\s+(?:sent|paid)\s+you\b""", Pattern.CASE_INSENSITIVE)
            )
            ParsedTransferDirection.OUTGOING -> listOf(
                Pattern.compile("""(?:to|sent to|paid to)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{3,30})""", Pattern.CASE_INSENSITIVE)
            )
        }

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(combinedText)
            if (matcher.find()) {
                return merchantCleaner.clean(matcher.group(1))
            }
        }

        return "Google Pay"
    }
}
