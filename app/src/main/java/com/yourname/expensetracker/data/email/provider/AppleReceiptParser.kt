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

        // Amount patterns for Apple receipts
        private val AMOUNT_PATTERNS = listOf(
            Pattern.compile("""Total\s*([^\n]{1,32})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Total Amount\s*([^\n]{1,32})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Amount\s*([^\n]{1,32})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Charged\s*([^\n]{1,32})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Price\s*([^\n]{1,32})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""([^\n]{1,32})\s*(?:USD|EUR|GBP|\$|€|£)""", Pattern.CASE_INSENSITIVE),
            // App Store specific pattern
            Pattern.compile("""total-price[^>]*>\s*([^<]{1,32})""", Pattern.CASE_INSENSITIVE)
        )

        // Order/Document ID patterns
        private val ORDER_ID_PATTERNS = listOf(
            Pattern.compile("""Document No\\.?\\s*:?\\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order ID\\s*:?\\s*([A-Z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Invoice\\s*#?\\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Receipt\\s*#?\\s*([A-Z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""HNY\\s*#?\\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE) // Apple hardware order format
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
        private val ITEM_PATTERN = Pattern.compile(
            """([^\\n]{10,80})\\s+(?:\\d+\\s+)?[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""",
            Pattern.MULTILINE
        )

        // Currency detection for Apple stores
        private val CURRENCY_INDICATORS = mapOf(
            "USD" to listOf("$", "USD", "US", "United States", "appstore.com"),
            "EUR" to listOf("€", "EUR", "Euro", "euro", "FR", "DE", "IT", "ES", "NL"),
            "GBP" to listOf("£", "GBP", "pound", "UK", "United Kingdom", "GB")
        )

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
        val isAppleSender = APPLE_SENDERS.any { sender.contains(it, ignoreCase = true) }
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
        
        return isAppleSender || (isAppleSubject && isAppleBody)
    }

    override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? {
        val cleanedBody = cleanHtml(emailBody)
        
        // Extract amount
        val amount = extractAppleAmount(cleanedBody)
        if (amount == null || amount <= 0) {
            Timber.w("Apple parser: Could not extract amount")
            return null
        }

        // Extract order/document ID
        val orderNumber = extractOrderId(cleanedBody)

        // Extract date
        val date = extractDate(cleanedBody) ?: receivedAt

        // Detect currency
        val currency = detectCurrency(cleanedBody, emailBody)

        // Detect purchase type and set merchant
        val purchaseType = detectPurchaseType(cleanedBody)
        val merchant = buildMerchantName(purchaseType, cleanedBody)

        // Extract items (for App Store purchases)
        val items = extractItems(cleanedBody)

        // Calculate confidence
        val confidence = calculateConfidence(amount, orderNumber, date != receivedAt, items.isNotEmpty())

        return ParsedEmailReceipt(
            merchant = merchant,
            amount = amount,
            currency = currency,
            date = date,
            items = items,
            orderNumber = orderNumber,
            confidence = confidence
        )
    }

    private fun extractAppleAmount(text: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                parseLocalizedAmount(matcher.group(1))?.let { return it }
            }
        }
        
        // Fallback: find any amount preceded by total/charged keywords
        val fallbackPattern = Pattern.compile(
            """(?:total|charged|amount|price)[^\d]{0,20}([^\n]{1,32})""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = fallbackPattern.matcher(text)
        if (matcher.find()) {
            return parseLocalizedAmount(matcher.group(1))
        }
        
        return null
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

    private fun detectCurrency(cleanedBody: String, rawBody: String): String {
        val text = (cleanedBody + rawBody).uppercase()
        
        for ((currency, indicators) in CURRENCY_INDICATORS) {
            if (indicators.any { text.contains(it) }) {
                return currency
            }
        }
        
        // Default based on common patterns
        return when {
            text.contains("$") && !text.contains("AUD") && !text.contains("CAD") -> "USD"
            text.contains("£") -> "GBP"
            text.contains("€") -> "EUR"
            else -> "USD" // Default to USD for Apple (largest market)
        }
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
                """(?:App|Item)\\s*:?\\s*([^\\n]{5,60})""",
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
                val description = matcher.group(1).trim()
                    .replace(Regex("""\\s+"""), " ")
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
