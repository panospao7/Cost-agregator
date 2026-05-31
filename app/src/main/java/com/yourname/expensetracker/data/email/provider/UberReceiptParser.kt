package com.yourname.expensetracker.data.email.provider

import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parser for Uber ride and Eats receipt emails.
 * Handles both Uber rides and Uber Eats order confirmations.
 */
class UberReceiptParser : BaseEmailParser() {

    companion object {
        private data class DatePattern(val pattern: Pattern, val dateGroup: Int = 1)

        private val UBER_SENDERS = listOf(
            "receipts@uber.com",
            "noreply@uber.com",
            "uber@uber.com",
            "uber-eats@uber.com"
        )

        // Amount extraction patterns for different Uber receipt types
        private val RIDE_AMOUNT_PATTERNS = listOf(
            Pattern.compile("""Total\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""You paid\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Charged\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Amount charged\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE)
        )

        private val EATS_AMOUNT_PATTERNS = listOf(
            Pattern.compile("""Total\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order Total\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""You paid\\s*:?[\\s]*(${amountCapturePattern})""", Pattern.CASE_INSENSITIVE)
        )

        // Trip/Order ID patterns
        private val TRIP_ID_PATTERNS = listOf(
            Pattern.compile("""Trip ID\\s*:?\\s*([A-Za-z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Order ID\\s*:?\\s*([A-Za-z0-9-]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""Receipt #\\s*:?\\s*([A-Za-z0-9-]+)""", Pattern.CASE_INSENSITIVE)
        )

        // Date patterns specific to Uber receipts
        private val DATE_PATTERNS = listOf(
            DatePattern(
                Pattern.compile(
                    """(?:Trip|Order) date\\s*:?\\s*([\\p{L}]+\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\s+[\\p{L}]+\\s+\\d{4})""",
                    Pattern.CASE_INSENSITIVE
                )
            ),
            DatePattern(
                Pattern.compile(
                    """(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),?\\s+([\\p{L}]+\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\s+[\\p{L}]+\\s+\\d{4})""",
                    Pattern.CASE_INSENSITIVE
                )
            ),
            DatePattern(
                Pattern.compile(
                    """\\d{1,2}:\\d{2}\\s+(?:AM|PM)?[^\\d]*([\\p{L}]+\\s+\\d{1,2}|\\d{1,2}\\s+[\\p{L}]+)""",
                    Pattern.CASE_INSENSITIVE
                )
            ),
            DatePattern(
                Pattern.compile(
                    """([\\p{L}]+\\s+\\d{1,2}|\\d{1,2}\\s+[\\p{L}]+)\\s+at\\s+\\d""",
                    Pattern.CASE_INSENSITIVE
                )
            )
        )

        private const val amountCapturePattern = "[€\\$£]?\\s*[0-9]+(?:[.,\\s\\u00A0\\u202F\\u2007][0-9]{3})*(?:[.,][0-9]{2})?\\s*(?:€|\\$|£|EUR|USD|GBP)?"

        // Currency detection
        private val CURRENCY_INDICATORS = mapOf(
            "USD" to listOf("$", "USD", "US", "United States"),
            "EUR" to listOf("€", "EUR", "Euro", "Europe", "GR", "DE", "FR", "IT", "ES"),
            "GBP" to listOf("£", "GBP", "pound", "UK", "United Kingdom", "London")
        )
    }

    override fun canParse(sender: String, subject: String, body: String): Boolean {
        // P11-PR3 (NEW-P11-003): Require Uber sender domain — subject/body match was too broad
        val isUberSender = UBER_SENDERS.any { sender.contains(it, ignoreCase = true) }
            || sender.contains("@uber.com", ignoreCase = true)
        return isUberSender
    }

    override fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt? {
        val cleanedBody = cleanHtml(emailBody)
        
        // Determine if this is Uber Eats or Uber Ride
        val isEats = isUberEats(cleanedBody, emailBody)
        
        // Extract amount
        val amount = if (isEats) {
            extractEatsAmount(cleanedBody)
        } else {
            extractRideAmount(cleanedBody)
        }
        
        if (amount == null || amount <= 0) {
            Timber.w("Uber parser: Could not extract amount")
            return null
        }

        // Extract trip/order ID
        val orderNumber = extractTripId(cleanedBody)

        // Extract date
        val date = extractDate(cleanedBody, receivedAt) ?: receivedAt

        // Detect currency
        val currency = detectCurrency(cleanedBody, emailBody)

        // Extract merchant info (driver name for rides, restaurant for eats)
        val merchant = extractMerchant(cleanedBody, isEats)

        // Calculate confidence
        val confidence = calculateConfidence(amount, orderNumber, date != receivedAt, merchant)

        return ParsedEmailReceipt(
            merchant = merchant,
            amount = amount,
            currency = currency,
            date = date,
            items = emptyList(), // Uber receipts don't typically have itemized lists
            orderNumber = orderNumber,
            confidence = confidence
        )
    }

    private fun isUberEats(body: String, rawBody: String): Boolean {
        val text = (body + rawBody).lowercase()
        return text.contains("uber eats") ||
               text.contains("eats order") ||
               text.contains("restaurant") ||
               text.contains("delivery") ||
               text.contains("food order")
    }

    private fun extractRideAmount(text: String): Double? {
        extractAmountFromPatterns(text, RIDE_AMOUNT_PATTERNS)?.let { return it }
        
        // Fallback: look for amount near "total" keyword
        val fallbackPattern = Pattern.compile(
            """total[^\\d]{0,30}(${amountCapturePattern})""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = fallbackPattern.matcher(text)
        if (matcher.find()) {
            return parseLocalizedAmount(matcher.group(1))
        }
        
        return null
    }

    private fun extractEatsAmount(text: String): Double? {
        extractAmountFromPatterns(text, EATS_AMOUNT_PATTERNS)?.let { return it }
        return extractRideAmount(text) // Fallback to ride patterns
    }

    private fun extractAmountFromPatterns(text: String, patterns: List<Pattern>): Double? {
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                parseLocalizedAmount(matcher.group(1))?.let { return it }
            }
        }

        return null
    }

    private fun extractTripId(text: String): String? {
        for (pattern in TRIP_ID_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1).trim()
            }
        }
        return null
    }

    private fun extractDate(text: String, receivedAt: Long): Long? {
        for (datePattern in DATE_PATTERNS) {
            val matcher = datePattern.pattern.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(datePattern.dateGroup)
                return parseUberDate(dateStr, receivedAt)
            }
        }
        return null
    }

    private fun parseUberDate(dateStr: String, receivedAt: Long): Long? {
        parseDate(dateStr)?.let { return it }

        val receivedInstant = Instant.ofEpochMilli(receivedAt)
        val receivedZone = ZoneId.systemDefault()
        val receivedYear = receivedInstant.atZone(receivedZone).year
        val futureClampThresholdMs = 7L * 24L * 60L * 60L * 1000L

        val formats = listOf(
            DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMMM dd", Locale.US),
            DateTimeFormatter.ofPattern("MMM dd", Locale.US)
        )

        for (format in formats) {
            try {
                val accessor = format.parse(dateStr)
                val hasYear = accessor.isSupported(ChronoField.YEAR)
                val zdt = if (hasYear) {
                    LocalDate.from(accessor).atStartOfDay(receivedZone)
                } else {
                    val monthDay = MonthDay.from(accessor)
                    monthDay.atYear(receivedYear).atStartOfDay(receivedZone)
                }
                val millis = zdt.toInstant().toEpochMilli()
                if (!hasYear && millis > receivedAt + futureClampThresholdMs) {
                    return zdt.minusYears(1).toInstant().toEpochMilli()
                }
                return millis
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun detectCurrency(cleanedBody: String, rawBody: String): String {
        val text = (cleanedBody + rawBody).uppercase()
        
        for ((currency, indicators) in CURRENCY_INDICATORS) {
            if (indicators.any { indicator -> containsBoundedToken(text, indicator.uppercase()) }) {
                return currency
            }
        }
        
        // Default based on common Uber markets
        return when {
            text.contains("USD") || text.contains("$") -> "USD"
            text.contains("£") || text.contains("UK") -> "GBP"
            else -> "EUR" // Default to EUR for European Uber operations
        }
    }

    private fun extractMerchant(text: String, isEats: Boolean): String {
        return if (isEats) {
            // Try to extract restaurant name for Eats
            val restaurantPattern = Pattern.compile(
                """(?:Restaurant|From|Ordered from)\\s*:?\\s*([^\\n]{2,50})""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = restaurantPattern.matcher(text)
            if (matcher.find()) {
                "Uber Eats - ${matcher.group(1).trim()}"
            } else {
                "Uber Eats"
            }
        } else {
            // For rides, try to get driver name or just return "Uber Ride"
            val driverPattern = Pattern.compile(
                """(?:Your driver|Driver)\\s*:?\\s*([A-Za-z]+)""",
                Pattern.CASE_INSENSITIVE
            )
            val matcher = driverPattern.matcher(text)
            if (matcher.find()) {
                "Uber - ${matcher.group(1).trim()}"
            } else {
                "Uber"
            }
        }
    }

    private fun calculateConfidence(
        amount: Double?,
        orderNumber: String?,
        hasDate: Boolean,
        merchant: String
    ): Double {
        var score = 0.5 // Base confidence
        
        if (amount != null && amount > 0) score += 0.25
        if (orderNumber != null) score += 0.15
        if (hasDate) score += 0.1
        if (merchant != "Uber" && merchant != "Uber Eats") score += 0.1 // Specific merchant name found
        
        return score.coerceIn(0.0, 1.0)
    }
}
