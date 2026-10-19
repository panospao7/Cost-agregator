package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsSweepMonthConsumption
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.completeMoneyAggregateForRows
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.SavingsGoalCurrencyPolicy
import com.yourname.expensetracker.domain.savings.SavingsDecisionQuality
import com.yourname.expensetracker.domain.savings.SavingsInputStage
import com.yourname.expensetracker.domain.savings.SmartSavingsOutcomeMetadata
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monthly Savings Sweep Use Case.
 *
 * Computes end-of-month savings sweep recommendations by analyzing:
 * 1. Budget underspend across all categories
 * 2. Upcoming bills/obligations still due before month end
 * 3. Monte Carlo risk buffer for spending uncertainty
 * 4. Safe sweep amount = underspend - riskBuffer
 * 5. Allocation across active savings goals based on urgency
 *
 * ## Formula
 * ```
 * safeSweep = max(0, underspend - riskBuffer)
 * where:
 * - underspend = overall remaining (if overall budget exists) OR sum of category remaining
 * - riskBuffer = upcomingBills + uncertaintyBuffer
 *   - upcomingBills = known recurring + MUST planned obligations due before month end
 *   - uncertaintyBuffer = MC uncertainty buffer (p75 - p50, or the deterministic fallback)
 * ```
 * The reported/persisted/displayed `riskBuffer` is the full sum, so the UI line
 * "Underspend X / Buffer Y" reconciles with the sweep amount.
 */
