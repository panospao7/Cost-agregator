package com.yourname.expensetracker.domain.util

import java.util.regex.Pattern

object CommonPatterns {
    
    // Core amount extraction regex (DUP-005 consolidation)
    // Matches patterns like 10.00, 1.234,56, € 10, 10 EUR, etc.
    val AMOUNT_REGEX: Pattern = Pattern.compile(
        """(?:([€$£]|EUR|USD|GBP)\s*)?([-+]?\s*\d+(?:[.,\s]\d{3})*(?:[.,]\d{2}))(?:\s*([€$£]|EUR|USD|GBP))?""",
        Pattern.CASE_INSENSITIVE
    )

    // Common merchant noise prefixes (DUP-006 consolidation)
    val MERCHANT_PREFIXES = listOf(
        "VRP*", "SQ *", "PAYPAL *", "IZ *", "ZETTLE *", "SUMUP *", 
        "STRIPE *", "AMZN Mktp", "APPLE.COM/BILL"
    )
}
