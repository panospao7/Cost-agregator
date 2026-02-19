package com.yourname.expensetracker.domain.util

object AppConstants {
    
    // Confidence Thresholds (LOGIC-004 Consolidation)
    object Confidence {
        const val RULE_BASED = 0.95f
        const val ML_PREDICTION = 0.60f
        const val FUZZY_MATCH = 0.80f
        const val MANUAL_OVERRIDE = 1.0f
        const val RECEIPT_FALLBACK = 0.70f
    }
    
    // Time Windows (In Milliseconds)
    object Windows {
        const val DUPLICATE_DETECTION = 300_000L // 5 minutes (LOGIC-002 expansion)
        const val NOTIFICATION_LRU_MAX_AGE = 30 * 60 * 1000L // 30 minutes
    }
    
    // Parser Limits
    object Parser {
        const val MAX_MERCHANT_LENGTH = 40
        const val MIN_VALID_AMOUNT = 0.10
        const val MAX_RECEIPT_AMOUNT = 50000.0
        const val MAX_GENERIC_PARSER_AMOUNT = 25000.0
    }
}
