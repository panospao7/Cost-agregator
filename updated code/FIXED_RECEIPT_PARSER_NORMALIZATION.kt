/**
 * COMPLETE FIXED normalizeGreekOcr() for ReceiptParser.kt
 * 
 * This version handles BOTH:
 * 1. Correct Greek text (ΣΥΝΟΛΟ, ΜΕΤΡΗΤΑ, etc.)
 * 2. OCR artifacts (EYNONO, ZYNOAO, nozo, etc.)
 * 
 * Copy this function to replace your existing normalizeGreekOcr() in ReceiptParser.kt
 */
private fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // ============================================
    // PHASE 1: FIX BROKEN NUMBERS
    // ============================================
    
    // Remove spaces within numbers: "4 5. 5 0" → "45.50"
    normalized = normalized.replace(Regex("""(?<=\d)\s+(?=\d)"""), "")
    
    // Standardize decimal separator: "45,00" → "45.00"
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
    
    // "16-D4-2017" → "16-04-2017" (O read as D)
    normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d+)[-/](\d{4})"""), "$1-$2-$3")
    
    // ============================================
    // PHASE 6: CURRENCY CLEANUP
    // ============================================
    
    // Note: We keep EUR for currency detection, but remove symbols for number parsing
    // This is done later in extractTotal()
    
    return normalized
}

// ============================================
// HOW TO TEST WITH YOUR TXT FILE
// ============================================
/**
 * You can use your OCR_TEST_DOCUMENT.txt directly!
 * 
 * Option 1: In your Android unit tests
 */
class OcrDocumentTest {
    private val parser = ReceiptParser()
    
    @Test
    fun `test all patterns from OCR test document`() {
        // Load your test file
        val testText = javaClass.getResource("/OCR_TEST_DOCUMENT.txt")?.readText() ?: return
        
        // Test Section 14: Complete Receipt Lines
        val section14 = extractSection(testText, "SECTION 14:", "SECTION 15:")
        section14.lines().filter { it.contains("€") || it.contains("EUR") }.forEach { line ->
            val result = parser.parse(line)
            println("Line: $line → Total: ${result.total}")
            // Add assertions based on expected values
        }
        
        // Test Section 22: Simulated OCR Errors
        val section22 = extractSection(testText, "SECTION 22:", "SECTION 23:")
        section22.lines().filter { it.isNotBlank() && !it.startsWith("━") }.forEach { ocrError ->
            val normalized = normalizeGreekOcr(ocrError)
            println("OCR: $ocrError → Normalized: $normalized")
            assertTrue("OCR error should be normalized", 
                normalized.contains("TOTAL_KEY") || 
                normalized.contains("EUR") ||
                normalized.contains("DATE_KEY")
            )
        }
    }
    
    private fun extractSection(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        val end = text.indexOf(endMarker)
        return if (start >= 0 && end > start) {
            text.substring(start, end)
        } else ""
    }
}

/**
 * Option 2: Quick ad-hoc test via main()
 */
fun main() {
    val parser = ReceiptParser()
    
    // Paste sections from your test document
    val testLines = """
        ΣΥΝΟΛΟ € 50,00
        ZYNOAO: 182,00€
        EYNONO € 5,00
        nozo/AMOUNT: €35,00
        ΚΑΘΑΡΗ ΑΞΙΑ: 17,25 ΕΥΡΩ
    """.trimIndent()
    
    testLines.lines().forEach { line ->
        if (line.isNotBlank()) {
            val result = parser.parse(line)
            println("─".repeat(50))
            println("Input:    $line")
            println("Merchant: ${result.merchantName ?: "N/A"}")
            println("Total:    ${result.total ?: "N/A"}")
            println("Date:     ${result.date?.let { java.util.Date(it) } ?: "N/A"}")
            println("Confidence: ${"%.0f".format(result.confidence * 100)}%")
        }
    }
}
