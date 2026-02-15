/**
 * COMPREHENSIVE FIXES FOR RECEIPT PARSER
 * Based on analysis of 14 real Greek receipts
 * 
 * Issues Found:
 * 1. Receipt numbers picked as totals (APIOMOE, ZEIPA)
 * 2. VAT percentages picked as totals (13.00%)
 * 3. Zero values accepted (0,00)
 * 4. E-prefixed amounts parse incorrectly (E0,13)
 * 5. Keyword and amount on different lines
 * 6. Wrong merchant extraction (card processors, keywords)
 */

// ============================================
// FIX #1: EXCLUDE RECEIPT NUMBERS AND IDs
// ============================================

private fun extractTotal(lines: List<String>): Double? {
    val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?!\s?%)""")
    
    // NEW: Lines that should be COMPLETELY skipped (receipt numbers, IDs, etc.)
    val skipLinePatterns = listOf(
        Regex("""APIOMOE|APIOMOX|APIØMOE""", RegexOption.IGNORE_CASE),  // Receipt number
        Regex("""ZEIPA|SERIAL|AA/Y""", RegexOption.IGNORE_CASE),        // Serial number
        Regex("""AOM|AFM|A\.F\.M\."""),                                  // Tax ID lines
        Regex("""THA|THA:"""),                                           // Phone lines
        Regex("""\d{9,}"""),                                             // Very long numbers (IDs, barcodes)
    )
    
    // NEW: Words that indicate the number is NOT a total
    val nonTotalIndicators = listOf(
        "APIOMOE", "APIOMOX", "ZEIPA", "SERIAL", "AA/Y",
        "AP.r.E.MH", "APIEMOE", "ANEAATH", "APIEMOX"
    )
    
    // Strategy 1: Look for TOTAL_KEY
    val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
    if (totalLineIndex != -1) {
        // Check this line and next 3 lines (amount may be split)
        for (offset in 0..3) {
            if (totalLineIndex + offset < lines.size) {
                val lineToCheck = lines[totalLineIndex + offset]
                // Skip if it looks like a receipt number line
                if (nonTotalIndicators.any { lineToCheck.contains(it) }) continue
                val amount = extractAmountFromLine(lineToCheck, amountRegex)
                if (amount != null && amount > 0.01) return amount  // FIX #3: Reject 0.00
            }
        }
    }
    
    // Strategy 2: Fallback - Find largest VALID amount
    var maxAmount = 0.0
    var maxAmountLine = -1
    
    for (i in lines.indices) {
        val line = lines[i]
        
        // NEW: Skip lines with non-total indicators
        if (nonTotalIndicators.any { line.contains(it) }) continue
        
        // Skip VAT percentage lines
        if (line.contains("%")) continue
        
        // Skip cash/change lines (but not if they also have TOTAL)
        val isCashOnly = (line.contains("CASH_KEY") || line.contains("METPHTA") || 
                          line.contains("METPHTA") || line.contains("CHANGE_KEY")) &&
                         !line.contains("TOTAL_KEY")
        if (isCashOnly) continue
        
        // Skip card reference lines
        if (line.contains("5356") || line.contains("****") || line.contains("ENTER BONUS")) continue
        
        val matches = amountRegex.findAll(line)
        for (match in matches) {
            val rawVal = match.groupValues[1]
            val amount = parseAmount(rawVal)
            
            // FIX #3: Reject zero and near-zero amounts
            if (amount < 0.01) continue
            
            if (isValidAmount(amount, line) && amount > maxAmount) {
                maxAmount = amount
                maxAmountLine = i
            }
        }
    }
    
    return if (maxAmount > 0.0) maxAmount else null
}

// ============================================
// FIX #2: IMPROVED VAT PERCENTAGE EXCLUSION
// ============================================

private fun extractAmountFromLine(line: String, regex: Regex): Double? {
    // NEW: First check if line contains percentage - if so, extract differently
    if (line.contains("%")) {
        // If line has both amount and %, the amount is likely VAT, not total
        // Try to find amount AFTER the percentage
        val afterPercent = line.substringAfter("%", "")
        val matches = regex.findAll(afterPercent)
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }
    
    val matches = regex.findAll(line)
    return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
}

// ============================================
// FIX #3: REJECT INVALID TOTALS
// ============================================

private fun isValidAmount(amount: Double, line: String): Boolean {
    // Reject zero or near-zero
    if (amount < 0.01) return false
    
    // Reject unreasonably large amounts
    if (amount > 5000) return false
    
    // Reject year-like numbers
    if (amount >= 2015.0 && amount <= 2035.0 && amount == amount.toLong().toDouble()) return false
    
    // NEW: Reject if line looks like a receipt number line
    val receiptNumberPatterns = listOf(
        Regex("""APIOMOE|APIOMOX""", RegexOption.IGNORE_CASE),
        Regex("""ZEIPA"""),
        Regex("""AP\.?r\.?E\.?MH"""),
    )
    if (receiptNumberPatterns.any { it.containsMatchIn(line) }) return false
    
    return true
}

// ============================================
// FIX #4: E-PREFIXED AMOUNTS
// ============================================

private fun parseAmount(rawAmount: String): Double {
    if (rawAmount.isBlank()) return 0.0
    
    var cleaned = rawAmount
    
    // NEW: Handle E-prefixed amounts (E0,13 -> try to extract 0.13 or skip)
    // "E" followed by digits often means EUR or is an OCR artifact
    if (cleaned.startsWith("E") || cleaned.startsWith("e")) {
        // Check if rest looks like a valid number
        val rest = cleaned.substring(1)
        if (rest.matches(Regex("""\d+[.,]\d{2}"""))) {
            cleaned = rest  // E0,13 -> 0,13
        }
    }
    
    // Remove all spaces
    cleaned = cleaned.replace(" ", "")
    
    // Find last separator
    val lastComma = cleaned.lastIndexOf(',')
    val lastDot = cleaned.lastIndexOf('.')
    val lastSepIndex = maxOf(lastComma, lastDot)
    
    return if (lastSepIndex >= 0) {
        val integerPart = cleaned.substring(0, lastSepIndex).replace(".", "").replace(",", "")
        val decimalPart = cleaned.substring(lastSepIndex + 1)
        "$integerPart.$decimalPart".toDoubleOrNull() ?: 0.0
    } else {
        cleaned.toDoubleOrNull() ?: 0.0
    }
}

// ============================================
// FIX #5: LOOK AHEAD MULTIPLE LINES
// ============================================

private fun extractTotalWithLookahead(lines: List<String>, startIdx: Int, maxLookahead: Int = 3): Double? {
    val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})""")
    
    for (offset in 0..maxLookahead) {
        if (startIdx + offset >= lines.size) break
        
        val line = lines[startIdx + offset]
        
        // Skip empty or noise lines
        if (line.isBlank()) continue
        if (line.length < 3) continue
        
        val amount = extractAmountFromLine(line, amountRegex)
        if (amount != null && amount > 0.01) {
            return amount
        }
    }
    return null
}

