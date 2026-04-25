package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import java.util.Random

/**
 * Financial Stress Forecast Engine (F8)
 * 
 * Predicts cash crunch based on recurring obligations + forecast at 30/60/90 day horizons.
 * Uses Monte Carlo simulation to compute probability of negative balance (P(balance < 0)).
 *
 * Confidence semantics note:
 * - Stress output is probability-first (risk tiers from P(crunch)).
 * - Monte Carlo dashboard output exposes data-quality confidence separately
 *   (HIGH/MODERATE/LOW via [SimulationConfidence]).
 * - This engine intentionally keeps risk-tier semantics isolated; UI may adapt
 *   probability tiers and simulation confidence side-by-side.
 */
@Singleton
class FinancialStressForecastEngine @Inject constructor(
    private val synthesisEngine: SynthesisEngine,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "FinancialStressForecast"
        private const val NUM_SIMULATIONS = 1000
        private const val DAYS_30 = 30
        private const val DAYS_60 = 60
        private const val DAYS_90 = 90
        private const val DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR
        private const val SEED = 42L
    }

    /**
     * Compute financial stress forecast for multiple horizons (30/60/90 days).
     * 
     * @return StressForecastResult containing forecasts for all horizons
     */
    suspend fun computeStressForecast(): StressForecastResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val now = timeProvider.now()

            // No canonical account-balance source exists in this pipeline.
            // Use a neutral starting point instead of presenting month-to-date
            // net cashflow as if it were the user's real cash balance.
            val currentBalance = resolveStartingBalanceBaseline()
            val patterns = recurringPatternsProvider.getConfirmedPatterns()

            // Calculate horizons
            val horizon30 = calculateHorizon(
                daysAhead = DAYS_30,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now
            )
            
            val horizon60 = calculateHorizon(
                daysAhead = DAYS_60,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now
            )
            
            val horizon90 = calculateHorizon(
                daysAhead = DAYS_90,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now
            )
            
            val horizons = listOf(horizon30, horizon60, horizon90)
            
            // Determine overall risk level (worst of all horizons)
            val overallRiskLevel = determineOverallRiskLevel(horizons)
            
            // Find earliest crunch date
            val earliestCrunchDate = findEarliestCrunchDate(horizons, now)
            
            // Generate recommendations
            val recommendations = generateRecommendations(horizons, patterns)
            
            val duration = System.currentTimeMillis() - startTime
            Timber.d("$TAG: Stress forecast computed in ${duration}ms - Risk: $overallRiskLevel")
            
            StressForecastResult(
                horizons = horizons,
                overallRiskLevel = overallRiskLevel,
                earliestCrunchDate = earliestCrunchDate,
                recommendations = recommendations
            )
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to compute stress forecast")
            // Return degraded fallback with non-LOW risk
            StressForecastResult(
                horizons = createDefaultHorizons(
                    fallbackRiskLevel = StressRiskLevel.MODERATE,
                    fallbackCrunchProbability = 0.20
                ),
                overallRiskLevel = StressRiskLevel.MODERATE,
                earliestCrunchDate = null,
                recommendations = listOf(
                    "Stress forecast is temporarily unavailable due to a calculation issue.",
                    "Showing a degraded estimate. Please retry shortly and verify your recent transactions."
                )
            )
        }
    }

    /**
     * Calculate stress forecast for a specific horizon.
     */
    private suspend fun calculateHorizon(
        daysAhead: Int,
        currentBalance: Double,
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        now: Long
    ): StressHorizon {
        val horizonStart = TimePeriodUtils.getStartOfDay(now)
        val horizonEnd = now + (daysAhead * TimePeriodUtils.DAY_IN_MILLIS)
        
        // 1. Calculate recurring obligations within this horizon
        val recurringOutflows = calculateRecurringOutflows(patterns, horizonStart, horizonEnd)
        
        // 2. Estimate expected income
        val expectedIncome = estimateIncome(daysAhead)
        
        // 3. Run Monte Carlo for discretionary spending
        val mcResult = runMonteCarloSimulation(daysAhead, patterns)
        
        // 4. Calculate projected balance
        val projectedBalance = currentBalance + expectedIncome - recurringOutflows - mcResult.percentile50
        val minProjectedBalance = currentBalance + expectedIncome - recurringOutflows - mcResult.percentile90
        
        // 5. Calculate probability of crunch (balance < 0)
        val crunchProbability = calculateCrunchProbability(
            currentBalance = currentBalance,
            expectedIncome = expectedIncome,
            recurringOutflows = recurringOutflows,
            simulatedTotals = mcResult.simulatedTotals
        )
        
        // 6. Classify risk tier
        val riskLevel = classifyRiskLevel(crunchProbability)
        
        // 7. Calculate discretionary buffer
        val discretionaryBuffer = (projectedBalance - DEFAULT_EMERGENCY_BUFFER).coerceAtLeast(0.0)
        
        return StressHorizon(
            daysAhead = daysAhead,
            projectedBalance = projectedBalance,
            minProjectedBalance = minProjectedBalance,
            probabilityOfCrunch = crunchProbability,
            riskLevel = riskLevel,
            recurringObligations = recurringOutflows,
            expectedIncome = expectedIncome,
            discretionaryBuffer = discretionaryBuffer
        )
    }

    /**
     * Calculate recurring outflows for a time period.
     */
    private fun calculateRecurringOutflows(
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        startDate: Long,
        endDate: Long
    ): Double {
        var totalOutflows = 0.0
        val calendar = Calendar.getInstance()
        
        for (pattern in patterns) {
            // Only include high-confidence patterns
            if (pattern.confidence < 0.50f) continue
            
            var nextDate = pattern.nextExpectedDate
            
            // Skip if next expected date is beyond our horizon
            if (nextDate > endDate) continue
            
            // Count occurrences within the horizon
            while (nextDate in startDate..endDate) {
                totalOutflows += pattern.averageAmount
                
                // Calculate next occurrence
                nextDate = when (pattern.frequency) {
                    RecurrenceFrequency.WEEKLY -> nextDate + (7 * TimePeriodUtils.DAY_IN_MILLIS)
                    RecurrenceFrequency.BIWEEKLY -> nextDate + (14 * TimePeriodUtils.DAY_IN_MILLIS)
                    RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(nextDate, 1)
                    RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(nextDate, 3)
                    RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(nextDate, 6)
                    RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(nextDate, 1)
                    else -> break // Unknown frequency, stop counting
                }
            }
        }
        
        return totalOutflows
    }

    /**
     * Estimate expected income for the horizon based on historical deposits.
     */
    private suspend fun estimateIncome(daysAhead: Int): Double {
        // Look back 90 days to estimate monthly income
        val now = timeProvider.now()
        val ninetyDaysAgo = now - (90 * TimePeriodUtils.DAY_IN_MILLIS)
        
        val deposits = expenseRepository.getDepositsBetween(ninetyDaysAgo, now)
        val totalDeposits = deposits.sumOf { it.effectiveAmount }
        
        // Average monthly income based on 90-day window
        val avgMonthlyIncome = totalDeposits / 3.0 // 3 months
        
        // Scale to the horizon
        val monthsInHorizon = daysAhead / 30.0
        return (avgMonthlyIncome * monthsInHorizon).coerceAtLeast(0.0)
    }

    /**
     * Run Monte Carlo simulation for discretionary spending.
     */
    private suspend fun runMonteCarloSimulation(
        daysAhead: Int,
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>
    ): MonteCarloHorizonResult {
        // Get historical spending for the last 60 days
        val now = timeProvider.now()
        val sixtyDaysAgo = now - (60 * TimePeriodUtils.DAY_IN_MILLIS)
        
        val expenses = expenseRepository.getExpensesBetween(sixtyDaysAgo, now)
        val purchases = expenses.filter { 
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE && !it.isNotMine 
        }

        val recurringMerchantKeys = patterns
            .filter { it.confidence >= 0.50f }
            .map { MerchantKeyGenerator.generate(it.merchantName) }
            .filter { it.isNotBlank() }
            .toSet()

        val discretionaryPurchases = if (recurringMerchantKeys.isEmpty()) {
            purchases
        } else {
            purchases.filterNot { purchase ->
                val purchaseMerchantKey = purchase.merchantKey ?: MerchantKeyGenerator.generate(purchase.merchant)
                purchaseMerchantKey in recurringMerchantKeys
            }
        }
        
        // If there is no recent purchase history at all, return conservative estimates.
        if (purchases.isEmpty()) {
            val random = Random(SEED)
            val fallbackTotals = DoubleArray(NUM_SIMULATIONS) {
                (daysAhead * 20.0 + random.nextGaussian() * 5.0).coerceAtLeast(0.0)
            }
            fallbackTotals.sort()

            return MonteCarloHorizonResult(
                percentile10 = percentile(fallbackTotals, 0.10),
                percentile25 = percentile(fallbackTotals, 0.25),
                percentile50 = percentile(fallbackTotals, 0.50),
                percentile75 = percentile(fallbackTotals, 0.75),
                percentile90 = percentile(fallbackTotals, 0.90),
                simulatedTotals = fallbackTotals.toList()
            )
        }

        // Build empirical distribution of daily discretionary totals, including
        // zero-spend days so sparse history does not overstate routine spending.
        val discretionaryTotalsByDay = discretionaryPurchases
            .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .mapValues { (_, txns) -> txns.sumOf { it.effectiveAmount } }

        val sampleStartDay = TimePeriodUtils.getStartOfDay(sixtyDaysAgo)
        val sampleEndDay = TimePeriodUtils.getStartOfDay(now)
        val dailyTotals = mutableListOf<Double>()
        var cursorDay = sampleStartDay

        while (cursorDay <= sampleEndDay) {
            dailyTotals += discretionaryTotalsByDay[cursorDay] ?: 0.0
            cursorDay = TimePeriodUtils.addDays(cursorDay, 1)
        }

        // Run simulations
        val random = Random(SEED)
        val simulatedTotals = DoubleArray(NUM_SIMULATIONS)

        for (i in 0 until NUM_SIMULATIONS) {
            var total = 0.0
            for (day in 0 until daysAhead) {
                // Bootstrap from empirical daily totals
                val sampledDaily = if (dailyTotals.isNotEmpty()) {
                    dailyTotals[random.nextInt(dailyTotals.size)]
                } else {
                    0.0
                }
                total += sampledDaily
            }
            simulatedTotals[i] = total
        }
        
        simulatedTotals.sort()
        
        return MonteCarloHorizonResult(
            percentile10 = percentile(simulatedTotals, 0.10),
            percentile25 = percentile(simulatedTotals, 0.25),
            percentile50 = percentile(simulatedTotals, 0.50),
            percentile75 = percentile(simulatedTotals, 0.75),
            percentile90 = percentile(simulatedTotals, 0.90),
            simulatedTotals = simulatedTotals.toList()
        )
    }

    /**
     * Calculate probability of cash crunch (balance < 0).
     */
    private fun calculateCrunchProbability(
        currentBalance: Double,
        expectedIncome: Double,
        recurringOutflows: Double,
        simulatedTotals: List<Double>
    ): Double {
        val availableForDiscretionary = currentBalance + expectedIncome - recurringOutflows
        
        val crunchCount = simulatedTotals.count { discretionarySpending ->
            (availableForDiscretionary - discretionarySpending) < 0
        }
        
        return crunchCount.toDouble() / simulatedTotals.size.toDouble()
    }

    /**
     * Classify risk level based on probability of crunch.
     */
    private fun classifyRiskLevel(probabilityOfCrunch: Double): StressRiskLevel {
        return when {
            probabilityOfCrunch < 0.10 -> StressRiskLevel.LOW
            probabilityOfCrunch < 0.25 -> StressRiskLevel.MODERATE
            probabilityOfCrunch < 0.50 -> StressRiskLevel.ELEVATED
            probabilityOfCrunch < 0.75 -> StressRiskLevel.HIGH
            else -> StressRiskLevel.CRITICAL
        }
    }

    /**
     * Determine overall risk level based on all horizons.
     */
    private fun determineOverallRiskLevel(horizons: List<StressHorizon>): StressRiskLevel {
        return horizons.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: StressRiskLevel.LOW
    }

    /**
     * Find the earliest date when crunch might occur.
     */
    private fun findEarliestCrunchDate(horizons: List<StressHorizon>, now: Long): Long? {
        val firstHighRisk = horizons.firstOrNull { it.riskLevel >= StressRiskLevel.HIGH }
            ?: horizons.firstOrNull { it.riskLevel >= StressRiskLevel.ELEVATED }
            ?: horizons.firstOrNull { it.riskLevel >= StressRiskLevel.MODERATE }
        
        return firstHighRisk?.let {
            now + (it.daysAhead * TimePeriodUtils.DAY_IN_MILLIS)
        }
    }

    /**
     * Generate personalized recommendations based on forecast.
     */
    private fun generateRecommendations(
        horizons: List<StressHorizon>,
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        val anyRisk = horizons.any { it.riskLevel >= StressRiskLevel.MODERATE }
        val highRisk = horizons.any { it.riskLevel >= StressRiskLevel.HIGH }
        
        if (anyRisk) {
            // Check recurring obligations vs income
            val avgRecurring = horizons.first().recurringObligations / 30.0 * 30.0 // Monthly rate
            val avgIncome = horizons.first().expectedIncome / horizons.first().daysAhead * 30.0
            
            if (avgRecurring > avgIncome * 0.8) {
                recommendations.add("Your recurring obligations are high. Consider canceling unused subscriptions.")
            }
            
            // Check for projected negative balance
            val worstHorizon = horizons.minByOrNull { it.projectedBalance }
            if (worstHorizon != null && worstHorizon.projectedBalance < 0) {
                val daysUntil = worstHorizon.daysAhead
                recommendations.add("You may run out of money in $daysUntil days. Consider reducing discretionary spending.")
            }
            
            // Check emergency buffer
            val minBuffer = horizons.minOf { it.discretionaryBuffer }
            if (minBuffer < DEFAULT_EMERGENCY_BUFFER) {
                recommendations.add("Your emergency buffer is low. Aim for at least €${DEFAULT_EMERGENCY_BUFFER.toInt()}.")
            }
        }
        
        if (highRisk) {
            recommendations.add("Critical: Consider delaying non-essential purchases until your cash flow improves.")
        }
        
        // Add positive reinforcement when healthy
        if (!anyRisk && horizons.all { it.projectedBalance > DEFAULT_EMERGENCY_BUFFER }) {
            recommendations.add("Great job! Your financial stress level is low. Keep up the good habits.")
        }
        
        return recommendations.ifEmpty { listOf("No immediate concerns. Continue monitoring your spending.") }
    }

    /**
     * Forecasting has no canonical account-balance source in this pipeline.
     * Use a neutral baseline instead of fabricating a balance from cashflow.
     */
    private fun resolveStartingBalanceBaseline(): Double {
        return 0.0
    }

    /**
     * Create default horizons for error case.
     */
    private fun createDefaultHorizons(
        fallbackRiskLevel: StressRiskLevel = StressRiskLevel.LOW,
        fallbackCrunchProbability: Double = 0.0
    ): List<StressHorizon> {
        return listOf(DAYS_30, DAYS_60, DAYS_90).map { days ->
            StressHorizon(
                daysAhead = days,
                projectedBalance = 0.0,
                minProjectedBalance = 0.0,
                probabilityOfCrunch = fallbackCrunchProbability,
                riskLevel = fallbackRiskLevel,
                recurringObligations = 0.0,
                expectedIncome = 0.0,
                discretionaryBuffer = 0.0
            )
        }
    }

    /**
     * Calculate standard deviation.
     */
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return kotlin.math.sqrt(variance)
    }

    /**
     * Extract percentile from sorted array.
     */
    private fun percentile(sortedValues: DoubleArray, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = p * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
        val fraction = index - lower
        return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
    }
}

