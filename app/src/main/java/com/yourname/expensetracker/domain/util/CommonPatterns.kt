package com.yourname.expensetracker.domain.util

import java.util.regex.Pattern

object CommonPatterns {
    
    // Core amount extraction regex (DUP-005 consolidation)
    // Matches patterns like 10.00, 1.234,56, € 10, 10 EUR, etc.
    val AMOUNT_REGEX: Pattern = Pattern.compile(
        """(?:([€$£]|EUR|USD|GBP)\s*)?([-+]?\s*\d+(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|[-+]?\s*\d+(?:[.,]\d{1,2})?)(?:\s*([€$£]|EUR|USD|GBP))?""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Shared grouped-amount token fragment for embedding inside parser-specific regexes.
     *
     * Captures the full raw numeric token including optional thousands separators.
     * Supported formats:
     * - Plain decimal: 12.50, 8,99
     * - US grouped: 1,234.56 / 12,345.67
     * - EU grouped: 1.234,56 / 12.345,67
     * - Integer with groups: 1,000 / 1.000
     *
     * The captured token should be passed to [AmountUtils.parseAmount] for normalization.
     * This fragment is intentionally narrow: it requires at least one digit and does NOT
     * match sign prefixes, currency symbols, or whitespace — those belong in the
     * surrounding parser regex.
     */
    const val GROUPED_AMOUNT_TOKEN: String =
        """\d{1,3}(?:[.,]\d{3})*[.,]\d{1,2}|\d+[.,]\d{1,2}|\d{1,3}(?:[.,]\d{3})+"""

    // Common merchant noise prefixes (DUP-006 consolidation)
    val MERCHANT_PREFIXES = listOf(
        "VRP*", "SQ *", "PAYPAL *", "IZ *", "ZETTLE *", "SUMUP *", 
        "STRIPE *", "AMZN Mktp", "APPLE.COM/BILL"
    )
}
