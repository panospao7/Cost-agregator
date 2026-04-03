package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.BudgetTrend
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * AI-powered budget autopilot engine.
 * 
 * Generates per-category budget adjustment recommendations based on:
 * - Historical spending trends
 * - Spending volatility/risk analysis
 * - Monte Carlo simulation results
 * - User-defined delta caps (±15% per cycle)
 * 
 * Formula:
 * recommendedBudget_c = trendAdjustedSpend_c * safetyFactor(risk, volatility)
 * with min/max delta caps (e.g., ±15% per cycle)
 */
@Singleton
class BudgetAutopilotEngine @Inject constructor(
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
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
        
        // Get historical spending data for the last 3 months
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val historicalExpenses = expenseRepository.getExpensesBetween(threeMonthsAgo, now)
        
        val categoryRecommendations = mutableListOf<CategoryBudgetRecommendation>()
        var totalCurrentBudget = 0.0
        var totalRecommendedBudget = 0.0
        var totalConfidence = 0.0
        
        for (budget in budgets) {
            val category = budget.categoryId?.let { catId -> 
                categories.find { it.id == catId } 
            }
            
            // 1. Get historical spend for this budget's category
            val historicalSpend = getHistoricalSpendForBudget(budget, historicalExpenses)
            
            // 2. Calculate trend
            val trend = calculateTrend(historicalSpend)
            
            // 3. Calculate trend-adjusted spend
            val trendAdjustedSpend = calculateTrendAdjustedSpend(historicalSpend, trend)
            
            // 4. Calculate volatility
            val volatility = calculateVolatility(historicalSpend)
            
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
            val confidence = calculateRecommendationConfidence(historicalSpend, volatility)
            
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
            
            totalCurrentBudget += budget.amount
            totalRecommendedBudget += recommendedBudget
            totalConfidence += confidence
        }
        
        val overallConfidence = if (categoryRecommendations.isNotEmpty()) {
            totalConfidence / categoryRecommendations.size
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
     * Get historical spending data for a specific budget.
     */
    private fun getHistoricalSpendForBudget(
        budget: com.yourname.expensetracker.data.database.entity.Budget,
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>
    ): List<Double> {
        val threeMonthsAgo = timeProvider.now() - (90L * 24 * 60 * 60 * 1000)

        val filteredExpenses = expenses
            .filter { expense ->
                // Filter by category if budget is category-specific
                val categoryMatch = budget.categoryId == null || expense.categoryId == budget.categoryId
                // Only include purchase transactions
                val isPurchase = expense.transactionType == 
                    com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
                // Exclude "not mine" expenses
                val isMine = !expense.isNotMine
                // Within time range
                val inRange = expense.date >= threeMonthsAgo
                
                categoryMatch && isPurchase && isMine && inRange
            }

        if (filteredExpenses.isEmpty()) return emptyList()

        // Aggregate spend by month (chronological), then operate on monthly totals.
        val monthlyTotals = filteredExpenses
            .sortedBy { it.date }
            .groupBy { getMonthKey(it.date) }
            .toSortedMap()
            .mapValues { (_, txns) -> txns.sumOf { it.effectiveAmount } }

        return monthlyTotals.values.toList()
    }

    private fun getMonthKey(timestamp: Long): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestamp
        }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d-%02d", year, month)
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
                "Consider increasing budget by ${((recommendedBudget - currentBudget) / currentBudget * 100).toInt()}% to avoid overspending."
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
     * Calculate confidence score for this recommendation.
     */
    private fun calculateRecommendationConfidence(
        historicalSpend: List<Double>,
        volatility: Double
    ): Double {
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
