package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
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
        
        // Calculate active budget period window and elapsed segment for spent-to-date.
        val (periodStart, periodEnd) = calculateCurrentBudgetPeriodRange(budget, now)
        val elapsedEnd = now.coerceAtMost(periodEnd)
        val spentToDate = getSpentAmount(budget, periodStart, elapsedEnd)
        
        // Get historical spending data for this budget's category
        val historicalData = getHistoricalSpendingData(budget)
        
        // Calculate predicted spending using multiple factors
        val predictedSpending = calculatePredictedSpending(
            historicalData = historicalData,
            forecastPeriodDays = forecastPeriodDays
        )
        
        // Calculate confidence based on data quality
        val confidence = calculateConfidence(historicalData)
        
        // Determine risk level
        val riskLevel = determineRiskLevel(
            budget = budget,
            predictedSpending = predictedSpending,
            confidence = confidence,
            spentToDate = spentToDate
        )
        
        // Calculate overspend probability
        val overspendProbability = calculateOverspendProbability(
            budgetAmount = budget.amount,
            predictedSpending = predictedSpending,
            spentToDate = spentToDate,
            confidence = confidence
        )
        
        // Calculate predicted remaining
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
            expenseDao.getExpensesByCategory(
                categoryId = budget.categoryId,
                startDate = threeMonthsAgo,
                endDate = now
            )
        } else {
            expenseDao.getExpensesByTypeBetween(
                startDate = threeMonthsAgo,
                endDate = now,
                type = "PURCHASE"
            )
        }
        
        // Group by month
        val monthlyTotals = mutableMapOf<String, Double>()
        for (expense in expenses) {
            val monthKey = getMonthKey(expense.date)
            val current = monthlyTotals[monthKey] ?: 0.0
            monthlyTotals[monthKey] = current + expense.effectiveAmount
        }
        
        // Calculate statistics
        val sortedMonthKeys = monthlyTotals.keys.sorted()
        val values = sortedMonthKeys.map { monthlyTotals[it] ?: 0.0 }
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
        confidence: Double,
        spentToDate: Double
    ): ForecastRiskLevel {
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
        spentToDate: Double,
        confidence: Double
    ): Double {
        val projectedTotal = spentToDate + predictedSpending
        val buffer = budgetAmount - projectedTotal
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
        val calendar = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
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
    private suspend fun getSpentAmount(budget: Budget, periodStart: Long, periodEnd: Long): Double {
        if (periodEnd <= periodStart) return 0.0

        return if (budget.categoryId != null) {
            expenseDao.getCategorySpentInPeriod(
                categoryId = budget.categoryId,
                startMs = periodStart,
                endMs = periodEnd
            )
        } else {
            expenseDao.getTotalSpentBetween(periodStart, periodEnd) ?: 0.0
        }
    }

    private fun calculateCurrentBudgetPeriodRange(budget: Budget, now: Long): Pair<Long, Long> {
        return if (budget.periodMode.equals("CALENDAR", ignoreCase = true)) {
            when (budget.period) {
                BudgetPeriod.DAILY -> TimePeriodUtils.getStartOfDay(now) to TimePeriodUtils.getEndOfDay(now)
                BudgetPeriod.WEEKLY -> TimePeriodUtils.getWeekRange(now)
                BudgetPeriod.MONTHLY -> TimePeriodUtils.getMonthRange(now)
                BudgetPeriod.YEARLY -> TimePeriodUtils.getYearRange(now)
            }
        } else {
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val startOfToday = TimePeriodUtils.getStartOfDay(now)
            val anchorCal = Calendar.getInstance().apply { timeInMillis = budget.startDate }

            when (budget.period) {
                BudgetPeriod.DAILY -> {
                    val start = startOfToday
                    val end = TimePeriodUtils.getEndOfDay(start)
                    start to end
                }

                BudgetPeriod.WEEKLY -> {
                    val cal = Calendar.getInstance().apply { timeInMillis = startOfToday }
                    val anchorDayOfWeek = anchorCal.get(Calendar.DAY_OF_WEEK)
                    while (cal.get(Calendar.DAY_OF_WEEK) != anchorDayOfWeek) {
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                    }
                    val start = cal.timeInMillis
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                    start to cal.timeInMillis
                }

                BudgetPeriod.MONTHLY -> {
                    val cal = Calendar.getInstance().apply { timeInMillis = startOfToday }
                    val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)

                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    val evalDay = calendar.get(Calendar.DAY_OF_MONTH)
                    val adjustedAnchorDay = anchorDay.coerceAtMost(
                        calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                    )

                    if (evalDay < adjustedAnchorDay) {
                        cal.add(Calendar.MONTH, -1)
                    }

                    val monthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(monthMax))
                    val start = cal.timeInMillis

                    cal.add(Calendar.MONTH, 1)
                    val nextMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextMonthMax))
                    start to cal.timeInMillis
                }

                BudgetPeriod.YEARLY -> {
                    val cal = Calendar.getInstance().apply { timeInMillis = startOfToday }
                    val anchorMonth = anchorCal.get(Calendar.MONTH)
                    val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)

                    val currentMonth = cal.get(Calendar.MONTH)
                    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                    val passedAnniversary = currentMonth > anchorMonth ||
                        (currentMonth == anchorMonth && currentDay >= anchorDay)

                    if (!passedAnniversary) {
                        cal.add(Calendar.YEAR, -1)
                    }

                    cal.set(Calendar.MONTH, anchorMonth)
                    cal.set(
                        Calendar.DAY_OF_MONTH,
                        anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    )
                    val start = cal.timeInMillis
                    cal.add(Calendar.YEAR, 1)
                    start to cal.timeInMillis
                }
            }
        }
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
