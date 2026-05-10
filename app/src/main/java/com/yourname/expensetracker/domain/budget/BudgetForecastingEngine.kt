package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * AI-powered budget forecasting engine.
 * Predicts spending patterns and budget adherence using historical data.
 */
/**
 * AI-powered budget forecasting engine.
 * Predicts spending patterns and budget adherence using historical data.
 *
 * CURRENCY NOTE: All monetary operations go through [AnalyticsCurrencyNormalizer]
 * to ensure multi-currency expenses are normalized to the home currency before
 * any sum, comparison, or trend computation. The engine no longer uses raw
 * SQL sums that bypass currency conversion (see getSpentAmount replacement).
 */
@Singleton
class BudgetForecastingEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val budgetRepository: BudgetRepository,
    private val budgetForecastDao: BudgetForecastDao,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    /** @suppress Normalizer injected for currency-aware spent-to-date computation. */
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    /** @suppress Repository injected for expense snapshot queries (required by normalizer). */
    private val expenseRepository: ExpenseRepository,
    /** @suppress Settings injected to resolve home currency code. */
    private val currencySettingsRepository: CurrencySettingsRepository,
    /** @suppress Converter injected to normalise budget.amount to home currency. */
    private val currencyConverter: CurrencyConverter,
    /** @suppress Write barrier injected to guard forecast writes during restore. */
    private val writeBarrier: com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
) {
    private val budgetCalculator = BudgetCalculator(timeProvider)

    companion object {
        const val MIN_HISTORY_MONTHS = 3
        const val CONFIDENCE_THRESHOLD_HIGH = 0.8
        const val CONFIDENCE_THRESHOLD_MEDIUM = 0.6
        private const val TREND_THRESHOLD = 0.10
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000.0
        private const val DESIRED_HISTORY_MONTHS = 4.0
    }

    /**
     * Generate a forecast for a specific budget.
     */
    suspend fun generateForecast(
        budget: Budget,
        forecastPeriodDays: Int = 30
    ): BudgetForecast = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("BudgetForecastingEngine.generateForecast")
        val now = timeProvider.now()
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")

        // P0-5: Normalize budget.amount to home currency if budget.currency differs
        val normalizedBudgetAmount = runCatching {
            val converted = currencyConverter.convert(budget.amount, budget.currency, homeCurrency)
            converted?.convertedAmount ?: budget.amount
        }.getOrElse {
            Timber.w("BudgetForecastingEngine: Failed to convert budget.amount=%.2f %s to %s, using raw amount",
                budget.amount, budget.currency, homeCurrency)
            budget.amount
        }

        // Calculate active budget period window and elapsed segment for spent-to-date.
        val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, now)
        val elapsedEnd = now.coerceAtMost(periodEnd)
        val spentToDate = getSpentAmount(budget, periodStart, elapsedEnd, homeCurrency)
        val remainingForecastDays = TimePeriodUtils.daysBetween(elapsedEnd, periodEnd).coerceAtLeast(0).toDouble()
        
        // Get historical spending data for this budget's category
        val historicalData = getHistoricalSpendingData(budget)
        
        // Calculate predicted spending using multiple factors
        val predictedSpending = calculatePredictedSpending(
            historicalData = historicalData,
            forecastPeriodDays = remainingForecastDays
        )
        
        // Calculate confidence based on data quality
        val confidence = calculateConfidence(historicalData)
        
        // Determine risk level
        val riskLevel = determineRiskLevel(
            budget = budget,
            predictedSpending = predictedSpending,
            confidence = confidence,
            spentToDate = spentToDate,
            normalizedBudgetAmount = normalizedBudgetAmount
        )
        
        // Calculate overspend probability
        val overspendProbability = calculateOverspendProbability(
            budgetAmount = normalizedBudgetAmount,
            predictedSpending = predictedSpending,
            spentToDate = spentToDate,
            confidence = confidence
        )
        
        // Calculate predicted remaining
        val predictedRemaining = normalizedBudgetAmount - spentToDate - predictedSpending
        
        val forecast = BudgetForecast(
            budgetId = budget.id,
            forecastDate = now,
            targetPeriodStart = periodStart,
            targetPeriodEnd = periodEnd,
            predictedSpending = predictedSpending,
            predictedRemaining = predictedRemaining,
            confidenceScore = confidence,
            riskLevel = riskLevel,
            overspendProbability = overspendProbability,
            createdAt = now,
            currency = homeCurrency
        )
        
        // Save forecast — deactivate any existing active forecast for the same
        // budget+period so only the newest forecast remains active.
        val persistedId = budgetForecastDao.insertWithDeactivation(forecast)

        forecast.copy(id = persistedId)
    }
    
    /**
     * Get historical spending data for pattern analysis.
     *
     * Fetches raw expense snapshots and normalises them to the home currency
     * via [AnalyticsCurrencyNormalizer] before grouping into monthly buckets.
     * This replaces the earlier raw-SQL aggregate approach (A.9) that summed
     * amounts across mixed currencies without conversion — see
     * [ExpenseDao.getMonthlySpendingTotalsByCategoryBetween] /
     * [ExpenseDao.getMonthlySpendingTotalsBetween] which are now deprecated
     * for exactly that reason.
     *
     * Gap months between first/last observed month keys are synthesized as
     * explicit zero-spend buckets so averages and trends are not skewed
     * upward when a user simply had no spending in an intermediate month.
     */
    private suspend fun getHistoricalSpendingData(budget: Budget): HistoricalData {
        val now = timeProvider.now()
        val threeMonthsAgo = TimePeriodUtils.addMonths(now, -3)
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")

        // ── Fetch raw snapshots and normalise to home currency ──────────────
        val rawExpenses = expenseRepository.getExpenseSnapshotsBetween(threeMonthsAgo, now)
        val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawExpenses, homeCurrency)
        val relevantExpenses = normalized.includedExpenses

        // Log conversion warnings if any occurred
        if (normalized.hasWarnings) {
            Timber.w(
                "BudgetForecastingEngine historical: ${normalized.warnings.size} conversion warning(s), " +
                "${normalized.excludedCount} transactions excluded"
            )
        }

        // Filter by category and spending type, then group into monthly buckets
        val filtered = if (budget.categoryId != null) {
            relevantExpenses.filter { it.categoryId == budget.categoryId }
        } else {
            relevantExpenses
        }

        val spendingExpenses = filtered.filter {
            (it.transactionType == DomainTransactionType.PURCHASE ||
             it.transactionType == DomainTransactionType.WITHDRAWAL) &&
            !it.isNotMine
        }

        val monthlyTotals: List<MonthlySpendingTotal> = spendingExpenses
            .groupBy { TimePeriodUtils.formatMonthKey(it.date) }
            .map { (monthKey, expenses) ->
                MonthlySpendingTotal(
                    monthKey = monthKey,
                    total = expenses.sumOf { it.effectiveAmount },
                    txCount = expenses.size
                )
            }
            .sortedBy { it.monthKey }

        val normalizedSeries = BudgetHistorySeriesBuilder.build(
            monthlyTotals = monthlyTotals,
            windowStartInclusive = threeMonthsAgo,
            windowEndExclusive = now
        )

        val monthlySpending = linkedMapOf<String, Double>()
        normalizedSeries.monthKeys.forEachIndexed { index, monthKey ->
            monthlySpending[monthKey] = normalizedSeries.values[index]
        }
        
        // Calculate statistics
        val values = normalizedSeries.values
        val average = if (values.isNotEmpty()) values.sum() / values.size else 0.0
        
        var variance = 0.0
        for (value in values) {
            variance += (value - average) * (value - average)
        }
        val standardDeviation = if (values.size > 1) {
            kotlin.math.sqrt(variance / (values.size - 1))
        } else 0.0
        
        val trend = when (BudgetHistorySeriesBuilder.classifyTrend(values, TREND_THRESHOLD)) {
            BudgetHistorySeriesBuilder.TrendDirection.INCREASING -> SpendingTrend.INCREASING
            BudgetHistorySeriesBuilder.TrendDirection.DECREASING -> SpendingTrend.DECREASING
            BudgetHistorySeriesBuilder.TrendDirection.STABLE -> SpendingTrend.STABLE
        }
        
        return HistoricalData(
            monthlySpending = monthlySpending,
            averageMonthly = average,
            standardDeviation = standardDeviation,
            monthsOfHistory = normalizedSeries.filledMonthCount,
            observedMonthCount = normalizedSeries.observedMonthCount,
            trend = trend
        )
    }
    
    /**
     * Calculate predicted spending using historical patterns.
     */
    private fun calculatePredictedSpending(
        historicalData: HistoricalData,
        forecastPeriodDays: Double
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
            val seasonalFactor = calculateSeasonalFactor()
            prediction *= seasonalFactor
        }
        
        return max(prediction, 0.0)
    }
    
    /**
     * Calculate confidence score based on data quality.
     */
    private fun calculateConfidence(historicalData: HistoricalData): Double {
        val historyCompleteness = (historicalData.observedMonthCount / DESIRED_HISTORY_MONTHS)
            .coerceIn(0.0, 1.0)
        var confidence = historyCompleteness * 0.8
        
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
        spentToDate: Double,
        normalizedBudgetAmount: Double
    ): ForecastRiskLevel {
        if (spentToDate >= normalizedBudgetAmount) return ForecastRiskLevel.CRITICAL

        val remaining = normalizedBudgetAmount - spentToDate
        
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
        // Deterministic overspend: already over budget before any forecast uncertainty.
        if (spentToDate >= budgetAmount) {
            return 1.0
        }

        val projectedTotal = spentToDate + predictedSpending
        if (projectedTotal >= budgetAmount) {
            return 1.0
        }

        val buffer = budgetAmount - projectedTotal
        val probability = when {
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
    private fun calculateSeasonalFactor(): Double {
        return 1.0
    }
    
    /**
     * Get amount already spent in current period, normalized to home currency.
     *
     * Replaces raw DAO SQL sums (which mixed currencies) with a normalizer-based
     * computation that converts all expenses to [homeCurrency] before summing.
     * Conversion warnings from the normalizer are logged but do not block the
     * computation — partially converted data is still used for the forecast.
     */
    private suspend fun getSpentAmount(
        budget: Budget,
        periodStart: Long,
        periodEnd: Long,
        homeCurrency: String
    ): Double {
        if (periodEnd <= periodStart) return 0.0

        // Fetch raw expenses in the period and normalize to home currency
        val rawExpenses = expenseRepository.getExpenseSnapshotsBetween(periodStart, periodEnd)
        val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawExpenses, homeCurrency)
        val relevantExpenses = normalized.includedExpenses

        // Filter by category if needed
        val filtered = if (budget.categoryId != null) {
            relevantExpenses.filter { it.categoryId == budget.categoryId }
        } else {
            relevantExpenses
        }

        // Sum only spending-type expenses (PURCHASE/WITHDRAWAL) that belong to the user
        val total = filtered
            .filter { (it.transactionType == DomainTransactionType.PURCHASE ||
                       it.transactionType == DomainTransactionType.WITHDRAWAL) &&
                      !it.isNotMine }
            .sumOf { it.effectiveAmount }

        // Log conversion warnings if any occurred
        if (normalized.hasWarnings) {
            Timber.w(
                "BudgetForecastingEngine: ${normalized.warnings.size} conversion warning(s), " +
                "${normalized.excludedCount} transactions excluded"
            )
        }

        return total
    }
    
    /**
     * Update a forecast with actual spending data after period ends.
     *
     * Computes forecast accuracy as:
     *   accuracy = 1 - (|predicted - actual| / max(predicted, actual))
     *
     * This produces a value in [0, 1] where 1.0 = perfect prediction,
     * 0.0 = completely wrong, and negative values mean actual exceeded
     * prediction by more than 2x.
     *
     * The result is clamped to [-1.0, 1.0] to bound outlier scenarios.
     */
    suspend fun updateForecastAccuracy(
        forecastId: Long,
        actualSpending: Double
    ) = withContext(ioDispatcher) {
        writeBarrier.checkWritesAllowed("BudgetForecastingEngine.updateForecastAccuracy")
        val forecast = budgetForecastDao.getById(forecastId)
            ?: return@withContext

        // BUD-6: Actual accuracy computation replacing placeholder.
        val predicted = forecast.predictedSpending
        val accuracy = if (predicted > 0.0) {
            val error = kotlin.math.abs(predicted - actualSpending)
            val denominator = maxOf(predicted, actualSpending)
            1.0 - (error / denominator)
        } else {
            // No meaningful prediction — accuracy is 0 if any actual spending occurred
            if (actualSpending > 0.0) 0.0 else 1.0
        }

        val clampedAccuracy = accuracy.coerceIn(-1.0, 1.0)

        budgetForecastDao.update(
            forecast.copy(
                actualSpending = actualSpending,
                forecastAccuracy = clampedAccuracy
            )
        )

        Timber.d(
            "updateForecastAccuracy: forecastId=%d predicted=%.2f actual=%.2f accuracy=%.4f",
            forecastId, predicted, actualSpending, clampedAccuracy
        )
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
    val observedMonthCount: Int,
    val trend: SpendingTrend
)

private enum class SpendingTrend {
    INCREASING,
    DECREASING,
    STABLE
}
