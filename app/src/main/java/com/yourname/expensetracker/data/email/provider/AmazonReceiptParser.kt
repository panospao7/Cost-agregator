package com.yourname.expensetracker.data.email.provider

import timber.log.Timber
import java.util.regex.Matcher
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

        private val ORDER_NUMBER_PATTERNS = listOf(
            // P11-PR3 (NEW-P11-005): Fixed double-escaped \s in raw strings
            Pattern.compile("""Order #?\s*([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order Number:\s*#?([0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""order-number[^>]*>#?([0-9-]+)""", Pattern.CASE_INSENSITIVE)
        )

        private val DATE_PATTERNS = listOf(
            Pattern.compile("""Order Date:\s*([\p{L}]+\s+\d{1,2},?\s+\d{4})"""),
            Pattern.compile("""Placed on:\s*([\p{L}]+\s+\d{1,2},?\s+\d{4})"""),
            Pattern.compile("""(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\s+([\p{L}]+\s+\d{1,2},?\s+\d{4})"""),
            Pattern.compile("""\d{1,2}\s+[\p{L}]+\s+\d{4}""")
        )

        private val ITEM_PATTERN = Pattern.compile(
            """([^\n]{10,100})\s+(?:Qty:\s*)?(\d+)\s+[€$£]?\s*([0-9,]+\.[0-9]{2})""",
            Pattern.MULTILINE
        )

        // RP-18 18-A: trusted Amazon storefront domains for currency resolution.
        // Symbols and word-bounded ISO codes are checked first (BaseEmailParser);
        // bare two-letter country tokens and word heuristics are no longer
        // currency signals, and there is no default currency.
        private val TRUSTED_DOMAIN_CODES = mapOf(
            "amazon.com" to "USD",
            "amazon.co.uk" to "GBP",
            "amazon.de" to "EUR",
            "amazon.fr" to "EUR",
            "amazon.it" to "EUR",
            "amazon.es" to "EUR"
        )
    }

    override fun canParse(sender: String, subject: String, body: String): Boolean {
        // P11-PR3 (NEW-P11-002): Require Amazon sender domain — body-only match was too broad
        val isAmazonSender = AMAZON_SENDERS.any { sender.contains(it, ignoreCase = true) }
            || sender.contains("@amazon.", ignoreCase = true)
        return isAmazonSender
    }

    override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? {
        return when (val outcome = parseWithOutcome(emailBody, receivedAt)) {
            is EmailParseOutcome.Parsed -> outcome.receipt
            is EmailParseOutcome.Skipped -> null
        }
    }

    override fun parseWithOutcome(emailBody: String, receivedAt: Long): EmailParseOutcome {
        val cleanedBody = cleanHtml(emailBody)

        // Extract order total
        val amount = extractOrderTotal(cleanedBody)
        if (amount == null || amount <= 0) {
            Timber.w("Amazon parser: Could not extract order total")
            return EmailParseOutcome.Skipped(EmailParseSkipReason.PARSE_FAILED)
        }

        // RP-18 18-A: unknown currency must skip — never guess a default currency
        val currency = detectCurrency(cleanedBody, emailBody)
            ?: return EmailParseOutcome.Skipped(EmailParseSkipReason.CURRENCY_UNRESOLVED)

        // Extract order number
        val orderNumber = extractOrderNumber(cleanedBody)

        // Extract date
        val date = extractDate(cleanedBody) ?: receivedAt

        // Extract items (best effort)
        val items = extractItems(cleanedBody)

        // Calculate confidence based on what we found
        val confidence = calculateConfidence(amount, orderNumber, date != receivedAt, items.isNotEmpty())

        return EmailParseOutcome.Parsed(
            ParsedEmailReceipt(
                merchant = "Amazon",
                amount = amount,
                currency = currency,
                date = date,
                items = items,
                orderNumber = orderNumber,
                confidence = confidence
            )
        )
    }

    private fun extractOrderTotal(text: String): Double? {
        // RP-18 18-A: line-anchored, specific-labels-first hierarchy —
        // "Order Total"/"Grand Total" beat a bare "Total", and mid-line words
        // ("Subtotal") or value-less "Total items: 3" lines can never match.
        return extractTotalAmount(text, listOf("order total", "grand total"))
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
    // trusted Amazon domains only. Returns null when unresolved — the caller
    // must skip with CURRENCY_UNRESOLVED instead of defaulting.
    private fun detectCurrency(cleanedBody: String, rawBody: String): String? {
        return detectCurrencyCode(cleanedBody + rawBody, TRUSTED_DOMAIN_CODES)
    }

    private fun extractItems(text: String): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()
        val matcher = ITEM_PATTERN.matcher(text)
        
        while (matcher.find()) {
            try {
                val description = matcher.group(1).trim().take(100)

                // RP-18 18-A: aggregate/summary rows and total/tax/vat lines are
                // never line items.
                if (isSummaryRow(matcher.group()) ||
                    description.contains("total", ignoreCase = true) ||
                    description.contains("subtotal", ignoreCase = true) ||
                    description.contains("tax", ignoreCase = true) ||
                    description.contains("vat", ignoreCase = true)
                ) {
                    continue
                }

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
