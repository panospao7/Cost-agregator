package com.yourname.expensetracker.domain.subscription

import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.StatisticsUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Data class representing a subscription candidate detection result.
 */
data class SubscriptionCandidateResult(
    val merchant: String,
    val canonicalMerchant: String,
    val averageAmount: Double,
    val currency: String,
    val detectedInterval: String,
    val confidence: Double,
    val transactionCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val estimatedAnnualCost: Double
)

/**
 * Service that analyzes parsed notification transactions to detect subscription candidates.
 * Uses statistical analysis of transaction patterns including:
 * - Interval consistency (regularity of transaction dates)
 * - Amount consistency (variance of transaction amounts)
 * - Recentness (how recent the transactions are)
 * 
 * Confidence formula:
 * confidence = 0.4*intervalConsistency + 0.4*(1-amountCV) + 0.2*recentness
 */
@Singleton
class NotificationSubscriptionDetector @Inject constructor(
    private val merchantNormalizer: MerchantNormalizer,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "NotificationSubscriptionDetector"
        
        // Minimum transactions required to form a subscription pattern
        const val MIN_TRANSACTIONS = 3
        
        // Maximum amount variance allowed (40% CV = coefficient of variation)
        const val MAX_AMOUNT_VARIANCE = 0.40
        
        // Minimum confidence threshold to consider a valid subscription candidate
        const val MIN_CONFIDENCE_THRESHOLD = 0.50
        
        // Weights for confidence calculation
        const val WEIGHT_INTERVAL_CONSISTENCY = 0.4
        const val WEIGHT_AMOUNT_CONSISTENCY = 0.4
        const val WEIGHT_RECENTNESS = 0.2
        
        // Days in intervals for annual cost calculation
        const val DAYS_IN_WEEK = 7
        const val DAYS_IN_MONTH = 30
        const val DAYS_IN_QUARTER = 90
        const val DAYS_IN_SEMI_ANNUAL = 180
        const val DAYS_IN_YEAR = 365
    }

    /**
     * Analyze parsed notification transactions to detect subscription candidates.
     * Groups transactions by merchant and evaluates each group for subscription patterns.
     * 
     * @param transactions List of expenses with category information (typically from notifications)
     * @return List of subscription candidates sorted by confidence (highest first)
     */
    suspend fun detectSubscriptions(
        transactions: List<ExpenseWithCategory>
    ): List<SubscriptionCandidateResult> {
        if (transactions.size < MIN_TRANSACTIONS) {
            Timber.d("Not enough transactions for subscription detection: ${transactions.size}")
            return emptyList()
        }

        val now = timeProvider.now()
        val candidates = mutableListOf<SubscriptionCandidateResult>()

        // Group transactions by (canonical merchant name, currency) to avoid
        // conflating subscriptions in different currencies (REC-12).
        val groupedByMerchant = transactions.groupBy { expenseWithCategory ->
            val expense = expenseWithCategory.expense
            val merchant = expense.merchant
            val currency = expense.currency.uppercase()
            val normalizedMerchant = try {
                val normalizedResult = merchantNormalizer.normalize(merchant, autoCreate = false)
                normalizedResult.canonical.normalizedName
            } catch (e: Exception) {
                Timber.w(e, "Failed to normalize merchant: $merchant")
                merchant.lowercase().trim()
            }
            "$normalizedMerchant::$currency"
        }

        Timber.d("Analyzing ${groupedByMerchant.size} merchant groups for subscription patterns")

        for ((canonicalMerchant, merchantTransactions) in groupedByMerchant) {
            // Need at least MIN_TRANSACTIONS occurrences
            if (merchantTransactions.size < MIN_TRANSACTIONS) {
                continue
            }

            // Get the most common original merchant name for display
            val actualMerchant = merchantTransactions
                .groupBy { it.expense.merchant }
                .maxByOrNull { it.value.size }
                ?.key
                ?: canonicalMerchant

            // Get currency (should be consistent, use first)
            val currency = merchantTransactions.first().expense.currency

            // Sort by date for interval analysis
            val sorted = merchantTransactions.sortedBy { it.expense.date }
            
            // Check amount consistency
            val amounts = sorted.map { it.expense.effectiveAmount }
            val avgAmount = amounts.average()
            if (avgAmount < 0.01) continue

            val amountCV = calculateCoefficientOfVariation(amounts)
            
            // Skip if amounts vary too much
            if (amountCV > MAX_AMOUNT_VARIANCE) {
                Timber.d("Skipping $canonicalMerchant: amount variance too high (${"%.2f".format(amountCV)})")
                continue
            }

            // Calculate interval consistency
            val dates = sorted.map { it.expense.date }
            val intervalResult = analyzeIntervals(dates, now)
            
            if (intervalResult == null) {
                Timber.d("Skipping $canonicalMerchant: could not determine interval pattern")
                continue
            }

            val (detectedInterval, intervalConsistency, daysSinceLast) = intervalResult

            // Calculate recentness score (higher = more recent)
            val recentness = calculateRecentness(daysSinceLast)

            // Calculate amount consistency score (higher = more consistent)
            val amountConsistency = max(0.0, 1.0 - amountCV)

            // Final confidence calculation
            val confidence = 
                WEIGHT_INTERVAL_CONSISTENCY * intervalConsistency +
                WEIGHT_AMOUNT_CONSISTENCY * amountConsistency +
                WEIGHT_RECENTNESS * recentness

            Timber.d("$canonicalMerchant: interval=$detectedInterval, intervalScore=${"%.2f".format(intervalConsistency)}, " +
                    "amountScore=${"%.2f".format(amountConsistency)}, recentScore=${"%.2f".format(recentness)}, " +
                    "confidence=${"%.2f".format(confidence)}")

            if (confidence >= MIN_CONFIDENCE_THRESHOLD) {
                val estimatedAnnualCost = calculateEstimatedAnnualCost(avgAmount, detectedInterval)
                
                candidates.add(
                    SubscriptionCandidateResult(
                        merchant = actualMerchant,
                        canonicalMerchant = canonicalMerchant,
                        averageAmount = avgAmount,
                        currency = currency,
                        detectedInterval = detectedInterval,
                        confidence = confidence,
                        transactionCount = merchantTransactions.size,
                        firstSeen = dates.first(),
                        lastSeen = dates.last(),
                        estimatedAnnualCost = estimatedAnnualCost
                    )
                )
            }
        }

        Timber.i("Detected ${candidates.size} subscription candidates from ${transactions.size} transactions")
        return candidates.sortedByDescending { it.confidence }
    }

    /**
     * Convert a detection result into a database entity.
     */
    fun toEntity(result: SubscriptionCandidateResult): SubscriptionCandidate {
        return SubscriptionCandidate(
            merchant = result.merchant,
            canonicalMerchant = result.canonicalMerchant,
            averageAmount = result.averageAmount,
            currency = result.currency,
            detectedInterval = result.detectedInterval,
            confidence = result.confidence,
            transactionCount = result.transactionCount,
            firstSeen = result.firstSeen,
            lastSeen = result.lastSeen,
            estimatedAnnualCost = result.estimatedAnnualCost
        )
    }

    /**
     * Analyze intervals between transaction dates to determine:
     * 1. The most likely recurrence interval (weekly, monthly, etc.)
     * 2. The consistency score (0-1, higher = more regular)
     * 3. Days since the last transaction
     */
    private fun analyzeIntervals(
        dates: List<Long>,
        now: Long
    ): Triple<String, Double, Long>? {
        if (dates.size < 2) return null

        // Calculate intervals in days between consecutive transactions
        val intervalsDays = mutableListOf<Int>()
        for (i in 0 until dates.size - 1) {
            val diffDays = TimePeriodUtils.daysBetween(dates[i], dates[i + 1])
            intervalsDays.add(diffDays)
        }

        if (intervalsDays.isEmpty()) return null

        // Determine the most common interval (mode)
        val frequencyMap = intervalsDays.groupingBy { it }.eachCount()
        val modeEntry = frequencyMap.maxByOrNull { it.value } ?: return null
        val mode = modeEntry.key
        val modeCount = modeEntry.value

        // Map mode to interval type
        val intervalType = when (mode) {
            in 5..11 -> "weekly"          // ~7 days
            in 12..18 -> "biweekly"      // ~14 days
            in 23..45 -> "monthly"       // ~30 days
            in 46..75 -> "bimonthly"     // ~60 days
            in 76..105 -> "quarterly"    // ~90 days
            in 136..200 -> "semiannual"  // ~180 days
            in 270..400 -> "annual"      // ~365 days
            else -> return null // Irregular interval, not a subscription
        }

        // Calculate interval consistency
        // How many intervals are within tolerance of the mode
        val tolerance = when (intervalType) {
            "monthly" -> 3  // ±3 days for monthly
            "weekly", "biweekly" -> 2  // ±2 days for weekly patterns
            "quarterly" -> 7  // ±7 days for quarterly
            "semiannual", "annual" -> 14  // ±14 days for longer intervals
            else -> (mode * 0.1).toInt().coerceAtLeast(1)
        }

        val matchingIntervals = intervalsDays.count { kotlin.math.abs(it - mode) <= tolerance }
        val intervalConsistency = matchingIntervals.toDouble() / intervalsDays.size

        // Days since last transaction
        val daysSinceLast = TimePeriodUtils.daysBetween(dates.last(), now)

        return Triple(intervalType, intervalConsistency, daysSinceLast.toLong())
    }

    /**
     * Calculate recentness score (0-1).
     * Higher score for more recent transactions.
     * Score drops linearly over 365 days.
     */
    private fun calculateRecentness(daysSinceLast: Long): Double {
        return max(0.0, 1.0 - (daysSinceLast.toDouble() / 365.0))
    }

    /**
     * Calculate coefficient of variation (CV) for a list of values.
     * CV = standard deviation / mean
     * Lower CV indicates more consistent values.
     */
    private fun calculateCoefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val stdDev = StatisticsUtils.calculateStdDev(values)
        return stdDev / mean
    }

    /**
     * Calculate estimated annual cost based on average amount and interval.
     */
    private fun calculateEstimatedAnnualCost(averageAmount: Double, interval: String): Double {
        val periodsPerYear = when (interval) {
            "weekly" -> DAYS_IN_YEAR.toDouble() / DAYS_IN_WEEK
            "biweekly" -> DAYS_IN_YEAR.toDouble() / (DAYS_IN_WEEK * 2)
            "monthly" -> 12.0
            "bimonthly" -> 6.0
            "quarterly" -> 4.0
            "semiannual" -> 2.0
            "annual" -> 1.0
            else -> 12.0 // Default to monthly
        }
        return averageAmount * periodsPerYear
    }
}
