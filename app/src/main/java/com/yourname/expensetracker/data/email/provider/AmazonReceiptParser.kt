package com.yourname.expensetracker.data.email.provider

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parser for Amazon order confirmation emails.
 * Handles multiple Amazon domains (amazon.com, amazon.co.uk, amazon.de, etc.)
 */
class AmazonReceiptParser : BaseEmailParser() {

    companion object {
        private val AMAZON_SENDERS = listOf(
            "auto-confirm@amazon",
            "order-update@amazon",
            "shipment-update@amazon",
            "digital-no-reply@amazon"
        )

        private val ORDER_PATTERNS = listOf(
            // Order total patterns
            Pattern.compile("""Order Total:\\s*[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Grand Total:\\s*[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Total:\\s*[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""", Pattern.CASE_INSENSITIVE),
            // Alternative format
            Pattern.compile("""(?:Order Total|Grand Total|Total)\\s+([0-9,]+\\.[0-9]{2})""", Pattern.CASE_INSENSITIVE),
            // HTML formatted
            Pattern.compile("""order-total[^>]*>[^<]*[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""", Pattern.CASE_INSENSITIVE)
        )

        private val ORDER_NUMBER_PATTERNS = listOf(
            Pattern.compile("""Order #?\\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order Number:\\s*#?([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""order-number[^>]*>#?([0-9-]+)""", Pattern.CASE_INSENSITIVE)
        )

        private val DATE_PATTERNS = listOf(
            Pattern.compile("""Order Date:\\s*([A-Za-z]+\\s+\\d{1,2},?\\s+\\d{4})"""),
            Pattern.compile("""Placed on:\\s*([A-Za-z]+\\s+\\d{1,2},?\\s+\\d{4})"""),
            Pattern.compile("""(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\\s+([A-Za-z]+\\s+\\d{1,2},?\\s+\\d{4})"""),
            Pattern.compile("""\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4}""")
        )

        private val ITEM_PATTERN = Pattern.compile(
            """([^\\n]{10,100})\\s+(?:Qty:\\s*)?(\\d+)\\s+[€\\$£]?\\s*([0-9,]+\\.[0-9]{2})""",
            Pattern.MULTILINE
        )

        // Currency detection patterns
        private val CURRENCY_PATTERNS = mapOf(
            "USD" to listOf("$", "USD", "dollar", "amazon.com"),
            "EUR" to listOf("€", "EUR", "euro", "amazon.de", "amazon.fr", "amazon.it", "amazon.es"),
            "GBP" to listOf("£", "GBP", "pound", "amazon.co.uk")
        )
    }

    override fun canParse(sender: String, subject: String, body: String): Boolean {
        val isAmazonSender = AMAZON_SENDERS.any { sender.contains(it, ignoreCase = true) }
        val isOrderEmail = subject.contains("order", ignoreCase = true) ||
                          subject.contains("your amazon", ignoreCase = true) ||
                          body.contains("amazon.com", ignoreCase = true) ||
                          body.contains("amazon.co.uk", ignoreCase = true) ||
                          body.contains("amazon.de", ignoreCase = true)
        return isAmazonSender || isOrderEmail
    }

    override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? {
        val cleanedBody = cleanHtml(emailBody)
        
        // Extract order total
        val amount = extractOrderTotal(cleanedBody)
        if (amount == null || amount <= 0) {
            Timber.w("Amazon parser: Could not extract order total")
            return null
        }

        // Extract order number
        val orderNumber = extractOrderNumber(cleanedBody)

        // Extract date
        val date = extractDate(cleanedBody) ?: receivedAt

        // Detect currency
        val currency = detectCurrency(emailBody)

        // Extract items (best effort)
        val items = extractItems(cleanedBody)

        // Calculate confidence based on what we found
        val confidence = calculateConfidence(amount, orderNumber, date != receivedAt, items.isNotEmpty())

        return ParsedEmailReceipt(
            merchant = "Amazon",
            amount = amount,
            currency = currency,
            date = date,
            items = items,
            orderNumber = orderNumber,
            confidence = confidence
        )
    }

    private fun extractOrderTotal(text: String): Double? {
        for (pattern in ORDER_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val amountStr = matcher.group(1).replace(",", "")
                return amountStr.toDoubleOrNull()
            }
        }
        
        // Fallback: look for any reasonable total amount near keywords
        val fallbackPattern = Pattern.compile(
            """(?:total|grand\\s+total|order\\s+total)[^\\d]{0,20}([0-9,]+\\.[0-9]{2})""",
            Pattern.CASE_INSENSITIVE
        )
        val fallbackMatcher = fallbackPattern.matcher(text)
        if (fallbackMatcher.find()) {
            val amountStr = fallbackMatcher.group(1).replace(",", "")
            return amountStr.toDoubleOrNull()
        }
        
        return null
    }

    private fun extractOrderNumber(text: String): String? {
        for (pattern in ORDER_NUMBER_PATTERNS) {
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
                val dateStr = matcher.group(1)
                return parseAmazonDate(dateStr)
            }
        }
        return null
    }

    private fun detectCurrency(text: String): String {
        val textLower = text.lowercase()
        
        for ((currency, indicators) in CURRENCY_PATTERNS) {
            if (indicators.any { textLower.contains(it.lowercase()) }) {
                return currency
            }
        }
        
        // Default based on domain
        return when {
            text.contains(".co.uk", ignoreCase = true) -> "GBP"
            text.contains("amazon.com", ignoreCase = true) -> "USD"
            text.contains(".de", ignoreCase = true) || 
            text.contains(".fr", ignoreCase = true) ||
            text.contains(".it", ignoreCase = true) ||
            text.contains(".es", ignoreCase = true) -> "EUR"
            else -> "USD" // Default to USD for Amazon
        }
    }

    private fun extractItems(text: String): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()
        val matcher = ITEM_PATTERN.matcher(text)
        
        while (matcher.find()) {
            try {
                val description = matcher.group(1).trim().take(100)
                val quantity = matcher.group(2).toIntOrNull() ?: 1
                val priceStr = matcher.group(3).replace(",", "")
                val unitPrice = priceStr.toDoubleOrNull() ?: continue
                
                items.add(ReceiptItem(
                    description = description,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    totalPrice = unitPrice * quantity
                ))
            } catch (_: Exception) {
                continue
            }
        }
        
        return items
    }

    private fun parseAmazonDate(dateStr: String): Long? {
        val formats = listOf(
            SimpleDateFormat("MMMM dd, yyyy", Locale.US),
            SimpleDateFormat("MMM dd, yyyy", Locale.US),
            SimpleDateFormat("dd MMMM yyyy", Locale.US),
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        )
        
        for (format in formats) {
            try {
                return format.parse(dateStr)?.time
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun calculateConfidence(
        amount: Double?,
        orderNumber: String?,
        hasDate: Boolean,
        hasItems: Boolean
    ): Double {
        var score = 0.5 // Base confidence
        
        if (amount != null && amount > 0) score += 0.2
        if (orderNumber != null) score += 0.15
        if (hasDate) score += 0.1
        if (hasItems) score += 0.05
        
        return score.coerceIn(0.0, 1.0)
    }
}
