package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton


/**
 * AI-powered budget autopilot engine.
 * 
 * Generates per-category budget adjustment recommendations based on:
 * - Historical spending trends (via aggregate SQL — A.9)
 * - Spending volatility/risk analysis
 * - User-defined delta caps (±15% per cycle)
 * 
 * Formula:
 * recommendedBudget_c = trendAdjustedSpend_c * safetyFactor(risk, volatility)
 * with min/max delta caps (e.g., ±15% per cycle)
 *
 * A.9: Historical spending is now retrieved through pre-aggregated monthly
 * totals from [ExpenseDao] instead of capped raw row reads from
 * [ExpenseRepository]. This eliminates silent data truncation on large
 * histories while normalizing sparse month gaps into zero-spend buckets for
 * trend and volatility analysis.
 */
@Singleton
class BudgetAutopilotEngine @Inject constructor(
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val expenseDao: ExpenseDao,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val insightsEngine: com.yourname.expensetracker.domain.analytics.InsightsEngine,
    private val spendingPaceCalculator: com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator,
    private val monteCarloSimulator: com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
) {
    companion object {
        private const val MIN_HISTORY_MONTHS = 3
        private const val DELTA_CAP_PERCENTAGE = 0.15 // ±15% per cycle
        private const val HIGH_VOLATILITY_THRESHOLD = 0.30 // 30% CV
        private const val MEDIUM_VOLATILITY_THRESHOLD = 0.15 // 15% CV
        private const val TREND_THRESHOLD = 0.10 // 10% change per month
        private const val PROJECTION_MONTHS = 3 // Project 3 months ahead
        
        // Safety factors based on volatility
        private const val HIGH_VOLATILITY_SAFETY_FACTOR = 1.15
        private const val MEDIUM_VOLATILITY_SAFETY_FACTOR = 1.08
        private const val LOW_VOLATILITY_SAFETY_FACTOR = 1.0
    }

    /**
     * Generate per-category budget adjustment recommendations.
     *
     * A.9: Uses aggregate monthly spending totals from [ExpenseDao] instead of
     * fetching raw expense rows through [ExpenseRepository], eliminating the
     * risk of silent truncation on large datasets.
     */
    suspend fun generateRecommendations(): BudgetAutopilotRecommendations {
        val now = timeProvider.now()
        val budgets = budgetRepository.getActiveBudgets()
        val categories = categoryRepository.allCategories.first()
        
        if (budgets.isEmpty()) {
            return BudgetAutopilotRecommendations(
                categoryRecommendations = emptyList(),
                totalCurrentBudget = 0.0,
                totalRecommendedBudget = 0.0,
                overallDelta = 0.0,
                confidence = 0.0,
                generatedAt = now
            )
        }
        
        val categoryRecommendations = mutableListOf<CategoryBudgetRecommendation>()
        val hasOverallBudget = budgets.any { it.categoryId == null }
        
        for (budget in budgets) {
            val category = budget.categoryId?.let { catId -> 
                categories.find { it.id == catId } 
            }
            
            // 1. Get historical monthly spend for this budget's category
            val historicalSpend = getHistoricalSpendForBudget(budget, now)
            
            // 2. Calculate trend
            val trend = calculateTrend(historicalSpend.values)
            
            // 3. Calculate trend-adjusted spend
            val trendAdjustedSpend = calculateTrendAdjustedSpend(historicalSpend.values, trend)
            
            // 4. Calculate volatility
            val volatility = calculateVolatility(historicalSpend.values)
            
            // 5. Determine safety factor based on risk/volatility
            val safetyFactor = calculateSafetyFactor(volatility)
            
            // 6. Compute recommended budget
            var recommendedBudget = trendAdjustedSpend * safetyFactor
            
            // 7. Apply delta caps (±15% per cycle)
            val maxDelta = budget.amount * DELTA_CAP_PERCENTAGE
            recommendedBudget = recommendedBudget.coerceIn(
                budget.amount - maxDelta,
                budget.amount + maxDelta
            )
            
            // 8. Calculate delta and percentage
            val delta = recommendedBudget - budget.amount
            val deltaPercentage = if (budget.amount > 0) (delta / budget.amount) * 100 else 0.0
            
            // 9. Generate reason
            val reason = generateReason(trend, volatility, safetyFactor, budget.amount, recommendedBudget)
            
            // 10. Calculate confidence based on data quality
            val confidence = calculateRecommendationConfidence(
                historicalSpend = historicalSpend.values,
                observedHistoryMonths = historicalSpend.observedMonthCount,
                volatility = volatility
            )
            
            // 11. Determine trend direction
            val trendDirection = when {
                trend > TREND_THRESHOLD -> BudgetTrend.INCREASING
                trend < -TREND_THRESHOLD -> BudgetTrend.DECREASING
                else -> BudgetTrend.STABLE
            }
            
            categoryRecommendations.add(
                CategoryBudgetRecommendation(
                    budgetId = budget.id,
                    categoryId = budget.categoryId,
                    categoryName = category?.name ?: "Overall Budget",
                    currentBudget = budget.amount,
                    recommendedBudget = recommendedBudget,
                    delta = delta,
                    deltaPercentage = deltaPercentage,
                    reason = reason,
                    confidence = confidence,
                    trend = trendDirection
                )
            )
            
        }

        val summaryRecommendations = categoryRecommendations.filter { recommendation ->
            if (hasOverallBudget) {
                recommendation.categoryId == null
            } else {
                recommendation.categoryId != null
            }
        }

        val totalCurrentBudget = summaryRecommendations.sumOf { it.currentBudget }
        val totalRecommendedBudget = summaryRecommendations.sumOf { it.recommendedBudget }

        val overallConfidence = if (summaryRecommendations.isNotEmpty()) {
            summaryRecommendations.map { it.confidence }.average()
        } else 0.0
        
        return BudgetAutopilotRecommendations(
            categoryRecommendations = categoryRecommendations,
            totalCurrentBudget = totalCurrentBudget,
            totalRecommendedBudget = totalRecommendedBudget,
            overallDelta = totalRecommendedBudget - totalCurrentBudget,
            confidence = overallConfidence.coerceIn(0.0, 1.0),
            generatedAt = now
        )
    }
    
    /**
     * Get historical spending data for a specific budget as a list of
     * chronologically ordered monthly totals.
     *
     * A.9: Uses aggregate SQL ([ExpenseDao.getMonthlySpendingTotalsByCategoryBetween]
     * / [ExpenseDao.getMonthlySpendingTotalsBetween]) instead of fetching raw
     * expense rows, eliminating the silent-truncation risk of capped row reads.
     *
     * Gap months between the first and last observed months are synthesized as
     * explicit zero-spend buckets before trend/volatility math runs.
     */
    private suspend fun getHistoricalSpendForBudget(
        budget: com.yourname.expensetracker.data.database.entity.Budget,
        now: Long
    ): HistoricalSpendSeries {
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)

        val monthlyTotals: List<MonthlySpendingTotal> = if (budget.categoryId != null) {
            expenseDao.getMonthlySpendingTotalsByCategoryBetween(
                categoryId = budget.categoryId,
                startDate = threeMonthsAgo,
                endDate = now
            )
        } else {
            expenseDao.getMonthlySpendingTotalsBetween(
                startDate = threeMonthsAgo,
                endDate = now
            )
        }

        if (monthlyTotals.isEmpty()) {
            return HistoricalSpendSeries(
                values = emptyList(),
                observedMonthCount = 0
            )
        }

        val totalsByMonth = linkedMapOf<String, Double>()
        for (monthlyTotal in monthlyTotals) {
            totalsByMonth[monthlyTotal.monthKey] = monthlyTotal.total
        }

        val sortedMonthKeys = totalsByMonth.keys.sorted()
        val infilledValues = runCatching {
            buildMonthKeyRange(sortedMonthKeys.first(), sortedMonthKeys.last()).map { monthKey ->
                totalsByMonth[monthKey] ?: 0.0
            }
        }.getOrElse {
            monthlyTotals.map { it.total }
        }

        return HistoricalSpendSeries(
            values = infilledValues,
            observedMonthCount = monthlyTotals.size
        )
    }

    /**
     * Calculate spending trend as percentage change per month.
     */
    private fun calculateTrend(historicalSpend: List<Double>): Double {
        if (historicalSpend.size < 2) return 0.0

        val avgSpend = historicalSpend.average()
        
        if (avgSpend <= 0) return 0.0
        
        // Input is monthly totals in chronological order.
        val sorted = historicalSpend
        val firstHalf = sorted.take(sorted.size / 2)
        val secondHalf = sorted.drop(sorted.size / 2)

        if (firstHalf.isEmpty() || secondHalf.isEmpty()) return 0.0

        val firstHalfAvg = firstHalf.average()
        val secondHalfAvg = secondHalf.average()

        // Normalize by number of month-buckets in each half.
        val periodsPerHalf = (sorted.size / 2.0).coerceAtLeast(1.0)
        
        return if (firstHalfAvg > 0) {
            ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) / periodsPerHalf
        } else 0.0
    }
    
    /**
     * Calculate trend-adjusted spend projecting forward.
     */
    private fun calculateTrendAdjustedSpend(
        historicalSpend: List<Double>, 
        trend: Double
    ): Double {
        if (historicalSpend.isEmpty()) return 0.0
        
        val average = historicalSpend.average()
        // Apply trend projection for 3 months
        return average * (1 + trend * PROJECTION_MONTHS)
    }
    
    /**
     * Calculate coefficient of variation (volatility).
     */
    private fun calculateVolatility(historicalSpend: List<Double>): Double {
        if (historicalSpend.size < 2) return 0.0
        
        val average = historicalSpend.average()
        if (average <= 0) return 0.0
        
        val variance = historicalSpend.map { (it - average) * (it - average) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        
        return stdDev / average // Coefficient of variation
    }
    
    /**
     * Calculate safety factor based on volatility.
     */
    private fun calculateSafetyFactor(volatility: Double): Double {
        return when {
            volatility > HIGH_VOLATILITY_THRESHOLD -> HIGH_VOLATILITY_SAFETY_FACTOR
            volatility > MEDIUM_VOLATILITY_THRESHOLD -> MEDIUM_VOLATILITY_SAFETY_FACTOR
            else -> LOW_VOLATILITY_SAFETY_FACTOR
        }
    }
    
    /**
     * Generate a human-readable reason for the recommendation.
     */
    private fun generateReason(
        trend: Double,
        volatility: Double,
        safetyFactor: Double,
        currentBudget: Double,
        recommendedBudget: Double
    ): String {
        return when {
            trend > TREND_THRESHOLD -> {
                "Spending in this category is increasing (+${(trend * 100).toInt()}%/month). " +
                buildIncreaseBudgetGuidance(currentBudget, recommendedBudget)
            }
            trend < -TREND_THRESHOLD -> {
                "Spending in this category is decreasing (${(trend * 100).toInt()}%/month). " +
                "You can reduce budget and save the difference."
            }
            volatility > HIGH_VOLATILITY_THRESHOLD -> {
                "High volatility in this category (${(volatility * 100).toInt()}% CV). " +
                "Safety buffer applied to prevent overspending."
            }
            volatility > MEDIUM_VOLATILITY_THRESHOLD -> {
                "Moderate volatility detected. Small buffer applied for safety."
            }
            safetyFactor > LOW_VOLATILITY_SAFETY_FACTOR -> {
                "Stable spending with safety buffer for unexpected expenses."
            }
            else -> {
                "Stable spending pattern. Budget aligned with historical average."
            }
        }
    }

    /**
     * Build safe increase guidance without dividing by zero.
     */
    private fun buildIncreaseBudgetGuidance(currentBudget: Double, recommendedBudget: Double): String {
        return if (currentBudget > 0.0) {
            "Consider increasing budget by ${((recommendedBudget - currentBudget) / currentBudget * 100).toInt()}% to avoid overspending."
        } else {
            "Consider setting an initial budget to avoid overspending."
        }
    }
    
    /**
     * Calculate confidence score for this recommendation.
     */
    private fun calculateRecommendationConfidence(
        historicalSpend: List<Double>,
        observedHistoryMonths: Int,
        volatility: Double
    ): Double {
        if (observedHistoryMonths < MIN_HISTORY_MONTHS) {
            return ((observedHistoryMonths.toDouble() / MIN_HISTORY_MONTHS) * 0.4)
                .coerceIn(0.0, 0.4)
        }

        var confidence = 0.5 // Base confidence
        
        // More data = higher confidence
        confidence += (historicalSpend.size / 100.0).coerceAtMost(0.2)
        
        // Lower volatility = higher confidence
        confidence += when {
            volatility < 0.1 -> 0.2
            volatility < 0.2 -> 0.1
            volatility < 0.3 -> 0.0
            else -> -0.1
        }
        
        return confidence.coerceIn(0.0, 1.0)
    }

    private fun buildMonthKeyRange(startMonthKey: String, endMonthKey: String): List<String> {
        val cursor = parseMonthKey(startMonthKey)
        val end = parseMonthKey(endMonthKey)
        val monthKeys = mutableListOf<String>()

        while (!cursor.after(end)) {
            monthKeys.add(formatMonthKey(cursor))
            cursor.add(Calendar.MONTH, 1)
        }

        return monthKeys
    }

    private fun parseMonthKey(monthKey: String): Calendar {
        val parts = monthKey.split("-")
        require(parts.size == 2) { "Unexpected month key: $monthKey" }

        val year = parts[0].toInt()
        val month = parts[1].toInt()
        require(month in 1..12) { "Unexpected month key: $monthKey" }

        return Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun formatMonthKey(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return String.format("%04d-%02d", year, month)
    }

    private data class HistoricalSpendSeries(
        val values: List<Double>,
        val observedMonthCount: Int
    )
}

/**
 * Container for all autopilot recommendations.
 */
data class BudgetAutopilotRecommendations(
    val categoryRecommendations: List<CategoryBudgetRecommendation>,
    val totalCurrentBudget: Double,
    val totalRecommendedBudget: Double,
    val overallDelta: Double,
    val confidence: Double,
    val generatedAt: Long
)

/**
 * Single category budget recommendation.
 */
data class CategoryBudgetRecommendation(
    val budgetId: Long,
    val categoryId: Long?,
    val categoryName: String,
    val currentBudget: Double,
    val recommendedBudget: Double,
    val delta: Double,
    val deltaPercentage: Double,
    val reason: String,
    val confidence: Double,
    val trend: BudgetTrend
)
