package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
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
 *
 * ## O4: AI cash flow forecast confidence indicator
 * This engine is deterministic (not AI-based). The [StressHorizon.probabilityOfCrunch]
 * field serves as a de facto confidence indicator — higher probability means more
 * confident prediction of a cash crunch. If an AI-based cash flow forecast is added
 * in the future, it should mirror the [FinancialForecast.confidence] field pattern
 * (0.0–1.0 range) and expose it to the UI for transparency.
 */
@Singleton
class FinancialStressForecastEngine @Inject constructor(
    private val synthesisEngine: SynthesisEngine,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
    private val recurringOccurrenceDao: RecurringOccurrenceDao,
    private val currencyConverter: CurrencyConverter,
    private val accountBalanceProvider: AccountBalanceProvider,
    private val databaseReadBarrier: DatabaseReadBarrier
) {
    companion object {
        private const val TAG = "FinancialStressForecast"
        private const val NUM_SIMULATIONS = 1000
        private const val DAYS_30 = 30
        private const val DAYS_60 = 60
        private const val DAYS_90 = 90
        // Note: 500.0 is in home currency units
        private const val DEFAULT_EMERGENCY_BUFFER_FALLBACK = 500.0
        private const val SEED = 42L
    }

    /**
     * Compute financial stress forecast for multiple horizons (30/60/90 days).
     *
     * @param displayCurrency The currency to display results in. If null, resolves from settings.
     * @return StressForecastResult containing forecasts for all horizons
     */
    suspend fun computeStressForecast(displayCurrency: String? = null): StressForecastResult {
        val startTime = System.currentTimeMillis()
        val resolvedDisplayCurrency = resolveDisplayCurrency(displayCurrency)
        
        return try {
            val now = timeProvider.now()

            // No canonical account-balance source exists in this pipeline.
            // Use a neutral starting point instead of presenting month-to-date
            // net cashflow as if it were the user's real cash balance.
            val currentBalance = resolveStartingBalanceBaseline()
            val patterns = recurringPatternsProvider.getConfirmedPatterns()

            // Normalize purchases and deposits to display currency
            val ninetyDaysAgo = now - (90 * TimePeriodUtils.DAY_IN_MILLIS)
            val sixtyDaysAgo = now - (60 * TimePeriodUtils.DAY_IN_MILLIS)

            // Pre-fetch and normalize deposits for income estimation
            val rawDeposits = expenseRepository.getDepositsBetween(ninetyDaysAgo, now)
            val normDeposits = analyticsCurrencyNormalizer.normalizeExpenses(rawDeposits, resolvedDisplayCurrency)
            val normalizedDeposits = normDeposits.includedExpenses

            // Pre-fetch and normalize expenses for Monte Carlo simulation
            val rawExpenses = expenseRepository.getExpensesBetween(sixtyDaysAgo, now)
            val normExpenses = analyticsCurrencyNormalizer.normalizeExpenses(rawExpenses, resolvedDisplayCurrency)
            val normalizedExpenses = normExpenses.includedExpenses

            // Calculate horizons
            val horizon30 = calculateHorizon(
                daysAhead = DAYS_30,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now,
                normalizedExpenses = normalizedExpenses,
                normalizedDeposits = normalizedDeposits,
                displayCurrency = resolvedDisplayCurrency
            )
            
            val horizon60 = calculateHorizon(
                daysAhead = DAYS_60,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now,
                normalizedExpenses = normalizedExpenses,
                normalizedDeposits = normalizedDeposits,
                displayCurrency = resolvedDisplayCurrency
            )
            
            val horizon90 = calculateHorizon(
                daysAhead = DAYS_90,
                currentBalance = currentBalance,
                patterns = patterns,
                now = now,
                normalizedExpenses = normalizedExpenses,
                normalizedDeposits = normalizedDeposits,
                displayCurrency = resolvedDisplayCurrency
            )
            
            val horizons = listOf(horizon30, horizon60, horizon90)
            
            // Determine overall risk level (worst of all horizons)
            val overallRiskLevel = determineOverallRiskLevel(horizons)
            
            // Find earliest crunch date
            val earliestCrunchDate = findEarliestCrunchDate(horizons, now)
            
            // Generate recommendations
            val recommendations = generateRecommendations(horizons, patterns, resolvedDisplayCurrency)
            
            val duration = System.currentTimeMillis() - startTime
            Timber.d("$TAG: Stress forecast computed in ${duration}ms - Risk: $overallRiskLevel")
            
            StressForecastResult(
                horizons = horizons,
                overallRiskLevel = overallRiskLevel,
                earliestCrunchDate = earliestCrunchDate,
                recommendations = recommendations,
                displayCurrency = resolvedDisplayCurrency,
                mode = StressForecastMode.NET_CASHFLOW_ESTIMATE
            )
            
        } catch (e: Exception) {
            // FCST-17: Structured diagnostics instead of silent catch-all.
            // The exception type and message are captured and logged so the
            // caller can distinguish between transient errors (network, timeout)
            // and permanent ones (data corruption, invalid configuration).
            val isRecoverable = e !is IllegalArgumentException &&
                e !is IllegalStateException
            Timber.e(
                e, "$TAG: Failed to compute stress forecast. " +
                "type=%s recoverable=%s stage=compute_stress_forecast",
                e::class.qualifiedName.orEmpty(), isRecoverable
            )
            // Return degraded fallback with non-LOW risk
            StressForecastResult(
                horizons = createDefaultHorizons(
                    fallbackRiskLevel = StressRiskLevel.MODERATE,
                    fallbackCrunchProbability = 0.20,
                    displayCurrency = resolvedDisplayCurrency
                ),
                overallRiskLevel = StressRiskLevel.MODERATE,
                earliestCrunchDate = null,
                recommendations = listOf(
                    "Stress forecast is temporarily unavailable due to a calculation issue.",
                    "Showing a degraded estimate. Please retry shortly and verify your recent transactions."
                ),
                displayCurrency = resolvedDisplayCurrency,
                mode = StressForecastMode.ESTIMATED_INDEX
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
        now: Long,
        normalizedExpenses: List<ExpenseSnapshot>,
        normalizedDeposits: List<ExpenseSnapshot>,
        displayCurrency: String
    ): StressHorizon {
        val horizonStart = TimePeriodUtils.getStartOfDay(now)
        val horizonEnd = now + (daysAhead * TimePeriodUtils.DAY_IN_MILLIS)
        
        // 1. Calculate recurring obligations within this horizon
        val recurringOutflowResult = calculateRecurringOutflows(patterns, horizonStart, horizonEnd, displayCurrency)
        val recurringOutflows = recurringOutflowResult.total
        
        // 2. Estimate expected income using pre-normalized deposits
        val expectedIncome = estimateIncome(daysAhead, normalizedDeposits)
        
        // 3. Run Monte Carlo for discretionary spending using pre-normalized expenses
        val mcResult = runMonteCarloSimulation(daysAhead, patterns, normalizedExpenses, displayCurrency)
        
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
        val emergencyBuffer = getEmergencyBuffer()
        val discretionaryBuffer = (projectedBalance - emergencyBuffer).coerceAtLeast(0.0)
        
        return StressHorizon(
            daysAhead = daysAhead,
            projectedBalance = projectedBalance,
            minProjectedBalance = minProjectedBalance,
            probabilityOfCrunch = crunchProbability,
            riskLevel = riskLevel,
            recurringObligations = recurringOutflows,
            expectedIncome = expectedIncome,
            discretionaryBuffer = discretionaryBuffer,
            displayCurrency = displayCurrency,
            // DBG-06: recurring obligations are unreliable when the restore barrier blocked
            // the materialized read (SKIPPED/CANCELLED overrides may be missing).
            recurringObligationsPartial = recurringOutflowResult.materializedReadBlocked
        )
    }

    /**
     * Calculate recurring outflows for a time period using canonical
     * [RecurringOccurrence] infrastructure.
     *
     * For each manual recurring rule (patterns with non-null id):
     * 1. Generate occurrences via [RecurringLifecycleCoordinator] (idempotent writes).
     * 2. Query all materialized occurrences in the date range.
     * 3. Sum expected amounts for PAID and PLANNED occurrences.
     *
     * Detected-only patterns (id == null) have no corresponding manual rule and
     * are handled by a simplified ad-hoc expansion for backward compatibility.
     */
    /**
     * Allowed statuses for recurring occurrences that represent active obligations.
     */
    private val ACTIVE_OCCURRENCE_STATUSES = setOf("PLANNED")

    /**
     * Statuses that represent skipped/cancelled/ignored occurrences (excluded from totals).
     */
    private val EXCLUDED_OCCURRENCE_STATUSES = setOf("SKIPPED", "CANCELLED", "IGNORED")

    private suspend fun calculateRecurringOutflows(
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        startDate: Long,
        endDate: Long,
        displayCurrency: String
    ): RecurringOutflowResult {
        val manualPatterns = patterns.filter { it.id != null && it.confidence >= 0.50f }
        val detectedPatterns = patterns.filter { it.id == null && it.confidence >= 0.50f }

        var totalOutflows = 0.0
        // DBG-06: set when the materialized read is blocked by the restore barrier, so the
        // caller can flag the horizon's recurring section as partial/unreliable (projections
        // bypass the barrier and would drop SKIPPED/CANCELLED overrides).
        var materializedReadBlocked = false

        // ── Part 1: Manual patterns — READ-ONLY occurrence path ─────────────
        if (manualPatterns.isNotEmpty()) {
            val ruleIds = manualPatterns.mapNotNull { it.id }.distinct()
            // P6-CURRENT-024: PROJECT occurrences in memory (no writes). Rules whose
            // projection throws fall back to ad-hoc expansion (bills not dropped).
            val failedRuleIds = mutableListOf<Long>()
            val projected = mutableListOf<RecurringOccurrence>()
            for (ruleId in ruleIds) {
                try {
                    projected += recurringLifecycleCoordinator.projectOccurrences(
                        ruleId = ruleId,
                        startDate = startDate,
                        endDate = endDate
                    )
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: projectOccurrences failed for ruleId=%d", ruleId)
                    failedRuleIds.add(ruleId)
                }
            }

            // Read already-materialized rows (READ-ONLY, barrier-guarded). They
            // carry authoritative status (incl. user overrides) and override
            // projections for the same occurrenceKey — no double-count.
            //
            // DBG-03: `ruleIds` is derived from `getConfirmedPatterns()` which (via
            // RecurringExpenseRepository.getAll → dao.getAllActive) yields ACTIVE rules
            // only. Filtering materialized rows to `sourceId in ruleIds` therefore drops
            // any previously-materialized PLANNED rows for a PAUSED (isActive=false) rule,
            // so a paused subscription no longer leaks in as a future obligation.
            val materialized = try {
                databaseReadBarrier.checkReadAllowed("FinancialStressForecastEngine.calculateRecurringOutflows")
                recurringOccurrenceDao.getByDateRange(startDate, endDate)
                    .filter { it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE && it.sourceId in ruleIds }
            } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                // DBG-06: restore barrier blocked the read — degrade and flag partial.
                Timber.w(e, "$TAG: materialized read blocked by restore barrier — marking partial")
                materializedReadBlocked = true
                emptyList()
            } catch (e: Exception) {
                Timber.w(e, "$TAG: reading materialized occurrences failed")
                emptyList()
            }

            val mergedByKey = LinkedHashMap<String, RecurringOccurrence>()
            for (occ in projected) mergedByKey[occ.occurrenceKey] = occ
            for (occ in materialized) mergedByKey[occ.occurrenceKey] = occ
            val allOccurrences = mergedByKey.values

            for (occ in allOccurrences) {
                // P0-1: Filter by status — only count PLANNED and PAID
                if (occ.status in EXCLUDED_OCCURRENCE_STATUSES) continue
                if (occ.status == "MISSED") {
                    Timber.w("$TAG: Occurrence id=%d ruleId=%d is MISSED", occ.id, occ.sourceId)
                    // Do not count MISSED occurrences
                    continue
                }
                if (occ.status !in ACTIVE_OCCURRENCE_STATUSES) {
                    Timber.w("$TAG: Occurrence id=%d has unknown status=%s, skipping", occ.id, occ.status)
                    continue
                }

                // P0-2: Convert amount to displayCurrency before summing
                val amount = when (occ.status) {
                    // NOTE (P3-06): The "PAID" branch is unreachable dead code — PAID is not in
                    // ACTIVE_OCCURRENCE_STATUSES so it is filtered out by the guard above.
                    // Kept for defensive safety in case ACTIVE_OCCURRENCE_STATUSES is expanded.
                    "PAID" -> occ.paidAmount ?: occ.expectedAmount
                    else -> occ.expectedAmount
                }
                val currency = when (occ.status) {
                    "PAID" -> occ.paidCurrency ?: occ.expectedCurrency
                    else -> occ.expectedCurrency
                }

                val converted = runCatching {
                    currencyConverter.convert(amount, currency, displayCurrency)
                }.getOrNull()

                if (converted != null) {
                    totalOutflows += converted.convertedAmount
                } else {
                    Timber.w("$TAG: Failed to convert occurrence id=%d amount=%.2f %s to %s, excluding",
                        occ.id, amount, currency, displayCurrency)
                }
            }

            // P0-3 fallback: for rules that failed projection, fall back to ad-hoc expansion
            if (failedRuleIds.isNotEmpty()) {
                val failedPatterns = manualPatterns.filter { it.id in failedRuleIds }
                if (failedPatterns.isNotEmpty()) {
                    Timber.w("$TAG: Falling back to ad-hoc expansion for %d failed rule(s)", failedRuleIds.size)
                    totalOutflows += expandDetectedPatterns(failedPatterns, startDate, endDate)
                }
            }
        }

        // ── Part 2: Detected-only patterns — simplified ad-hoc fallback ──────
        if (detectedPatterns.isNotEmpty()) {
            totalOutflows += expandDetectedPatterns(detectedPatterns, startDate, endDate)
        }

        return RecurringOutflowResult(total = totalOutflows, materializedReadBlocked = materializedReadBlocked)
    }

    /**
     * Expanded ad-hoc recurrence calculation for detected-only patterns that
     * do not have a corresponding manual rule and therefore cannot use the
     * [RecurringLifecycleCoordinator].
     */
    private fun expandDetectedPatterns(
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        startDate: Long,
        endDate: Long
    ): Double {
        var total = 0.0
        for (pattern in patterns) {
            var nextDate = pattern.nextExpectedDate
            if (nextDate > endDate) continue
            while (nextDate in startDate..endDate) {
                total += pattern.averageAmount
                nextDate = when (pattern.frequency) {
                    RecurrenceFrequency.WEEKLY -> nextDate + (7 * TimePeriodUtils.DAY_IN_MILLIS)
                    RecurrenceFrequency.BIWEEKLY -> nextDate + (14 * TimePeriodUtils.DAY_IN_MILLIS)
                    RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(nextDate, 1)
                    RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(nextDate, 3)
                    RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(nextDate, 6)
                    RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(nextDate, 1)
                    else -> break
                }
            }
        }
        return total
    }

    /**
     * Estimate expected income for the horizon based on normalized historical deposits.
     */
    private suspend fun estimateIncome(
        daysAhead: Int,
        normalizedDeposits: List<ExpenseSnapshot>
    ): Double {
        // SAFE: data normalized via AnalyticsCurrencyNormalizer at line 83
        val totalDeposits = normalizedDeposits.sumOf { it.effectiveAmount }
        
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
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        normalizedExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): MonteCarloHorizonResult {
        val purchases = normalizedExpenses.filter { 
            it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine 
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
                simulatedTotals = fallbackTotals.toList(),
                displayCurrency = displayCurrency
            )
        }

        // Build empirical distribution of daily discretionary totals, including
        // zero-spend days so sparse history does not overstate routine spending.
        // SAFE: discretionaryPurchases derived from normalizedExpenses normalized at line 89
        val discretionaryTotalsByDay = discretionaryPurchases
            .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .mapValues { (_, txns) -> txns.sumOf { it.effectiveAmount } }

        val sixtyDaysAgo = discretionaryPurchases.minOfOrNull { it.date }
            ?: (timeProvider.now() - (60 * TimePeriodUtils.DAY_IN_MILLIS))
        val now = timeProvider.now()
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
            simulatedTotals = simulatedTotals.toList(),
            displayCurrency = displayCurrency
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
    private suspend fun generateRecommendations(
        horizons: List<StressHorizon>,
        patterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
        displayCurrency: String
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
            val emergencyBuffer = getEmergencyBuffer()
            val minBuffer = horizons.minOf { it.discretionaryBuffer }
            if (minBuffer < emergencyBuffer) {
                val formattedBuffer = CurrencyFormatter.format(emergencyBuffer, displayCurrency)
                recommendations.add("Your emergency buffer is low. Aim for at least $formattedBuffer.")
            }
        }
        
        if (highRisk) {
            recommendations.add("Critical: Consider delaying non-essential purchases until your cash flow improves.")
        }
        
        // Add positive reinforcement when healthy
        val emergencyBuffer = getEmergencyBuffer()
        if (!anyRisk && horizons.all { it.projectedBalance > emergencyBuffer }) {
            recommendations.add("Great job! Your financial stress level is low. Keep up the good habits.")
        }
        
        return recommendations.ifEmpty { listOf("No immediate concerns. Continue monitoring your spending.") }
    }

    /**
     * Resolve a starting balance baseline for the forecast.
     *
     * P6-P1-13: Delegates to [AccountBalanceProvider] (currently [NetCashflowBalanceProvider]).
     * Falls back to inline 90-day net cashflow estimate if the provider fails.
     *
     * Future implementations can swap in BankConnectionBalanceProvider or
     * ManualBalanceProvider via DI without changing this engine.
     */
    private suspend fun resolveStartingBalanceBaseline(): Double {
        // P6-P1-13: Delegate to AccountBalanceProvider (currently NetCashflowBalanceProvider)
        val homeCurrency = resolveDisplayCurrency(null)
        val balance = runCatching { accountBalanceProvider.currentBalance(homeCurrency) }.getOrNull()
        if (balance != null) {
            Timber.d("FCST-9: resolveStartingBalanceBaseline via AccountBalanceProvider = %.2f", balance)
            return balance
        }

        // Inline fallback if provider fails
        val now = timeProvider.now()
        val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS
        val recentDeposits = runCatching {
            multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now).displayAmount
        }.getOrDefault(0.0)
        val recentExpenses = runCatching {
            multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now).displayAmount
        }.getOrDefault(0.0)
        val netCashflow = recentDeposits - recentExpenses
        Timber.d("FCST-9: resolveStartingBalanceBaseline fallback — deposits=%.2f, expenses=%.2f, net=%.2f", recentDeposits, recentExpenses, netCashflow)
        return netCashflow.coerceAtLeast(0.0)
    }

    /**
     * Resolve the emergency buffer amount in the home/display currency.
     * Defaults to [DEFAULT_EMERGENCY_BUFFER_FALLBACK] if the setting is unavailable.
     */
    private suspend fun getEmergencyBuffer(): Double {
        return runCatching { currencySettingsRepository.emergencyBuffer().first() }
            .getOrElse { DEFAULT_EMERGENCY_BUFFER_FALLBACK }
    }

    /**
     * Resolve the display currency from the optional parameter or settings.
     */
    private suspend fun resolveDisplayCurrency(fallback: String?): String {
        return fallback ?: runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
    }

    /**
     * Create default horizons for error case.
     */
    private fun createDefaultHorizons(
        fallbackRiskLevel: StressRiskLevel = StressRiskLevel.LOW,
        fallbackCrunchProbability: Double = 0.0,
        displayCurrency: String = ""
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
                discretionaryBuffer = 0.0,
                displayCurrency = displayCurrency
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
/**
 * Indicates the baseline balance mode used by the stress forecast engine.
 * The UI should display appropriate labeling based on the mode:
 * - [ESTIMATED_INDEX]: Label as "Stress Index Estimate" — not a real cash balance.
 * - [NET_CASHFLOW_ESTIMATE]: Label as "Projected balance estimate based on recent cashflow".
 *
 * P6-P1-13: [AccountBalanceProvider] interface implemented. When a
 * BankConnectionBalanceProvider or ManualBalanceProvider is added, introduce
 * USER_ENTERED_BALANCE and BANK_BALANCE modes here.
 */
enum class StressForecastMode {
    /** Neutral baseline (0.0) — no balance source available. */
    ESTIMATED_INDEX,
    /** 90-day net cashflow estimate (deposits minus expenses). */
    NET_CASHFLOW_ESTIMATE
}

data class StressForecastResult(
    val horizons: List<StressHorizon>,
    val overallRiskLevel: StressRiskLevel,
    val earliestCrunchDate: Long?,
    val recommendations: List<String>,
    val displayCurrency: String = "",
    val mode: StressForecastMode = StressForecastMode.NET_CASHFLOW_ESTIMATE
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
    val discretionaryBuffer: Double,
    val displayCurrency: String = "",
    /**
     * DBG-06: true when the recurring obligations figure is unreliable because the
     * materialized-occurrence read was blocked by the restore barrier. When set, the
     * recurring section may be missing user overrides (SKIPPED/CANCELLED) and the UI
     * should label it as a degraded/partial estimate rather than authoritative.
     * Additive with a neutral default — existing constructors are unaffected.
     */
    val recurringObligationsPartial: Boolean = false
) {
    val moneyProjectedBalance: MoneyAmount get() = MoneyAmount(projectedBalance, CurrencyCode(displayCurrency))
    val moneyMinProjectedBalance: MoneyAmount get() = MoneyAmount(minProjectedBalance, CurrencyCode(displayCurrency))
    val moneyRecurringObligations: MoneyAmount get() = MoneyAmount(recurringObligations, CurrencyCode(displayCurrency))
    val moneyExpectedIncome: MoneyAmount get() = MoneyAmount(expectedIncome, CurrencyCode(displayCurrency))
    val moneyDiscretionaryBuffer: MoneyAmount get() = MoneyAmount(discretionaryBuffer, CurrencyCode(displayCurrency))
}

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
    val simulatedTotals: List<Double>,
    val displayCurrency: String = ""
) {
    val moneyPercentile10: MoneyAmount get() = MoneyAmount(percentile10, CurrencyCode(displayCurrency))
    val moneyPercentile25: MoneyAmount get() = MoneyAmount(percentile25, CurrencyCode(displayCurrency))
    val moneyPercentile50: MoneyAmount get() = MoneyAmount(percentile50, CurrencyCode(displayCurrency))
    val moneyPercentile75: MoneyAmount get() = MoneyAmount(percentile75, CurrencyCode(displayCurrency))
    val moneyPercentile90: MoneyAmount get() = MoneyAmount(percentile90, CurrencyCode(displayCurrency))
}

/**
 * Internal result of recurring-outflow calculation for a horizon.
 *
 * DBG-06: [materializedReadBlocked] signals that the materialized-occurrence read
 * was blocked by the restore barrier, so [total] is based on projections only and
 * may be missing user overrides (SKIPPED/CANCELLED). Callers should flag the horizon
 * as partial/degraded in that case.
 */
private data class RecurringOutflowResult(
    val total: Double,
    val materializedReadBlocked: Boolean = false
)
