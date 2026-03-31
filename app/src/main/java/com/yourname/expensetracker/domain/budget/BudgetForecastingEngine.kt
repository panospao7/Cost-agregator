package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AI-powered budget forecasting engine.
 * Predicts spending patterns and budget adherence using historical data.
 */
@Singleton
class BudgetForecastingEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val budgetRepository: BudgetRepository,
    private val budgetForecastDao: BudgetForecastDao,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val MIN_HISTORY_MONTHS = 3
        const val CONFIDENCE_THRESHOLD_HIGH = 0.8
        const val CONFIDENCE_THRESHOLD_MEDIUM = 0.6
    }

    /**
     * Generate a forecast for a specific budget.
     */
    suspend fun generateForecast(
        budget: Budget,
        forecastPeriodDays: Int = 30
    ): BudgetForecast = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        
        // Calculate period dates
        val periodStart = now
        val periodEnd = now + (forecastPeriodDays * 24 * 60 * 60 * 1000L)
        
        // Get historical spending data for this budget's category
        val historicalData = getHistoricalSpendingData(budget)
        
        // Calculate predicted spending using multiple factors
        val predictedSpending = calculatePredictedSpending(
            budget = budget,
            historicalData = historicalData,
            forecastPeriodDays = forecastPeriodDays
        )
        
        // Calculate confidence based on data quality
        val confidence = calculateConfidence(historicalData)
        
        // Determine risk level
        val riskLevel = determineRiskLevel(budget, predictedSpending, confidence)
        
        // Calculate overspend probability
        val overspendProbability = calculateOverspendProbability(
            budgetAmount = budget.amount,
            predictedSpending = predictedSpending,
            confidence = confidence
        )
        
        // Calculate predicted remaining
        val spentToDate = getSpentAmount(budget, periodStart)
        val predictedRemaining = budget.amount - spentToDate - predictedSpending
        
        val forecast = BudgetForecast(
            budgetId = budget.id,
            forecastDate = now,
            targetPeriodStart = periodStart,
            targetPeriodEnd = periodEnd,
            predictedSpending = predictedSpending,
            predictedRemaining = predictedRemaining,
            confidenceScore = confidence,
            riskLevel = riskLevel,
            overspendProbability = overspendProbability
        )
        
        // Save forecast
        budgetForecastDao.insert(forecast)
        
        forecast
    }
    
    /**
     * Get historical spending data for pattern analysis.
     */
    private suspend fun getHistoricalSpendingData(budget: Budget): HistoricalData {
        val now = timeProvider.now()
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        
        // Get expenses for this budget's category
        val expenses = if (budget.categoryId != null) {
            expenseDao.getExpensesByTypeBetween(
                threeMonthsAgo, 
                now, 
                "PURCHASE"
            ).filter { it.categoryId == budget.categoryId }
        } else {
            expenseDao.getExpensesBetween(threeMonthsAgo, now)
        }
        
        // Group by month
        val monthlyTotals = mutableMapOf<String, Double>()
        for (expense in expenses) {
            val monthKey = getMonthKey(expense.date)
            val current = monthlyTotals[monthKey] ?: 0.0
            monthlyTotals[monthKey] = current + expense.amount
        }
        
        // Calculate statistics
        val values = monthlyTotals.values.toList()
        val average = if (values.isNotEmpty()) values.sum() / values.size else 0.0
        
        var variance = 0.0
        for (value in values) {
            variance += (value - average) * (value - average)
        }
        val standardDeviation = if (values.size > 1) {
            kotlin.math.sqrt(variance / (values.size - 1))
        } else 0.0
        
        // Detect trend
        val trend = if (values.size >= 2) {
            val recent = values.takeLast(2).average()
            val older = values.dropLast(2).average()
            when {
                recent > older * 1.1 -> SpendingTrend.INCREASING
                recent < older * 0.9 -> SpendingTrend.DECREASING
                else -> SpendingTrend.STABLE
            }
        } else SpendingTrend.STABLE
        
        return HistoricalData(
            monthlySpending = monthlyTotals,
            averageMonthly = average,
            standardDeviation = standardDeviation,
            monthsOfHistory = monthlyTotals.size,
            trend = trend
        )
    }
    
    /**
     * Calculate predicted spending using historical patterns.
     */
    private fun calculatePredictedSpending(
        budget: Budget,
        historicalData: HistoricalData,
        forecastPeriodDays: Int
    ): Double {
        val months = forecastPeriodDays / 30.0
        
        // Base prediction from historical average
        var prediction = historicalData.averageMonthly * months
        
        // Adjust for trend
        prediction = when (historicalData.trend) {
            SpendingTrend.INCREASING -> prediction * 1.1
            SpendingTrend.DECREASING -> prediction * 0.9
            SpendingTrend.STABLE -> prediction
        }
        
        // Add seasonal adjustment (if we have enough history)
        if (historicalData.monthsOfHistory >= 6) {
            val seasonalFactor = calculateSeasonalFactor(historicalData)
            prediction *= seasonalFactor
        }
        
        // Cap at budget amount
        prediction = min(prediction, budget.amount)
        
        return max(prediction, 0.0)
    }
    
    /**
     * Calculate confidence score based on data quality.
     */
    private fun calculateConfidence(historicalData: HistoricalData): Double {
        var confidence = 0.5 // Base confidence
        
        // More data = higher confidence
        confidence += min(historicalData.monthsOfHistory / 12.0, 0.3)
        
        // Lower variance = higher confidence
        val coefficientOfVariation = if (historicalData.averageMonthly > 0) {
            historicalData.standardDeviation / historicalData.averageMonthly
        } else 0.0
        
        confidence += when {
            coefficientOfVariation < 0.1 -> 0.2
            coefficientOfVariation < 0.3 -> 0.1
            coefficientOfVariation < 0.5 -> 0.0
            else -> -0.1
        }
        
        return min(max(confidence, 0.0), 1.0)
    }
    
    /**
     * Determine risk level based on prediction vs budget.
     */
    private fun determineRiskLevel(
        budget: Budget,
        predictedSpending: Double,
        confidence: Double
    ): ForecastRiskLevel {
        val spentToDate = getSpentAmountSync(budget)
        val remaining = budget.amount - spentToDate
        
        // Calculate percentage of remaining budget that will be used
        val usageRatio = if (remaining > 0) predictedSpending / remaining else 1.0
        
        return when {
            usageRatio > 1.0 && confidence > CONFIDENCE_THRESHOLD_MEDIUM -> ForecastRiskLevel.CRITICAL
            usageRatio > 0.9 && confidence > CONFIDENCE_THRESHOLD_MEDIUM -> ForecastRiskLevel.HIGH
            usageRatio > 0.75 -> ForecastRiskLevel.MEDIUM
            else -> ForecastRiskLevel.LOW
        }
    }
    
    /**
     * Calculate probability of overspending.
     */
    private fun calculateOverspendProbability(
        budgetAmount: Double,
        predictedSpending: Double,
        confidence: Double
    ): Double {
        val buffer = budgetAmount - predictedSpending
        val probability = when {
            buffer < 0 -> 1.0 // Already predicted to exceed
            buffer < budgetAmount * 0.1 -> 0.8 // Very tight
            buffer < budgetAmount * 0.25 -> 0.5 // Tight
            buffer < budgetAmount * 0.5 -> 0.2 // Comfortable
            else -> 0.05 // Very comfortable
        }
        
        // Adjust by confidence
        return probability * confidence
    }
    
    /**
     * Calculate seasonal adjustment factor.
     */
    private fun calculateSeasonalFactor(historicalData: HistoricalData): Double {
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH)
        
        // Simple seasonal factor (can be expanded)
        // December tends to have higher spending
        if (currentMonth == Calendar.DECEMBER) {
            return 1.2
        }
        
        return 1.0
    }
    
    /**
     * Get amount already spent in current period.
     */
    private suspend fun getSpentAmount(budget: Budget, periodStart: Long): Double {
        val periodEnd = timeProvider.now()
        val totalSpent = expenseDao.getTotalSpentBetween(periodStart, periodEnd) ?: 0.0
        
        // If budget is category-specific, we need to filter (simplified here)
        return totalSpent
    }
    
    /**
     * Synchronous version for risk calculation.
     */
    private fun getSpentAmountSync(budget: Budget): Double {
        // Simplified - in real implementation would need to query
        return 0.0
    }
    
    private fun getMonthKey(timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return "$year-${month.toString().padStart(2, '0')}"
    }
    
    /**
     * Update a forecast with actual spending data after period ends.
     */
    suspend fun updateForecastAccuracy(
        forecastId: Long,
        actualSpending: Double
    ) = withContext(Dispatchers.IO) {
        val forecast = budgetForecastDao.getForecastsForBudget(forecastId).let { flow ->
            // Get the specific forecast - this is a Flow so we'd need to collect it
            // Simplified for now
            null
        }
        
        // Calculate accuracy
        // accuracy = 1 - (|predicted - actual| / predicted)
        // This is a simplified accuracy metric
    }
}

/**
 * Historical spending data for forecasting.
 */
private data class HistoricalData(
    val monthlySpending: Map<String, Double>,
    val averageMonthly: Double,
    val standardDeviation: Double,
    val monthsOfHistory: Int,
    val trend: SpendingTrend
)

private enum class SpendingTrend {
    INCREASING,
    DECREASING,
    STABLE
}