// ============================================
// FIX #6: IMPROVED MERCHANT EXTRACTION
// ============================================

private fun extractMerchant(lines: List<String>): String? {
    // Expanded invalid merchant patterns
    val invalidMerchants = listOf(
        // Keywords that should never be merchants
        "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START",
        "EAPA", "ADDRESS", "THA", "TEL", "AFM", "AOM", "A.M.", 
        "EYNONO", "ZYNOAO", "SYNOAO", "TOTAL_KEY", "CASH_KEY",
        // Card processors
        "CARDLINK", "WORLDLINE", "VISA", "MASTERCARD", "MAESTRO",
        // Serial/reference patterns
        "ZEIPA", "SERIAL",
        // Garbage
        "WWW.", "HTTP", ".GR", ".COM"
    )
    
    // Header markers (indicate we're past the merchant name)
    val headerMarkers = listOf(
        "ΑΦΜ", "A.Φ.Μ.", "Α.Φ.Μ", "@.M.", "A.M.", "AΦM",
        "Α.Ο.Υ.", "ΑΟΥ", "A.0.Y.", "Δ.Ο.Υ.", "ΔΟΥ",
        "ΤΗΛ", "THA", "THΛ", "ΤΗΛ:", "THA:",
        "ΟΔΟΣ", "ΣΤΡ.", "STR.", "ADDRESS",
        "Τ.Κ.", "TK", "Τ.Κ", "T.K.",
        "Α.Μ.Μ.", "ΑΜΜ", "ΑΜΜ.",
        "ΗΜΕΡΟΜΗΝΙΑ", "HM/NIA", "DATE_KEY",
        // NEW: Card receipt markers
        "ΑΓΟΡΑ", "AGORA", "SALE", "PURCHASE"
    )
    
    // Find markers and extract merchant above them
    for ((index, line) in lines.withIndex()) {
        if (index > 10) break
        
        for (marker in headerMarkers) {
            if (line.contains(marker, ignoreCase = true)) {
                // Scan upwards for valid merchant
                for (j in index - 1 downTo 0) {
                    val candidate = lines[j]
                    if (isValidMerchantLine(candidate, invalidMerchants)) {
                        val cleaned = cleanMerchantName(candidate)
                        // Additional check: don't return card processor names
                        if (!isCardProcessor(cleaned)) {
                            return cleaned
                        }
                    }
                }
            }
        }
    }
    
    // Fallback
    for (line in lines.take(5)) {
        if (isValidMerchantLine(line, invalidMerchants)) {
            val cleaned = cleanMerchantName(line)
            if (!isCardProcessor(cleaned)) {
                return cleaned
            }
        }
    }
    
    return null
}

