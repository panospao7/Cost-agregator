package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingDecision {
    AUTO_ACCEPT,    // High confidence → create expense immediately
    NEEDS_REVIEW,   // Medium confidence → add to review queue
    AUTO_REJECT     // Low confidence → silently drop
}

data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)

@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsRepository: SourceStatsRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val classifier: TransactionClassifier,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
        const val CACHE_TTL = 60_000L // 1 minute
        
        // ML Thresholds
        private const val ML_CONFIDENT_THRESHOLD = 0.8f
        private const val ML_LIKELY_THRESHOLD = 0.3f
        
        // ML Sample sizes for weight calculation
        private const val ML_SAMPLES_MIN = 20
        private const val ML_SAMPLES_LOW = 50
        private const val ML_SAMPLES_MED = 100
        private const val ML_SAMPLES_HIGH = 200
        
        // Trust Modifiers
        private const val TRUST_MOD_SPAM = 0.1f
        private const val TRUST_MOD_HIGH = 1.1f
        private const val TRUST_MOD_NEUTRAL = 1.0f
        private const val TRUST_MOD_LOW = 0.9f
        private const val TRUST_MOD_BAD = 0.5f
        
        // Trust Score Thresholds
        private const val TRUST_SCORE_HIGH = 0.8f
        private const val TRUST_SCORE_NEUTRAL = 0.4f
        private const val TRUST_SCORE_LOW = 0.15f
        
        // Source Stats Requirement
        private const val MIN_NOTIFICATIONS_FOR_TRUST = 10
        
        // Rejection Thresholds
        private const val MERCHANT_REJECTION_THRESHOLD = 0.5f
        private const val PACKAGE_REJECTION_THRESHOLD = 0.7f
        private const val PREVIOUS_APPROVAL_BOOST = 1.2f
        private const val AUTO_REJECT_PENALTY_PACKAGE = 0.3f
        private const val UNKNOWN_MERCHANT_PENALTY = 0.5f
    }

    // Caches with timestamp: Value -> Timestamp
    private val sourceStatsCache = ConcurrentHashMap<String, Pair<SourceStats?, Long>>()
    private val merchantRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val packageRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val approvalCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    
    private val MAX_CACHE_SIZE = 1000

    private fun checkCacheSize() {
        if (sourceStatsCache.size > MAX_CACHE_SIZE) sourceStatsCache.clear()
        if (merchantRejectionCache.size > MAX_CACHE_SIZE) merchantRejectionCache.clear()
        if (packageRejectionCache.size > MAX_CACHE_SIZE) packageRejectionCache.clear()
        if (approvalCache.size > MAX_CACHE_SIZE) approvalCache.clear()
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        checkCacheSize()
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()

        // 1. ML classifier prediction (if ready and needed)
        // Skip ML if parser is extremely confident (e.g. exact template match) to save resources
        if (notificationText != null && parsed.confidence < 1.0f) {
            val mlPrediction = classifier.predict(notificationText)
            val classifierStats = classifier.getStats()

            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight

                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight

                if (mlPrediction < ML_LIKELY_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > ML_CONFIDENT_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }

        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { getCachedSourceStats(packageName) }
            val merchantRejectionRateDeferred = async { getCachedMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getCachedPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { getCachedHasPreviousApprovals(parsed.merchant, packageName) }

            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > MIN_NOTIFICATIONS_FOR_TRUST) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < TRUST_MOD_LOW) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }

            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > MERCHANT_REJECTION_THRESHOLD) {
                adjustedConfidence *= TRUST_MOD_BAD
                reasons.add("Merchant often rejected")
            }

            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > PACKAGE_REJECTION_THRESHOLD) {
                adjustedConfidence *= AUTO_REJECT_PENALTY_PACKAGE
                reasons.add("Package mostly rejected")
            }

            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * PREVIOUS_APPROVAL_BOOST).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }

        // 6. Penalty for Unknown merchant
        if (parsed.merchant.isBlank() || parsed.merchant.equals("Unknown", ignoreCase = true)) {
            adjustedConfidence *= UNKNOWN_MERCHANT_PENALTY
            reasons.add("Unknown merchant")
        }

        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)

        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }

        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }

        return RoutingResult(decision, adjustedConfidence, reason)
    }

    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < ML_SAMPLES_MIN -> 0f       // Not ready
            totalSamples < ML_SAMPLES_LOW -> 0.2f     // Low confidence in ML
            totalSamples < ML_SAMPLES_MED -> 0.35f   // Growing confidence
            totalSamples < ML_SAMPLES_HIGH -> 0.5f    // Moderate
            else -> 0.6f                   // Max capped at 60% (LOG-007)
        }
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> TRUST_MOD_SPAM // LOG-014: Heavy penalty for spam
            stats.trustScore > TRUST_SCORE_HIGH -> TRUST_MOD_HIGH
            stats.trustScore > TRUST_SCORE_NEUTRAL -> TRUST_MOD_NEUTRAL // 40-80% is neutral
            stats.trustScore > TRUST_SCORE_LOW -> TRUST_MOD_LOW // 15-40% is slight penalty
            else -> TRUST_MOD_BAD // < 15% is heavy penalty
        }
    }

    // === Cached Data Access ===

    private suspend fun getCachedSourceStats(packageName: String): SourceStats? {
        val now = timeProvider.now()
        val cached = sourceStatsCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        val stats = sourceStatsRepository.getByPackage(packageName)
        sourceStatsCache[packageName] = Pair(stats, now)
        return stats
    }

    private suspend fun getCachedMerchantRejectionRate(merchant: String): Float {
        val now = timeProvider.now()
        val key = merchant.lowercase()
        val cached = merchantRejectionCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val total = userCorrectionRepository.getMerchantTotalCorrections(merchant)
        val result = if (total < 3) 0f else {
            val rejections = userCorrectionRepository.getMerchantRejectionCount(merchant)
            rejections.toFloat() / total
        }

        merchantRejectionCache[key] = Pair(result, now)
        return result
    }

    private suspend fun getCachedPackageRejectionRate(packageName: String): Float {
        val now = timeProvider.now()
        val cached = packageRejectionCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val total = userCorrectionRepository.getTotalCorrections(packageName)
        val result = if (total < 5) 0f else {
            val rejections = userCorrectionRepository.getRejectionCount(packageName)
            rejections.toFloat() / total
        }

        packageRejectionCache[packageName] = Pair(result, now)
        return result
    }

    private suspend fun getCachedHasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val now = timeProvider.now()
        val key = "${merchant.lowercase()}|$packageName"
        val cached = approvalCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        val result = userCorrectionRepository.hasPreviousApprovals(merchant, packageName)
        approvalCache[key] = Pair(result, now)
        return result
    }

    suspend fun ensureSourceStats(packageName: String) {
        // Optimistic check using cache first to avoid DB read
        val cached = sourceStatsCache[packageName]?.first
        if (cached != null) return

        val existing = sourceStatsRepository.getByPackage(packageName)
        if (existing == null) {
            sourceStatsRepository.insertIfNotExists(SourceStats(packageName = packageName))
        }
        // Update cache
        sourceStatsCache[packageName] = Pair(existing ?: SourceStats(packageName = packageName), System.currentTimeMillis())
    }
}
