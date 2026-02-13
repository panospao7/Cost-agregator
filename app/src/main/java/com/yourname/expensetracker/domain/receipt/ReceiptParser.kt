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
            """(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΠΟΣΟ|ΑΞΙΑ|VALUE|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|ΛΟΓΑΡΙΑΣΜΟ[ΣΖ]|TOTAL|AMOUNT|PAYMENT|ΠΛΗΡΩΜΗ)\s*[:\s]*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Amount with currency symbol at end
        Pattern.compile(
            """(?:TOTAL|ΣΥΝΟΛΟ|ΠΟΣΟ|AMOUNT|ΑΞΙΑ|VALUE|PAYMENT|ΠΛΗΡΩΜΗ)\s*[:\s]*(\d+[\s.,]\s*\d{2})\s*(?:€|EUR)""",
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

    private fun normalizeGreekOcr(text: String): String {
        var normalized = text.uppercase()

        // ============================================
        // PHASE 1: FIX BROKEN NUMBERS
        // ============================================

        // Remove spaces within numbers: "4 5. 5 0" -> "45.50"
        // CRITICAL FIX: Use [ \t\u00A0] instead of \s to avoid merging numbers across newlines (e.g. "PUMP 1\n45.00" -> "145.00")
        normalized = normalized.replace(Regex("""(?<=\d)[ \t\u00A0]+(?=\d)"""), "")

        // Standardize decimal separator: "45,00" -> "45.00"
        normalized = normalized.replace(Regex("""(\d+)\s*[.,]\s*(\d{2})\b"""), "$1.$2")

        // ============================================
        // PHASE 2: COMPOUND KEYWORDS FIRST (Multi-word)
        // ============================================

        // These must come BEFORE single-word patterns to avoid partial matches

        // ΣΥΝΟΛΙΚΗ ΑΞΙΑ (Total Value)
        normalized = normalized.replace(Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""[EZI23]YN[O0]IKH\s+A[E3]IA"""), "TOTAL_KEY")

        // ΚΑΘΑΡΗ ΑΞΙΑ (Net Value / Subtotal)
        normalized = normalized.replace(Regex("""ΚΑΘΑΡΗ\s+ΑΞΙΑ"""), "SUBTOTAL_KEY")
        normalized = normalized.replace(Regex("""KA[ΘA]APH\s+A[E3]IA"""), "SUBTOTAL_KEY")

        // ΓΕΝΙΚΟ ΣΥΝΟΛΟ (Grand Total)
        normalized = normalized.replace(Regex("""ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ"""), "TOTAL_KEY")

        // ΜΕΡΙΚΟ ΣΥΝΟΛΟ (Partial Total)
        normalized = normalized.replace(Regex("""ΜΕΡΙΚΟ\s+ΣΥΝΟΛΟ"""), "SUBTOTAL_KEY")

        // ΤΙΜΗ ΜΟΝΑΔΟΣ (Unit Price) - should NOT be picked as total
        normalized = normalized.replace(Regex("""ΤΙΜΗ\s+ΜΟΝΑΔΟΣ"""), "UNIT_PRICE_KEY")

        // ============================================
        // PHASE 3: CORRECT GREEK SINGLE KEYWORDS
        // ============================================

        // ΣΥΝΟΛΟ (Total)
        normalized = normalized.replace(Regex("""\bΣΥΝΟΛΟ\b"""), "TOTAL_KEY")

        // ΤΕΛΙΚΟ (Final)
        normalized = normalized.replace(Regex("""\bΤΕΛΙΚΟ\b"""), "TOTAL_KEY")

        // ΠΛΗΡΩΤΕΟ (Payable)
        normalized = normalized.replace(Regex("""\bΠΛΗΡΩΤΕΟ\b"""), "TOTAL_KEY")

        // ΠΟΣΟ (Amount)
        normalized = normalized.replace(Regex("""\bΠΟΣΟ\b"""), "AMOUNT_KEY")

        // ΑΞΙΑ (Value) - standalone
        normalized = normalized.replace(Regex("""\bΑΞΙΑ\b"""), "VALUE_KEY")

        // ΜΕΤΡΗΤΑ (Cash)
        normalized = normalized.replace(Regex("""\bΜΕΤΡΗΤΑ\b"""), "CASH_KEY")

        // ΚΑΡΤΑ (Card)
        normalized = normalized.replace(Regex("""\bΚΑΡΤΑ\b"""), "CARD_KEY")

        // ΕΥΡΩ (Euro)
        normalized = normalized.replace(Regex("""\bΕΥΡΩ\b"""), "EUR")

        // ΦΠΑ / Φ.Π.Α. (VAT)
        normalized = normalized.replace(Regex("""\bΦ\.?Π\.?Α\.?\b"""), "VAT_KEY")

        // ΗΜΕΡΟΜΗΝΙΑ (Date)
        normalized = normalized.replace(Regex("""\bΗΜΕΡΟΜΗΝΙΑ\b"""), "DATE_KEY")

        // ΩΡΑ (Time)
        normalized = normalized.replace(Regex("""\bΩΡΑ\b"""), "TIME_KEY")

        // ΕΚΠΤΩΣΗ (Discount)
        normalized = normalized.replace(Regex("""\bΕΚΠΤΩΣΗ\b"""), "DISCOUNT_KEY")

        // ΡΕΣΤΑ (Change)
        normalized = normalized.replace(Regex("""\bΡΕΣΤΑ\b"""), "CHANGE_KEY")

        // ============================================
        // PHASE 4: OCR ARTIFACT PATTERNS
        // These handle common OCR misreadings
        // ============================================

        // --- TOTAL (ΣΥΝΟΛΟ) OCR Variants ---
        // E→Σ, Z→Σ, 2→Σ, I→Σ, Y→Υ, N→Ν, O→Ο/0, Λ→A/Λ

        // Pattern: [E-Z-I-2-3][Y-V-U-I]N[O-0]?[Λ-A-L-V]?[O-0]?
        normalized = normalized.replace(
            Regex("""\b[EZI23][YVUI]N[O0I]?[AΛVL]?[O0ΩI]?\b"""),
            "TOTAL_KEY"
        )

        // Extra coverage for tricky variants
        normalized = normalized.replace(Regex("""\bZYNOAO\b"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""\bZYNOIO\b"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""\bIYNOAO\b"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""\bIYN\.?\s*[O0]?N[AΛV]?O[NT]?\b"""), "TOTAL_KEY")


        // --- AMOUNT (ΠΟΣΟ) OCR Variants ---
        // Π→N, n, O→O/0, Σ→s/z
        normalized = normalized.replace(Regex("""\b[NΠn][O0][SZsz][O0]?\b"""), "AMOUNT_KEY")
        normalized = normalized.replace(Regex("""\bnozo\b"""), "AMOUNT_KEY")

        // --- PAYABLE (ΠΛΗΡΩΤΕΟ) OCR Variants ---
        normalized = normalized.replace(
            Regex("""\b[NΠ][AΛ][ΗHN][PR][ΩOQ]TE[OA]?\b"""),
            "TOTAL_KEY"
        )
        normalized = normalized.replace(Regex("""\bNAHPQTEO\b"""), "TOTAL_KEY")

        // --- CASH (ΜΕΤΡΗΤΑ) OCR Variants ---
        normalized = normalized.replace(Regex("""\bM[E3]TP[HΉ]TA\b"""), "CASH_KEY")

        // --- EURO (ΕΥΡΩ) OCR Variants ---
        normalized = normalized.replace(Regex("""\b[E3]YP[ΩO9]\b"""), "EUR")

        // --- DATE (ΗΜΕΡΟΜΗΝΙΑ) OCR Variants ---
        normalized = normalized.replace(Regex("""\bHM[/\.]?[ΗH]N?IA\b"""), "DATE_KEY")

        // --- VAT (ΦΠΑ) OCR Variants ---
        normalized = normalized.replace(Regex("""\b[FΦ]II?A\.?\b"""), "VAT_KEY")

        // --- CONTACTLESS (ΑΝΕΠΑΦΗ) OCR Variants ---
        normalized = normalized.replace(Regex("""\bANE[ΠN]A[ΦFQ]H\b"""), "CONTACTLESS_KEY")
        normalized = normalized.replace(Regex("""\bANEIIAQH\b"""), "CONTACTLESS_KEY")

        // ============================================
        // PHASE 5: DATE FIXES
        // ============================================

        // "16-D4-2017" -> "16-04-2017" (O read as D)
        normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d+)[-/](\d{4})"""), "$1-$2-$3")

        // ============================================
        // PHASE 6: CURRENCY CLEANUP
        // ============================================

        // Note: We keep EUR for currency detection, but remove symbols for number parsing
        // This is done later in extractTotal()
        
        return normalized
            .replace("EUR", "") // Removed at end to avoid interfering with keywords
            .replace("€", "")
    }

    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Expanded list of header markers that indicate we're past the merchant name
        val headerMarkers = listOf(
            // Tax IDs
            "ΑΦΜ", "A.Φ.Μ.", "Α.Φ.Μ", "@.M.", "A.M.", "AΦM",
            // Business types
            "Α.Ο.Υ.", "ΑΟΥ", "A.0.Y.", "Δ.Ο.Υ.", "ΔΟΥ",
            // Phone
            "ΤΗΛ", "THA", "THΛ", "ΤΗΛ:", "THA:", "PHONE_KEY",
            // Address
            "ΟΔΟΣ", "ΣΤΡ.", "STR.", "ADDRESS",
            // Postal code
            "Τ.Κ.", "TK", "Τ.Κ", "T.K.",
            // Registration
            "Α.Μ.Μ.", "ΑΜΜ", "ΑΜΜ.",
            // Date/Time
            "ΗΜΕΡΟΜΗΝΙΑ", "HM/NIA", "DATE_KEY",
            // Store indicators
            "ΥΠΟΚΑΤΑΣΤΗΜΑ", "KATASTHMA", "ΚΑΤΑΣΤΗΜΑ",
            // Receipt info
            "ΑΠΟΔΕΙΞΗ", "AΠΟΔΕΙΞΗ", "ΛΙΑΝΙΚΗ", "ΛΙΑΝΙΚΗΣ"
        )

        // Lines that should be skipped as they're not merchant names
        val invalidHeaders = listOf(
            "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START",
            "EAPA", "ADDRESS", "THA", "TEL", "AFM", "AOM", "A.M.", "TAX_ID_KEY",
            "YPOTRTEIO", "KATASTHMA"
        )

        // Scan for markers and extract merchant above them
        for ((index, line) in lines.withIndex()) {
            if (index > 10) break  // Merchant usually in first 10 lines
            
            // Check if this line is an anchor
            for (marker in headerMarkers) {
                if (line.contains(marker, ignoreCase = true)) {
                    // Found marker - scan upwards for valid merchant
                    for (j in index - 1 downTo 0) {
                        val candidate = lines[j]
                        if (isValidMerchantLine(candidate, invalidHeaders)) {
                            return cleanMerchantName(candidate)
                        }
                    }
                }
            }
        }
        
        // Fallback: First valid line
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
        
        // Removed bottom 70% restriction to catch totals at top (rare but possible) or middle
        val searchStart = 0
        
        for (i in searchStart until lines.size) {
            val line = lines[i]

            // FILTER: Ignore lines that definitely aren't the total
            if (line.contains("%") || line.contains("VAT_KEY")) continue // Ignore VAT lines
            if (line.contains("METPHTA") || line.contains("CASH") || line.contains("CASH_KEY")) continue // Ignore Cash Given
            if (line.contains("RESTA") || line.contains("ΡΕΣΤΑ") || line.contains("CHANGE_KEY")) continue // Ignore Change
            if (line.contains("KARTA") || line.contains("CARD") || line.contains("CARD_KEY")) continue // Ignore "Card" references
            if (line.contains("UNIT_PRICE_KEY")) continue // Ignore Unit Prices

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
        // Basic bounds
        if (amount <= 0.0) return false
        if (amount > 5000.0) return false  // Reasonable receipt max
        
        // Year check - but allow decimal amounts in year range
        // e.g., 2020.50 is a valid amount, 2020 is likely a year
        if (amount >= 2015.0 && amount <= 2035.0) {
            // If it's a whole number, it's probably a year
            if (amount % 1.0 == 0.0) return false
            // If it has decimals, it might be a valid price like €2020.50
        }
        
        // Time check
        if (line.contains("ΩΡΑ") || line.contains("ORA") || line.contains("QPA")) {
            return false
        }
        
        // Unit price check (e.g., "1,574 €/ΛΤ")
        if (line.contains("/") || line.contains("€/") || line.contains("EUR/") || line.contains("UNIT_PRICE_KEY")) {
            return false
        }
        
        // Percentage check (VAT rates)
        if (line.contains("%")) {
            return false
        }
        
        return true
    }

    private fun parseAmount(rawAmount: String): Double {
        // Safe parsing: 
        // 1. Identify last separator (. or ,)
        // 2. Everything before is thousands separator -> remove
        // 3. Last separator is decimal -> replace with .
        
        if (rawAmount.isBlank()) return 0.0
        
        val lastComma = rawAmount.lastIndexOf(',')
        val lastDot = rawAmount.lastIndexOf('.')
        val lastSeparatorIndex = kotlin.math.max(lastComma, lastDot)
        
        var clean = rawAmount
        
        if (lastSeparatorIndex != -1) {
            // Check if it's really a decimal separator (followed by 1 or 2 digits usually)
            // But strict "last wins" is safer for mixed formats like 1.250,50
            
            // Remove all OTHER separators
            val prefix = rawAmount.substring(0, lastSeparatorIndex).replace(Regex("[.,]"), "")
            val suffix = rawAmount.substring(lastSeparatorIndex + 1)
            
            clean = "$prefix.$suffix"
        }
        
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
                
                // SANITY CHECK: Year must be reasonable (e.g., 2015-2035)
                // Fixes Receipt #8 where OCR read 2058
                val yearInt = year.toIntOrNull() ?: 0
                if (yearInt in 2015..2035) { 
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
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ|AMOUNT|ΠΟΣΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΑΞΙΑ|VALUE)"""
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