@Singleton
class MonthlySavingsSweepUseCase @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val recurringOccurrenceDao: RecurringOccurrenceDao,
    private val sweepMonthConsumption: SavingsSweepMonthConsumption
) {
    companion object {
        /** Minimum safe sweep amount to show recommendation (in home currency) */
        const val MIN_SWEEP_AMOUNT = 5.0

        /** Days before month-end to start showing sweep recommendations */
        const val DAYS_BEFORE_MONTH_END = 5

        /** Confidence threshold for showing recommendations */
        const val MIN_CONFIDENCE = 0.4

        /** Maximum single allocation as percentage of safe sweep */
        const val MAX_SINGLE_ALLOCATION_PERCENT = 0.8
    }

    /**
     * Typed decision boundary. `Unavailable` is distinct from a valid empty/no-op
     * recommendation so callers cannot silently flatten failed money input to null.
     */
    suspend fun computeSweepRecommendationResult(): SavingsSweepComputationResult {
        val quality = SavingsDecisionQuality("")
        return try {
            computeAvailableSweepRecommendation(quality)?.let {
                SavingsSweepComputationResult.Available(it, quality.snapshot())
            }
                ?: SavingsSweepComputationResult.NoRecommendation(quality.snapshot())
        } catch (error: CancellationException) {
            throw error
        } catch (error: MoneyDisplayUnavailableException) {
            SavingsSweepComputationResult.Unavailable(error.reasonCode.name, quality.snapshot(unavailable = true))
        } catch (_: Exception) {
            SavingsSweepComputationResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                quality.snapshot(unavailable = true)
            )
        }
    }

    /** Compatibility adapter for legacy nullable presentation callers. */
    @Deprecated("Presentation compatibility only; use computeSweepRecommendationResult")
    suspend fun computeSweepRecommendation(): SavingsSweepRecommendation? =
        (computeSweepRecommendationResult() as? SavingsSweepComputationResult.Available)?.recommendation

    private suspend fun computeAvailableSweepRecommendation(quality: SavingsDecisionQuality): SavingsSweepRecommendation? {
        val now = timeProvider.now()
        val settings = try {
            withTimeoutOrNull(5_000L) {
                val currency = currencySettingsRepository.homeCurrency().first()
                quality.currency = currency
                currency to currencySettingsRepository.lastRateUpdate().first()
            } ?: quality.fail(MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            quality.fail(MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE)
        }
        val (homeCurrency, rateTimestamp) = settings
        quality.currency = homeCurrency
        quality.settingsRateTimestamp = rateTimestamp
        val targetCurrency = CurrencyCode.parse(homeCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: quality.fail(MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY)
        // G-TIME-01: derive calendar fields from the injected TimeProvider instant
        // (java.time, system default timezone — same field values the Calendar read).
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // Check if we're within the recommendation window (last N days of month)
        if (!isWithinSweepWindow(today)) {
            Timber.d("Not within sweep window, skipping")
            return null
        }

        // A month whose sweep was already accepted must not offer a new card. The
        // same rule the acceptance coordinator enforces; checked before budget reads.
        val sweepMonthEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        if (sweepMonthConsumption.isMonthConsumed(sweepMonthEnd)) {
            Timber.d("SAVINGS_SWEEP_MONTH_CONSUMED")
            return null
        }

        // Check provenance before any zero/underspend shortcut. Explicit foreign
        // goals remain unsupported for allocation; unknown goals are not zero gaps.
        val allGoals = savingsGoalRepository.observeSavingsGoals().first()
        if (SavingsGoalCurrencyPolicy.hasUnknownActiveGoals(allGoals)) {
            quality.fail(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        }
        val goals = allGoals.filter { SavingsGoalCurrencyPolicy.isRecommendationEligible(it, targetCurrency) }

        // Calculate month boundaries
        val daysInMonth = today.lengthOfMonth()
        val dayOfMonth = today.dayOfMonth
        val daysRemaining = daysInMonth - dayOfMonth

        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonthStart = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEndInclusive = nextMonthStart - 1L

        // Get budget statuses and calculate underspend
        val budgetStatuses = budgetRepository.getBudgetStatuses().first()
        if (budgetStatuses.any { it.isPartial || !it.percentKnown ||
                it.conversionWarning != null ||
                CurrencyCode.parse(it.currency) != targetCurrency ||
                CurrencyCode.parse(it.budget.currency) != targetCurrency }) {
            quality.fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        }
        if (budgetStatuses.any { !it.remainingAmount.isFinite() || !it.spentAmount.isFinite() ||
                !it.budget.amount.isFinite() }) quality.fail(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        if (budgetStatuses.isEmpty()) {
            Timber.d("No active budgets, skipping sweep")
            return null
        }

        val (underspend, totalBudgeted, totalSpent) = calculateUnderspend(budgetStatuses)
        if (listOf(underspend, totalBudgeted, totalSpent).any { !it.isFinite() }) {
            quality.fail(MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT)
        }
        if (underspend <= 0) {
            Timber.d("SAVINGS_SWEEP_NO_UNDERSPEND")
            return null
        }

        // Calculate spent to date for Monte Carlo (normalized to home currency)
        val spentToDate = calculateSpentToDate(monthStart, now, homeCurrency, quality)
        val knownUpcoming = calculateKnownUpcomingObligations(now, nextMonthStart, homeCurrency, quality)

        // Run Monte Carlo simulation
        val monteCarloResult = monteCarloSimulator.simulate(
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = totalBudgeted,
            displayCurrency = homeCurrency
        )
        quality.requireForecast(monteCarloResult)

        // Calculate the uncertainty part of the buffer from Monte Carlo spread
        val uncertaintyBuffer = calculateRiskBuffer(
            monteCarloResult = monteCarloResult,
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = totalBudgeted,
            currentDayOfMonth = dayOfMonth,
            daysRemaining = daysRemaining
        )
        // HZ01b1-F1: upcoming bills/obligations still due this month are money that is
        // not safe to sweep. They are part of the displayed "Buffer" so that
        // "Underspend X - Buffer Y" adds up to the sweep amount (and the acceptance
        // coordinator invariant safeSweep <= underspend - riskBuffer holds).
        val riskBuffer = knownUpcoming + uncertaintyBuffer

        // Calculate safe sweep amount
        val safeSweepAmount = (underspend - riskBuffer).coerceAtLeast(0.0)
        if (safeSweepAmount < MIN_SWEEP_AMOUNT) {
            Timber.d("SAVINGS_SWEEP_BELOW_MINIMUM")
            return null
        }

        if (goals.isEmpty()) {
            Timber.d("No active savings goals, skipping sweep")
            return null
        }

        // Calculate goal allocations
        val allocations = allocateAcrossGoals(safeSweepAmount, goals).map { it.copy(currency = homeCurrency) }

        // Calculate confidence score
        val confidence = calculateConfidence(
            underspend = underspend,
            // Confidence keeps measuring forecast uncertainty only (unchanged semantics).
            riskBuffer = uncertaintyBuffer,
            monteCarloConfidence = monteCarloResult?.confidence?.score ?: 0.5,
            daysRemaining = daysRemaining,
            goalCount = goals.size
        )

        if (confidence < MIN_CONFIDENCE) {
            Timber.d("SAVINGS_SWEEP_LOW_CONFIDENCE")
            return null
        }

        return SavingsSweepRecommendation(
            totalUnderspend = underspend,
            riskBuffer = riskBuffer,
            safeSweepAmount = safeSweepAmount,
            goalAllocations = allocations,
            computedAt = now,
            monthEnd = monthEndInclusive,
            confidence = confidence,
            currency = homeCurrency,
            rateTimestamp = rateTimestamp
        )
    }

    /**
     * Check if we're within the sweep recommendation window.
     * Returns true during the last [DAYS_BEFORE_MONTH_END] days of the month.
     */
    private fun isWithinSweepWindow(today: LocalDate): Boolean {
        val daysInMonth = today.lengthOfMonth()
        val dayOfMonth = today.dayOfMonth
        return dayOfMonth >= (daysInMonth - DAYS_BEFORE_MONTH_END + 1)
    }

    /**
     * Calculate total underspend across all budget categories.
     * Only counts remaining amounts (not overspent budgets).
     *
     * @return Triple of (underspend, totalBudgeted, totalSpent)
     */
    private fun calculateUnderspend(budgetStatuses: List<BudgetStatus>): Triple<Double, Double, Double> {
        val overallBudget = budgetStatuses.firstOrNull { it.budget.categoryId == null }
        val selectedStatuses = if (overallBudget != null) {
            listOf(overallBudget)
        } else {
            budgetStatuses.filter { it.budget.categoryId != null }
        }

        val underspend = selectedStatuses.sumOf { status ->
            status.remainingAmount.coerceAtLeast(0.0)
        }
        // T4C oracle: the sweep risk baseline uses the budgeted amount.
        // effectiveLimit is a BudgetMonitor display concept and is 0 in
        // legacy status snapshots, which would zero the budget-based
        // fallback risk buffer.
        val totalBudgeted = selectedStatuses.sumOf { it.budget.amount }
        val totalSpent = selectedStatuses.sumOf { it.spentAmount }

        return Triple(underspend, totalBudgeted, totalSpent)
    }

    /**
     * Calculate total spending from month start to current time (normalized to home currency).
     */
    private suspend fun calculateSpentToDate(monthStart: Long, now: Long, homeCurrency: String, quality: SavingsDecisionQuality): Double {
        val rawExpenses = expenseRepository.getExpenseSnapshotsBetween(monthStart, now)
        val normalized = analyticsCurrencyNormalizer.normalizeSnapshots(rawExpenses, homeCurrency)
        quality.requireComplete(SavingsInputStage.CURRENT_SPENDING, normalized)
        // SAFE: normalized via AnalyticsCurrencyNormalizer at line 220
        return normalized.includedExpenses
            .filter { it.transactionType.isSpendingType() && !it.isNotMine }
            .let { spending ->
                normalized.completeMoneyAggregateForRows(spending, homeCurrency).displayAmount
            }
    }

    private suspend fun calculateKnownUpcomingObligations(now: Long, monthEndExclusive: Long, homeCurrency: String, quality: SavingsDecisionQuality): Double {

        // ── Recurring expenses (normalised to home currency) ──────────────────
        val recurringPairs = recurringExpenseRepository.getAllFlow().first()
            .filter { it.nextDate in now until monthEndExclusive }
            .map { it.amount to it.currency }

        val recurringAggregate = currencyConverter.convertMultiple(recurringPairs, homeCurrency) // G-MONEY-ALLOW[CURR-587][G-MONEY-17]: savings sweep latest-rate estimate, not a dashboard money widget
        quality.recordUpcoming(
            SavingsInputStage.RECURRING_UPCOMING, recurringPairs.size, recurringAggregate.failedConversions.size,
            recurringAggregate.failedConversions.map {
                if (it.failureType == com.yourname.expensetracker.domain.currency.FailedConversion.STALE_RATE)
                    "STALE_EXCHANGE_RATE" else "MISSING_EXCHANGE_RATE"
            }, recurringPairs.all { CurrencyCode.parse(it.second) == CurrencyCode.parse(homeCurrency) }
        )
        val recurringUpcoming = recurringAggregate.total
        if (recurringAggregate.hasFailures) {
            quality.fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        }

        // ── Occurrence-aware dedup for planned expenses ──────────────────────
        // Query materialized occurrences in the month window to build a set of
        // occurrenceKeys. Planned expenses whose sourceOccurrenceKey matches any
        // materialized occurrence are excluded to prevent double-counting.
        val materializedKeys: Set<String> = try {
            recurringOccurrenceDao.getByDateRange(now, monthEndExclusive)
                .map { it.occurrenceKey }
                .toSet()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            quality.fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        }

        val plannedPairs = plannedExpenseRepository.getAllPlannedExpenses().first()
            .filter {
                it.date in now until monthEndExclusive &&
                    it.priority == com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST &&
                    (it.sourceOccurrenceKey == null || it.sourceOccurrenceKey !in materializedKeys)
            }
            .map { it.amount to it.currency }

        val plannedAggregate = currencyConverter.convertMultiple(plannedPairs, homeCurrency) // G-MONEY-ALLOW[CURR-587][G-MONEY-17]: savings sweep latest-rate estimate, not a dashboard money widget
        quality.recordUpcoming(
            SavingsInputStage.PLANNED_UPCOMING, plannedPairs.size, plannedAggregate.failedConversions.size,
            plannedAggregate.failedConversions.map {
                if (it.failureType == com.yourname.expensetracker.domain.currency.FailedConversion.STALE_RATE)
                    "STALE_EXCHANGE_RATE" else "MISSING_EXCHANGE_RATE"
            }, plannedPairs.all { CurrencyCode.parse(it.second) == CurrencyCode.parse(homeCurrency) }
        )
        val plannedUpcoming = plannedAggregate.total
        if (plannedAggregate.hasFailures) {
            quality.fail(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
        }

        return MoneyAggregateBuilder.fromHomeCurrencyAmounts(listOf(recurringUpcoming, plannedUpcoming), homeCurrency).displayAmount
    }

    /**
     * Calculate risk buffer from Monte Carlo uncertainty.
     * Uses the spread between p75 and p50 as a conservative uncertainty buffer.
     */
    private fun calculateRiskBuffer(
        monteCarloResult: com.yourname.expensetracker.domain.forecasting.MonteCarloResult?,
        spentToDate: Double,
        knownUpcoming: Double,
        budgetAmount: Double,
        currentDayOfMonth: Int,
        daysRemaining: Int
    ): Double {
        if (monteCarloResult == null) {
            val safeCurrentDay = currentDayOfMonth.coerceAtLeast(1)
            val currentDailySpend = spentToDate / safeCurrentDay
            val projectedRemainingSpend = currentDailySpend * daysRemaining.coerceAtLeast(0)
            val baselineBuffer = maxOf(
                knownUpcoming * 0.15,
                projectedRemainingSpend * 0.20,
                budgetAmount * 0.03
            )
            val maxFallbackBuffer = maxOf(knownUpcoming, budgetAmount * 0.25)
            return baselineBuffer.coerceIn(0.0, maxFallbackBuffer)
        }

        // Risk buffer = p75 - p50 (uncertainty in the upper range)
        val buffer = monteCarloResult.percentile75 - monteCarloResult.percentile50

        // Apply a cap to prevent overly conservative estimates
        val maxBuffer = monteCarloResult.percentile50 * 0.3 // Max 30% of median projection

        return buffer.coerceIn(0.0, maxBuffer)
    }

    /**
     * Distribute safe sweep amount across active goals proportionally
     * based on urgency (how far from target).
     *
     * Allocation formula:
     * - urgency = 1.0 - (currentAmount / targetAmount)
     * - percentage = urgency / totalUrgency
     * - allocation = safeSweepAmount * percentage
     */
    private fun allocateAcrossGoals(
        safeSweepAmount: Double,
        goals: List<SavingsGoal>
    ): List<GoalAllocation> {
        data class GoalAllocationState(
            val goal: SavingsGoal,
            val urgency: Double,
            val remainingGap: Double,
            var allocated: Double = 0.0
        )

        val states = goals.map { goal ->
            val remainingGap = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
            val progress = if (goal.targetAmount > 0.0) goal.currentAmount / goal.targetAmount else 0.0
            GoalAllocationState(
                goal = goal,
                urgency = (1.0 - progress).coerceIn(0.0, 1.0),
                remainingGap = remainingGap
            )
        }.filter { it.remainingGap > 0.0 }

        if (states.isEmpty()) return emptyList()

        val maxAllocation = safeSweepAmount * MAX_SINGLE_ALLOCATION_PERCENT
        var remainingSweep = safeSweepAmount
        var madeProgress: Boolean

        do {
            madeProgress = false
            val openStates = states.filter {
                it.remainingGap - it.allocated > 0.0 &&
                    maxAllocation - it.allocated > 0.0
            }
            if (openStates.isEmpty() || remainingSweep <= 0.0) break

            val totalUrgency = openStates.sumOf { it.urgency }
            val equalWeight = if (openStates.isNotEmpty()) 1.0 / openStates.size else 0.0

            openStates.forEachIndexed { index, state ->
                if (remainingSweep <= 0.0) return@forEachIndexed

                val weight = if (totalUrgency > 0.0) state.urgency / totalUrgency else equalWeight
                val provisionalShare = if (index == openStates.lastIndex) {
                    remainingSweep
                } else {
                    remainingSweep * weight
                }
                val gapCapped = provisionalShare.coerceAtMost(state.remainingGap - state.allocated)
                val concentrationCapped = gapCapped.coerceAtMost(maxAllocation - state.allocated)

                if (concentrationCapped > 0.0) {
                    state.allocated += concentrationCapped
                    remainingSweep -= concentrationCapped
                    madeProgress = true
                }
            }
        } while (madeProgress)

        return states
            .map { state ->
                // A rounded subtraction can produce a gap whose addition exceeds
                // the target. Keep the persistence cap exact at the producer.
                while (state.goal.currentAmount + state.allocated > state.goal.targetAmount) {
                    state.allocated = Math.nextDown(state.allocated)
                }
                state
            }
            .filter { it.allocated > 0.0 }
            .map { state ->
                GoalAllocation(
                    goalId = state.goal.id,
                    goalName = state.goal.name,
                    currentProgress = state.goal.currentAmount,
                    targetAmount = state.goal.targetAmount,
                    suggestedAllocation = state.allocated,
                    allocationPercentage = if (safeSweepAmount > 0.0) state.allocated / safeSweepAmount else 0.0
                )
            }
            .sortedByDescending { it.suggestedAllocation }
    }

    /**
     * Calculate overall confidence score for the recommendation.
     */
    private fun calculateConfidence(
        underspend: Double,
        riskBuffer: Double,
        monteCarloConfidence: Double,
        daysRemaining: Int,
        goalCount: Int
    ): Double {
        var score = 0.0

        // Base confidence from Monte Carlo
        score += monteCarloConfidence * 0.4

        // Higher confidence when risk buffer is small relative to underspend
        val bufferRatio = if (underspend > 0) riskBuffer / underspend else 1.0
        score += (1.0 - bufferRatio.coerceIn(0.0, 1.0)) * 0.3

        // Higher confidence closer to month end (less uncertainty)
        val dayConfidence = if (daysRemaining <= 2) 0.2 else if (daysRemaining <= 5) 0.1 else 0.0
        score += dayConfidence

        // Having multiple goals slightly increases confidence (diversification)
        if (goalCount >= 2) {
            score += 0.1
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Check if a sweep recommendation should be shown based on timing and state.
     * Call this to determine if the sweep UI should be displayed.
     */
    fun shouldShowSweepPrompt(): Boolean {
        val now = timeProvider.now()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return isWithinSweepWindow(today)
    }
}

/**
 * End-of-month savings sweep recommendation.
 */
sealed interface SavingsSweepComputationResult {
    data class Available(
        val recommendation: SavingsSweepRecommendation,
        val metadata: SmartSavingsOutcomeMetadata = SmartSavingsOutcomeMetadata()
    ) : SavingsSweepComputationResult
    data class Unavailable(
        val reasonCode: String,
        val metadata: SmartSavingsOutcomeMetadata = SmartSavingsOutcomeMetadata(conversionQuality = ConversionQuality.UNAVAILABLE)
    ) : SavingsSweepComputationResult
    data class NoRecommendation(
        val metadata: SmartSavingsOutcomeMetadata = SmartSavingsOutcomeMetadata()
    ) : SavingsSweepComputationResult
}

data class SavingsSweepRecommendation(
    /** Total underspend across all budget categories */
    val totalUnderspend: Double,

    /** Buffer subtracted from the underspend: upcoming bills plus the spending-uncertainty buffer */
    val riskBuffer: Double,

    /** Safe amount available to sweep to savings (underspend - riskBuffer) */
    val safeSweepAmount: Double,

    /** Allocations across active savings goals */
    val goalAllocations: List<GoalAllocation>,

    /** Timestamp when recommendation was computed */
    val computedAt: Long,

    /** Timestamp for the end of current month */
    val monthEnd: Long,

    /** Confidence score 0.0-1.0 for the recommendation */
    val confidence: Double,
    val currency: String = "",
    val rateTimestamp: Long = 0L
) {
    val moneySafeSweepAmount: com.yourname.expensetracker.domain.core.money.MoneyAmount?
        get() = CurrencyCode.parse(currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?.let { com.yourname.expensetracker.domain.core.money.MoneyAmount(safeSweepAmount, it) }
    /**
     * Get a human-readable summary of the sweep recommendation.
     */
    fun getSummary(displayCurrency: String = currency): String {
        require(CurrencyCode.parse(displayCurrency) == moneySafeSweepAmount?.currency && moneySafeSweepAmount != null) {
            "SWEEP_CURRENCY_MISMATCH"
        }
        return "Safe to save: ${CurrencyFormatter.formatMoney(safeSweepAmount, displayCurrency)} " +
               "(underspend: ${CurrencyFormatter.formatMoney(totalUnderspend, displayCurrency)}, " +
               "risk buffer: ${CurrencyFormatter.formatMoney(riskBuffer, displayCurrency)})"
    }

    /**
     * Get the primary allocation (largest share).
     */
    fun getPrimaryAllocation(): GoalAllocation? {
        return goalAllocations.maxByOrNull { it.suggestedAllocation }
    }
}

/**
 * Individual goal allocation within a sweep recommendation.
 */
data class GoalAllocation(
    /** Goal ID */
    val goalId: Long,

    /** Goal name for display */
    val goalName: String,

    /** Current progress amount */
    val currentProgress: Double,

    /** Target amount */
    val targetAmount: Double,

    /** Suggested allocation amount from sweep */
    val suggestedAllocation: Double,

    /** Percentage of total sweep allocated to this goal */
    val allocationPercentage: Double,
    val currency: String = ""
) {
    val moneySuggestedAllocation: com.yourname.expensetracker.domain.core.money.MoneyAmount?
        get() = CurrencyCode.parse(currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?.let { com.yourname.expensetracker.domain.core.money.MoneyAmount(suggestedAllocation, it) }
    /**
     * Calculate progress percentage after applying this allocation.
     */
    fun getProgressAfterAllocation(): Double {
        val newAmount = currentProgress + suggestedAllocation
        return ((newAmount / targetAmount) * 100).coerceIn(0.0, 100.0)
    }

    /**
     * Calculate the gap to target after applying this allocation.
     */
    fun getRemainingAfterAllocation(): Double {
        return (targetAmount - currentProgress - suggestedAllocation).coerceAtLeast(0.0)
    }
}

/**
 * Extension to check if transaction type is a spending type (data-layer TransactionType).
 */
private fun com.yourname.expensetracker.data.database.entity.TransactionType.isSpendingType(): Boolean {
    return this == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE ||
           this == com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL
}

/**
 * Extension to check if transaction type is a spending type (domain-layer DomainTransactionType).
 */
private fun DomainTransactionType.isSpendingType(): Boolean {
    return this == DomainTransactionType.PURCHASE || this == DomainTransactionType.WITHDRAWAL
}

/**
 * Extension to get effective amount (considering shared expenses).
 */
private val com.yourname.expensetracker.data.database.entity.Expense.effectiveAmount: Double
    get() = when {
        isNotMine -> 0.0
        isSharedExpense && myShareAmount != null -> myShareAmount
        isSharedExpense && mySharePercentage != null -> amount * mySharePercentage / 100.0
        else -> amount
    }
