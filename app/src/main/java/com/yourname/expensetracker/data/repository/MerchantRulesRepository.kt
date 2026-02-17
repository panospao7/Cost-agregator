package com.yourname.expensetracker.data.repository

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantRulesRepository @Inject constructor() {

    // Keywords that should never be considered as merchant names
    private val invalidMerchants = listOf(
        // Keywords that should never be merchants
        "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START",
        "EAPA", "ADDRESS", "THA", "TEL", "AFM", "AOM", "A.M.", "ΑΦΜ",
        "EYNONO", "ZYNOAO", "SYNOAO", "TOTAL_KEY", "CASH_KEY", "AMOUNT_KEY",
        // Banks & Processors (Specific check available via isCardProcessor)
        "PIRAEUS", "EUROBANK", "ALPHA BANK", "NBG", "NATIONAL BANK",
        "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "REVOLUT",
        "VIVA", "SUMUP", "MYPOS", "STRIPE",
        "CARDLINK", "WORLDLINE", "VISA", "MASTERCARD", "MAESTRO",
        "AMERICAN EXPRESS", "AMEX", "DINERS", "DISCOVER",
        // Transaction types
        "AGORA", "SALE", "PURCHASE", "CONTACTLESS", "TERMINAL",
        "TRANSACTION", "ΠΑΡΑΛΑΒΗ", "ΑΓΟΡΑ",
        // Serial/reference patterns
        "ZEIPA", "SERIAL", "ΑΡΙΘΜΟΣ", "APIOMOE", "APIOMOX",
        // URLs and garbage
        "WWW.", "HTTP", ".GR", ".COM", "HTTPS://",
        // Payment related
        "KAPTA", "KAPTEE", "CARD", "ΚΑΡΤΑ", "METPHTA", "ΜΕΤΡΗΤΑ"
    )

    // Markers indicating the end of the merchant section (header)
    private val headerMarkers = listOf(
        "ΑΦΜ", "A.Φ.Μ.", "Α.Φ.Μ", "@.M.", "A.M.", "AΦM",
        "Α.Ο.Υ.", "ΑΟΥ", "A.0.Y.", "Δ.Ο.Υ.", "ΔΟΥ",
        "ΤΗΛ", "THA", "THΛ", "ΤΗΛ:", "THA:",
        "ΟΔΟΣ", "ΣΤΡ.", "STR.", "ADDRESS",
        "Τ.Κ.", "TK", "Τ.Κ", "T.K.",
        "Α.Μ.Μ.", "ΑΜΜ", "ΑΜΜ.",
        "ΗΜΕΡΟΜΗΝΙΑ", "HM/NIA", "DATE_KEY",
        // Card receipt markers
        "ΑΓΟΡΑ", "AGORA", "AGORA-SALE", "SALE", "PURCHASE", 
        "CONTACTLESS", "TERMINAL", "TRANSACTION", "ENTER BONUS",
        // Card reference patterns
        "****", "5356", "MARK:", "UID:", "AUTH:"
    )

    // Card processor names to explicitly exclude
    private val cardProcessors = listOf(
        "CARDLINK", "WORLDLINE", "VIVA", "PIRAEUS", "EUROBANK", "ALPHA BANK",
        "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "NBG", "REVOLUT", "STRIPE",
        "SUMUP", "MYPOS", "CIBC", "TD BANK", "AMEX", "AMERICAN EXPRESS", "DINERS"
    )

    companion object {
        private val LOCATION_PATTERN = Regex(
            """\s*#[\dA-Za-z]+|""" +
            """\s*-\s*\d+\s*$|""" +
            """\s*Store\s*#?\s*\d+|""" +
            """\s*Branch\s*#?\s*\d+|""" +
            """\s*Unit\s*#?\s*\d+|""" +
            """\s*At\s+[A-Z][a-z]+|""" + 
            """\s*\([\d\s]+\)"""
        )
        
        private val CORPORATE_SUFFIXES = listOf(
            "INC", "INC.", "LLC", "LTD", "LTD.", "CORP", "CORP.", "CORPORATION",
            "CO", "CO.", "COMPANY", "GMBH", "S.A.", "S.A.S", "B.V.", "A.G.", "E.E.", "O.E."
        )
    }

    /**
     * Checks if a potential merchant name contains invalid keywords.
     */
    fun isValidMerchantName(name: String): Boolean {
        if (name.length < 3) return false
        val upperName = name.uppercase()
        return invalidMerchants.none { upperName.contains(it) }
    }

    /**
     * Checks if a line contains any header markers (indicating end of merchant section).
     */
    fun containsHeaderMarker(line: String): Boolean {
        return headerMarkers.any { line.contains(it, ignoreCase = true) }
    }
    
    /**
     * Checks if a name corresponds to a known card processor or bank.
     */
    fun isCardProcessor(name: String): Boolean {
        return cardProcessors.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * Validates a candidate line for merchant extraction using multiple heuristics.
     */
    fun isValidMerchantLine(line: String): Boolean {
        if (line.length < 3) return false
        if (line.all { !it.isLetter() }) return false // Must have letters
        if (invalidMerchants.any { line.contains(it, ignoreCase = true) }) return false
        
        // Skip if line is mostly numbers
        val digitCount = line.count { it.isDigit() }
        if (digitCount > line.length / 2) return false
        
        // Skip lines that are dates or times or tax IDs
        if (line.matches(Regex(""".*(\d{2}[/-]\d{2}[/-]\d{4}|\d{2}:\d{2}:\d{2}|A\.?Φ\.?Μ\.?).*"""))) return false
        
        return true
    }

    /**
     * Cleans a raw merchant string by removing special characters, location markers, and corporate suffixes.
     */
    fun cleanMerchantName(raw: String): String {
        var cleaned = raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s&.'-]"), "").trim()
        
        // Remove location patterns (Store #123)
        cleaned = LOCATION_PATTERN.replace(cleaned, "")
        
        // Remove corporate suffixes
        val upper = cleaned.uppercase()
        for (suffix in CORPORATE_SUFFIXES) {
            if (upper.endsWith(" $suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            } else if (upper.endsWith(",$suffix")) {
                cleaned = cleaned.dropLast(suffix.length + 1).trim()
            }
        }
        
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned.ifEmpty { raw.trim() }
    }
}
