package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyRow
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.coroutines.cancellation.CancellationException

/**
 * Classifies users' spending behavior into personality types and provides coaching nudges.
 * 
 * Feature vector:
 * - impulseRatio: % of transactions on same day as income
 * - merchantDiversity: unique merchants / total transactions
 * - weekendSpendShare: % of spending on weekends
 * - nightSpendShare: % of spending after 8pm
 * - variance: spending variance over time
 * - budgetAdherence: how well user sticks to budgets
 * - anomalyFrequency: how often anomalies are detected
 * 
 * Rule-based v1 (transparent), optional ML later.
 *
 * The deprecated no-argument overload remains for isolated legacy tests and
 * compatibility only. Production analytics callers use the normalized-input
 * overload, so monetary feature extraction does not mix source currencies.
 */
@Singleton
class SpendingPersonalityClassifier @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val insightsEngine: InsightsEngine,
    private val spendingPaceCalculator: SpendingPaceCalculator,
    private val anomalyDetector: AnomalyDetector,
    private val totalsAggregationEngine: TotalsAggregationEngine,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "SpendingPersonality"
        private const val ANALYSIS_MONTHS = 3
        private const val MIN_TRANSACTIONS_FOR_ANALYSIS = 10
        private const val MINIMALIST_MAX_TRANSACTIONS_PER_MONTH = 20
        private const val CONFIDENCE_MAX_TRANSACTIONS_PER_MONTH = 120.0
        private const val NIGHT_HOUR_THRESHOLD = 20 // 8 PM
        private const val IMPULSE_WINDOW_DAYS = 1 // Same day as income

        private val FEATURE_KEYS_FOR_CONFIDENCE = setOf(
            "impulseRatio",
            "merchantDiversity",
            "weekendSpendShare",
            "nightSpendShare",
            "variance",
            "budgetAdherence",
            "anomalyFrequency",
            "categoryDiversity",
            "avgTransactionSize"
        )
    }

    /**
     * PR8-GUARDRAIL: Raw self-fetching path. Use classify(NormalizedAnalyticsInput) in production.
     * This path remains for backward compatibility and tests only.
     */
    @Deprecated(
        message = "Use classify(NormalizedAnalyticsInput) for normalized, currency-safe personality classification",
        replaceWith = ReplaceWith("classify(input)", "com.yourname.expensetracker.domain.analytics.NormalizedAnalyticsInput"),
        level = DeprecationLevel.WARNING
    )
    internal suspend fun classify(): SpendingPersonalityProfile = withContext(Dispatchers.Default) {
        val allExpenses = expenseRepository.getExpenseSnapshotsBetween(
            TimePeriodUtils.addMonths(timeProvider.now(), -ANALYSIS_MONTHS),
            timeProvider.now()
        )
        val purchases = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine
        }
        if (purchases.size < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            return@withContext createInsufficientDataProfile()
        }
        val normalizedInput = try {
            SingleCurrencyAnalyticsInput.from(
                allExpenses,
                SingleCurrencyAnalyticsInput.currencyOf(purchases)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext createUnavailableDataProfile(
                MoneyAggregateResult.Unavailable(
                    reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                    requestedRateBasis = RateBasis.IDENTITY
                )
            )
        }
        classify(normalizedInput)
    }

    /**
     * Determine personality type based on feature scores using rule-based classification.
     */
    private fun determinePersonalityType(featureScores: Map<String, Double>): SpendingPersonalityType {
        val impulseRatio = featureScores["impulseRatio"] ?: 0.0
        val variance = featureScores["variance"] ?: 0.0
        val budgetAdherence = featureScores["budgetAdherence"] ?: 0.0
        val anomalyFrequency = featureScores["anomalyFrequency"] ?: 0.0
        val weekendSpendShare = featureScores["weekendSpendShare"] ?: 0.0
        val nightSpendShare = featureScores["nightSpendShare"] ?: 0.0
        val merchantDiversity = featureScores["merchantDiversity"] ?: 0.0
        val categoryDiversity = featureScores["categoryDiversity"] ?: 0.0
        val transactionsPerMonth = featureScores["transactionsPerMonth"] ?: 0.0
        
        // Score each personality type
        val scores = mutableMapOf<SpendingPersonalityType, Double>()
        
        // PLANNER: High budget adherence, low variance, low impulse, low anomalies
        scores[SpendingPersonalityType.PLANNER] = 
            (budgetAdherence * 0.3) +
            ((1.0 - variance.coerceIn(0.0, 1.0)) * 0.25) +
            ((1.0 - impulseRatio) * 0.25) +
            ((1.0 - anomalyFrequency.coerceIn(0.0, 1.0)) * 0.2)
        
        // IMPULSE: High impulse ratio, high variance, high anomalies, low budget adherence
        scores[SpendingPersonalityType.IMPULSE] = 
            (impulseRatio * 0.3) +
            (variance.coerceIn(0.0, 1.0) * 0.25) +
            (anomalyFrequency.coerceIn(0.0, 1.0) * 0.25) +
            ((1.0 - budgetAdherence) * 0.2)
        
        // OPTIMIZER: High merchant diversity, low weekend spend, low variance, high category diversity
        scores[SpendingPersonalityType.OPTIMIZER] = 
            (merchantDiversity * 0.3) +
            ((1.0 - weekendSpendShare) * 0.2) +
            ((1.0 - variance.coerceIn(0.0, 1.0)) * 0.25) +
            (categoryDiversity * 0.25)
        
        // SOCIAL_SPENDER: High weekend spend, high night spend, high merchant diversity
        scores[SpendingPersonalityType.SOCIAL_SPENDER] = 
            (weekendSpendShare * 0.35) +
            (nightSpendShare * 0.25) +
            (merchantDiversity * 0.25) +
            (categoryDiversity * 0.15)
        
        // MINIMALIST: Low transactions per month, low variance, low merchant diversity
        val minimalistScore = if (transactionsPerMonth <= MINIMALIST_MAX_TRANSACTIONS_PER_MONTH) {
            0.4 + ((1.0 - variance.coerceIn(0.0, 1.0)) * 0.3) +
            ((1.0 - merchantDiversity) * 0.3)
        } else {
            0.0
        }
        scores[SpendingPersonalityType.MINIMALIST] = minimalistScore
        
        // BALANCED: No dominant pattern - determined by process of elimination
        // If no type scores above threshold, it's balanced
        
        // Find highest scoring type
        val maxScore = scores.values.maxOrNull() ?: 0.0
        val winner = scores.maxByOrNull { it.value }?.key ?: SpendingPersonalityType.BALANCED
        
        // If winner score is too low or close to others, classify as BALANCED
        val runnerUpScore = scores.filter { it.key != winner }.values.maxOrNull() ?: 0.0
        val scoreGap = maxScore - runnerUpScore
        
        return if (maxScore < 0.5 || scoreGap < 0.1) {
            SpendingPersonalityType.BALANCED
        } else {
            winner
        }
    }
    
    /**
     * Calculate confidence in the classification based on data quality.
     */
    private fun calculateConfidence(
        transactionCount: Int,
        featureScores: Map<String, Double>
    ): Double {
        // Count-based data quality is separate from feature-scale stability.
        val countDataQuality = (transactionCount.toDouble() / (ANALYSIS_MONTHS * 30)).coerceIn(0.0, 1.0)

        // Feature stability must use normalized inputs only.
        val normalizedFeatureValues = buildList {
            FEATURE_KEYS_FOR_CONFIDENCE.forEach { key ->
                featureScores[key]?.let { add(it.coerceIn(0.0, 1.0)) }
            }
            val transactionsPerMonth = featureScores["transactionsPerMonth"]
            if (transactionsPerMonth != null) {
                add(normalizeTransactionsPerMonth(transactionsPerMonth))
            }
        }

        if (normalizedFeatureValues.isEmpty()) return 0.0

        val featureMean = normalizedFeatureValues.average()
        val featureVariance = normalizedFeatureValues
            .sumOf { (it - featureMean) * (it - featureMean) } / normalizedFeatureValues.size
        val featureStability = (1.0 - featureVariance).coerceIn(0.0, 1.0)

        return (countDataQuality * 0.6 + featureStability * 0.4).coerceIn(0.0, 1.0)
    }

    private fun normalizeTransactionsPerMonth(transactionsPerMonth: Double): Double {
        return (transactionsPerMonth / CONFIDENCE_MAX_TRANSACTIONS_PER_MONTH).coerceIn(0.0, 1.0)
    }
    
    /**
     * Generate explanation for why this personality type was assigned.
     */
    private fun generateExplanation(
        personalityType: SpendingPersonalityType,
        featureScores: Map<String, Double>
    ): List<String> {
        val explanations = mutableListOf<String>()
        
        when (personalityType) {
            SpendingPersonalityType.PLANNER -> {
                explanations.add("You consistently stay within your budgets")
                explanations.add("Your spending is predictable and well-organized")
                if ((featureScores["variance"] ?: 0.0) < 0.2) {
                    explanations.add("Low spending variation shows careful planning")
                }
            }
            SpendingPersonalityType.IMPULSE -> {
                explanations.add("You make purchases soon after receiving money")
                explanations.add("Your spending varies significantly from day to day")
                if ((featureScores["anomalyFrequency"] ?: 0.0) > 0.15) {
                    explanations.add("You occasionally make unusually large purchases")
                }
            }
            SpendingPersonalityType.OPTIMIZER -> {
                explanations.add("You shop at a diverse range of merchants")
                explanations.add("You tend to spend more on weekdays when deals are available")
                if ((featureScores["categoryDiversity"] ?: 0.0) > 0.5) {
                    explanations.add("You explore multiple categories for best value")
                }
            }
            SpendingPersonalityType.SOCIAL_SPENDER -> {
                explanations.add("You spend more on weekends and evenings")
                explanations.add("Your merchant diversity suggests social outings")
                if ((featureScores["weekendSpendShare"] ?: 0.0) > 0.5) {
                    explanations.add("Over half your spending happens on weekends")
                }
            }
            SpendingPersonalityType.MINIMALIST -> {
                explanations.add("You have very few transactions each month")
                explanations.add("Your spending is consistently low and controlled")
            }
            SpendingPersonalityType.BALANCED -> {
                explanations.add("You show a healthy mix of different spending patterns")
                explanations.add("No single spending style dominates your behavior")
            }
        }
        
        return explanations
    }
    
    /**
     * Generate coaching tips based on personality type.
     */
    private fun generateCoachingTips(
        personalityType: SpendingPersonalityType,
        featureScores: Map<String, Double>
    ): List<String> {
        return when (personalityType) {
            SpendingPersonalityType.PLANNER -> listOf(
                "Great job staying on budget! Consider investing your surplus.",
                "Your planning skills are excellent - maybe set a stretch savings goal?",
                "You've mastered budgeting - time to focus on growing your wealth."
            )
            SpendingPersonalityType.IMPULSE -> listOf(
                "Try the 24-hour rule: wait a day before non-essential purchases.",
                "Set up automatic savings transfers on payday before you can spend.",
                "Create a 'fun money' budget so spontaneity doesn't derail your goals."
            )
            SpendingPersonalityType.OPTIMIZER -> listOf(
                "You're great at finding value! Consider bulk buying for extra savings.",
                "Your deal-finding skills are top-notch - share them with friends!",
                "Use price tracking tools to automate your bargain hunting."
            )
            SpendingPersonalityType.SOCIAL_SPENDER -> listOf(
                "Consider setting aside a 'social budget' to enjoy guilt-free.",
                "Look for happy hours and group discounts to stretch your social funds.",
                "Suggest lower-cost social activities like potlucks or free events."
            )
            SpendingPersonalityType.MINIMALIST -> listOf(
                "Your spending is very controlled. Are you meeting your needs?",
                "Consider treating yourself occasionally - balance is healthy.",
                "Your restraint is admirable, but don't forget to enjoy life too."
            )
            SpendingPersonalityType.BALANCED -> listOf(
                "You have a healthy mix of planning and flexibility. Keep it up!",
                "Your balanced approach serves you well in different situations.",
                "Maintain this equilibrium - you're on the right track!"
            )
        }
    }
    
    /**
     * Create a default profile when there's insufficient data.
     */
    private fun createInsufficientDataProfile(): SpendingPersonalityProfile {
        return SpendingPersonalityProfile(
            personalityType = SpendingPersonalityType.BALANCED,
            confidence = 0.0,
            featureScores = emptyMap(),
            explanation = listOf("Need more transaction history to analyze your spending personality"),
            coachingTips = listOf(
                "Keep using the app to build up your transaction history.",
                "Once we have more data, we can provide personalized insights."
            ),
            lastUpdated = timeProvider.now()
        )
    }

    /**
     * A05-FIXED: Classify personality from pre-normalized [NormalizedAnalyticsInput].
     *
     * PR6-CAVEAT: Known behavioral differences vs raw path:
     * - budgetAdherence is neutralized at 0.5 because NormalizedAnalyticsInput
     *   does not carry budget data.
     * - weekendSpendShare and nightSpendShare are count-based (weekend tx count /
     *   total tx count) rather than spend-weighted (weekend spend / total spend).
     * - impulseRatio only detects DEPOSIT income events, not INCOMING transfers.
     *
     * These differences affect the feature vector but prevent raw-Double
     * mixed-currency summing. The normalized path is currency-safe. Excluded
     * rows are not reintroduced as zero-value purchases, and data quality
     * reduces confidence rather than fabricating a complete result.
     *
     * Uses [input.includedExpenses] (already converted to home currency) instead of
     * querying raw snapshots directly. The dataQuality flag reduces confidence when
     * input is partial.
     */
    suspend fun classify(input: NormalizedAnalyticsInput): SpendingPersonalityProfile {
        val expenses = input.includedExpenses.filter {
            input.period?.contains(it.date) != false
        }
        val purchases = expenses.filter { it.transactionType == "PURCHASE" && !it.isNotMine }
        val allExpensesList = expenses.filter { !it.isNotMine }

        if (purchases.size < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            Timber.tag(TAG).d("Insufficient data for personality classification: ${purchases.size} transactions")
            return createInsufficientDataProfile()
        }

        val aggregateResult = aggregateNormalizedPurchases(input, purchases)
        if (aggregateResult is MoneyAggregateResult.Unavailable) {
            Timber.tag(TAG).w(
                "Normalized personality input unavailable reason=%s",
                aggregateResult.reason
            )
            return createUnavailableDataProfile(aggregateResult)
        }

        // Calculate feature scores from normalized input
        val featureScores = mutableMapOf<String, Double>()

        // 1. Impulse Ratio: % of purchases within 1 day of income deposits
        featureScores["impulseRatio"] = calculateImpulseRatioFromNormalized(purchases, allExpensesList)

        // 2. Merchant Diversity: unique merchants / total transactions
        featureScores["merchantDiversity"] = calculateMerchantDiversityFromNormalized(purchases)

        // 3. Weekend Spend Share: % of spending on weekends
        featureScores["weekendSpendShare"] = calculateWeekendShareFromNormalized(purchases)

        // 4. Night Spend Share: % of spending after 8 PM
        featureScores["nightSpendShare"] = calculateNightShareFromNormalized(purchases)

        // 5. Spending Variance: coefficient of variation
        featureScores["variance"] = calculateSpendingVarianceFromNormalized(purchases)

        // 6. Budget Adherence: neutral (no budgets available in normalized input)
        featureScores["budgetAdherence"] = 0.5

        // 7. Anomaly Frequency: outlier transactions
        featureScores["anomalyFrequency"] = calculateAnomalyFrequencyFromNormalized(purchases)

        // 8. Category Diversity: unique categories / total transactions
        featureScores["categoryDiversity"] = calculateCategoryDiversityFromNormalized(purchases)

        // 9. Transactions per month
        featureScores["transactionsPerMonth"] = purchases.size.toDouble() / ANALYSIS_MONTHS

        // 10. Average transaction size (normalized 0-1)
        featureScores["avgTransactionSize"] = calculateAvgTransactionSizeFromNormalized(
            purchases,
            aggregateResult
        )

        Timber.tag(TAG).d("Feature scores (normalized input): $featureScores")

        // Determine personality type based on feature scores
        val personalityType = determinePersonalityType(featureScores)

        // Confidence from data quality
        val baseConfidence = 0.8
        val penalty = input.dataQuality.confidencePenalty
        val multiplier = input.dataQuality.confidenceMultiplier
        val confidence = (baseConfidence * multiplier - penalty).coerceIn(0.0, 1.0)

        // Generate explanation
        val explanation = generateExplanation(personalityType, featureScores)

        // Generate coaching tips
        val coachingTips = generateCoachingTips(personalityType, featureScores)

        return SpendingPersonalityProfile(
            personalityType = personalityType,
            confidence = confidence,
            featureScores = featureScores,
            explanation = explanation,
            coachingTips = coachingTips,
            lastUpdated = timeProvider.now(),
            moneyResult = aggregateResult
        )
    }

    private fun aggregateNormalizedPurchases(
        input: NormalizedAnalyticsInput,
        purchases: List<NormalizedExpense>
    ): MoneyAggregateResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
        input = input,
        includedExpenses = purchases,
        startInclusiveMillis = input.period?.startInclusiveMillis,
        endExclusiveMillis = input.period?.endExclusiveMillis
    )

    private fun calculateMerchantDiversityFromNormalized(purchases: List<NormalizedExpense>): Double {
        val uniqueMerchants = purchases.map { it.merchantKey ?: it.merchant }.distinct().size
        return (uniqueMerchants.toDouble() / purchases.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    private fun calculateWeekendShareFromNormalized(purchases: List<NormalizedExpense>): Double {
        val weekendCount = purchases.count { exp ->
            val dayOfWeek = java.time.Instant.ofEpochMilli(exp.date)
                .atZone(java.time.ZoneId.systemDefault()).dayOfWeek
            dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
        }
        return weekendCount.toDouble() / purchases.size.coerceAtLeast(1)
    }

    private fun calculateNightShareFromNormalized(purchases: List<NormalizedExpense>): Double {
        val nightCount = purchases.count { exp ->
            val hour = java.time.Instant.ofEpochMilli(exp.date)
                .atZone(java.time.ZoneId.systemDefault()).hour
            hour >= NIGHT_HOUR_THRESHOLD
        }
        return nightCount.toDouble() / purchases.size.coerceAtLeast(1)
    }

    private fun calculateSpendingVarianceFromNormalized(purchases: List<NormalizedExpense>): Double {
        val dailyAmounts = purchases
            .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .values
            .map { dayExpenses -> aggregateNormalizedAmounts(dayExpenses) ?: return 0.0 }
        if (dailyAmounts.size < 2) return 0.0
        val mean = dailyAmounts.average()
        if (mean == 0.0) return 0.0
        val variance = dailyAmounts.sumOf { (it - mean) * (it - mean) } / dailyAmounts.size
        return (Math.sqrt(variance) / mean).coerceIn(0.0, 1.0)
    }

    private fun calculateCategoryDiversityFromNormalized(purchases: List<NormalizedExpense>): Double {
        val uniqueCategories = purchases.map { it.categoryNameSnapshot ?: it.categoryId?.toString() ?: "unknown" }.distinct().size
        return (uniqueCategories.toDouble() / purchases.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    private fun calculateAvgTransactionSizeFromNormalized(
        purchases: List<NormalizedExpense>,
        aggregateResult: MoneyAggregateResult
    ): Double {
        val avgAmount = (aggregateResult as? MoneyAggregateResult.Available)
            ?.aggregate
            ?.displayAmount
            ?.div(purchases.size)
            ?: 0.0
        // Normalize: typical range €5 - €200, map to 0-1 (matches no-arg calculateNormalizedAvgTransactionSize)
        return (avgAmount / 200.0).coerceIn(0.0, 1.0)
    }

    private fun createUnavailableDataProfile(
        moneyResult: MoneyAggregateResult.Unavailable
    ): SpendingPersonalityProfile = SpendingPersonalityProfile(
        personalityType = SpendingPersonalityType.BALANCED,
        confidence = 0.0,
        featureScores = emptyMap(),
        explanation = listOf("Normalized money data is unavailable for personality analysis"),
        coachingTips = listOf("Resolve the missing currency or conversion data before relying on this analysis."),
        lastUpdated = timeProvider.now(),
        moneyResult = moneyResult
    )

    private fun calculateImpulseRatioFromNormalized(
        purchases: List<NormalizedExpense>,
        allExpenses: List<NormalizedExpense>
    ): Double {
        // Find income/deposit dates
        val incomeDates = allExpenses
            .filter { it.transactionType == "DEPOSIT" }
            .map { it.date }
            .distinct()

        if (incomeDates.isEmpty()) return 0.0

        val impulsePurchases = purchases.count { purchase ->
            incomeDates.any { incomeDate ->
                val diffDays = abs(TimePeriodUtils.daysBetween(incomeDate, purchase.date))
                diffDays <= IMPULSE_WINDOW_DAYS
            }
        }

        return impulsePurchases.toDouble() / purchases.size.coerceAtLeast(1)
    }

    private fun calculateAnomalyFrequencyFromNormalized(purchases: List<NormalizedExpense>): Double {
        if (purchases.size < 5) return 0.0

        val mean = (aggregateNormalizedAmounts(purchases) ?: return 0.0) / purchases.size
        val squaredDeviation = purchases.fold(0.0) { total, expense ->
            val deviation = expense.normalizedAmount - mean
            total + deviation * deviation
        }
        val stdDev = sqrt(squaredDeviation / purchases.size)

        if (stdDev == 0.0) return 0.0

        // Count transactions > 2 std dev from mean
        val anomalies = purchases.count { abs(it.normalizedAmount - mean) > 2 * stdDev }

        return anomalies.toDouble() / purchases.size
    }

    private fun aggregateNormalizedAmounts(expenses: List<NormalizedExpense>): Double? {
        val targetCurrency = CurrencyCode.parse(expenses.firstOrNull()?.normalizedCurrency)
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return null
        val rows = expenses.map { expense ->
            NormalizedMoneyRow(
                amount = expense.normalizedAmount,
                normalizedCurrency = targetCurrency,
                sourceCurrency = CurrencyCode.parse(expense.originalCurrency),
                rateValidDate = expense.rateValidDate,
                transactionId = expense.id
            )
        }
        return when (val result = MoneyAggregateBuilder.fromNormalizedRows(rows, targetCurrency)) {
            is MoneyAggregateResult.Available -> result.aggregate.displayAmount
            is MoneyAggregateResult.Unavailable -> null
        }
    }

}

/**
 * User's spending personality profile with classification results.
 */
data class SpendingPersonalityProfile(
    val personalityType: SpendingPersonalityType,
    val confidence: Double,
    val featureScores: Map<String, Double>,
    val explanation: List<String>,
    val coachingTips: List<String>,
    val lastUpdated: Long,
    val moneyResult: MoneyAggregateResult? = null
)

/**
 * Spending personality types.
 */
enum class SpendingPersonalityType {
    PLANNER,        // Organized, budget-conscious, predictable
    IMPULSE,        // Spontaneous, high variance, frequent anomalies
    OPTIMIZER,      // Value-seeking, compares, looks for deals
    SOCIAL_SPENDER, // High weekend/night spend, diverse merchants
    MINIMALIST,     // Low spending, few transactions, consistent
    BALANCED        // Mix of traits, no dominant pattern
}
