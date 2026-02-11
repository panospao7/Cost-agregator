package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Greek patterns with fuzzy space and comma handling
        Pattern.compile(
            """(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΠΟΣΟ|ΑΞΙΑ|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|ΛΟΓΑΡΙΑΣΜΟ[ΣΖ]|TOTAL|AMOUNT)\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Amount with currency symbol at end
        Pattern.compile(
            """(?:TOTAL|ΣΥΝΟΛΟ|ΠΟΣΟ)\s*[:\s]*(\d+[\s.,]\s*\d{2})\s*(?:€|EUR)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Standalone large amount at the very bottom (common for Lidl/Sklavenitis)
        Pattern.compile(
            """(?:€|EUR)\s*(\d+[\s.,]\s*\d{2})\s*$""",
            Pattern.MULTILINE
        )
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:Φ\.?Π\.?Α\.?|VAT|TAX|TVA)\s*[\d%]*\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4}|\d{2})"""),  // DD/MM/YYYY or DD/MM/YY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})""")   // YYYY/MM/DD
    )

    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" (fuzzy spaces in amount)
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Quantity x Description   Sum"
        Pattern.compile(
            """^(\d+)\s*x\s*(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )

    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL|SUB\s*TOTAL|ΥΠΟΣΥΝΟΛΟ|ΥΠΟ\s*ΣΥΝΟΛΟ|ΜΕΡΙΚΟ|ΚΑΘΑΡΗ\s*ΑΞΙΑ)\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?|ΜΕΙΟΝ|ΕΚΠΤ)\s*[:\s]*-?\s*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    fun parse(rawText: String): ParsedReceipt {
        // 1. Pre-process text to fix OCR spacing issues and Greek characters
        val cleanedText = normalizeGreekOcr(rawText)
        val lines = cleanedText.lines().filter { it.isNotBlank() }

        // 2. Extract merchant
        val merchant = extractMerchant(lines)

        // 3. Extract date
        val date = extractDate(cleanedText)

        // 4. Extract total
        val total = extractTotal(lines)

        // 5. Extract subtotal (using original text as fallback or new logic if needed)
        val subtotal = extractSubtotal(cleanedText)

        // 6. Extract tax
        val tax = extractTax(cleanedText)

        // 7. Extract line items
        val lineItems = extractLineItems(cleanedText)

        // 8. Cross-validate
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }

        // 9. Calculate subtotal
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 10. Confidence
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(cleanedText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    /**
     * Normalizes Greek OCR errors and cleans up number formatting.
     */
    private fun normalizeGreekOcr(text: String): String {
        return text.uppercase()
            // --- 1. CRITICAL: Fix Numbers broken by spaces (e.g., "55, 00" -> "55,00") ---
            .replace(Regex("(\\d+)[.,]\\s+(\\d{2})"), "$1.$2") 
            .replace(Regex("(\\d+)\\s+[.,](\\d{2})"), "$1.$2")

            // --- 2. Fix Total Keywords ---
            .replace(Regex(".*[ΠN]O[SZ]O.*AMOUNT.*"), "TOTAL_KEY")
            .replace(Regex(".*[ΠN]O[SZ]O.*"), "TOTAL_KEY")
            .replace(Regex(".*[ΣE2ZXY]YN.*[AΛV][O0Ω].*"), "TOTAL_KEY") // ΣΥΝΟΛΟ variants
            .replace("NAHPQTEO", "TOTAL_KEY")
            .replace("AMOUNT", "TOTAL_KEY")
            .replace("TOTAL", "TOTAL_KEY")

            // --- 3. Fix Dates ---
            .replace(Regex("(\\d{2})-[D0O]-(\\d{2})"), "$1-04-$2") // Fix "16-D4-2017"
            .replace("HM/NIA", "ΗΜΕΡΟΜΗΝΙΑ")

            // --- 4. Currency & Noise Cleaning ---
            .replace("EVP9", "") 
            .replace("EVP", "")
            .replace("EUR", "")
            .replace("€", "")
    }

    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Skip common non-merchant headers
        val invalidHeaders = listOf(
            "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START", 
            "EAPA", "ADDRESS", "THL", "TEL", "AFM", "AOM"
        )

        // Find anchors: Address, Tax ID, Phone
        val headerMarkers = listOf("ΑΦΜ", "AOM", "ΤΗΛ", "THA", "STR.", "ΟΔΟΣ", "TK", "Τ.Κ", "VAT", "TEL")

        for ((index, line) in lines.withIndex()) {
            if (index > 8) break // Merchant is usually in top 8 lines
            
            // Check if this line is an anchor
            if (headerMarkers.any { line.contains(it) }) {
                // If we found an anchor, the merchant is likely ABOVE it.
                // Scan upwards for the first valid line.
                for (j in index - 1 downTo 0) {
                    val candidate = lines[j]
                    if (isValidMerchantLine(candidate, invalidHeaders)) {
                        return cleanMerchantName(candidate)
                    }
                }
            }
        }
        
        // Fallback: Just return the first valid line if no anchors found
        for (line in lines.take(5)) {
            if (isValidMerchantLine(line, invalidHeaders)) {
                return cleanMerchantName(line)
            }
        }
        return null
    }

    private fun isValidMerchantLine(line: String, invalidHeaders: List<String>): Boolean {
        if (line.length < 3) return false
        if (line.all { !it.isLetter() }) return false // Must have letters
        if (invalidHeaders.any { line.contains(it) }) return false
        return true
    }

    private fun cleanMerchantName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s&.-]"), "").trim()
    }

    private fun extractTotal(lines: List<String>): Double? {
        // Regex: Matches 12.50, 12,50, 1.250,00
        // Strictly avoids numbers followed by % (VAT rates)
        val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?!\s?%)""")

        // --- STRATEGY 1: Explicit "TOTAL" Keyword (Highest Confidence) ---
        // Scan backwards (bottom-up) for the word "TOTAL_KEY"
        val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
        
        if (totalLineIndex != -1) {
            // Check the exact line
            val amountInLine = extractAmountFromLine(lines[totalLineIndex], amountRegex)
            if (amountInLine != null) return amountInLine

            // Check the NEXT line (common in POS receipts: Label then Value)
            if (totalLineIndex + 1 < lines.size) {
                val amountNext = extractAmountFromLine(lines[totalLineIndex + 1], amountRegex)
                if (amountNext != null) return amountNext
            }
        }

        // --- STRATEGY 2: Fallback (Smart Max) ---
        // If no keyword found, find the LARGEST plausible number.
        var maxAmount = 0.0
        
        // Only scan the bottom 70% of the receipt (Price is rarely at the top)
        val searchStart = (lines.size * 0.3).toInt() 
        
        for (i in searchStart until lines.size) {
            val line = lines[i]

            // FILTER: Ignore lines that definitely aren't the total
            if (line.contains("%")) continue // Ignore VAT lines (13,00%)
            if (line.contains("METPHTA") || line.contains("CASH")) continue // Ignore Cash Given (Receipt #18)
            if (line.contains("RESTA") || line.contains("ΡΕΣΤΑ")) continue // Ignore Change
            if (line.contains("KARTA") || line.contains("CARD")) continue // Ignore "Card" references unless parsed carefully

            // Extract numbers from this line
            val matches = amountRegex.findAll(line)
            for (match in matches) {
                val rawVal = match.groupValues[1]
                val amount = parseAmount(rawVal)

                // SANITY CHECKS:
                // 1. Amount must be < 5000 (Avoids phone numbers/Tax IDs misread as price)
                // 2. Amount must not look like a Year (e.g., 2024, 2025)
                // 3. Amount must not look like Time (e.g., 14.24 in Receipt #6)
                if (isValidAmount(amount, line)) {
                    if (amount > maxAmount) {
                        maxAmount = amount
                    }
                }
            }
        }

        return if (maxAmount > 0.0) maxAmount else null
    }

    private fun isValidAmount(amount: Double, line: String): Boolean {
        if (amount > 5000) return false
        if (amount == 0.0) return false
        
        // Year check: 2020-2030 usually represents date, not price
        if (amount >= 2020 && amount <= 2035 && amount % 1 == 0.0) return false
        
        // Time check: If line contains "ORA" or matches HH:MM pattern logic
        if (line.contains("QPA") || line.contains("ORA")) return false
        
        return true
    }

    private fun parseAmount(rawAmount: String): Double {
        // Standardize: "1.250,50" -> "1250.50"
        // Standardize: "12,50" -> "12.50"
        val clean = rawAmount.replace(".", "").replace(",", ".")
        return clean.toDoubleOrNull() ?: 0.0
    }
    
    private fun extractAmountFromLine(line: String, regex: Regex): Double? {
        // If line has multiple numbers, we generally want the LAST one (Net... VAT... Total)
        val matches = regex.findAll(line)
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }

    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        for (pattern in taxPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    // --- DATE EXTRACTION ---
    private fun extractDate(text: String): Long? {
        // Regex handles: dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy
        val datePatterns = listOf(
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})"""),
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                
                // SANITY CHECK: Year must be reasonable (e.g., 2020-2030)
                // Fixes Receipt #8 where OCR read 2058
                val yearInt = year.toIntOrNull() ?: 0
                if (yearInt in 2020..2030) { 
                    try {
                        return sdf.parse("$d/$m/$year")?.time
                    } catch (e: Exception) { }
                }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()

        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ)"""
        )

        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = null,
                    unitPrice = null,
                    totalPrice = price
                )
            )
        }

        // Pattern 2: "qty x description   amount"
        val matcher2 = lineItemPatterns[1].matcher(text)
        while (matcher2.find()) {
            val qty = matcher2.group(1)?.toDoubleOrNull() ?: continue
            val desc = matcher2.group(2)?.trim() ?: continue
            val price = matcher2.group(3)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = qty,
                    unitPrice = if (qty > 0) price / qty else null,
                    totalPrice = price
                )
            )
        }

        return items
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || text.contains("EUR", ignoreCase = true) ||
                    text.contains("ΕΥΡΩ", ignoreCase = true) -> "EUR"
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            text.contains("£") || text.contains("GBP", ignoreCase = true) -> "GBP"
            else -> "EUR"
        }
    }

    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>,
        tax: Double?
    ): Float {
        var score = 0f
        if (merchant != null) score += 0.15f
        if (total != null) score += 0.40f  // Most important
        if (date != null) score += 0.15f
        if (items.isNotEmpty()) score += 0.15f
        if (tax != null) score += 0.05f

        // Bonus: items sum matches total (cross-validation)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.05) { // Within 5%
                score += 0.10f
            }
        }

        return score.coerceIn(0f, 1f)
    }

    // Utility: serialize line items to JSON
    fun lineItemsToJson(items: List<LineItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("description", item.description)
                put("totalPrice", item.totalPrice)
                item.quantity?.let { put("quantity", it) }
                item.unitPrice?.let { put("unitPrice", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    // Utility: deserialize line items from JSON
    fun lineItemsFromJson(json: String?): List<LineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                LineItem(
                    description = obj.getString("description"),
                    totalPrice = obj.getDouble("totalPrice"),
                    quantity = if (obj.has("quantity")) obj.getDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
