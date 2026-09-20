package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CategoryMonthlySpend
import com.yourname.expensetracker.domain.core.money.SpendScope
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton


/**
 * AI-powered budget autopilot engine.
 * 
 * Generates per-category budget adjustment recommendations based on:
 * - Historical spending trends (via typed repository aggregates — RP-08)
 * - Spending volatility/risk analysis
 * - User-defined delta caps (±15% per cycle)
 * 
 * Formula:
 * recommendedBudget_c = trendAdjustedSpend_c * safetyFactor(risk, volatility)
 * with min/max delta caps (e.g., ±15% per cycle)
 *
 * RP-08 (P6-002/P6-003): Historical spending is retrieved through
 * [MultiCurrencyRepository.getHistoricalCategoryMonthlySpend] — a typed,
 * currency-normalized (`RateBasis.TRANSACTION_DATE`) per-scope/per-month
 * aggregate. No reflection, no deprecated DAO calls, no raw mixed-currency
 * sums. Repository/home-currency failures propagate as typed exceptions
 * (never converted into empty history), and conversion failures surface as
 * partial aggregates ([com.yourname.expensetracker.domain.core.money.MoneyAggregate.isPartial])
 * mapped to [BudgetRecommendationQuality.PARTIAL_DATA] — never silent zeros.
 *
 * RP-08 (P6-004): incomplete edge months are excluded from the history
 * series ([com.yourname.expensetracker.domain.budget.BudgetHistorySeriesBuilder])
 * and fewer than [MIN_COMPLETE_HISTORY_MONTHS] complete months yields
 * [BudgetRecommendationQuality.LOW_HISTORY] with an identity recommendation
 * (keep the current budget; not actionable).
 */
