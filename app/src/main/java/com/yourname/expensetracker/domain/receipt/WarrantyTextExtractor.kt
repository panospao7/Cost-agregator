package com.yourname.expensetracker.domain.receipt

import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Data class representing extracted warranty information from OCR text.
 */
data class WarrantyExtractionData(
    val productName: String? = null,
    val merchantName: String? = null,
    val purchaseDate: Long? = null,
    val warrantyDurationMonths: Int? = null,
    val warrantyEndDate: Long? = null,
    val warrantyType: String? = null,
    val supportPhone: String? = null,
    val supportEmail: String? = null,
    val returnWindowDays: Int? = null,
    val confidence: Double = 0.0
)

/**
 * Extracts warranty information from OCR receipt text using regex patterns.
 * 
 * This is a lightweight, on-device extraction that doesn't require cloud AI.
 */
class WarrantyTextExtractor {

    private val dateFormatters = listOf(
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
        SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()),
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()),
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()),
        SimpleDateFormat("MMM dd, yyyy", Locale.US),
        SimpleDateFormat("dd MMM yyyy", Locale.US)
    )

    /**
     * Main extraction method that processes OCR text and returns warranty data.
     */
    fun extract(ocrText: String): WarrantyExtractionData {
        val normalizedText = normalizeText(ocrText)
        
        val purchaseDate = extractPurchaseDate(normalizedText)
        val warrantyDurationMonths = extractWarrantyDuration(normalizedText)
        val warrantyEndDate = calculateWarrantyEndDate(purchaseDate, warrantyDurationMonths)
        val productName = extractProductName(normalizedText)
        val merchantName = extractMerchantName(normalizedText)
        val warrantyType = extractWarrantyType(normalizedText)
        val supportPhone = extractSupportPhone(normalizedText)
        val supportEmail = extractSupportEmail(normalizedText)
        val returnWindowDays = extractReturnWindowDays(normalizedText)
        
        val confidence = calculateConfidence(
            purchaseDate = purchaseDate,
            warrantyDurationMonths = warrantyDurationMonths,
            productName = productName,
            merchantName = merchantName,
            warrantyType = warrantyType
        )
        
        return WarrantyExtractionData(
            productName = productName,
            merchantName = merchantName,
            purchaseDate = purchaseDate,
            warrantyDurationMonths = warrantyDurationMonths,
            warrantyEndDate = warrantyEndDate,
            warrantyType = warrantyType,
            supportPhone = supportPhone,
            supportEmail = supportEmail,
            returnWindowDays = returnWindowDays,
            confidence = confidence
        )
    }
    
    /**
     * Normalizes text for better pattern matching.
     */
    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("\r\n"), "\n")
            .replace(Regex("\r"), "\n")
            .replace(Regex("[ ]+"), " ")
            .uppercase(Locale.getDefault())
    }
    
    /**
     * Extracts purchase date from various date patterns.
     */
    private fun extractPurchaseDate(text: String): Long? {
        // Look for common date patterns on receipts
        val datePatterns = listOf(
            // "Date: 12/05/2024" or "Date 12/05/2024"
            Pattern.compile("DATE[:\\s]*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // "Purchase Date: 12/05/2024"
            Pattern.compile("PURCHASE(?:\\s+DATE)?[:\\s]*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // "Transaction Date: 12/05/2024"
            Pattern.compile("TRANSACTION(?:\\s+DATE)?[:\\s]*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // "Receipt Date: 12/05/2024"
            Pattern.compile("RECEIPT(?:\\s+DATE)?[:\\s]*(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // Just a date at the start of a line
            Pattern.compile("^(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // Date with month name: "12 MAY 2024" or "MAY 12, 2024"
            Pattern.compile("(\\d{1,2}\\s+[A-Z]{3,9}\\s+\\d{2,4})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([A-Z]{3,9}\\s+\\d{1,2},?\\s+\\d{2,4})", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in datePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(1)?.trim() ?: continue
                val parsedDate = parseDate(dateStr)
                if (parsedDate != null && isReasonablePurchaseDate(parsedDate)) {
                    return parsedDate
                }
            }
        }
        
        return null
    }
    
    /**
     * Parses a date string using various formats.
     */
    private fun parseDate(dateStr: String): Long? {
        for (formatter in dateFormatters) {
            try {
                val date = formatter.parse(dateStr)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // Try next formatter
            }
        }
        return null
    }
    
    /**
     * Checks if the date is reasonable (not in the future, not too old).
     */
    private fun isReasonablePurchaseDate(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val oneYearAgo = now - (365L * 24 * 60 * 60 * 1000)
        // Date should be within last year and not in the future
        return timestamp in oneYearAgo..now
    }
    
    /**
     * Extracts warranty duration in months from various patterns.
     */
    private fun extractWarrantyDuration(text: String): Int? {
        // Look for warranty duration patterns
        val durationPatterns = listOf(
            // "2 Year Warranty", "2-Year Warranty"
            Pattern.compile("(\\d+)\\s*-?\\s*(?:YEAR|YR|YRS)\\s*(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
            // "24 Months Warranty", "24-Month Warranty"
            Pattern.compile("(\\d+)\\s*-?\\s*(?:MONTH|MONTHS|MO|MOS)\\s*(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
            // "Warranty: 2 Years"
            Pattern.compile("WARRANTY[:\\s]*(\\d+)\\s*-?\\s*(?:YEAR|YR|MONTH|MONTHS)", Pattern.CASE_INSENSITIVE),
            // "Warranty Period: 24 Months"
            Pattern.compile("WARRANTY(?:\\s+PERIOD)?[:\\s]*(\\d+)\\s*-?\\s*(?:YEAR|YR|MONTH|MONTHS)", Pattern.CASE_INSENSITIVE),
            // "1Y Warranty" (short form)
            Pattern.compile("(\\d+)(?:Y|M)\\s*(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
            // Extended warranty
            Pattern.compile("EXTENDED\\s+(?:WARRANTY|GUARANTEE)[:\\s]*(\\d+)\\s*-?\\s*(?:YEAR|YR|MONTH|MONTHS)", Pattern.CASE_INSENSITIVE),
            // "Warranty: 12M"
            Pattern.compile("WARRANTY[:\\s]*(\\d+)(?:Y|M)", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in durationPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val value = matcher.group(1)?.toIntOrNull() ?: continue
                
                // Determine if this is years or months based on the matched text
                val matchedText = matcher.group(0)?.uppercase(Locale.getDefault()) ?: ""
                val unitRegex = Regex("""(\d+)\s*([YM])""", RegexOption.IGNORE_CASE)
                val unitMatch = unitRegex.find(matchedText)
                val explicitUnit = unitMatch?.groupValues?.getOrNull(2)?.uppercase(Locale.getDefault())

                return when {
                    explicitUnit == "M" -> value
                    explicitUnit == "Y" -> value * 12
                    matchedText.contains("YEAR") || matchedText.contains("YR") -> value * 12
                    matchedText.contains("MONTH") || matchedText.contains("MO") -> value
                    else -> value // Assume months if unclear
                }
            }
        }
        
        // Default warranty periods based on merchant type
        val defaultWarranties = mapOf(
            Pattern.compile("APPLE", Pattern.CASE_INSENSITIVE) to 12,
            Pattern.compile("SAMSUNG", Pattern.CASE_INSENSITIVE) to 12,
            Pattern.compile("ELECTRONICS|COMPUTER|LAPTOP|PHONE|TV|APPLIANCE", Pattern.CASE_INSENSITIVE) to 12,
            Pattern.compile("FURNITURE|MATTRESS", Pattern.CASE_INSENSITIVE) to 12,
            Pattern.compile("JEWELRY|WATCH", Pattern.CASE_INSENSITIVE) to 12,
            Pattern.compile("TOOL|HARDWARE", Pattern.CASE_INSENSITIVE) to 12
        )
        
        for ((pattern, months) in defaultWarranties) {
            if (pattern.matcher(text).find()) {
                return months
            }
        }
        
        return null
    }
    
    /**
     * Calculates the warranty end date based on purchase date and duration.
     */
    private fun calculateWarrantyEndDate(purchaseDate: Long?, durationMonths: Int?): Long? {
        if (purchaseDate == null || durationMonths == null) return null
        
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = purchaseDate
        calendar.add(Calendar.MONTH, durationMonths)
        return calendar.timeInMillis
    }
    
    /**
     * Extracts product name from receipt text.
     */
    private fun extractProductName(text: String): String? {
        // Try to find product descriptions
        val productPatterns = listOf(
            // "Item: iPhone 15 Pro" or "Product: MacBook Air"
            Pattern.compile("(?:ITEM|PRODUCT|DESCRIPTION)[:\\s]+([^\\n]{5,50})", Pattern.CASE_INSENSITIVE),
            // Lines with model numbers
            Pattern.compile("^([^\\n]{5,40}(?:MODEL|SKU|UPC)#[^\\n]{3,20})", Pattern.CASE_INSENSITIVE),
            // Common product indicators
            Pattern.compile("((?:IPHONE|IPAD|MACBOOK|IMAC|AIRPODS|WATCH|GALAXY|PIXEL)[^\\n]{0,30})", Pattern.CASE_INSENSITIVE),
            // Generic item description (first substantial line after date)
            Pattern.compile("(?:DATE|RECEIPT).{0,100}\\n([^\\n\\d]{5,40})", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in productPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val product = matcher.group(1)?.trim()
                if (!product.isNullOrEmpty() && product.length > 3) {
                    return cleanProductName(product)
                }
            }
        }
        
        // Fallback: look for lines that might be product names (capitalized, medium length)
        val lines = text.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 5..40 && 
                trimmed[0].isUpperCase() &&
                !isLikelyNotProduct(trimmed)) {
                return cleanProductName(trimmed)
            }
        }
        
        return null
    }
    
    /**
     * Cleans up extracted product name.
     */
    private fun cleanProductName(name: String): String {
        return name
            .replace(Regex("^[-\\s]+"), "") // Leading dashes/spaces
            .replace(Regex("[-\\s]+\$"), "") // Trailing dashes/spaces
            .replace(Regex("\\s+"), " ") // Multiple spaces
            .replace(Regex("\\d+\\s*[Xx]\\s*\\d+.*$"), "") // Remove sizes at end
            .replace(Regex("\$\\d+.*$"), "") // Remove prices at end
            .take(50) // Limit length
            .trim()
    }
    
    /**
     * Checks if a line is likely not a product name.
     */
    private fun isLikelyNotProduct(line: String): Boolean {
        val nonProductIndicators = listOf(
            "THANK", "WELCOME", "RECEIPT", "INVOICE", "BILL", "TOTAL", "SUBTOTAL",
            "TAX", "VAT", "CHANGE", "CASH", "CARD", "DEBIT", "CREDIT", "PAYMENT",
            "DATE", "TIME", "ORDER", "TRANSACTION", "MERCHANT", "STORE", "SHOP",
            "TEL", "PHONE", "FAX", "EMAIL", "WWW", "HTTP", "STREET", "AVENUE",
            "ROAD", "BLVD", "CITY", "STATE", "ZIP", "POSTAL", "COUNTRY"
        )
        
        val upperLine = line.uppercase(Locale.getDefault())
        return nonProductIndicators.any { upperLine.contains(it) }
    }
    
    /**
     * Extracts merchant/store name from receipt text.
     */
    private fun extractMerchantName(text: String): String? {
        // Look for merchant patterns
        val merchantPatterns = listOf(
            // "Merchant: Amazon" or "Store: Walmart"
            Pattern.compile("(?:MERCHANT|STORE|SHOP|SELLER|VENDOR)[:\\s]+([^\\n]{2,30})", Pattern.CASE_INSENSITIVE),
            // "From: Amazon"
            Pattern.compile("(?:FROM|BUY\\s+FROM)[:\\s]+([^\\n]{2,30})", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in merchantPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.trim()?.take(30)
            }
        }
        
        // Try to get the first substantial line (often the store name)
        val lines = text.split("\n")
        for (line in lines.take(5)) { // Usually in first few lines
            val trimmed = line.trim()
            if (trimmed.length in 3..30 &&
                trimmed[0].isUpperCase() &&
                !trimmed.contains(Regex("^\\d")) &&
                !isLikelyNotMerchant(trimmed)) {
                return trimmed.take(30)
            }
        }
        
        return null
    }
    
    /**
     * Checks if text is likely not a merchant name.
     */
    private fun isLikelyNotMerchant(text: String): Boolean {
        val nonMerchantIndicators = listOf(
            "RECEIPT", "INVOICE", "DATE", "TIME", "ORDER", "TRANSACTION",
            "TEL", "PHONE", "FAX", "EMAIL", "TAX", "TOTAL", "SUBTOTAL"
        )
        val upperText = text.uppercase(Locale.getDefault())
        return nonMerchantIndicators.any { upperText.contains(it) }
    }
    
    /**
     * Extracts warranty type (manufacturer, extended, store, etc.)
     */
    private fun extractWarrantyType(text: String): String? {
        val typePatterns = mapOf(
            "EXTENDED" to listOf(
                Pattern.compile("EXTENDED\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("EXTENDED\\s+COVERAGE", Pattern.CASE_INSENSITIVE),
                Pattern.compile("PREMIUM\\s+(?:WARRANTY|PROTECTION)", Pattern.CASE_INSENSITIVE)
            ),
            "STORE" to listOf(
                Pattern.compile("STORE\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("RETAILER\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("SALES\\s+PROTECTION", Pattern.CASE_INSENSITIVE)
            ),
            "THIRD_PARTY" to listOf(
                Pattern.compile("(?:THIRD\\s*PARTY|3RD\\s*PARTY)\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:SQUARETRADE|ASSURIANT|ASURION)", Pattern.CASE_INSENSITIVE)
            ),
            "MANUFACTURER" to listOf(
                Pattern.compile("MANUFACTURER(?:'S|S)?\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("FACTORY\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("ORIGINAL\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("STANDARD\\s+(?:WARRANTY|GUARANTEE)", Pattern.CASE_INSENSITIVE)
            )
        )
        
        for ((type, patterns) in typePatterns) {
            for (pattern in patterns) {
                if (pattern.matcher(text).find()) {
                    return type
                }
            }
        }
        
        // Default to manufacturer if any warranty is mentioned
        if (text.contains("WARRANTY", ignoreCase = true) || 
            text.contains("GUARANTEE", ignoreCase = true)) {
            return "MANUFACTURER"
        }
        
        return null
    }
    
    /**
     * Extracts support phone number.
     */
    private fun extractSupportPhone(text: String): String? {
        val phonePatterns = listOf(
            // "Support: +1-800-123-4567" or "Tel: 1-800-123-4567"
            Pattern.compile("(?:SUPPORT|TEL|PHONE|CALL|CONTACT|HELP)[:\\s]+([+\\d\\s\\-\\(\\)]{7,20})", Pattern.CASE_INSENSITIVE),
            // "1-800-FLOWERS" format
            Pattern.compile("(1[-\\s]?\\d{3}[-\\s]?\\d{3}[-\\s]?\\d{4})", Pattern.CASE_INSENSITIVE),
            // International format
            Pattern.compile("(\\+\\d{1,3}[-\\s]?\\d{1,4}[-\\s]?\\d{1,4}[-\\s]?\\d{1,4})")
        )
        
        for (pattern in phonePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val phone = matcher.group(1)?.trim()?.replace(Regex("\\s+"), " ")
                if (phone != null && phone.length >= 7) {
                    return phone
                }
            }
        }
        
        return null
    }
    
    /**
     * Extracts support email.
     */
    private fun extractSupportEmail(text: String): String? {
        val emailPattern = Pattern.compile(
            "(?:EMAIL|SUPPORT|E-MAIL)[:\\s]+([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z]{2,6})",
            Pattern.CASE_INSENSITIVE
        )
        
        val matcher = emailPattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.lowercase(Locale.getDefault())
        }
        
        // Generic email pattern
        val genericPattern = Pattern.compile(
            "([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z]{2,6})",
            Pattern.CASE_INSENSITIVE
        )
        
        val genericMatcher = genericPattern.matcher(text)
        if (genericMatcher.find()) {
            val email = genericMatcher.group(1)?.lowercase(Locale.getDefault())
            // Filter out common non-support emails
            if (email != null && !email.contains("example.com") && !email.contains("test.com")) {
                return email
            }
        }
        
        return null
    }
    
    /**
     * Extracts return window in days.
     */
    private fun extractReturnWindowDays(text: String): Int? {
        val returnPatterns = listOf(
            // "30 Day Return", "30-Day Return"
            Pattern.compile("(\\d+)\\s*-?\\s*(?:DAY|DAYS)\\s*(?:RETURN|REFUND|EXCHANGE)", Pattern.CASE_INSENSITIVE),
            // "Return Policy: 30 Days"
            Pattern.compile("RETURN(?:\\s+POLICY)?[:\\s]*(\\d+)\\s*-?\\s*(?:DAY|DAYS)", Pattern.CASE_INSENSITIVE),
            // "Returns accepted within 30 days"
            Pattern.compile("RETURN.*?(\\d+)\\s*-?\\s*(?:DAY|DAYS)", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in returnPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.toIntOrNull()
            }
        }
        
        // Default return windows by merchant type
        val defaultReturns = mapOf(
            Pattern.compile("AMAZON", Pattern.CASE_INSENSITIVE) to 30,
            Pattern.compile("BEST\\s*BUY", Pattern.CASE_INSENSITIVE) to 15,
            Pattern.compile("APPLE", Pattern.CASE_INSENSITIVE) to 14,
            Pattern.compile("WALMART", Pattern.CASE_INSENSITIVE) to 90,
            Pattern.compile("TARGET", Pattern.CASE_INSENSITIVE) to 90,
            Pattern.compile("COSTCO", Pattern.CASE_INSENSITIVE) to 90
        )
        
        for ((pattern, days) in defaultReturns) {
            if (pattern.matcher(text).find()) {
                return days
            }
        }
        
        return null
    }
    
    /**
     * Calculates overall confidence score based on extracted fields.
     */
    private fun calculateConfidence(
        purchaseDate: Long?,
        warrantyDurationMonths: Int?,
        productName: String?,
        merchantName: String?,
        warrantyType: String?
    ): Double {
        var score = 0.0
        var maxScore = 0.0
        
        // Purchase date (25% weight)
        maxScore += 0.25
        if (purchaseDate != null) score += 0.25
        
        // Warranty duration (30% weight) - critical field
        maxScore += 0.30
        if (warrantyDurationMonths != null) score += 0.30
        
        // Product name (25% weight)
        maxScore += 0.25
        if (productName != null) {
            score += when {
                productName.length > 10 -> 0.25
                productName.length > 5 -> 0.15
                else -> 0.10
            }
        }
        
        // Merchant name (15% weight)
        maxScore += 0.15
        if (merchantName != null) score += 0.15
        
        // Warranty type (5% weight - nice to have)
        maxScore += 0.05
        if (warrantyType != null) score += 0.05
        
        // Normalize to percentage
        return if (maxScore > 0) (score / maxScore) * 100.0 else 0.0
    }
}