private fun isCardProcessor(name: String): Boolean {
    val processors = listOf("CARDLINK", "WORLDLINE", "VIVA", "PIRAEUS", "EUROBANK", "ALPHA BANK")
    return processors.any { name.contains(it, ignoreCase = true) }
}

// ============================================
// UPDATED NORMALIZATION (include new patterns)
// ============================================

private fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // Fix numbers FIRST
    normalized = normalized.replace(Regex("""\s*([.,])\s*"""), "$1")
    normalized = normalized.replace(Regex("""(?<=\d)\s+(?=\d)"""), "")
    
    // Compound keywords
    normalized = normalized.replace(Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""ΚΑΘΑΡΗ\s+ΑΞΙΑ"""), "SUBTOTAL_KEY")
    normalized = normalized.replace(Regex("""ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")
    
    // Single keywords - CORRECT GREEK
    normalized = normalized.replace(Regex("""\bΣΥΝΟΛΟ\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bΤΕΛΙΚΟ\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bΠΛΗΡΩΤΕΟ\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bΠΟΣΟ\b"""), "AMOUNT_KEY")
    normalized = normalized.replace(Regex("""\bΜΕΤΡΗΤΑ\b"""), "CASH_KEY")
    normalized = normalized.replace(Regex("""\bΕΥΡΩ\b"""), "EUR")
    normalized = normalized.replace(Regex("""\bΦΠΑ\b"""), "VAT_KEY")
    normalized = normalized.replace(Regex("""\bΗΜΕΡΟΜΗΝΙΑ\b"""), "DATE_KEY")
    normalized = normalized.replace(Regex("""\bΡΕΣΤΑ\b"""), "CHANGE_KEY")
    
    // NEW: Card receipt keywords
    normalized = normalized.replace(Regex("""\bΑΓΟΡΑ-SALE\b"""), "CARD_PURCHASE")
    normalized = normalized.replace(Regex("""\bΑΝΕΠΑΦΗ/CONTACTLESS\b"""), "CONTACTLESS_KEY")
    
    // OCR artifacts - TOTAL variants
    normalized = normalized.replace(Regex("""\b[EZI23][YVUI]N[O0I]?[AΛVL]?[O0ΩI]?\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bZYNOAO\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bZYNOIO\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bIYNOAO\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\b2YNOAO\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\b2YNONO\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\b2YN\.?\s*AEIA\b"""), "SUBTOTAL_KEY")
    
    // OCR artifacts - AMOUNT
    normalized = normalized.replace(Regex("""\b[NΠn][O0][SZsz][O0]?\b"""), "AMOUNT_KEY")
    normalized = normalized.replace(Regex("""\bnozo\b"""), "AMOUNT_KEY")
    
    // OCR artifacts - PAYABLE
    normalized = normalized.replace(Regex("""\b[NΠ][AΛ][ΗHN][PR][ΩOQ]TE[OA]?\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bNAHPQTEO\b"""), "TOTAL_KEY")
    
    // OCR artifacts - CASH
    normalized = normalized.replace(Regex("""\bM[E3]TP[HΉ]TA\b"""), "CASH_KEY")
    normalized = normalized.replace(Regex("""\bMETPHTA\b"""), "CASH_KEY")
    
    // OCR artifacts - EUR
    normalized = normalized.replace(Regex("""\b[E3]YP[ΩO9]\b"""), "EUR")
    normalized = normalized.replace(Regex("""\bEVP9\b"""), "EUR")
    normalized = normalized.replace(Regex("""\bEYPQ\b"""), "EUR")
    normalized = normalized.replace(Regex("""\bEYPΩ\b"""), "EUR")
    
    // OCR artifacts - DATE
    normalized = normalized.replace(Regex("""\bHM[/\.]?[ΗH]N?IA\b"""), "DATE_KEY")
    
    // Date fixes
    normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d+)[-/](\d{4})"""), "$1-$2-$3")
    
    return normalized
}
