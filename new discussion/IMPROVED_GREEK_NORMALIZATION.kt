/**
 * IMPROVED GREEK OCR NORMALIZATION
 * Based on real OCR output analysis from Greek receipts
 * 
 * Add this to your ReceiptParser.kt to replace the existing normalizeGreekOcr() function
 */
private fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // ============================================
    // PHASE 1: FIX BROKEN NUMBERS (OCR artifacts)
    // ============================================
    
    // "4 5. 5 0" -> "45.50" (spaces in numbers)
    normalized = normalized.replace(Regex("""(?<=\d)\s+(?=\d)"""), "")
    
    // "45 , 00" -> "45.00" (standardize decimal separator)
    normalized = normalized.replace(Regex("""(\d+)\s*[.,]\s*(\d{2})\b"""), "$1.$2")
    
    // ============================================
    // PHASE 2: GREEK LETTER CORRECTIONS
    // Common OCR misreadings based on visual similarity
    // ============================================
    
    // Map of OCR misreadings to correct Greek letters
    // Key: what OCR outputs, Value: what it should be
    val letterCorrections = mapOf(
        // Sigma (Σ) misreadings
        "E" to "Σ",   // E → Σ (most common)
        "Z" to "Σ",   // Z → Σ
        "2" to "Σ",   // 2 → Σ
        "I" to "Σ",   // I → Σ (less common)
        
        // Upsilon (Υ) misreadings  
        "Y" to "Υ",   // Y → Υ
        "V" to "Υ",   // V → Υ
        "U" to "Υ",   // U → Υ
        
        // Omicron (Ο) misreadings
        "0" to "Ο",   // 0 → Ο (in Greek words)
        "O" to "Ο",   // O → Ο (normalize to Greek)
        
        // Lambda (Λ) misreadings
        "A" to "Λ",   // A → Λ (in context)
        "V" to "Λ",   // V → Λ (sometimes)
        
        // Omega (Ω) misreadings
        "W" to "Ω",   // W → Ω
        "O" to "Ω",   // O → Ω (at end of words)
        
        // Pi (Π) misreadings
        "N" to "Π",   // N → Π (at word start)
        "n" to "Π",   // n → Π
        
        // Phi (Φ) misreadings
        "@" to "Φ",   // @ → Φ (common in ΑΦΜ)
        "Q" to "Φ",   // Q → Φ
        "O" to "Φ",   // O → Φ (in context)
        
        // Theta (Θ) misreadings
        "O" to "Θ",   // O → Θ (in context)
        "8" to "Θ",   // 8 → Θ
        
        // Eta (Η) misreadings
        "H" to "Η",   // H → Η (normalize to Greek)
        
        // Tau (Τ) misreadings
        "T" to "Τ",   // T → Τ (normalize to Greek)
    )
    
    // ============================================
    // PHASE 3: KEYWORD PATTERNS
    // Match common receipt keywords with fuzzy matching
    // ============================================
    
    // TOTAL (ΣΥΝΟΛΟ) - Multiple OCR misreadings
    // Covers: EYNONO, ZYNOAO, 2YNONO, IYNOAO, ZYNOIO, YNOA.NONTON, etc.
    normalized = normalized.replace(
        Regex("""\b[EZIY23]?[YVUI]N[O0]?[AΛ\.V]?[NNO0]?[TO0Λ\.V]?[O0ΩI]?\b"""), 
        "TOTAL_KEY"
    )
    
    // Alternative TOTAL patterns seen in receipts
    normalized = normalized.replace(
        Regex("""\b[EZIY23][YVUI]N[O0]?[AΛV][O0ΩI]?\b"""),  // Short form
        "TOTAL_KEY"
    )
    
    // AMOUNT/POSO (ΠΟΣΟ)
    // Covers: NOsO0, ΠΟΣΟ, POSO, ΠΟΣΟ/AMOUNT
    normalized = normalized.replace(
        Regex("""\b[ΠN][O0][Ss][O0]?(?:/AMOUNT)?\b"""),
        "AMOUNT_KEY"
    )
    
    // PAYABLE (ΠΛΗΡΩΤΕΟ)
    normalized = normalized.replace(
        Regex("""\b[ΠN][AΛ][ΗHN][PR][ΩOQ]TE[OA]?\b"""),
        "TOTAL_KEY"
    )
    
    // CASH (ΜΕΤΡΗΤΑ)
    // Covers: METPHTA, ΜΕΤΡΗΤΑ
    normalized = normalized.replace(
        Regex("""\bM[E3]TP[HΉ]TA\b"""),
        "CASH_KEY"
    )
    
    // TAX ID (ΑΦΜ/Α.Φ.Μ.)
    // Covers: @.M., ΑΦΜ, AΦM, A.M.
    normalized = normalized.replace(
        Regex("""\b[AΑ][\.]?[ΦF@][\.]?[ΜM][\.]?\b"""),
        "TAX_ID_KEY"
    )
    
    // PHONE (ΤΗΛ)
    // Covers: THA, ΤΗΛ, THΛ
    normalized = normalized.replace(
        Regex("""\bT[ΗH][ΛA][:\.]?\b"""),
        "PHONE_KEY"
    )
    
    // DATE (ΗΜΕΡΟΜΗΝΙΑ)
    // Covers: HM/NIA, HMEROmhNIA
    normalized = normalized.replace(
        Regex("""\bHM[/\.]?[HN]IA\b"""),
        "DATE_KEY"
    )
    
    // EURO (ΕΥΡΩ)
    // Covers: EYPΩ, EYP9, ΕΥΡΩ
    normalized = normalized.replace(
        Regex("""\b[E3]YP[ΩO9]\b"""),
        "EUR"
    )
    
    // THANK YOU (ΕΥΧΑΡΙΣΤΟΥΜΕ)
    normalized = normalized.replace(
        Regex("""\bEYXAPISTOYME\b"""),
        "THANKYOU_KEY"
    )
    
    // CONTACTLESS (ΑΝΕΠΑΦΗ)
    // Covers: ANENAQH, ΑΝΕΠΑΦΗ
    normalized = normalized.replace(
        Regex("""\bANE[ΠN]A[ΦFQ]H\b"""),
        "CONTACTLESS_KEY"
    )
    
    // ============================================
    // PHASE 4: DATE FIXES
    // ============================================
    
    // "16-D4-2017" → "16-04-2017" (O read as 0, 0 read as D)
    normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d+)[-/](\d{4})"""), "$1-$2-$3")
    
    // ============================================
    // PHASE 5: CURRENCY NOISE REMOVAL
    // ============================================
    
    // Remove currency symbols that may interfere with number parsing
    normalized = normalized
        .replace("EUR", " ")
        .replace("€", " ")
        .replace(Regex("""\s+"""), " ")  // Normalize whitespace
        .trim()
    
    return normalized
}

/**
 * IMPROVED MERCHANT EXTRACTION
 * Add more Greek receipt header markers
 */
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

/**
 * ENHANCED AMOUNT VALIDATION
 */
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
    if (line.contains("/") || line.contains("€/") || line.contains("EUR/")) {
        return false
    }
    
    // Percentage check (VAT rates)
    if (line.contains("%")) {
        return false
    }
    
    return true
}
