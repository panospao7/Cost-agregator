package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monthly Savings Sweep Use Case.
 *
 * Computes end-of-month savings sweep recommendations by analyzing:
 * 1. Budget underspend across all categories
 * 2. Monte Carlo risk buffer for spending uncertainty
 * 3. Safe sweep amount = underspend - riskBuffer
 * 4. Allocation across active savings goals based on urgency
 *
 * ## Formula
 * ```
 * safeSweep = max(0, underspend - riskBuffer)
 * where:
 * - underspend = overall remaining (if overall budget exists) OR sum of category remaining
 * - riskBuffer = MC uncertainty buffer (p75 - p50)
 * ```
 */
@Singleton
class MonthlySavingsSweepUseCase @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val timeProvider: TimeProvider
) {
    companion object {
        /** Minimum safe sweep amount to show recommendation (EUR) */
        const val MIN_SWEEP_AMOUNT = 5.0

        /** Days before month-end to start showing sweep recommendations */
        const val DAYS_BEFORE_MONTH_END = 5

        /** Confidence threshold for showing recommendations */
        const val MIN_CONFIDENCE = 0.4

        /** Maximum single allocation as percentage of safe sweep */
        const val MAX_SINGLE_ALLOCATION_PERCENT = 0.8
    }

    /**
     * Compute end-of-month savings sweep recommendation.
     *
     * Returns null if:
     * - Not within the recommendation window (last 5 days of month)
     * - No active budgets or no underspend
     * - No active savings goals
     * - Safe sweep amount below minimum threshold
     * - Confidence below threshold
     *
     * @return SavingsSweepRecommendation with allocation plan, or null
     */
    suspend fun computeSweepRecommendation(): SavingsSweepRecommendation? {
        val now = timeProvider.now()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        // Check if we're within the recommendation window (last N days of month)
        if (!isWithinSweepWindow(calendar)) {
            Timber.d("Not within sweep window, skipping")
            return null
        }

        // Calculate month boundaries
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        val monthStart = calendar.clone() as Calendar
        monthStart.set(Calendar.DAY_OF_MONTH, 1)
        monthStart.set(Calendar.HOUR_OF_DAY, 0)
        monthStart.set(Calendar.MINUTE, 0)
        monthStart.set(Calendar.SECOND, 0)
        monthStart.set(Calendar.MILLISECOND, 0)

        val nextMonthStart = monthStart.clone() as Calendar
        nextMonthStart.add(Calendar.MONTH, 1)
        val monthEndInclusive = nextMonthStart.timeInMillis - 1L

        // Get budget statuses and calculate underspend
        val budgetStatuses = budgetRepository.getBudgetStatuses().first()
        if (budgetStatuses.isEmpty()) {
            Timber.d("No active budgets, skipping sweep")
            return null
        }

        val (underspend, totalBudgeted, totalSpent) = calculateUnderspend(budgetStatuses)
        if (underspend <= 0) {
            Timber.d("No underspend available (underspend=$underspend), skipping sweep")
            return null
        }

        // Calculate spent to date for Monte Carlo
        val spentToDate = calculateSpentToDate(monthStart.timeInMillis, now)
        val knownUpcoming = calculateKnownUpcomingObligations(now, nextMonthStart.timeInMillis)

        // Run Monte Carlo simulation
        val monteCarloResult = monteCarloSimulator.simulate(
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = totalBudgeted
        )

        // Calculate risk buffer from Monte Carlo uncertainty
        val riskBuffer = calculateRiskBuffer(
            monteCarloResult = monteCarloResult,
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = totalBudgeted,
            currentDayOfMonth = dayOfMonth,
            daysRemaining = daysRemaining
        )

        // Calculate safe sweep amount
        val safeSweepAmount = (underspend - riskBuffer).coerceAtLeast(0.0)
        if (safeSweepAmount < MIN_SWEEP_AMOUNT) {
            Timber.d("Safe sweep amount too low ($safeSweepAmount), skipping")
            return null
        }

        // Get active goals
        val goals = savingsGoalRepository.observeSavingsGoals().first()
            .filter { it.targetAmount > 0.0 && it.currentAmount < it.targetAmount } // Only valid, incomplete goals

        if (goals.isEmpty()) {
            Timber.d("No active savings goals, skipping sweep")
            return null
        }

        // Calculate goal allocations
        val allocations = allocateAcrossGoals(safeSweepAmount, goals)

        // Calculate confidence score
        val confidence = calculateConfidence(
            underspend = underspend,
            riskBuffer = riskBuffer,
            monteCarloConfidence = monteCarloResult?.confidence?.score ?: 0.5,
            daysRemaining = daysRemaining,
            goalCount = goals.size
        )

        if (confidence < MIN_CONFIDENCE) {
            Timber.d("Confidence too low ($confidence), skipping sweep")
            return null
        }

        return SavingsSweepRecommendation(
            totalUnderspend = underspend,
            riskBuffer = riskBuffer,
            safeSweepAmount = safeSweepAmount,
            goalAllocations = allocations,
            computedAt = now,
            monthEnd = monthEndInclusive,
            confidence = confidence
        )
    }

    /**
     * Check if we're within the sweep recommendation window.
     * Returns true during the last [DAYS_BEFORE_MONTH_END] days of the month.
     */
    private fun isWithinSweepWindow(calendar: Calendar): Boolean {
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
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
        val totalBudgeted = selectedStatuses.sumOf { it.budget.amount }
        val totalSpent = selectedStatuses.sumOf { it.spentAmount }

        return Triple(underspend, totalBudgeted, totalSpent)
    }

    /**
     * Calculate total spending from month start to current time.
     */
    private suspend fun calculateSpentToDate(monthStart: Long, now: Long): Double {
        val expenses = expenseRepository.getExpensesBetween(monthStart, now)
        return expenses
            .filter { it.transactionType.isSpendingType() && !it.isNotMine }
            .sumOf { it.effectiveAmount }
    }

    private suspend fun calculateKnownUpcomingObligations(now: Long, monthEndExclusive: Long): Double {
        val recurringUpcoming = recurringExpenseRepository.getAllFlow().first()
            .filter { it.nextDate in now until monthEndExclusive }
            .sumOf { it.amount }

        val plannedUpcoming = plannedExpenseRepository.getAllPlannedExpenses().first()
            .filter {
                it.date in now until monthEndExclusive &&
                    it.priority == com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
            }
            .sumOf { it.amount }

        return recurringUpcoming + plannedUpcoming
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
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        return isWithinSweepWindow(calendar)
    }
}

/**
 * End-of-month savings sweep recommendation.
 */
data class SavingsSweepRecommendation(
    /** Total underspend across all budget categories */
    val totalUnderspend: Double,

    /** Risk buffer subtracted for spending uncertainty */
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
    val confidence: Double
) {
    /**
     * Get a human-readable summary of the sweep recommendation.
     */
    fun getSummary(): String {
        return "Safe to save: €${String.format("%.2f", safeSweepAmount)} " +
               "(underspend: €${String.format("%.2f", totalUnderspend)}, " +
               "risk buffer: €${String.format("%.2f", riskBuffer)})"
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
    val allocationPercentage: Double
) {
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
 * Extension to check if transaction type is a spending type.
 */
private fun com.yourname.expensetracker.data.database.entity.TransactionType.isSpendingType(): Boolean {
    return this == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE ||
           this == com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL
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
