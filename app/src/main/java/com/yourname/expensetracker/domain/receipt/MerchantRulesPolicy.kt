package com.yourname.expensetracker.domain.receipt

/**
 * Domain-level contract for merchant line detection/cleanup rules used by receipt parsing.
 */
interface MerchantRulesPolicy {
    fun containsHeaderMarker(line: String): Boolean
    fun isValidMerchantLine(line: String): Boolean
    fun cleanMerchantName(raw: String): String
    fun isCardProcessor(name: String): Boolean
}
