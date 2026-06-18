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

    private val peerHandlePattern = """@[A-Za-z0-9._-]+"""

    private val personLikeCounterpartyPattern =
        """($peerHandlePattern|[A-Za-zΑ-Ωα-ω][A-Za-zΑ-Ωα-ω'.-]*(?:\s+[A-Za-zΑ-Ωα-ω][A-Za-zΑ-Ωα-ω'.-]*){0,2}|(?:my\s+)?(?:friend|contact|mom|dad|mother|father|brother|sister|wife|husband|partner|roommate|buddy|family|colleague|someone\s+you\s+know))"""

    private val incomingP2pPatterns by lazy {
        listOf(
            Pattern.compile("""(?:from|paid by)\s+$personLikeCounterpartyPattern""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""$personLikeCounterpartyPattern\s+(?:sent|paid)\s+you\b""", Pattern.CASE_INSENSITIVE)
        )
    }

    private val outgoingP2pPatterns by lazy {
        listOf(
            Pattern.compile("""(?:to|sent to|paid to)\s+$personLikeCounterpartyPattern""", Pattern.CASE_INSENSITIVE)
        )
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

    private val P2P_KEYWORDS = listOf(
        "friend", "contact", "mom", "dad", "mother", "father",
        "brother", "sister", "wife", "husband", "partner", "roommate",
        "buddy", "family", "colleague", "someone you know"
    )

    private val PAYMENT_APP_INDICATORS = listOf(
        "paypal", "venmo", "cash app", "cashapp", "zelle",
        "wise", "revolut", "apple cash"
    )

    private val PURCHASE_CONTEXT_KEYWORDS = listOf(
        "mastercard", "visa", "amex", "card", "purchase", "transaction",
        "order", "subscription", "bill", "checkout"
    )

    private val EXPLICIT_P2P_ONLY_KEYWORDS = listOf(
        "google pay", "gpay", "upi", "peer", "peer-to-peer", "p2p"
    )

    private val MERCHANT_LIKE_KEYWORDS = listOf(
        "store", "shop", "market", "mart", "cafe", "coffee", "restaurant",
        "hotel", "pharmacy", "bakery", "bar", "grill", "pizza", "burger",
        "station", "fuel", "supermarket", "mall"
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

        val transferDirection = detectP2pTransferDirection(title, text, bigText, fullText, lowerFull)
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
            merchant = merchant.trim(),
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
        fullText: String,
        lowerFull: String
    ): ParsedTransferDirection? {
        val incomingCounterparty = extractExplicitP2pCounterparty(fullText, ParsedTransferDirection.INCOMING)
        val outgoingCounterparty = extractExplicitP2pCounterparty(fullText, ParsedTransferDirection.OUTGOING)

        val explicitDirection = when {
            Regex("""\b(?:received|receive)\b.*\bfrom\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                Regex("""\b(?:sent|paid)\s+you\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                lowerFull.contains("sent to you") -> ParsedTransferDirection.INCOMING

            else -> null
        }?.takeIf {
            hasExplicitP2pCue(title, lowerFull, incomingCounterparty, ParsedTransferDirection.INCOMING)
        } ?: when {
            Regex("""\b(?:sent|send|paid)\b.*\bto\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) ||
                Regex("""\btransfer(?:red)?\b.*\bto\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerFull) -> ParsedTransferDirection.OUTGOING

            else -> null
        }?.takeIf {
            hasExplicitP2pCue(title, lowerFull, outgoingCounterparty, ParsedTransferDirection.OUTGOING)
        }

        val detectorAccountName = directionDetector.extractAccountName(title, text, bigText)
        return explicitDirection ?: directionDetector.detectDirection(
            title = title,
            text = text,
            bigText = bigText,
            transactionType = ParsedTransactionType.TRANSFER
        )?.takeIf { detectedDirection ->
            hasExplicitP2pCue(
                title = title,
                lowerFull = lowerFull,
                counterparty = extractExplicitP2pCounterparty(fullText, detectedDirection) ?: detectorAccountName,
                direction = detectedDirection
            )
        }
    }

    private fun extractExplicitP2pCounterparty(
        fullText: String,
        direction: ParsedTransferDirection
    ): String? {
        val patterns = when (direction) {
            ParsedTransferDirection.INCOMING -> incomingP2pPatterns
            ParsedTransferDirection.OUTGOING -> outgoingP2pPatterns
        }

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(fullText)
            if (matcher.find()) {
                return matcher.group(1)?.trim()
            }
        }

        return null
    }

    private fun hasExplicitP2pCue(
        title: String?,
        lowerFull: String,
        counterparty: String?,
        direction: ParsedTransferDirection
    ): Boolean {
        if (direction == ParsedTransferDirection.INCOMING &&
            (lowerFull.contains("paid you") || lowerFull.contains("sent to you"))
        ) {
            return true
        }

        val cleanedCounterparty = counterparty
            ?.let { merchantCleaner.clean(it) }
            ?.trim()
            ?.trimEnd(',', '.', ':', ';', '-', '–', '—')
            .orEmpty()

        if (hasPeerHandleCue(lowerFull) || hasPeerHandleCue(cleanedCounterparty)) {
            return true
        }

        if (direction == ParsedTransferDirection.OUTGOING) {
            return hasOutgoingPeerTransferMarker(lowerFull, cleanedCounterparty)
        }

        if (PURCHASE_CONTEXT_KEYWORDS.any { lowerFull.contains(it) }) {
            return false
        }

        if (EXPLICIT_P2P_ONLY_KEYWORDS.any { lowerFull.contains(it) } &&
            looksLikeExplicitPersonName(cleanedCounterparty, allowSingleWordName = false)
        ) {
            return true
        }

        if (P2P_KEYWORDS.any { lowerFull.contains(it) } ||
            PAYMENT_APP_INDICATORS.any { lowerFull.contains(it) }
        ) {
            return true
        }

        if (cleanedCounterparty.isBlank()) {
            return false
        }

        val cleanedTitle = title?.let { merchantCleaner.clean(it) }?.trim().orEmpty()
        if (cleanedTitle.isNotBlank() &&
            !isWalletOrPaymentTitle(cleanedTitle) &&
            cleanedTitle.equals(cleanedCounterparty, ignoreCase = true)
        ) {
            return false
        }

        val allowSingleWordName = !(direction == ParsedTransferDirection.OUTGOING &&
            lowerFull.contains("paid") &&
            P2P_KEYWORDS.none { lowerFull.contains(it) })

        return looksLikeExplicitPersonName(cleanedCounterparty, allowSingleWordName)
    }

    private fun hasOutgoingPeerTransferMarker(lowerFull: String, cleanedCounterparty: String): Boolean {
        if (PURCHASE_CONTEXT_KEYWORDS.any { lowerFull.contains(it) || cleanedCounterparty.contains(it, ignoreCase = true) }) {
            return false
        }

        if (P2P_KEYWORDS.any { lowerFull.contains(it) || cleanedCounterparty.contains(it, ignoreCase = true) }) {
            return true
        }

        if (PAYMENT_APP_INDICATORS.any { lowerFull.contains(it) || cleanedCounterparty.contains(it, ignoreCase = true) }) {
            return true
        }

        if (EXPLICIT_P2P_ONLY_KEYWORDS.any { lowerFull.contains(it) || cleanedCounterparty.contains(it, ignoreCase = true) }) {
            return looksLikeExplicitPersonName(cleanedCounterparty, allowSingleWordName = false)
        }

        return false
    }

    private fun hasPeerHandleCue(value: String): Boolean {
        return Regex("""(?:^|\s)$peerHandlePattern\b""").containsMatchIn(value)
    }

    private fun isWalletOrPaymentTitle(title: String): Boolean {
        val lowerTitle = title.lowercase()
        return listOf("google pay", "google wallet", "wallet", "payment", "transaction").any {
            lowerTitle.contains(it)
        }
    }

    private fun looksLikeExplicitPersonName(value: String, allowSingleWordName: Boolean): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return false
        }

        val lowerValue = normalized.lowercase()
        if (P2P_KEYWORDS.any { lowerValue.contains(it) }) {
            return true
        }

        if (MERCHANT_LIKE_KEYWORDS.any { lowerValue.contains(it) }) {
            return false
        }

        val parts = normalized.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 3) {
            return false
        }

        if (!allowSingleWordName && parts.size < 2) {
            return false
        }

        return parts.all { it.matches(Regex("""[A-Za-zΑ-Ωα-ω][A-Za-zΑ-Ωα-ω'.-]+""")) }
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