@Singleton
class BudgetAutopilotEngine @Inject constructor(
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
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

        // RP-08 (P6-004 low-history contract): fewer than this many COMPLETE
        // months → identity recommendation (quality = LOW_HISTORY).
        private const val MIN_COMPLETE_HISTORY_MONTHS = 2
        
        // Safety factors based on volatility
        private const val HIGH_VOLATILITY_SAFETY_FACTOR = 1.15
        private const val MEDIUM_VOLATILITY_SAFETY_FACTOR = 1.08
        private const val LOW_VOLATILITY_SAFETY_FACTOR = 1.0
    }

    /**
     * Generate per-category budget adjustment recommendations.
     *
     * RP-08 (P6-002): loads the typed per-scope/per-month history **once** via
     * [MultiCurrencyRepository.getHistoricalCategoryMonthlySpend]. Failures
     * (home currency unavailable, DAO errors, cancellation) propagate to the
     * caller — they are never converted into empty history.
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
        
        // RP-08 (P6-002/P6-003): one typed load for the whole window; no
        // reflection, no deprecated DAO calls. Typed exceptions propagate
        // unchanged (including CancellationException).
        val threeMonthsAgo = com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths(now, -3)
        val scopedHistory = indexScopedHistory(
            multiCurrencyRepository.getHistoricalCategoryMonthlySpend(threeMonthsAgo, now)
        )
        
        val categoryRecommendations = mutableListOf<CategoryBudgetRecommendation>()
        val hasOverallBudget = budgets.any { it.categoryId == null }
        
        for (budget in budgets) {
            val category = budget.categoryId?.let { catId -> 
                categories.find { it.id == catId } 
            }
            
            // 1. Get historical monthly spend for this budget's scope
            val historicalSpend = getHistoricalSpendForBudget(budget, threeMonthsAgo, now, scopedHistory)

            // RP-08 (P6-004 low-history contract): fewer than 2 complete months
            // of history → identity recommendation (keep current budget), not
            // actionable. Delta caps, safety factors, and bounds are unchanged.
            if (historicalSpend.completeMonthCount < MIN_COMPLETE_HISTORY_MONTHS) {
                categoryRecommendations.add(
                    CategoryBudgetRecommendation(
                        budgetId = budget.id,
                        categoryId = budget.categoryId,
                        categoryName = category?.name ?: "Overall Budget",
                        currentBudget = budget.amount,
                        recommendedBudget = budget.amount,
                        delta = 0.0,
                        deltaPercentage = 0.0,
                        reason = "Insufficient complete history (${historicalSpend.completeMonthCount} complete month(s)); keeping current budget.",
                        confidence = calculateRecommendationConfidence(
                            historicalSpend = historicalSpend.values,
                            observedHistoryMonths = historicalSpend.completeMonthCount,
                            volatility = 0.0
                        ),
                        trend = BudgetTrend.STABLE,
                        quality = BudgetRecommendationQuality.LOW_HISTORY,
                        isActionable = false
                    )
                )
                continue
            }

            // RP-08 (P6-003): conversion failures surface as partial aggregates —
            // mapped to PARTIAL_DATA, never read as zero spend.
            val quality = if (historicalSpend.isPartial) {
                BudgetRecommendationQuality.PARTIAL_DATA
            } else {
                BudgetRecommendationQuality.COMPLETE
            }
            
            // 2. Calculate trend
            val trend = calculateTrend(historicalSpend.values)
            
            // 3. Calculate trend-adjusted spend
            val trendAdjustedSpend = calculateTrendAdjustedSpend(historicalSpend.values, trend)
            
            // 4. Calculate volatility
            val volatility = calculateVolatility(historicalSpend.values)
            
            // 5. Determine safety factor based on risk/volatility
            val safetyFactor = calculateSafetyFactor(volatility)
            
            // 6. Compute recommended budget (in monthly terms from historical data)
            var recommendedBudget = trendAdjustedSpend * safetyFactor

            // BUD-19: Normalize by budget period so that recommended amounts
            // match the user's actual budget period denomination.
            // - MONTHLY: keep as-is (historical data is monthly)
            // - WEEKLY:  divide by ~4.33 weeks/month
            // - DAILY:   divide by ~30.44 days/month
            // - YEARLY:  multiply by 12
            val periodNormalizer = when (budget.period) {
                com.yourname.expensetracker.data.database.entity.BudgetPeriod.WEEKLY -> 1.0 / com.yourname.expensetracker.domain.logic.RecurrenceCalculator.monthlyMultiplier(
                    com.yourname.expensetracker.domain.model.RecurrenceFrequency.WEEKLY
                )
                com.yourname.expensetracker.data.database.entity.BudgetPeriod.DAILY -> 1.0 / 30.44
                com.yourname.expensetracker.data.database.entity.BudgetPeriod.YEARLY -> 12.0
                com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY -> 1.0
            }
            recommendedBudget *= periodNormalizer
            
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
                    trend = trendDirection,
                    quality = quality,
                    isActionable = true
                )
            )
            
        }

        // BUD-5: Enforce hierarchy — category budget totals must not exceed the
        // overall budget. If they do, proportionally scale down each category
        // recommendation so the sum fits within the overall budget.
        val adjustedRecommendations = if (hasOverallBudget) {
            val overallRec = categoryRecommendations.find { it.categoryId == null }
            val categoryRecs = categoryRecommendations.filter { it.categoryId != null }
            if (overallRec != null && categoryRecs.isNotEmpty()) {
                val categorySum = categoryRecs.sumOf { it.recommendedBudget }
                if (categorySum > overallRec.recommendedBudget && categorySum > 0.0) {
                    val scaleFactor = overallRec.recommendedBudget / categorySum
                    categoryRecommendations.map { rec ->
                        if (rec.categoryId != null) {
                            val scaledBudget = rec.recommendedBudget * scaleFactor
                            rec.copy(recommendedBudget = scaledBudget, delta = scaledBudget - rec.currentBudget,
                                deltaPercentage = if (rec.currentBudget > 0) ((scaledBudget - rec.currentBudget) / rec.currentBudget * 100) else 0.0,
                                reason = rec.reason + " (scaled to fit overall budget)")
                        } else rec
                    }
                } else categoryRecommendations
            } else categoryRecommendations
        } else {
            categoryRecommendations
        }

        val categoryRecommendationsFinal = adjustedRecommendations

        val summaryRecommendations = categoryRecommendationsFinal.filter { recommendation ->
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
            categoryRecommendations = categoryRecommendationsFinal,
            totalCurrentBudget = totalCurrentBudget,
            totalRecommendedBudget = totalRecommendedBudget,
            overallDelta = totalRecommendedBudget - totalCurrentBudget,
            confidence = overallConfidence.coerceIn(0.0, 1.0),
            generatedAt = now
        )
    }
    
    /**
     * Get historical spending data for a specific budget's scope as a list of
     * chronologically ordered monthly totals.
     *
     * RP-08 (P6-002/P6-003): reads from the pre-indexed
     * [MultiCurrencyRepository.getHistoricalCategoryMonthlySpend] result —
     * `SpendScope.Overall` for an overall budget, `SpendScope.Category(id)`
     * (including `Category(null)` for uncategorized) for category budgets.
     * No reflection, no deprecated DAO calls, no raw mixed-currency sums.
     *
     * Gap months between the first and last observed months are synthesized as
     * explicit zero-spend buckets before trend/volatility math runs.
     * Incomplete leading/trailing edge months are excluded (P6-004).
     */
    private fun getHistoricalSpendForBudget(
        budget: com.yourname.expensetracker.data.database.entity.Budget,
        threeMonthsAgo: Long,
        now: Long,
        scopedHistory: Map<SpendScope, Map<String, com.yourname.expensetracker.domain.core.money.MoneyAggregate>>
    ): HistoricalSpendSeries {
        val scope = if (budget.categoryId != null) {
            SpendScope.Category(budget.categoryId)
        } else {
            SpendScope.Overall
        }
        val byMonth = scopedHistory[scope] ?: emptyMap()

        val monthlyTotals = byMonth.map { (monthKey, aggregate) ->
            MonthlySpendingTotal(monthKey = monthKey, total = aggregate.displayAmount, txCount = 0)
        }

        val series = BudgetHistorySeriesBuilder.build(
            monthlyTotals = monthlyTotals,
            windowStartInclusive = threeMonthsAgo,
            windowEndExclusive = now,
            excludeIncompleteEdgeMonths = true
        )

        return HistoricalSpendSeries(
            values = series.values,
            observedMonthCount = series.observedMonthCount,
            completeMonthCount = series.completeMonthCount,
            isPartial = byMonth.values.any { it.isPartial }
        )
    }

    /**
     * RP-08 (P6-002): index the typed history rows once by
     * `(scope, monthKey)` so each budget resolves its series without a
     * repository round-trip and without any scope ambiguity
     * (`SpendScope.Overall` is never conflated with `Category(null)`).
     */
    private fun indexScopedHistory(
        rows: List<CategoryMonthlySpend>
    ): Map<SpendScope, Map<String, com.yourname.expensetracker.domain.core.money.MoneyAggregate>> {
        val byScope = linkedMapOf<SpendScope, LinkedHashMap<String, com.yourname.expensetracker.domain.core.money.MoneyAggregate>>()
        for (row in rows) {
            byScope.getOrPut(row.scope) { linkedMapOf() }[row.monthKey] = row.aggregate
        }
        return byScope
    }

    /**
     * Calculate spending trend as percentage change per month.
     */
    private fun calculateTrend(historicalSpend: List<Double>): Double {
        return BudgetHistorySeriesBuilder.calculateNormalizedTrendRate(historicalSpend)
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

    private data class HistoricalSpendSeries(
        val values: List<Double>,
        val observedMonthCount: Int,
        val completeMonthCount: Int,
        val isPartial: Boolean
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
 * RP-08 (P6-004 low-history contract): recommendation data quality.
 *
 * - [COMPLETE]: complete, fully converted history — normal Apply allowed.
 * - [PARTIAL_DATA]: complete months but some conversions excluded — bounded
 *   recommendation retained; UI must show the existing data-quality warning
 *   before any apply action (ViewModel gating is slice C3).
 * - [LOW_HISTORY]: fewer than the required complete months — recommendation
 *   equals the current budget and is not actionable.
 *
 * Quality is in-memory only; it is never persisted.
 */
enum class BudgetRecommendationQuality {
    COMPLETE,
    PARTIAL_DATA,
    LOW_HISTORY
}

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
    val trend: BudgetTrend,
    /** RP-08 (P6-004): recommendation data quality (in-memory only, never persisted). */
    val quality: BudgetRecommendationQuality = BudgetRecommendationQuality.COMPLETE,
    /**
     * RP-08 (P6-004): `false` for [BudgetRecommendationQuality.LOW_HISTORY] —
     * the recommendation equals the current budget and must not be applied.
     * Apply/Apply-All gating in BudgetViewModel is slice C3.
     */
    val isActionable: Boolean = true
)
