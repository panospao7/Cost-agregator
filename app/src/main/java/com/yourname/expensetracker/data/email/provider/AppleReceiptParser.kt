package com.yourname.expensetracker.data.email.provider

import timber.log.Timber
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parser for Apple receipt emails.
 * Handles App Store purchases, iTunes receipts, Apple Music, iCloud, and Apple Pay.
 */
class AppleReceiptParser : BaseEmailParser() {

    companion object {
        private val APPLE_SENDERS = listOf(
            "do_not_reply@apple.com",
            "no_reply@apple.com",
            "receipts@apple.com",
            "apple@email.apple.com",
            "itunes@apple.com",
            "invoice@apple.com"
        )

        // RP-18 18-A: trusted Apple domains only. Two-letter country tokens
        // ("US", "FR", "DE", "IT", "ES", "NL", "GB", "UK") and country names
        // are no longer currency signals — they false-positive on ordinary
        // words ("us", "de", "it"). There is no default currency.
        private val TRUSTED_DOMAIN_CODES = mapOf(
            "apple.com" to "USD",
            "appstore.com" to "USD"
        )

        // Order/Document ID patterns
        // NEW-P11-2026-001: unescaped raw-string regexes — the previous \\s/\\. escapes
        // made these patterns look for literal backslashes, so they never matched real
        // receipts and orderNumber was always null. All patterns below (including the
        // HNY hardware-order line, unescaped in the NEW-P11-2026-002 residual pass)
        // now use single-escape raw strings.
        private val ORDER_ID_PATTERNS = listOf(
            Pattern.compile("""Document No\.?\s*:?\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order ID\s*:?\s*([A-Z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Invoice\s*#?\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Receipt\s*#?\s*([A-Z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""HNY\s*#?\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE) // Apple hardware order format
        )

        // Date patterns for Apple receipts
        private val DATE_PATTERNS = listOf(
            Pattern.compile("""Date\s*:?\s*([\p{L}]+\s+\d{1,2},?\s+\d{4})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Issue Date\s*:?\s*([\p{L}]+\s+\d{1,2},?\s+\d{4})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order Date\s*:?\s*([\p{L}]+\s+\d{1,2},?\s+\d{4})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""\d{1,2}\s+[\p{L}]+\s+\d{4}"""),
            Pattern.compile("""[\p{L}]+\s+\d{1,2},\s+\d{4}""")
        )

        // App/item extraction patterns
        // NEW-P11-2026-001: unescaped raw-string regexes (same defect class as ORDER_ID_PATTERNS)
        private val ITEM_PATTERN = Pattern.compile(
            """([^\n]{10,80})\s+(?:\d+\s+)?[€$£]?\s*([0-9,]+\.[0-9]{2})""",
            Pattern.MULTILINE
        )

        // RP-18 18-A: the old CURRENCY_INDICATORS map with bare two-letter
        // tokens ("US", "FR", "DE", …) was removed; currency resolution now
        // uses TRUSTED_DOMAIN_CODES above plus symbols/bounded ISO codes from
        // BaseEmailParser.detectCurrencyCode.

        // Purchase type detection
        private val PURCHASE_TYPE_PATTERNS = mapOf(
            "iCloud" to listOf("icloud", "storage plan", "50gb", "200gb", "2tb"),
            "Apple Music" to listOf("apple music", "music subscription"),
            "Apple TV+" to listOf("apple tv+", "tv+", "apple tv plus"),
            "Apple Arcade" to listOf("arcade", "apple arcade"),
            "App Store" to listOf("app store", "in-app purchase", "iap"),
            "Apple Pay" to listOf("apple pay", "sent with apple pay", "payment sent"),
            "Hardware" to listOf("order confirmation", "shipping confirmation", "delivered")
        )
    }

    override fun canParse(sender: String, subject: String, body: String): Boolean {
        // NEW-P11-2026-002 (PARTIAL): sender-gated HERE, like the Amazon/Uber parsers
        // (P11-PR3 precedent) — subject/body are only corroboration, never standalone
        // acceptance inside canParse.
        //
        // RESIDUAL (pipeline level, deferred as a follow-up to preserve forwarder mail):
        // canParse is the only sender-gated call site, but detectProvider in
        // EmailReceiptIngestionService.kt can still route non-Apple-sender mail to
        // appleParser.parse via (a) its body fallback (apple.com/itunes in body →
        // provider "apple") and (b) the unknown-provider try-all path. Fixing that
        // needs a forwarder design pass — gating the body fallback outright would
        // break Apple mail re-sent from forwarding/aliasing addresses.
        val isAppleSender = APPLE_SENDERS.any { sender.contains(it, ignoreCase = true) } ||
            sender.contains("@apple.", ignoreCase = true) ||
            sender.contains("@email.apple.com", ignoreCase = true)
        if (!isAppleSender) return false

        val isAppleSubject = subject.contains("apple", ignoreCase = true) ||
                            subject.contains("itunes", ignoreCase = true) ||
                            subject.contains("app store", ignoreCase = true) ||
                            subject.contains("receipt", ignoreCase = true) ||
                            subject.contains("invoice", ignoreCase = true) ||
                            subject.contains("order confirmation", ignoreCase = true)
        val isAppleBody = body.contains("apple.com", ignoreCase = true) ||
                         body.contains("itunes", ignoreCase = true) ||
                         body.contains("app store", ignoreCase = true) ||
                         body.contains("apple id", ignoreCase = true)

        return isAppleSubject || isAppleBody
    }

    override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? {
        return when (val outcome = parseWithOutcome(emailBody, receivedAt)) {
            is EmailParseOutcome.Parsed -> outcome.receipt
            is EmailParseOutcome.Skipped -> null
        }
    }

    override fun parseWithOutcome(emailBody: String, receivedAt: Long): EmailParseOutcome {
        val cleanedBody = cleanHtml(emailBody)

        // Extract amount
        val amount = extractAppleAmount(cleanedBody)
        if (amount == null || amount <= 0) {
            Timber.w("Apple parser: Could not extract amount")
            return EmailParseOutcome.Skipped(EmailParseSkipReason.PARSE_FAILED)
        }

        // RP-18 18-A: unknown currency must skip — never guess a default currency
        val currency = detectCurrency(cleanedBody, emailBody)
            ?: return EmailParseOutcome.Skipped(EmailParseSkipReason.CURRENCY_UNRESOLVED)

        // Extract order/document ID
        val orderNumber = extractOrderId(cleanedBody)

        // Extract date
        val date = extractDate(cleanedBody) ?: receivedAt

        // Detect purchase type and set merchant
        val purchaseType = detectPurchaseType(cleanedBody)
        val merchant = buildMerchantName(purchaseType, cleanedBody)

        // Extract items (for App Store purchases)
        val items = extractItems(cleanedBody)

        // Calculate confidence
        val confidence = calculateConfidence(amount, orderNumber, date != receivedAt, items.isNotEmpty())

        return EmailParseOutcome.Parsed(
            ParsedEmailReceipt(
                merchant = merchant,
                amount = amount,
                currency = currency,
                date = date,
                items = items,
                orderNumber = orderNumber,
                confidence = confidence
            )
        )
    }

    private fun extractAppleAmount(text: String): Double? {
        // RP-18 18-A: "total amount" (specific) → "total" → "charged". The
        // former catch-alls ("Amount"/"Price" and "any text followed by a
        // currency token") were removed: they could select unit prices and
        // VAT percentages instead of the actual total.
        return extractTotalAmount(text, listOf("total amount"))
            ?: extractLabeledLineAmount(text, "charged")
    }

    private fun extractOrderId(text: String): String? {
        for (pattern in ORDER_ID_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1).trim()
            }
        }
        return null
    }

    private fun extractDate(text: String): Long? {
        for (pattern in DATE_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                extractMatchedDateText(matcher)?.let(::parseLocalizedDate)?.let { return it }
            }
        }
        return null
    }

    private fun extractMatchedDateText(matcher: Matcher): String? {
        val dateText = if (matcher.groupCount() >= 1) {
            matcher.group(1)?.takeIf { it.isNotBlank() } ?: matcher.group()
        } else {
            matcher.group()
        }

        return dateText?.trim()?.takeIf { it.isNotEmpty() }
    }

    // RP-18 18-A: currency resolution via symbols, word-bounded ISO codes and
    // trusted Apple domains only. Returns null when unresolved — the caller
    // must skip with CURRENCY_UNRESOLVED instead of defaulting to USD.
    private fun detectCurrency(cleanedBody: String, rawBody: String): String? {
        return detectCurrencyCode(cleanedBody + rawBody, TRUSTED_DOMAIN_CODES)
    }

    private fun detectPurchaseType(text: String): String {
        val textLower = text.lowercase()
        
        for ((type, indicators) in PURCHASE_TYPE_PATTERNS) {
            if (indicators.any { textLower.contains(it.lowercase()) }) {
                return type
            }
        }
        
        return "Apple" // Default
    }

    private fun buildMerchantName(purchaseType: String, text: String): String {
        // Try to extract specific app name for App Store purchases
        if (purchaseType == "App Store" || purchaseType == "iCloud") {
            val appPattern = Pattern.compile(
                """(?:App|Item)\s*:?\s*([^\n]{5,60})""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = appPattern.matcher(text)
            if (matcher.find()) {
                val appName = matcher.group(1).trim().take(40)
                return "Apple - $appName"
            }
        }
        
        return "Apple $purchaseType"
    }

    private fun extractItems(text: String): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()
        
        // Look for app/item lines with prices
        val matcher = ITEM_PATTERN.matcher(text)
        
        while (matcher.find()) {
            try {
                // RP-18 18-A: aggregate/summary rows are never line items.
                if (isSummaryRow(matcher.group())) {
                    continue
                }

                val description = matcher.group(1).trim()
                    .replace(Regex("""\s+"""), " ")
                    .take(100)
                
                // Skip lines that are clearly not items
                if (description.contains("total", ignoreCase = true) ||
                    description.contains("subtotal", ignoreCase = true) ||
                    description.contains("tax", ignoreCase = true) ||
                    description.length < 5) {
                    continue
                }
                
                val priceStr = matcher.group(2).replace(",", "")
                val price = priceStr.toDoubleOrNull() ?: continue
                
                items.add(ReceiptItem(
                    description = description,
                    quantity = 1,
                    unitPrice = price,
                    totalPrice = price
                ))
            } catch (_: Exception) {
                continue
            }
        }
        
        return items.take(10) // Limit to first 10 items
    }

    private fun calculateConfidence(
        amount: Double?,
        orderNumber: String?,
        hasDate: Boolean,
        hasItems: Boolean
    ): Double {
        var score = 0.5 // Base confidence
        
        if (amount != null && amount > 0) score += 0.25
        if (orderNumber != null) score += 0.15
        if (hasDate) score += 0.1
        if (hasItems) score += 0.05
        
        return score.coerceIn(0.0, 1.0)
    }
}
