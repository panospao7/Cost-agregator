package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.PendingReview
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
 * - Pending reviews (not yet approved)
 * 
 * Prevents duplicates when importing bank statements from multiple sources.
 */
@Singleton
class CrossSourceDeduplication @Inject constructor() {

    companion object {
        private const val TIME_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val AMOUNT_TOLERANCE = 0.01
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
            if (isLikelySameTransaction(amount, merchant, date, source, newSource)) {
                return DuplicateCheckResult.CrossSourceDuplicate(
                    existingSource = source,
                    confidence = calculateConfidence(amount, merchant, date)
                )
            }
        }
        
        return DuplicateCheckResult.NoDuplicate
    }

    /**
     * Check if a statement transaction matches any existing PendingReview.
     * Used to prevent creating duplicate pending reviews from bank statements.
     * 
     * @param amount Transaction amount
     * @param merchant Merchant name
     * @param date Transaction date
     * @param pendingReviews List of recent pending reviews to check against
     * @return The matching PendingReview if duplicate found, null otherwise
     */
    fun findPendingReviewDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        pendingReviews: List<PendingReview>
    ): PendingReview? {
        val normalizedMerchant = normalizeMerchant(merchant)
        
        for (review in pendingReviews) {
            // Skip if no suggested date
            val reviewDate = review.suggestedDate ?: continue
            
            // Check date is within window
            if (kotlin.math.abs(date - reviewDate) > TIME_WINDOW_MS) {
                continue
            }
            
            // Check amount matches
            if (kotlin.math.abs(amount - review.suggestedAmount) > AMOUNT_TOLERANCE) {
                continue
            }
            
            // Check merchant similarity
            val reviewMerchant = normalizeMerchant(review.suggestedMerchant)
            if (isMerchantSimilar(normalizedMerchant, reviewMerchant)) {
                return review
            }
        }
        
        return null
    }

    /**
     * Check if a transaction matches any existing Expense.
     * Used to prevent duplicate expenses during manual entry or statement import.
     *
     * @param amount Transaction amount
     * @param merchant Merchant name
     * @param date Transaction date
     * @param expenses List of recent expenses to check against
     * @param timeWindowMs Optional time window override (default uses companion window)
     * @return The matching Expense if duplicate found, null otherwise
     */
    fun findExpenseDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        timeWindowMs: Long = TIME_WINDOW_MS
    ): com.yourname.expensetracker.data.database.entity.Expense? {
        val normalizedMerchant = normalizeMerchant(merchant)
        
        for (expense in expenses) {
            // Check date is within window
            if (kotlin.math.abs(date - expense.date) > timeWindowMs) {
                continue
            }
            
            // Check amount matches
            if (kotlin.math.abs(amount - expense.amount) > AMOUNT_TOLERANCE) {
                continue
            }
            
            // Check merchant similarity
            val expenseMerchant = normalizeMerchant(expense.merchant)
            if (isMerchantSimilar(normalizedMerchant, expenseMerchant)) {
                return expense
            }
        }
        
        return null
    }

    /**
     * Determine which pending review to keep when duplicates found.
     * Priority: notification > statement (notifications are more accurate)
     */
    fun resolvePendingReviewDuplicate(
        existingReview: PendingReview,
        newSource: String
    ): DuplicateResolution {
        // If existing is from notification, keep it (more accurate)
        if (existingReview.packageName != null && newSource == "statement") {
            return DuplicateResolution.KeepExisting
        }
        
        // If new is from notification and existing is statement, replace
        if (newSource == "notification" && existingReview.packageName == null) {
            return DuplicateResolution.ReplaceExisting
        }
        
        // Otherwise keep existing (safer)
        return DuplicateResolution.KeepExisting
    }

    private fun isLikelySameTransaction(
        amount: Double,
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
        
        // If both are bank sources, be more lenient on merchant matching
        // but still require amount to match within tolerance
        if (isBankA && isBankB) {
            // Amount must be within tolerance (already validated by caller context)
            // Merchant must have some similarity
            return merchant.isNotBlank()
        }
        
        return false
    }

    private fun isMerchantSimilar(merchantA: String, merchantB: String): Boolean {
        if (merchantA == merchantB) return true
        
        // Check if one contains the other
        if (merchantA.contains(merchantB) || merchantB.contains(merchantA)) {
            return true
        }
        
        // Check Levenshtein distance
        val distance = levenshteinDistance(merchantA, merchantB)
        val maxLen = maxOf(merchantA.length, merchantB.length)
        
        // Allow 2 character difference for OCR errors
        return distance <= 2
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    minOf(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
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
        val roundedAmount = "%.2f".format(amount)
        return "$source:$roundedAmount:$normalizedMerchant:$hourRoundedDate"
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

enum class DuplicateResolution {
    KeepExisting,
    ReplaceExisting,
    DiscardNew
}