/**
 * Result of financial stress forecast computation.
 */
data class StressForecastResult(
    val horizons: List<StressHorizon>,
    val overallRiskLevel: StressRiskLevel,
    val earliestCrunchDate: Long?,
    val recommendations: List<String>
)

/**
 * Stress forecast for a specific time horizon.
 */
data class StressHorizon(
    val daysAhead: Int, // 30, 60, or 90
    val projectedBalance: Double,
    val minProjectedBalance: Double,
    val probabilityOfCrunch: Double, // P(balance < 0)
    val riskLevel: StressRiskLevel,
    val recurringObligations: Double,
    val expectedIncome: Double,
    val discretionaryBuffer: Double
)

/**
 * Risk level classification for financial stress.
 */
enum class StressRiskLevel {
    LOW,        // P(crunch) < 10%
    MODERATE,   // P(crunch) 10-25%
    ELEVATED,   // P(crunch) 25-50%
    HIGH,       // P(crunch) 50-75%
    CRITICAL    // P(crunch) > 75%
}

/**
 * Internal result of Monte Carlo simulation for a horizon.
 */
private data class MonteCarloHorizonResult(
    val percentile10: Double,
    val percentile25: Double,
    val percentile50: Double,
    val percentile75: Double,
    val percentile90: Double,
    val simulatedTotals: List<Double>
)
