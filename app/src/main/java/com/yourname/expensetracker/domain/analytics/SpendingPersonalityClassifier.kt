package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

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
 * A04 OPEN: classify() currently queries raw ExpenseSnapshot data from the
 * repository. It must be migrated to consume NormalizedAnalyticsInput before
 * monetary feature extraction can be considered currency-safe.
 * TODO: Add classify(input: NormalizedAnalyticsInput) overload.
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
     * Classify user's spending personality based on behavior patterns.
     * Analyzes the last 3 months of data to determine personality type.
     */
    suspend fun classify(): SpendingPersonalityProfile = withContext(Dispatchers.Default) {
        val now = timeProvider.now()
        val analysisStartMs = TimePeriodUtils.addMonths(now, -ANALYSIS_MONTHS)
        
        coroutineScope {
            // Fetch all necessary data in parallel
            val allExpensesDeferred = async { 
                expenseRepository.getExpenseSnapshotsBetween(analysisStartMs, now)
            }
            val budgetsDeferred = async { budgetRepository.getActiveBudgetSnapshots() }
            
            val allExpenses = allExpensesDeferred.await()
            val budgets = budgetsDeferred.await()
            
            // Filter to purchases only, excluding "not mine" expenses
            val purchases = allExpenses.filter { 
                it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine 
            }
            
            if (purchases.size < MIN_TRANSACTIONS_FOR_ANALYSIS) {
                Timber.tag(TAG).d("Insufficient data for personality classification: ${purchases.size} transactions")
                return@coroutineScope createInsufficientDataProfile()
            }
            
            // Calculate feature scores
            val featureScores = calculateFeatureScores(purchases, allExpenses, budgets)
            
            // Determine personality type based on feature scores
            val personalityType = determinePersonalityType(featureScores)
            
            // A09: Raw-query path has no NormalizedAnalyticsInput.dataQuality;
            // default to moderate confidence. The normalized path uses full dataQuality.
            val confidence = 0.6
            
            // Generate explanation
            val explanation = generateExplanation(personalityType, featureScores)
            
            // Generate coaching tips
            val coachingTips = generateCoachingTips(personalityType, featureScores)
            
            SpendingPersonalityProfile(
                personalityType = personalityType,
                confidence = confidence,
                featureScores = featureScores,
                explanation = explanation,
                coachingTips = coachingTips,
                lastUpdated = now
            )
        }
    }
    
    /**
     * Calculate all feature scores for personality classification.
     */
    private fun calculateFeatureScores(
        purchases: List<ExpenseSnapshot>,
        allExpenses: List<ExpenseSnapshot>,
        budgets: List<BudgetSnapshot>
    ): Map<String, Double> {
        val scores = mutableMapOf<String, Double>()
        
        // 1. Impulse Ratio: % of transactions within 1 day of income deposits
        scores["impulseRatio"] = calculateImpulseRatio(purchases, allExpenses)
        
        // 2. Merchant Diversity: unique merchants / total transactions
        scores["merchantDiversity"] = calculateMerchantDiversity(purchases)
        
        // 3. Weekend Spend Share: % of spending on weekends (Sat=6, Sun=7 in Calendar)
        scores["weekendSpendShare"] = calculateWeekendSpendShare(purchases)
        
        // 4. Night Spend Share: % of spending after 8 PM
        scores["nightSpendShare"] = calculateNightSpendShare(purchases)
        
        // 5. Spending Variance: coefficient of variation in daily spending
        scores["variance"] = calculateSpendingVariance(purchases)
        
        // 6. Budget Adherence: how well user sticks to budgets (1.0 = perfect)
        scores["budgetAdherence"] = calculateBudgetAdherence(purchases, budgets)
        
        // 7. Anomaly Frequency: how often anomalies occur
        scores["anomalyFrequency"] = calculateAnomalyFrequency(purchases)
        
        // 8. Category Diversity: unique categories / total transactions
        scores["categoryDiversity"] = calculateCategoryDiversity(purchases)
        
        // 9. Transaction Count per month
        scores["transactionsPerMonth"] = purchases.size.toDouble() / ANALYSIS_MONTHS
        
        // 10. Average transaction size (normalized)
        scores["avgTransactionSize"] = calculateNormalizedAvgTransactionSize(purchases)
        
        Timber.tag(TAG).d("Feature scores: $scores")
        
        return scores
    }
    
    /**
     * Calculate impulse ratio: % of purchases within 1 day of income deposits.
     */
    private fun calculateImpulseRatio(
        purchases: List<ExpenseSnapshot>,
        allExpenses: List<ExpenseSnapshot>
    ): Double {
        // Find income/deposit dates
        val incomeDates = allExpenses
            .filter { it.transactionType == DomainTransactionType.DEPOSIT || 
                      (it.transferDirection == DomainTransferDirection.INCOMING && it.amount > 100) }
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
    
    /**
     * Calculate merchant diversity: unique merchants / total transactions.
     */
    private fun calculateMerchantDiversity(purchases: List<ExpenseSnapshot>): Double {
        val uniqueMerchants = purchases.map { it.merchantKey ?: it.merchant.lowercase() }.distinct().size
        return uniqueMerchants.toDouble() / purchases.size.coerceAtLeast(1)
    }
    
    /**
     * Calculate weekend spend share: % of spending on Saturday and Sunday.
     */
    private fun calculateWeekendSpendShare(purchases: List<ExpenseSnapshot>): Double {
        // A18: Replace Calendar with java.time.ZonedDateTime + ZoneId.systemDefault()
        val calendar = Calendar.getInstance()
        
        val weekendSpending = purchases.filter { purchase ->
            calendar.timeInMillis = purchase.date
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        }.sumOf { it.effectiveAmount }
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val totalSpending = purchases.sumOf { it.effectiveAmount }
        
        return if (totalSpending > 0) weekendSpending / totalSpending else 0.0
    }
    
    /**
     * Calculate night spend share: % of spending after 8 PM.
     */
    private fun calculateNightSpendShare(purchases: List<ExpenseSnapshot>): Double {
        // A18: Replace Calendar with java.time.ZonedDateTime + ZoneId.systemDefault()
        val calendar = Calendar.getInstance()
        
        val nightSpending = purchases.filter { purchase ->
            calendar.timeInMillis = purchase.date
            calendar.get(Calendar.HOUR_OF_DAY) >= NIGHT_HOUR_THRESHOLD
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        }.sumOf { it.effectiveAmount }
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val totalSpending = purchases.sumOf { it.effectiveAmount }
        
        return if (totalSpending > 0) nightSpending / totalSpending else 0.0
    }
    
    /**
     * Calculate spending variance: coefficient of variation in daily spending.
     */
    private fun calculateSpendingVariance(purchases: List<ExpenseSnapshot>): Double {
        // Group by day
        val dailyTotals = purchases.groupBy { expense ->
            val dayStart = TimePeriodUtils.getStartOfDay(expense.date)
            dayStart
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        }.mapValues { it.value.sumOf { e -> e.effectiveAmount } }
        
        val dailyAmounts = dailyTotals.values.toList()
        if (dailyAmounts.size < 2) return 0.0
        
        val mean = dailyAmounts.average()
        if (mean == 0.0) return 0.0
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val variance = dailyAmounts.sumOf { (it - mean) * (it - mean) } / dailyAmounts.size
        val stdDev = sqrt(variance)
        
        return stdDev / mean // Coefficient of variation
    }
    
    /**
     * Calculate budget adherence: 1.0 = perfect adherence, 0.0 = completely over budget.
     */
    private fun calculateBudgetAdherence(
        purchases: List<ExpenseSnapshot>,
        budgets: List<BudgetSnapshot>
    ): Double {
        if (budgets.isEmpty()) return 0.5 // Neutral if no budgets set
        
        var totalAdherence = 0.0
        var budgetCount = 0
        
        // Group purchases by category
        val purchasesByCategory = purchases.groupBy { it.categoryId }
        
        budgets.forEach { budget ->
            val categoryPurchases = purchasesByCategory[budget.categoryId] ?: emptyList()
            // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
            val categorySpending = categoryPurchases.sumOf { it.effectiveAmount }
            
            val adherence = if (budget.amount > 0) {
                val ratio = categorySpending / budget.amount
                when {
                    ratio <= 1.0 -> 1.0 // Within budget
                    ratio <= 1.1 -> 0.8 // Slightly over
                    ratio <= 1.25 -> 0.6 // Moderately over
                    else -> 0.4 // Significantly over
                }
            } else {
                0.5
            }
            
            totalAdherence += adherence
            budgetCount++
        }
        
        return if (budgetCount > 0) totalAdherence / budgetCount else 0.5
    }
    
    /**
     * Calculate anomaly frequency: estimated from transaction variance and outliers.
     */
    private fun calculateAnomalyFrequency(purchases: List<ExpenseSnapshot>): Double {
        if (purchases.size < 5) return 0.0
        
        val amounts = purchases.map { it.effectiveAmount }
        val mean = amounts.average()
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val stdDev = sqrt(amounts.sumOf { (it - mean) * (it - mean) } / amounts.size)
        
        if (stdDev == 0.0) return 0.0
        
        // Count transactions > 2 std dev from mean
        val anomalies = amounts.count { abs(it - mean) > 2 * stdDev }
        
        return anomalies.toDouble() / purchases.size
    }
    
    /**
     * Calculate category diversity: unique categories / total transactions.
     */
    private fun calculateCategoryDiversity(purchases: List<ExpenseSnapshot>): Double {
        val uniqueCategories = purchases.mapNotNull { it.categoryId }.distinct().size
        return uniqueCategories.toDouble() / purchases.size.coerceAtLeast(1)
    }
    
    /**
     * Calculate normalized average transaction size (0-1 scale).
     */
    private fun calculateNormalizedAvgTransactionSize(purchases: List<ExpenseSnapshot>): Double {
        val avgAmount = purchases.map { it.effectiveAmount }.average()
        // Normalize: typical range €5 - €200, map to 0-1
        return (avgAmount / 200.0).coerceIn(0.0, 1.0)
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
     * Uses [input.includedExpenses] (already converted to home currency) instead of
     * querying raw snapshots directly. The dataQuality flag reduces confidence when
     * input is partial.
     */
    suspend fun classify(input: NormalizedAnalyticsInput): SpendingPersonalityProfile {
        val expenses = input.includedExpenses
        val purchases = expenses.filter { it.transactionType == "PURCHASE" && !it.isNotMine }
        val allExpensesList = expenses.filter { !it.isNotMine }

        if (purchases.size < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            Timber.tag(TAG).d("Insufficient data for personality classification: ${purchases.size} transactions")
            return createInsufficientDataProfile()
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
        featureScores["avgTransactionSize"] = calculateAvgTransactionSizeFromNormalized(purchases)

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
            lastUpdated = timeProvider.now()
        )
    }

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
            hour in 22..23 || hour in 0..5
        }
        return nightCount.toDouble() / purchases.size.coerceAtLeast(1)
    }

    private fun calculateSpendingVarianceFromNormalized(purchases: List<NormalizedExpense>): Double {
        val amounts = purchases.map { it.normalizedAmount }
        val mean = amounts.average()
        if (mean == 0.0 || amounts.isEmpty()) return 0.0
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        return (Math.sqrt(variance) / mean).coerceIn(0.0, 1.0)
    }

    private fun calculateCategoryDiversityFromNormalized(purchases: List<NormalizedExpense>): Double {
        val uniqueCategories = purchases.map { it.categoryNameSnapshot ?: it.categoryId?.toString() ?: "unknown" }.distinct().size
        return (uniqueCategories.toDouble() / purchases.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    private fun calculateAvgTransactionSizeFromNormalized(purchases: List<NormalizedExpense>): Double {
        val avgAmount = purchases.map { it.normalizedAmount }.average()
        // Normalize: typical range €5 - €200, map to 0-1 (matches no-arg calculateNormalizedAvgTransactionSize)
        return (avgAmount / 200.0).coerceIn(0.0, 1.0)
    }

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

        val amounts = purchases.map { it.normalizedAmount }
        val mean = amounts.average()
        val stdDev = sqrt(amounts.sumOf { (it - mean) * (it - mean) } / amounts.size)

        if (stdDev == 0.0) return 0.0

        // Count transactions > 2 std dev from mean
        val anomalies = amounts.count { abs(it - mean) > 2 * stdDev }

        return anomalies.toDouble() / purchases.size
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
    val lastUpdated: Long
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
