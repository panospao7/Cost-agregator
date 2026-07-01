package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.entity.HealthScoreHistory
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Financial Health Score Calculator Version 2.0
 * 
 * Creates a single 0-100 KPI combining:
 * - Savings Rate Score (30%): How much income is being saved
 * - Runway Score (25%): Financial buffer in months
 * - Budget Adherence Score (25%): How well user stays within budgets
 * - Bill Reliability Score (20%): On-time payment rate for recurring bills
 * 
 * Formula:
 * score = 0.30*savingsRateScore + 0.25*runwayScore + 0.25*budgetAdherenceScore + 0.20*billReliabilityScore
 */
@Singleton
class FinancialHealthScoreV2 @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val expenseRepository: ExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val healthScoreHistoryDao: HealthScoreHistoryDao,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    /** @suppress Injected for AIML-25: upcoming-bill-aware runway calculation. */
    private val cashFlowCalculator: CashFlowCalculator,
    private val writeBarrier: com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
) {
    companion object {
        // Component weights (must sum to 1.0)
        private const val SAVINGS_RATE_WEIGHT = 0.30
        private const val RUNWAY_WEIGHT = 0.25
        private const val BUDGET_ADHERENCE_WEIGHT = 0.25
        private const val BILL_RELIABILITY_WEIGHT = 0.20
        
        // Savings rate thresholds
        private const val SAVINGS_RATE_TARGET = 0.20 // 20% is target for 100 points
        private const val SAVINGS_RATE_MIN = 0.0
        
        // Runway thresholds (months)
        private const val RUNWAY_TARGET_MONTHS = 6.0 // 6+ months = 100 points
        private const val RUNWAY_MIN_MONTHS = 0.0
        
        // Trend calculation threshold
        private const val TREND_THRESHOLD_POINTS = 5 // Score change of 5+ indicates trend

        // Runway stabilization policy
        private const val RUNWAY_BASELINE_LOOKBACK_DAYS = 90
        private const val RUNWAY_MIN_COVERAGE_WITHOUT_BASELINE = 0.15
    }

    /**
     * Calculate comprehensive financial health score (0-100).
     *
     * NOTE: This method has a side-effect — it persists the calculated result to
     * `healthScoreHistoryDao` via [saveToHistory]. Callers that need a pure
     * read without persistence should use the component methods directly
     * (calculateSavingsRateScore, calculateRunwayScore, etc.) and avoid
     * this convenience method.
     *
     * @param periodStart Start of the evaluation period (inclusive)
     * @param periodEnd End of the evaluation period (inclusive)
     * @return FinancialHealthResult containing the score and all component breakdowns
     */
    suspend fun calculateHealthScore(
        periodStart: Long = TimePeriodUtils.getStartOfMonth(timeProvider.now()),
        periodEnd: Long = TimePeriodUtils.getEndOfMonth(timeProvider.now())
    ): FinancialHealthResult {
        val startTime = System.currentTimeMillis()
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
        
        return try {
            // Fetch all necessary data
            val expenses = expenseRepository.getExpensesBetween(periodStart, periodEnd)
            val normalized = runCatching {
                analyticsCurrencyNormalizer.normalizeExpenses(expenses, homeCurrency)
            }.getOrNull()
            val normalizedExpenses = normalized?.includedExpenses
                ?: expenses.map { it.toExpenseSnapshot() }

            val purchases = normalizedExpenses.filter {
                it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine
            }
            val deposits = normalizedExpenses.filter {
                it.transactionType == DomainTransactionType.DEPOSIT
            }

            // Get budget statuses
            val budgetStatuses = budgetRepository.getBudgetStatusesAt(resolveBudgetEvaluationTime(periodStart, periodEnd))
            val savingsGoals = savingsGoalRepository.getSavingsGoals()
            
            // Calculate individual component scores
            val savingsRateScore = calculateSavingsRateScore(deposits, purchases)
            val runwayScore = calculateRunwayScore(
                purchases = purchases,
                savingsGoals = savingsGoals,
                periodStart = periodStart,
                periodEnd = periodEnd
            )
            val budgetAdherenceScore = calculateBudgetAdherenceScore(budgetStatuses)
            val billReliabilityScore = calculateBillReliabilityScore(expenses, periodStart, periodEnd)

            // Compute conversion confidence from normalization warnings
            val conversionConfidence = computeConversionConfidence(normalized)
            
            // Calculate weighted overall score
            val overallScore = (
                savingsRateScore * SAVINGS_RATE_WEIGHT +
                runwayScore * RUNWAY_WEIGHT +
                budgetAdherenceScore * BUDGET_ADHERENCE_WEIGHT +
                billReliabilityScore * BILL_RELIABILITY_WEIGHT
            ).toInt().coerceIn(0, 100)
            
            // Determine trend by comparing to previous score
            val trend = determineTrend(overallScore, periodStart, periodEnd)
            
            // Generate recommendation based on scores
            val recommendation = generateRecommendation(
                overallScore = overallScore,
                savingsRateScore = savingsRateScore,
                runwayScore = runwayScore,
                budgetAdherenceScore = budgetAdherenceScore,
                billReliabilityScore = billReliabilityScore
            )
            
            // Build factor contributions
            val factorContributions = listOf(
                HealthFactorContribution(
                    name = "Savings Rate",
                    score = savingsRateScore,
                    weight = SAVINGS_RATE_WEIGHT,
                    explanation = if (savingsRateScore >= 80) "Excellent savings rate!" 
                        else if (savingsRateScore >= 50) "Good progress on savings"
                        else "Consider increasing your savings rate"
                ),
                HealthFactorContribution(
                    name = "Financial Runway",
                    score = runwayScore,
                    weight = RUNWAY_WEIGHT,
                    explanation = if (runwayScore >= 80) "Strong financial buffer"
                        else if (runwayScore >= 50) "Moderate financial cushion"
                        else "Build up your emergency fund"
                ),
                HealthFactorContribution(
                    name = "Budget Adherence",
                    score = budgetAdherenceScore,
                    weight = BUDGET_ADHERENCE_WEIGHT,
                    explanation = if (budgetAdherenceScore >= 80) "Great budget discipline!"
                        else if (budgetAdherenceScore >= 50) "Budget mostly on track"
                        else "Review and adjust your budgets"
                ),
                HealthFactorContribution(
                    name = "Bill Reliability",
                    score = billReliabilityScore,
                    weight = BILL_RELIABILITY_WEIGHT,
                    explanation = if (billReliabilityScore >= 80) "Excellent payment history"
                        else if (billReliabilityScore >= 50) "Most bills paid on time"
                        else "Set up reminders for bill payments"
                )
            )
            
            // Save to history for trend tracking
            saveToHistory(
                overallScore = overallScore,
                savingsRateScore = savingsRateScore,
                runwayScore = runwayScore,
                budgetAdherenceScore = budgetAdherenceScore,
                billReliabilityScore = billReliabilityScore,
                periodStart = periodStart,
                periodEnd = periodEnd,
                trend = trend,
                recommendation = recommendation
            )
            
            val duration = System.currentTimeMillis() - startTime
            Timber.d("FinancialHealthScoreV2 calculated in ${duration}ms: overall=$overallScore, savings=$savingsRateScore, runway=$runwayScore, budget=$budgetAdherenceScore, bills=$billReliabilityScore")
            
            FinancialHealthResult(
                overallScore = overallScore,
                savingsRateScore = savingsRateScore,
                runwayScore = runwayScore,
                budgetAdherenceScore = budgetAdherenceScore,
                billReliabilityScore = billReliabilityScore,
                factorContributions = factorContributions,
                trend = trend,
                recommendation = recommendation,
                displayCurrency = homeCurrency,
                conversionConfidence = conversionConfidence
            )
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate financial health score")
            // Return a default result with warning status
            FinancialHealthResult(
                overallScore = 50,
                savingsRateScore = 50,
                runwayScore = 50,
                budgetAdherenceScore = 50,
                billReliabilityScore = 50,
                factorContributions = emptyList(),
                trend = HealthTrend.STABLE,
                recommendation = "Unable to calculate full health score. Please check your data.",
                displayCurrency = homeCurrency,
                conversionConfidence = 0.5f
            )
        }
    }
    
    /**
     * Calculate Savings Rate Score (30% weight)
     * 
     * Savings rate = (income - expenses) / income
     * Score: 0% = 0 points, 20%+ = 100 points
     */
    private fun calculateSavingsRateScore(
        deposits: List<ExpenseSnapshot>,
        purchases: List<ExpenseSnapshot>
    ): Int {
        // SAFE: data normalized at lines 89-91 via AnalyticsCurrencyNormalizer
        val totalIncome = deposits.sumOf { it.effectiveAmount }
        // SAFE: data normalized at lines 89-91 via AnalyticsCurrencyNormalizer
        val totalExpenses = purchases.sumOf { it.effectiveAmount }
        
        // If no income data, return neutral score
        if (totalIncome <= 0) {
            return 50 // Neutral score when income data is unavailable
        }
        
        // Calculate savings rate
        val savingsAmount = (totalIncome - totalExpenses).coerceAtLeast(0.0)
        val savingsRate = savingsAmount / totalIncome
        
        // Score calculation: linear scale from 0 to 20% savings rate
        return ((savingsRate / SAVINGS_RATE_TARGET) * 100)
            .toInt()
            .coerceIn(0, 100)
    }
    
    /**
     * Calculate Runway Score (25% weight)
     * 
     * Runway = currentBalance / monthlyExpenses
     * Score: <1 month = 0 points, 6+ months = 100 points
     */
    private suspend fun calculateRunwayScore(
        purchases: List<ExpenseSnapshot>,
        savingsGoals: List<SavingsGoal>,
        periodStart: Long,
        periodEnd: Long
    ): Int {
        val now = timeProvider.now()
        val effectivePeriodEnd = minOf(now, periodEnd)
        if (effectivePeriodEnd <= periodStart) {
            return 50
        }

        val periodDays = TimePeriodUtils.daysBetween(periodStart, periodEnd).coerceAtLeast(1)
        val elapsedDays = if (effectivePeriodEnd >= periodEnd) {
            periodDays
        } else {
            (TimePeriodUtils.daysBetween(periodStart, effectivePeriodEnd) + 1).coerceAtLeast(1)
        }
        val coverage = (elapsedDays.toDouble() / periodDays.toDouble()).coerceIn(0.0, 1.0)

        // SAFE: purchases already normalized at lines 89-91 via AnalyticsCurrencyNormalizer
        val observedSpend = purchases
            .filter { it.date < effectivePeriodEnd }
            .sumOf { it.effectiveAmount }
        val daysInReferenceMonth = TimePeriodUtils.getDaysInMonth(periodStart)
        val projectedMonthlyBurn = if (observedSpend > 0.0 && elapsedDays > 0) {
            observedSpend / elapsedDays.toDouble() * daysInReferenceMonth.toDouble()
        } else {
            0.0
        }

        val historicalMonthlyBaseline = calculateHistoricalMonthlyBaseline(periodStart)

        if (historicalMonthlyBaseline == null && coverage < RUNWAY_MIN_COVERAGE_WITHOUT_BASELINE) {
            // Too little data to infer a stable burn rate without historical baseline.
            return 50
        }

        // Blend toward current projection as period coverage grows.
        val monthlyExpenses = when {
            historicalMonthlyBaseline != null && projectedMonthlyBurn > 0.0 -> {
                (coverage * projectedMonthlyBurn) + ((1.0 - coverage) * historicalMonthlyBaseline)
            }
            historicalMonthlyBaseline != null -> historicalMonthlyBaseline
            else -> projectedMonthlyBurn
        }

        // Use accumulated savings, not unspent monthly budget, for runway calculation.
        // TODO: Convert goal.currentAmount to comparable currency before summing across goals
        // SAFE: savings goals are user-defined in home currency (amounts are logically already in home currency)
        val totalSavings = savingsGoals.sumOf { it.currentAmount }

        // AIML-25: Subtract upcoming known bills from savings so the runway reflects
        // the net buffer available after paying committed/likely obligations.
        val daysRemainingInPeriod = TimePeriodUtils.daysBetween(
            timeProvider.now().coerceIn(periodStart, periodEnd),
            periodEnd
        ).coerceAtLeast(0)
        val upcomingBills = if (daysRemainingInPeriod > 0) {
            try {
                cashFlowCalculator.getUpcomingBills(daysAhead = daysRemainingInPeriod)
                    .sumOf { it.averageAmount }
            } catch (e: Exception) {
                Timber.w(e, "Failed to compute upcoming bills for runway, using gross savings")
                0.0
            }
        } else 0.0
        val netSavings = (totalSavings - upcomingBills).coerceAtLeast(0.0)

        // If no expenses, return neutral (insufficient spending baseline)
        if (monthlyExpenses <= 0) {
            return 50
        }

        Timber.d(
            "Runway stabilization: observed=%.2f, projectedMonthly=%.2f, historicalBaseline=%.2f, coverage=%.3f, effectiveMonthly=%.2f",
            observedSpend,
            projectedMonthlyBurn,
            historicalMonthlyBaseline ?: 0.0,
            coverage,
            monthlyExpenses
        )

        // Calculate runway in months (using netSavings which subtracts upcoming bills)
        val runwayMonths = if (monthlyExpenses > 0) {
            netSavings / monthlyExpenses
        } else {
            RUNWAY_TARGET_MONTHS // If no expenses, assume perfect runway
        }
        
        // Score calculation: linear scale from 0 to 6 months
        return ((runwayMonths / RUNWAY_TARGET_MONTHS) * 100)
            .toInt()
            .coerceIn(0, 100)
    }

    private suspend fun calculateHistoricalMonthlyBaseline(currentPeriodStart: Long): Double? {
        val baselineStart = TimePeriodUtils.getStartOfMonth(
            TimePeriodUtils.addMonths(currentPeriodStart, -3)
        )
        val historicalExpenses = expenseRepository
            .getExpensesBetween(baselineStart, currentPeriodStart)
            .filter {
                it.transactionType.toDomain() == DomainTransactionType.PURCHASE && !it.isNotMine && it.date < currentPeriodStart
            }

        if (historicalExpenses.isEmpty()) {
            return null
        }

        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
        val normalized = runCatching {
            analyticsCurrencyNormalizer.normalizeExpenses(historicalExpenses, homeCurrency)
        }.getOrNull()
        val normalizedAmountById = normalized?.includedExpenses?.associateBy { it.id }
            ?: emptyMap()

        // SAFE: normalized via AnalyticsCurrencyNormalizer before summing
        val monthlyTotals = historicalExpenses
            .groupBy {
                "${TimePeriodUtils.getYear(it.date)}-${TimePeriodUtils.getMonth(it.date)}"
            }
            .values
            .map { monthRows -> monthRows.sumOf {
                normalizedAmountById[it.id]?.effectiveAmount ?: it.effectiveAmount
            } }
            .filter { it > 0.0 }

        return monthlyTotals.takeIf { it.isNotEmpty() }?.average()
    }

    private fun resolveBudgetEvaluationTime(periodStart: Long, periodEnd: Long): Long {
        val now = timeProvider.now()
        val latestInPeriod = if (periodEnd > periodStart) periodEnd - 1 else periodStart
        return now.coerceIn(periodStart, latestInPeriod)
    }
    
    /**
     * Calculate Budget Adherence Score (25% weight)
     * 
     * Adherence = 1 - (overspendAmount / budgetAmount)
     * Score: 0% adherence = 0 points, 100%+ = 100 points
     */
    private fun calculateBudgetAdherenceScore(
        budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>
    ): Int {
        if (budgetStatuses.isEmpty()) {
            return 50 // Neutral when no budgets set
        }
        
        var totalBudget = 0.0
        var totalOverspend = 0.0
        
        for (status in budgetStatuses) {
            totalBudget += status.effectiveLimit
            if (status.spentAmount > status.effectiveLimit) {
                totalOverspend += (status.spentAmount - status.effectiveLimit)
            }
        }
        
        if (totalBudget <= 0) {
            return 50
        }
        
        // Calculate adherence: 1.0 = perfect, 0.0 = fully overspent
        val overspendRatio = (totalOverspend / totalBudget).coerceIn(0.0, 1.0)
        val adherence = 1.0 - overspendRatio
        
        return (adherence * 100).toInt().coerceIn(0, 100)
    }
    
    /**
     * Calculate Bill Reliability Score (20% weight)
     * 
     * Uses recurring-pattern cadence only as a weak proxy for timing consistency.
     * It does not treat pattern-detection confidence as payment reliability.
     *
     * ## AIML-26: Bill reliability from actual payment data
     * Callers can supply [occurrenceReliability] computed from actual occurrence
     * history (e.g. [RecurringOccurrenceDao] PAID/MISSED counts). When provided,
     * this value is used directly, bypassing the cadence-based proxy below.
     *
     * Reliability from occurrence history should be computed as:
     * `paidCount / (paidCount + missedCount) * 100`.
     * - Query `recurring_occurrences` where `sourceType = 'RECURRING_RULE'` and
     *   `sourceId = ruleId` for PAID/MISSED status.
     * - Fall back to 75 (default) when no history exists.
     *
     * If [occurrenceReliability] is null (default), the method falls back to
     * interval-regularity heuristics, returning a hardcoded 75 when no recurring
     * patterns are detected.
     *
     * @param occurrenceReliability Optional externally-computed reliability score
     *                              (0-100) from actual PAID/MISSED occurrence data.
     *                              Null means fall back to cadence-based heuristic.
     */
    private suspend fun calculateBillReliabilityScore(
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        periodStart: Long,
        periodEnd: Long,
        occurrenceReliability: Double? = null
    ): Int {
        // AIML-26: Use externally-provided occurrence reliability if available.
        // This bypasses cadence-based heuristics and uses actual PAID/MISSED data.
        if (occurrenceReliability != null) {
            return occurrenceReliability.toInt().coerceIn(0, 100)
        }

        // Get recurring patterns
        val patterns = recurringExpenseEngine.getPatterns(expenses)

        if (patterns.isEmpty()) {
            return 75 // Default good score if no recurring patterns detected
        }

        val relevantPatterns = patterns.filter { pattern ->
            pattern.previousDates.isNotEmpty() &&
                pattern.previousDates.last() < periodEnd &&
                pattern.nextExpectedDate > periodStart
        }

        if (relevantPatterns.isEmpty()) {
            return 75
        }

        var totalWeight = 0.0
        var weightedReliability = 0.0

        for (pattern in relevantPatterns) {
            val cadenceReliability = calculatePatternTimingReliability(pattern)
            val weight = pattern.averageAmount
            weightedReliability += cadenceReliability * weight
            totalWeight += weight
        }

        val overallReliability = if (totalWeight > 0) {
            weightedReliability / totalWeight
        } else {
            0.75 // Default if no weights
        }

        return (overallReliability * 100).toInt().coerceIn(0, 100)
    }
    
    /**
     * Determine the trend direction by comparing to the most recent completed period.
     */
    private suspend fun determineTrend(
        currentScore: Int,
        currentPeriodStart: Long,
        currentPeriodEnd: Long
    ): HealthTrend {
        val previousRecord = healthScoreHistoryDao.getMostRecentBefore(currentPeriodStart, currentPeriodEnd)
        
        return if (previousRecord != null) {
            val difference = currentScore - previousRecord.overallScore
            when {
                difference >= TREND_THRESHOLD_POINTS -> HealthTrend.IMPROVING
                difference <= -TREND_THRESHOLD_POINTS -> HealthTrend.DECLINING
                else -> HealthTrend.STABLE
            }
        } else {
            HealthTrend.STABLE // No previous data
        }
    }

    private fun calculatePatternTimingReliability(
        pattern: com.yourname.expensetracker.domain.model.RecurringPattern
    ): Double {
        val previousDates = pattern.previousDates.sorted()
        if (previousDates.size < 2) {
            return 0.75
        }

        val intervalPairs = buildList {
            for (index in 1 until previousDates.size) {
                add(previousDates[index - 1] to previousDates[index])
            }
        }

        val deviations = intervalPairs.mapNotNull { (currentDate, nextObservedDate) ->
            expectedIntervalDaysFromDate(currentDate, pattern.frequency)?.let { expectedDays ->
                val actualDays = TimePeriodUtils.daysBetween(currentDate, nextObservedDate).toDouble()
                kotlin.math.abs(actualDays - expectedDays)
            }
        }

        if (deviations.isEmpty()) {
            return 0.75
        }

        val averageDeviationDays = deviations.average()

        return when {
            averageDeviationDays <= 1.0 -> 1.0
            averageDeviationDays <= 3.0 -> 0.9
            averageDeviationDays <= 7.0 -> 0.75
            averageDeviationDays <= 14.0 -> 0.6
            else -> 0.45
        }
    }

    private fun expectedIntervalDaysFromDate(
        currentDate: Long,
        frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency
    ): Double? {
        if (frequency.isIrregular) {
            return null
        }

        val expectedNextDate = RecurrenceCalculator.calculateNextDate(currentDate, frequency)
        val expectedDays = TimePeriodUtils.daysBetween(currentDate, expectedNextDate).toDouble()
        return expectedDays.takeIf { it > 0.0 }
    }
    
    /**
     * Generate a personalized recommendation based on the lowest scoring component.
     */
    private fun generateRecommendation(
        overallScore: Int,
        savingsRateScore: Int,
        runwayScore: Int,
        budgetAdherenceScore: Int,
        billReliabilityScore: Int
    ): String? {
        // Find the lowest score component
        val scores = listOf(
            "savings" to savingsRateScore,
            "runway" to runwayScore,
            "budget" to budgetAdherenceScore,
            "bills" to billReliabilityScore
        )
        
        val lowest = scores.minByOrNull { it.second } ?: return null
        
        return when (lowest.first) {
            "savings" -> when {
                savingsRateScore < 30 -> "Try to save at least 5% of your income this month"
                savingsRateScore < 60 -> "Good start! Aim to increase savings to 15%"
                else -> "You're building healthy savings habits"
            }
            "runway" -> when {
                runwayScore < 30 -> "Focus on building a 1-month emergency fund first"
                runwayScore < 60 -> "You're making progress - aim for 3 months of expenses"
                else -> "Keep building your financial cushion"
            }
            "budget" -> when {
                budgetAdherenceScore < 30 -> "Review your budgets and set realistic limits"
                budgetAdherenceScore < 60 -> "Track daily spending to stay on budget"
                else -> "You're mostly staying within budget - keep it up!"
            }
            "bills" -> when {
                billReliabilityScore < 30 -> "Set up automatic payments for your recurring bills"
                billReliabilityScore < 60 -> "Review upcoming bills and set payment reminders"
                else -> "Your bill payment record is looking good"
            }
            else -> null
        }
    }
    
    /**
     * Save the calculated scores to history for trend tracking.
     */
    private suspend fun saveToHistory(
        overallScore: Int,
        savingsRateScore: Int,
        runwayScore: Int,
        budgetAdherenceScore: Int,
        billReliabilityScore: Int,
        periodStart: Long,
        periodEnd: Long,
        trend: HealthTrend,
        recommendation: String?
    ) {
        try {
            writeBarrier.checkWritesAllowed("FinancialHealthScoreV2.saveToHistory")
            val existing = healthScoreHistoryDao.getHistoryForPeriod(periodStart, periodEnd).firstOrNull()

            if (existing != null) {
                healthScoreHistoryDao.update(
                    existing.copy(
                        overallScore = overallScore,
                        savingsRateScore = savingsRateScore,
                        runwayScore = runwayScore,
                        budgetAdherenceScore = budgetAdherenceScore,
                        billReliabilityScore = billReliabilityScore,
                        trend = trend.name,
                        recommendation = recommendation,
                        calculatedAt = timeProvider.now()
                    )
                )
            } else {
                val history = HealthScoreHistory(
                    overallScore = overallScore,
                    savingsRateScore = savingsRateScore,
                    runwayScore = runwayScore,
                    budgetAdherenceScore = budgetAdherenceScore,
                    billReliabilityScore = billReliabilityScore,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    trend = trend.name,
                    recommendation = recommendation
                )
                healthScoreHistoryDao.insert(history)
            }
            
            // Clean up old records (keep last 90 days)
            val ninetyDaysAgo = timeProvider.now() - (90L * 24 * 60 * 60 * 1000)
            healthScoreHistoryDao.deleteOlderThan(ninetyDaysAgo)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to save health score history")
        }
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }

    /**
     * Converts a data-layer [Expense] into a domain [ExpenseSnapshot] preserving
     * the original effective amount and currency. Used as a fallback when
     * cross-currency normalization is unavailable.
     */
    private fun com.yourname.expensetracker.data.database.entity.Expense.toExpenseSnapshot(): ExpenseSnapshot =
        ExpenseSnapshot(
            id = id,
            amount = effectiveAmount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = transactionType.toDomain(),
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            transferDirection = transferDirection?.let { d ->
                when (d) {
                    com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING ->
                        DomainTransferDirection.INCOMING
                    com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING ->
                        DomainTransferDirection.OUTGOING
                }
            },
            notes = notes
        )

    /**
     * Compute conversion confidence based on the share of transactions that failed normalization.
     * - 0% failures → 1.0
     * - < 5% failures → 0.95
     * - < 20% failures → 0.80
     * - >= 20% failures → 0.50
     */
    private fun computeConversionConfidence(normalized: AnalyticsNormalizationResult?): Float {
        if (normalized == null || normalized.totalInputCount == 0) return 0.5f
        val loss = normalized.lossPercentage
        return when {
            loss == 0.0 -> 1.0f
            loss < 5.0 -> 0.95f
            loss < 20.0 -> 0.80f
            else -> 0.50f
        }
    }
}

/**
 * Result of the financial health score calculation.
 */
data class FinancialHealthResult(
    val overallScore: Int, // 0-100
    val savingsRateScore: Int,
    val runwayScore: Int,
    val budgetAdherenceScore: Int,
    val billReliabilityScore: Int,
    val factorContributions: List<HealthFactorContribution>,
    val trend: HealthTrend, // IMPROVING, STABLE, DECLINING
    val recommendation: String?,
    val displayCurrency: String = "",
    val conversionConfidence: Float = 1.0f // confidence in currency conversion (1.0 = all conversions succeeded)
)

/**
 * Individual factor contribution to the overall score.
 */
data class HealthFactorContribution(
    val name: String,
    val score: Int,
    val weight: Double,
    val explanation: String
)

/**
 * Trend direction for the financial health score.
 */
enum class HealthTrend {
    IMPROVING,
    STABLE,
    DECLINING
}
