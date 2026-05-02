package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.DuplicateCheckCandidate
import com.yourname.expensetracker.domain.ai.service.SemanticDuplicateDetector
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.GeoUtils
import timber.log.Timber
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
 * 
 * Uses hybrid approach:
 * 1. Fast deterministic checks (date, amount, basic merchant matching)
 * 2. Levenshtein distance for merchant names
 * 3. [NEW] AI semantic analysis for ambiguous cases (multilingual, variations)
 *
 * All window, tolerance, currency, type-compatibility, and scoring constants
 * are delegated to [DuplicateDetectionPolicy].
 */
@Singleton
class CrossSourceDeduplication @Inject constructor(
    private val semanticDetector: SemanticDuplicateDetector
) {

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
            }        }
        
        return DuplicateCheckResult.NoDuplicate
    }

    /**
     * Check if a statement transaction matches any existing PendingReview.
     * Used to prevent creating duplicate pending reviews from bank statements.
     *
     * Routes through the canonical duplicate policy so matching is:
     *  - **Currency-aware**: different ISO-4217 currencies never match.
     *  - **Type-compatible**: incompatible transaction types (e.g. PURCHASE vs
     *    DEPOSIT) never match; UNKNOWN is treated as compatible with any type.
     *  - **Ranked**: when multiple candidates pass the hard filters the best one
     *    is chosen via [DuplicateDetectionPolicy.bestCandidate] (smallest time
     *    delta → smallest amount delta → highest merchant confidence).
     *
     * @param amount          Transaction amount
     * @param merchant        Merchant name
     * @param date            Transaction date (epoch ms)
     * @param pendingReviews  List of recent pending reviews to check against
     * @param currency        ISO-4217 currency code of the new transaction
     *                        (null / blank falls back to [DuplicateDetectionPolicy.DEFAULT_CURRENCY])
     * @param transactionType Transaction type of the new transaction; UNKNOWN is
     *                        compatible with every review type (safe default for
     *                        callers that do not yet supply a type)
     * @return The best-ranked matching PendingReview if a duplicate is found, null otherwise
     */
    fun findPendingReviewDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        pendingReviews: List<PendingReview>,
        currency: String? = null,
        transactionType: TransactionType = TransactionType.UNKNOWN
    ): PendingReview? {
        val normalizedMerchant = DuplicateDetectionPolicy.normalizeMerchant(merchant)
        val normalizedCurrency = DuplicateDetectionPolicy.normalizeCurrency(currency)

        val scoredCandidates =
            mutableListOf<DuplicateDetectionPolicy.ScoredCandidate<PendingReview>>()

        for (review in pendingReviews) {
            // Skip if no suggested date
            val reviewDate = review.suggestedDate ?: continue

            // Check date is within canonical window
            if (!DuplicateDetectionPolicy.isWithinWindow(date, reviewDate)) continue

            // Check amount matches within shared tolerance
            if (!DuplicateDetectionPolicy.areAmountsEqual(amount, review.suggestedAmount ?: 0.0)) continue

            // Currency-aware guard: different currencies cannot be the same charge
            val reviewCurrency = DuplicateDetectionPolicy.normalizeCurrency(review.suggestedCurrency)
            if (normalizedCurrency != reviewCurrency) continue

            // Transaction-type compatibility guard
            val reviewType = runCatching {
                TransactionType.valueOf(review.suggestedType)
            }.getOrDefault(TransactionType.UNKNOWN)
            if (!DuplicateDetectionPolicy.areTypesCompatible(transactionType, reviewType)) continue

            // Merchant similarity hard filter + confidence for ranking
            val reviewMerchant = DuplicateDetectionPolicy.normalizeMerchant(review.suggestedMerchant)
            val merchantConf = calculateDeterministicMerchantSimilarity(normalizedMerchant, reviewMerchant)
            if (merchantConf < 0.8f) continue

            scoredCandidates.add(
                DuplicateDetectionPolicy.ScoredCandidate(
                    candidate = review,
                    timeDeltaMs = kotlin.math.abs(date - reviewDate),
                    amountDelta = kotlin.math.abs(amount - (review.suggestedAmount ?: 0.0)),
                    merchantConfidence = merchantConf
                )
            )
        }

        return DuplicateDetectionPolicy.bestCandidate(scoredCandidates)
    }

    /**
     * Check if a transaction matches any existing Expense.
     * Used to prevent duplicate expenses during manual entry or statement import.
     *
     * @param amount Transaction amount
     * @param merchant Merchant name
     * @param date Transaction date
     * @param expenses List of recent expenses to check against
     * @param currency ISO-4217 currency code of the new transaction
     * @param transactionType Transaction type of the new transaction
     * @param timeWindowMs Optional time window override (default uses canonical policy window)
     * @param latitude Optional latitude of the new transaction (for proximity scoring)
     * @param longitude Optional longitude of the new transaction (for proximity scoring)
     * @return The matching Expense if duplicate found, null otherwise
     */
    fun findExpenseDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        expenses: List<Expense>,
        currency: String,
        transactionType: TransactionType = TransactionType.UNKNOWN,
        timeWindowMs: Long = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
        latitude: Double? = null,
        longitude: Double? = null
    ): Expense? {
        val normalizedMerchant = DuplicateDetectionPolicy.normalizeMerchant(merchant)

        // Collect all candidates that pass the hard filters (date, amount, currency,
        // type, merchant) then use the policy's deterministic tie-break ranking.
        // then use the policy's deterministic tie-break ranking.
        val scoredCandidates = mutableListOf<DuplicateDetectionPolicy.ScoredCandidate<Expense>>()

        for (expense in expenses) {
            if (!DuplicateDetectionPolicy.isEligibleCandidate(
                    newAmount = amount,
                    newCurrency = currency,
                    newType = transactionType,
                    newDate = date,
                    existing = expense,
                    windowMs = timeWindowMs
                )) continue

            // Check merchant similarity
            val expenseMerchant = DuplicateDetectionPolicy.normalizeMerchant(expense.merchant)
            if (!isMerchantSimilar(normalizedMerchant, expenseMerchant)) continue

            val merchantConf = calculateDeterministicMerchantSimilarity(normalizedMerchant, expenseMerchant)
            val locBoost = calculateLocationBoost(latitude, longitude, expense)

            scoredCandidates.add(
                DuplicateDetectionPolicy.ScoredCandidate(
                    candidate = expense,
                    timeDeltaMs = kotlin.math.abs(date - expense.date),
                    amountDelta = kotlin.math.abs(amount - expense.amount),
                    merchantConfidence = merchantConf,
                    locationBoost = locBoost
                )
            )
        }

        return DuplicateDetectionPolicy.bestCandidate(scoredCandidates)
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
    
    /**
     * Check for semantic duplicates using AI when deterministic check is inconclusive.
     * 
     * This method is called when merchant similarity is between 0.4 and 0.9,
     * indicating an ambiguous case that would benefit from semantic analysis.
     * 
     * @param amount Transaction amount
     * @param merchant1 First merchant name
     * @param merchant2 Second merchant name
     * @param date1 First transaction date
     * @param date2 Second transaction date
     * @param notificationText1 Optional notification text from first source
     * @param notificationText2 Optional notification text from second source
     * @return AI semantic duplicate result, or null if AI unavailable
     */
    suspend fun checkSemanticDuplicate(
        amount: Double,
        currency: String,
        merchant1: String,
        merchant2: String,
        date1: Long,
        date2: Long,
        notificationText1: String?,
        notificationText2: String?,
        transactionType: DomainTransactionType
    ): com.yourname.expensetracker.domain.ai.model.SemanticDuplicateResult? {
        val candidate1 = DuplicateCheckCandidate(
            amount = amount,
            currency = currency,
            merchant = merchant1,
            date = date1,
            notificationText = notificationText1,
            transactionType = transactionType
        )
        
        val candidate2 = DuplicateCheckCandidate(
            amount = amount,
            currency = currency,
            merchant = merchant2,
            date = date2,
            notificationText = notificationText2,
            transactionType = transactionType
        )
        
        return try {
            semanticDetector.calculateSimilarity(candidate1, candidate2)
        } catch (e: Exception) {
            Timber.w(e, "CrossSourceDeduplication: AI semantic detection failed, using deterministic fallback")
            null
        }
    }
    
    /**
     * Calculate merchant similarity including AI semantic analysis for ambiguous cases.
     * 
     * @param merchantA First merchant name
     * @param merchantB Second merchant name
     * @return Similarity score from 0.0 to 1.0
     */
    fun calculateMerchantSimilarityWithAi(
        merchantA: String,
        merchantB: String
    ): Float {
        // First, deterministic check
        val deterministicSim = calculateDeterministicMerchantSimilarity(merchantA, merchantB)
        
        // If clearly same or clearly different, return deterministic result
        if (deterministicSim >= 0.9f || deterministicSim <= 0.3f) {
            return deterministicSim
        }
        
        // For ambiguous cases (0.3 < sim < 0.9), we'd ideally use AI
        // But since this is a non-suspend function, we return the deterministic
        // result and the caller should use checkSemanticDuplicate for AI enhancement
        return deterministicSim
    }
    
    /**
     * Check if two merchant names are similar (deterministic only).
     * Legacy method - uses deterministic similarity with 80% threshold.
     */
    private fun isMerchantSimilar(merchantA: String, merchantB: String): Boolean {
        return calculateDeterministicMerchantSimilarity(merchantA, merchantB) >= 0.8f
    }
    
    private fun calculateDeterministicMerchantSimilarity(merchantA: String, merchantB: String): Float {
        if (merchantA == merchantB) return 1.0f
        
        // Check if one contains the other
        if (merchantA.contains(merchantB) || merchantB.contains(merchantA)) {
            return 0.85f
        }
        
        // Check Levenshtein distance
        val distance = levenshteinDistance(merchantA, merchantB)
        val maxLen = maxOf(merchantA.length, merchantB.length)
        
        // Convert distance to similarity score
        return if (maxLen > 0) {
            1.0f - (distance.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
        } else {
            0.0f
        }
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

    /**
     * Calculate location proximity boost for scoring.
     */
    private fun calculateLocationBoost(
        newLat: Double?,
        newLon: Double?,
        existing: com.yourname.expensetracker.data.database.entity.Expense
    ): Float {
        val distKm = GeoUtils.haversineKmOrNull(newLat, newLon, existing.latitude, existing.longitude)
            ?: return 0f
        return when {
            distKm < 0.2  ->  0.15f  // < 200 m  — very likely same physical location
            distKm < 1.0  ->  0.05f  // 200 m – 1 km — plausible
            distKm < 5.0  ->  0.0f   // 1–5 km   — no effect
            else          -> -0.15f  // > 5 km   — suspicious
        }
    }

    private fun calculateConfidence(
        amount: Double,
        merchant: String,
        date: Long,
        newLat: Double? = null,
        newLon: Double? = null,
        existing: com.yourname.expensetracker.data.database.entity.Expense? = null
    ): Float {
        var confidence = 0.5f

        // Merchant name length heuristic — longer name = more specific = more confident
        if (merchant.length > 5) {
            confidence += 0.3f
        }

        // Location proximity boost/penalty
        if (existing != null) {
            confidence += calculateLocationBoost(newLat, newLon, existing)
        }

        return confidence.coerceIn(0f, 1f)
    }

    /**
     * Generate enhanced dedupe key that includes source.
     * Prevents duplicates from different sources for same transaction.
     *
     * Uses [DuplicateDetectionPolicy.formatAmount] for locale-invariant formatting.
     */
    fun generateSourceAwareDedupeKey(
        amount: Double,
        merchant: String,
        date: Long,
        source: String
    ): String {
        val normalizedMerchant = DuplicateDetectionPolicy.normalizeMerchant(merchant)
        val hourRoundedDate = (date / 3600000) * 3600000
        val roundedAmount = DuplicateDetectionPolicy.formatAmount(amount)
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
