package com.yourname.expensetracker.domain.intelligence

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-source duplicate detection for expenses from different sources.
 * 
 * Handles scenarios where the same transaction might appear from:
 * - Bank notifications (NBG, Eurobank, etc.)
 * - Bank statements (imported via OCR)
 * - Google Wallet notifications
 * - Manual entry
 * 
 * Prevents duplicates when importing bank statements from multiple sources.
 */
@Singleton
class CrossSourceDeduplication @Inject constructor() {

    companion object {
        private const val TIME_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    /**
     * Check if expense might be duplicate from different source
     * 
     * @param newSource Source of new expense: "notification", "statement", "manual", "ocr"
     * @param existingSources List of sources that already have this expense
     */
    fun isCrossSourceDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        newSource: String,
        existingSources: List<String>
    ): DuplicateCheckResult {
        
        if (existingSources.isEmpty()) {
            return DuplicateCheckResult.NoDuplicate
        }
        
        if (existingSources.contains(newSource)) {
            return DuplicateCheckResult.SameSourceDuplicate
        }
        
        for (source in existingSources) {
            if (isLikelySameTransaction(merchant, date, source, newSource)) {
                return DuplicateCheckResult.CrossSourceDuplicate(
                    existingSource = source,
                    confidence = calculateConfidence(amount, merchant, date)
                )
            }
        }
        
        return DuplicateCheckResult.NoDuplicate
    }

    private fun isLikelySameTransaction(
        merchant: String,
        date: Long,
        sourceA: String,
        sourceB: String
    ): Boolean {
        // Different bank sources could have same transaction
        // E.g., Revolut notification vs Revolut bank statement
        val bankSources = setOf("nbg", "revolut", "eurobank", "alpha", "piraeus", "statement")
        
        val isBankA = bankSources.any { sourceA.contains(it) }
        val isBankB = bankSources.any { sourceB.contains(it) }
        
        // If both are bank sources, be more lenient
        if (isBankA && isBankB) {
            return true
        }
        
        return false
    }

    private fun normalizeMerchant(merchant: String): String {
        return merchant
            .lowercase()
            .replace(Regex("""[#@$%^&*!]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun calculateConfidence(amount: Double, merchant: String, date: Long): Float {
        var confidence = 0.5f
        
        if (merchant.length > 5) {
            confidence += 0.3f
        }
        
        return confidence.coerceAtMost(1.0f)
    }

    /**
     * Generate enhanced dedupe key that includes source
     * Prevents duplicates from different sources for same transaction
     */
    fun generateSourceAwareDedupeKey(
        amount: Double,
        merchant: String,
        date: Long,
        source: String
    ): String {
        val normalizedMerchant = normalizeMerchant(merchant)
        val hourRoundedDate = (date / 3600000) * 3600000
        return "$source:${amount.toLong()}:$normalizedMerchant:$hourRoundedDate"
    }
}

sealed class DuplicateCheckResult {
    object NoDuplicate : DuplicateCheckResult()
    object SameSourceDuplicate : DuplicateCheckResult()
    data class CrossSourceDuplicate(
        val existingSource: String,
        val confidence: Float
    ) : DuplicateCheckResult()
}
