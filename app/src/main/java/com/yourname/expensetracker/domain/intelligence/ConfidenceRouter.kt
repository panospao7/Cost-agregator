package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
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

                if (mlPrediction < 0.3f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > 0.8f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }

        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { sourceStatsDao.getByPackage(packageName) }
            val merchantRejectionRateDeferred = async { getMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { hasPreviousApprovals(parsed.merchant, packageName) }

            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > 10) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < 0.9f) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }

            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > 0.5f) {
                adjustedConfidence *= 0.5f
                reasons.add("Merchant often rejected")
            }

            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > 0.7f) {
                adjustedConfidence *= 0.3f
                reasons.add("Package mostly rejected")
            }

            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * 1.2f).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }

        // 6. Penalty for Unknown merchant
        if (parsed.merchant.equals("Unknown", ignoreCase = true)) {
            adjustedConfidence *= 0.5f
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
            totalSamples < 20 -> 0f       // Not ready
            totalSamples < 50 -> 0.2f     // Low confidence in ML
            totalSamples < 100 -> 0.35f   // Growing confidence
            totalSamples < 200 -> 0.5f    // Moderate
            else -> 0.6f                   // Max capped at 60% (LOG-007)
        }
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> 0.1f // LOG-014: Heavy penalty for spam
            stats.trustScore > 0.8f -> 1.1f
            stats.trustScore > 0.4f -> 1.0f // 40-80% is neutral
            stats.trustScore > 0.15f -> 0.9f // 15-40% is slight penalty
            else -> 0.5f // < 15% is heavy penalty
        }
    }

    private suspend fun getMerchantRejectionRate(merchant: String): Float {
        val total = userCorrectionDao.getMerchantTotalCorrections(merchant)
        if (total < 3) return 0f
        val rejections = userCorrectionDao.getMerchantRejectionCount(merchant)
        return rejections.toFloat() / total
    }

    private suspend fun getPackageRejectionRate(packageName: String): Float {
        val total = userCorrectionDao.getTotalCorrections(packageName)
        if (total < 5) return 0f
        val rejections = userCorrectionDao.getRejectionCount(packageName)
        return rejections.toFloat() / total
    }

    private suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean {
        return userCorrectionDao.hasPreviousApprovals(merchant, packageName)
    }

    suspend fun ensureSourceStats(packageName: String) {
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = packageName))
        }
    }
}
