package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.toRecurringPattern
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.text.DomainTextKeys
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SynthesisEngine - Core financial forecasting and budgeting logic.
 *
 * ## Source of truth for recurrence expansion
 *
 * This engine consumes [RecurringPattern] and [PlannedExpense] lists from
 * [ForecastInputAssembler]. The **recurring lifecycle coordinator**
 * ([com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator])
 * is the canonical source of truth for expanding recurrence rules into
 * concrete occurrences. Any future migration that generates [PlannedExpense]
 * rows from occurrences (e.g. [com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService])
 * MUST ensure that the assembler deduplicates these against the recurring
 * patterns it already produces, preventing double-counting.
 * 
 * ## Block Party Algorithm
 * 
 * The Block Party feature provides daily budget tracking with intelligent forecasting:
 * 
 * ### Calculation Steps:
 * 1. **Calculate Monthly Totals**: Sum recurring expenses and planned expenses for the month
 * 2. **Determine Discretionary Pool**: budgetLimit - recurring - planned - goalReserves
 * 3. **Calculate Daily Rate**: discretionaryPool / daysInMonth
 * 4. **Pre-calculate Recurring Days**: Identify which days have recurring bills
 * 5. **Build Daily Targets**: baseRate + recurringOnDay + plannedOnDay
 * 6. **Group Expenses by Day**: Filter and group actual expenses for comparison
 * 
 * ### Status Types:
 * - TODAY: Current day
 * - BILL_DAY: Future day with recurring expense
 * - FUTURE: Future day without bills
 * - UNDER_BUDGET: Spent <= target
 * - OVER_BUDGET: Spent > target
 * - NO_DATA: No expenses recorded
 * 
 * ### Priority Weighting:
 * - MUST planned expenses: 100%
 * - LIKELY planned expenses: 70%
 * - OPTIONAL planned expenses: 0%
 */
@Singleton
class SynthesisEngine @Inject constructor(
    private val timeProvider: TimeProvider,
    /** @suppress Optional — when present, occurrences replace ad-hoc recurrence expansion in block-party calendar. */
    private val recurringOccurrenceDao: RecurringOccurrenceDao? = null
) {
    companion object {
        private const val TAG = "SynthesisEngine"
        private const val LIKELY_EXPENSE_WEIGHT = 0.7 // 70% weight for LIKELY planned expenses - middle ground
        private const val BIWEEKLY_CYCLE_DAYS = 14
        private const val BIWEEKLY_TOLERANCE_DAYS = 2
    }

    fun synthesize(
        input: ForecastInputAssembler.ForecastInput
    ): FinancialForecast {
        return synthesize(
            pastSumDaily = input.pastSumDaily,
            recurringPatterns = input.recurringPatterns,
            plannedExpenses = input.plannedExpenses,
            savingsGoals = input.savingsGoals,
            budgetStatuses = input.budgetStatuses,
            spendingPace = input.spendingPace
        )
    }

    fun synthesize(
        pastSumDaily: List<Double>,
        recurringPatterns: List<RecurringPattern>,
        plannedExpenses: List<PlannedExpense>,
        savingsGoals: List<SavingsGoal>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        spendingPace: SpendingPace
    ): FinancialForecast {
        return try {
            synthesizeInternal(pastSumDaily, recurringPatterns, plannedExpenses, savingsGoals, budgetStatuses, spendingPace)
        } catch (e: Exception) {
            val fallbackNow = timeProvider.now()
            Timber.e(e, "Error in synthesize")
            FinancialForecast(
                horizon = ForecastHorizon.REST_OF_MONTH,
                generatedAt = Instant.ofEpochMilli(fallbackNow),
                confidence = 0.0,
                components = ForecastComponents(
                    recurringExpenses = emptyList(),
                    plannedExpenses = emptyList(),
                    goalReserves = 0.0,
                    pastSpendingPoints = pastSumDaily,
                    projectedSpendingPoints = emptyList(),
                    totalCommitted = 0.0,
                    totalLikely = 0.0,
                    predictedDiscretionary = 0.0,
                    discretionaryBudget = 0.0,
                    riskLevel = RiskLevel.MEDIUM
                ),
                actionableInsights = emptyList()
            )
        }
    }

    private fun synthesizeInternal(
        pastSumDaily: List<Double>,
        recurringPatterns: List<RecurringPattern>,
        plannedExpenses: List<PlannedExpense>,
        savingsGoals: List<SavingsGoal>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        spendingPace: SpendingPace
    ): FinancialForecast {
        val now = timeProvider.now()
        val sanitizedPastSumDaily = sanitizePastSumDaily(pastSumDaily)
        
        // Fix: Use single Calendar instance to avoid inconsistent dates if crossing midnight
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(0)

        val (_, endOfMonthExclusive) = TimePeriodUtils.getMonthRange(now)
        val startOfToday = TimePeriodUtils.getStartOfDay(now)

        // 1. Calculate Committed (Highly likely/Automated/Must happen)
        val committedUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate < endOfMonthExclusive 
        }.sumOf { it.averageAmount }
        
        val committedPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.MUST && it.date >= startOfToday && it.date < endOfMonthExclusive
        }.sumOf { it.amount }

        val totalCommitted = committedUpcomingBills + committedPlanned

        // 2. Calculate Likely (Probable behavior)
        // Fix: Confidence Interval Gap (0.89-0.90 was missing)
        val likelyUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.70f && it.confidence < 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate < endOfMonthExclusive
        }.sumOf { it.averageAmount }
        
        val likelyPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.LIKELY && it.date >= startOfToday && it.date < endOfMonthExclusive
        }.sumOf { it.amount } * LIKELY_EXPENSE_WEIGHT
        
        val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
            when (pattern.frequency) {
                RecurrenceFrequency.IRREGULAR -> 0.0
                else -> RecurrenceCalculator.toMonthlyAmount(pattern.averageAmount, pattern.frequency)
            }
        }

        val typicalDailyDiscretionary = spendingPace.averageMonthlyTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth } 
            ?: (spendingPace.previousMonthTotal?.let { (it - monthlyRecurringTotal).coerceAtLeast(0.0) / daysInMonth })
            ?: 0.0
            
        val predictedDiscretionary = typicalDailyDiscretionary * daysRemaining
        val totalLikely = likelyUpcomingBills + likelyPlanned

        // 3. Goal Reserves
        // Strict goals are subtracted from "Available"
        // 3. Goal Reserves (Pro-rated for strict goals - LOG-019)
        val goalReserves = savingsGoals
            .filter { it.protectionLevel == GoalProtectionLevel.STRICT }
            .sumOf { goal ->
                 val remaining = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
                 if (remaining <= 0) 0.0
                 else {
                     val targetDate = goal.targetDate
                      if (targetDate == null || targetDate <= now) remaining // Due now or past due
                      else {
                          val msRemaining = targetDate - now
                          val daysRemainingInGoal = (msRemaining.toDouble() / TimeUnit.DAYS.toMillis(1).toDouble()).coerceAtLeast(1.0)
                          val targetMonthsRemaining = (daysRemainingInGoal / daysInMonth.toDouble()).coerceAtLeast(1.0)
                          val remainingMonthly = remaining / targetMonthsRemaining
                          // For this month specifically
                         remainingMonthly
                     }
                 }
            }

        // 4. Calculate Projected Timeline Points with date-based spikes
        val lastKnownTotal = sanitizedPastSumDaily.lastOrNull() ?: 0.0

        // Collect planned expenses with their dates (MUST=100%, LIKELY=70%)
        val plannedExpensesInRange = plannedExpenses.filter { it.date >= startOfToday && it.date < endOfMonthExclusive }
        
        // Reuse single Calendar instance for grouping
        val dayCalendar = Calendar.getInstance()
        val mustExpensesByDay = plannedExpensesInRange
            .filter { it.priority == PlannedExpensePriority.MUST }
            .groupBy { expense ->
                dayCalendar.apply { timeInMillis = expense.date }.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { it.value.sumOf { exp -> exp.amount } }
        
        val likelyExpensesByDay = plannedExpensesInRange
            .filter { it.priority == PlannedExpensePriority.LIKELY }
            .groupBy { expense ->
                dayCalendar.apply { timeInMillis = expense.date }.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { it.value.sumOf { exp -> exp.amount } * LIKELY_EXPENSE_WEIGHT }

        // Pre-compute running totals for O(n) projection instead of O(n²)
        val mustDays = mustExpensesByDay.keys.sorted()
        val likelyDays = likelyExpensesByDay.keys.sorted()
        var mustCumulative = 0.0
        var likelyCumulative = 0.0
        var mustIndex = 0
        var likelyIndex = 0
        
        val projectedPoints = (dayOfMonth..daysInMonth).map { targetDay ->
            // Add any expenses that occur on or before this day to running total
            while (mustIndex < mustDays.size && mustDays[mustIndex] <= targetDay) {
                mustCumulative += mustExpensesByDay[mustDays[mustIndex]] ?: 0.0
                mustIndex++
            }
            while (likelyIndex < likelyDays.size && likelyDays[likelyIndex] <= targetDay) {
                likelyCumulative += likelyExpensesByDay[likelyDays[likelyIndex]] ?: 0.0
                likelyIndex++
            }
            
            val daysFromNow = targetDay - dayOfMonth
            val discretionarySpending = typicalDailyDiscretionary * daysFromNow
            
            lastKnownTotal + discretionarySpending + mustCumulative + likelyCumulative
        }
        
        // 5. Calculate Discretionary (Available)
        val overallBudget = budgetStatuses.find { it.budgetCategoryId == null }?.budgetAmount ?: 0.0
        val categoryBudgetsSum = budgetStatuses.filter { it.budgetCategoryId != null }.sumOf { it.budgetAmount }
        val budgetLimit = if (overallBudget > 0) overallBudget else categoryBudgetsSum
        
        val spentSoFar = spendingPace.currentMonthSpent
        
        // Revised Formula: Limit - (Spent + Future Committed + Future Likely + Goal Reserves)
        // If budgetLimit is 0, we use a fallback or express "Unknown" state
        val projectedObligations = committedUpcomingBills + committedPlanned + likelyUpcomingBills + likelyPlanned
        
        val discretionaryBudget = if (budgetLimit > 0) {
            (budgetLimit - (spentSoFar + projectedObligations + goalReserves)).coerceAtLeast(0.0)
        } else {
            // If no budget is set, the discretionary "pool" isn't 0 (which looks like "No money left"),
            // it's effectively unlimited/unknown vs a goal. 
            // We'll return 0.0 for now but the RiskLevel will signal NO_BUDGET
            0.0
        }

        // 6. Determine Risk Level
        val riskLevel = determineRiskLevel(
            spendingPace, 
            budgetStatuses, 
            discretionaryBudget, 
            budgetLimit
        )

        // Dynamic Confidence Calculation based on data quality
        var forecastConfidence = 0.85
        // Reduce confidence if no budget or no baseline
        if (budgetLimit <= 0) forecastConfidence -= 0.15
        if (spendingPace.averageMonthlyTotal == null) forecastConfidence -= 0.10
        if (recurringPatterns.isEmpty()) forecastConfidence -= 0.05
        
        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.ofEpochMilli(now),
            confidence = forecastConfidence.coerceIn(0.1, 0.95), 
            components = ForecastComponents(
                recurringExpenses = recurringPatterns,
                plannedExpenses = plannedExpenses,
                goalReserves = goalReserves,
                pastSpendingPoints = sanitizedPastSumDaily,
                projectedSpendingPoints = projectedPoints,
                totalCommitted = totalCommitted,
                totalLikely = totalLikely,
                predictedDiscretionary = predictedDiscretionary,
                discretionaryBudget = discretionaryBudget,
                riskLevel = riskLevel
            ),
            actionableInsights = buildInsights(riskLevel, budgetStatuses, spendingPace, plannedExpenses, savingsGoals)
        )
    }

    fun calculateBlockPartyData(
        forecast: FinancialForecast,
        expenses: List<TransactionSummary>,
        dailySpending: List<Float>,
        budgetLimit: Double
    ): List<BlockPartyDay> {
        val now = timeProvider.now()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val (startOfMonth, endOfMonthExclusive) = TimePeriodUtils.getMonthRange(now)
        
        val components = forecast.components
        
        // 1. Calculate Monthly Totals for pro-rating (frequency-adjusted)
        val totalMonthlyRecurring = components.recurringExpenses.sumOf { pattern ->
            when (pattern.frequency) {
                RecurrenceFrequency.IRREGULAR -> 0.0
                else -> RecurrenceCalculator.toMonthlyAmount(pattern.averageAmount, pattern.frequency)
            }
        }
        
        // Filter planned expenses for this month only using timestamp range
        // MUST at 100%, LIKELY at 70%, OPTIONAL ignored
        val thisMonthPlanned = components.plannedExpenses.filter { it.date >= startOfMonth && it.date < endOfMonthExclusive }
        val totalMonthlyPlanned = thisMonthPlanned
            .filter { it.priority != PlannedExpensePriority.OPTIONAL }
            .sumOf { expense ->
                when (expense.priority) {
                    PlannedExpensePriority.MUST -> expense.amount
                    PlannedExpensePriority.LIKELY -> expense.amount * LIKELY_EXPENSE_WEIGHT
                    PlannedExpensePriority.OPTIONAL -> 0.0
                }
            }
        
        // Centralized Logic Gain: Factoring in Goal Reserves (Savings)
        val goalReserves = components.goalReserves
        
        // LOG-021: Fix - Use Discretionary Pool Formula correctly
        val discretionaryTotal = (budgetLimit - totalMonthlyRecurring - totalMonthlyPlanned - goalReserves).coerceAtLeast(0.0)
        val baseDiscretionaryRate = if (budgetLimit > 0) discretionaryTotal / daysInMonth else 0.0

        // Optimization: Group raw expenses by day once O(N) - use timestamp range filter
        // Pre-sort expenses by amount within each day for top 3 transactions
        val dayBucketCalendar = Calendar.getInstance()
        val expensesByDay = expenses.filter {
            it.date >= startOfMonth &&
                it.date < endOfMonthExclusive
        }
            .groupBy { expense ->
                dayBucketCalendar.apply { timeInMillis = expense.date }.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { (_, dayExpenses) -> 
                dayExpenses.sortedByDescending { it.amount }
            }

        // Optimization: Group planned expenses by day - use timestamp range filter
        val plannedByDay = thisMonthPlanned.groupBy { expense ->
            dayBucketCalendar.apply { timeInMillis = expense.date }.get(Calendar.DAY_OF_MONTH)
        }

        val dateCal = Calendar.getInstance().apply { timeInMillis = now }

        // Pre-calculate which days have recurring expenses.
        // When the occurrence DAO is available, use the canonical occurrence source
        // (PAID + PLANNED) instead of ad-hoc date matching.
        val recurringByDay = if (recurringOccurrenceDao != null) {
            buildRecurringByDayFromOccurrences(
                components.recurringExpenses,
                startOfMonth,
                endOfMonthExclusive,
                daysInMonth,
                recurringOccurrenceDao
            )
        } else {
            buildRecurringByDayLegacy(
                components.recurringExpenses,
                now,
                daysInMonth,
                dateCal
            )
        }

        return (1..daysInMonth).map { day ->
            dateCal.set(Calendar.DAY_OF_MONTH, day)
            dateCal.set(Calendar.HOUR_OF_DAY, 12)
            val dateMs = dateCal.timeInMillis

            // 1. Use pre-calculated recurring expenses for this day
            val recurringItemsOnDay = recurringByDay[day] ?: emptyList()
            val recurringOnDay = recurringItemsOnDay.sumOf { it.averageAmount }
            val recurringNames = recurringItemsOnDay.map { it.merchantName }

            // 2. Identify Planned on this day
            // Apply priority weighting: MUST=100%, LIKELY=70%, OPTIONAL=0%
            val plannedItemsOnDay = plannedByDay[day] ?: emptyList()
            val plannedOnDay = plannedItemsOnDay
                .filter { it.priority != PlannedExpensePriority.OPTIONAL }
                .sumOf { expense ->
                    when (expense.priority) {
                        PlannedExpensePriority.MUST -> expense.amount
                        PlannedExpensePriority.LIKELY -> expense.amount * LIKELY_EXPENSE_WEIGHT
                        PlannedExpensePriority.OPTIONAL -> 0.0
                    }
                }
            val plannedNames = plannedItemsOnDay.map { it.description }

            val dailyTarget = baseDiscretionaryRate + recurringOnDay + plannedOnDay
            val actualFromHistory = dailySpending.getOrNull(day - 1)?.toDouble()
            // SAFE: Callers (ComputeDashboardWidgetsUseCase) must normalize expenses via
            // AnalyticsCurrencyNormalizer before invoking SynthesisEngine.
            // If adding a new caller, normalize first.
            val actualFromExpenses = expensesByDay[day]?.sumOf { it.effectiveAmount }
            // Fallback to per-day expense aggregation when daily history is unavailable.
            val actual = actualFromHistory ?: actualFromExpenses
            val actualOrZero = actual ?: 0.0

            val dayTransactions = (expensesByDay[day] ?: emptyList())
                .take(3)

            val status = when {
                day == dayOfMonth -> BlockPartyStatus.TODAY
                day > dayOfMonth -> {
                    if (recurringItemsOnDay.isNotEmpty()) BlockPartyStatus.BILL_DAY
                    else BlockPartyStatus.FUTURE
                }
                actual == null -> BlockPartyStatus.NO_DATA
                actual <= dailyTarget -> BlockPartyStatus.UNDER_BUDGET
                else -> BlockPartyStatus.OVER_BUDGET
            }

            BlockPartyDay(
                dayOfMonth = day,
                date = dateMs,
                actualSpent = actualOrZero,
                targetBudget = dailyTarget,
                isToday = day == dayOfMonth,
                status = status,
                baseTarget = baseDiscretionaryRate,
                recurringImpact = recurringOnDay,
                plannedImpact = plannedOnDay,
                recurringItems = recurringNames,
                plannedItems = plannedNames,
                topTransactions = dayTransactions
            )
        }
    }

    private fun isRecurringExpected(
        pattern: RecurringPattern, 
        dateCal: Calendar, 
        anchorCal: Calendar
    ): Boolean {
        val anchor = pattern.nextExpectedDate
        val frequency = pattern.frequency
        
        anchorCal.timeInMillis = anchor
        
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> {
                dateCal.get(Calendar.DAY_OF_WEEK) == anchorCal.get(Calendar.DAY_OF_WEEK)
            }
            RecurrenceFrequency.BIWEEKLY -> {
                val daysDiff = TimePeriodUtils.daysBetween(anchor, dateCal.timeInMillis)
                val mod = Math.floorMod(daysDiff, BIWEEKLY_CYCLE_DAYS)
                val distanceToCycle = minOf(mod, BIWEEKLY_CYCLE_DAYS - mod)
                distanceToCycle <= BIWEEKLY_TOLERANCE_DAYS
            }
            RecurrenceFrequency.MONTHLY -> {
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                val targetDay = dateCal.get(Calendar.DAY_OF_MONTH)
                val maxDayInTargetMonth = dateCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                when {
                    anchorDay > maxDayInTargetMonth -> targetDay == maxDayInTargetMonth
                    else -> targetDay == anchorDay
                }
            }
            RecurrenceFrequency.QUARTERLY -> {
                 // Check if this day-of-month matches the anchor AND the month is a quarter boundary from anchor
                 val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                 val targetDay = dateCal.get(Calendar.DAY_OF_MONTH)
                 val maxDayInTargetMonth = dateCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                 val dayMatch = if (anchorDay > maxDayInTargetMonth) targetDay == maxDayInTargetMonth else targetDay == anchorDay
                 val monthDiff = (dateCal.get(Calendar.YEAR) - anchorCal.get(Calendar.YEAR)) * 12 +
                         (dateCal.get(Calendar.MONTH) - anchorCal.get(Calendar.MONTH))
                 dayMatch && monthDiff >= 0 && monthDiff % 3 == 0
            }
            RecurrenceFrequency.SEMI_ANNUALLY -> {
                 val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                 val targetDay = dateCal.get(Calendar.DAY_OF_MONTH)
                 val maxDayInTargetMonth = dateCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                 val dayMatch = if (anchorDay > maxDayInTargetMonth) targetDay == maxDayInTargetMonth else targetDay == anchorDay
                 val monthDiff = (dateCal.get(Calendar.YEAR) - anchorCal.get(Calendar.YEAR)) * 12 +
                         (dateCal.get(Calendar.MONTH) - anchorCal.get(Calendar.MONTH))
                 dayMatch && monthDiff >= 0 && monthDiff % 6 == 0
            }
            RecurrenceFrequency.ANNUALLY -> {
                 val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                 val targetDay = dateCal.get(Calendar.DAY_OF_MONTH)
                 val maxDayInTargetMonth = dateCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                 val dayMatch = if (anchorDay > maxDayInTargetMonth) targetDay == maxDayInTargetMonth else targetDay == anchorDay
                 val monthMatch = dateCal.get(Calendar.MONTH) == anchorCal.get(Calendar.MONTH)
                 dayMatch && monthMatch
            }
            else -> false 
        }
    }

    /**
     * Builds the [recurringByDay] map from materialised [RecurringOccurrence] rows.
     * This is the canonical source for manual recurring rules.
     *
     * Detected-only patterns (id == null) are handled via the legacy ad-hoc
     * matcher so they still appear in the block-party calendar.
     */
    private fun buildRecurringByDayFromOccurrences(
        recurringPatterns: List<RecurringPattern>,
        monthStart: Long,
        monthEnd: Long,
        daysInMonth: Int,
        occurrenceDao: RecurringOccurrenceDao
    ): Map<Int, List<RecurringPattern>> {
        val result = mutableMapOf<Int, MutableList<RecurringPattern>>()

        // ── Occurrence path for manual rules ────────────────────────────────
        val manualIds = recurringPatterns
            .filter { it.id != null }
            .mapNotNull { it.id }
            .toSet()

        if (manualIds.isNotEmpty()) {
            // runBlocking is acceptable here because this is called from a
            // non-suspend context (calculateBlockPartyData) and the DAO query
            // is purely read-only / fast.
            val occurrences = runBlocking {
                occurrenceDao.getByDateRange(monthStart, monthEnd)
            }.filter {
                    it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
                        it.sourceId in manualIds &&
                        (it.status == "PLANNED" || it.status == "PAID")
                }
            val dayCal = Calendar.getInstance()
            for (occ in occurrences) {
                val day = dayCal.apply { timeInMillis = occ.dueDate }.get(Calendar.DAY_OF_MONTH)
                if (day in 1..daysInMonth) {
                    result.getOrPut(day) { mutableListOf() }.add(occ.toRecurringPattern())
                }
            }
        }

        // ── Legacy path for detected-only patterns ──────────────────────────
        val detectedPatterns = recurringPatterns.filter { it.id == null }
        if (detectedPatterns.isNotEmpty()) {
            val dateCal = Calendar.getInstance()
            val anchorCal = Calendar.getInstance()
            for (day in 1..daysInMonth) {
                dateCal.set(Calendar.DAY_OF_MONTH, day)
                dateCal.set(Calendar.HOUR_OF_DAY, 12)
                val onDay = detectedPatterns.filter { isRecurringExpected(it, dateCal, anchorCal) }
                if (onDay.isNotEmpty()) {
                    result.getOrPut(day) { mutableListOf() }.addAll(onDay)
                }
            }
        }

        return result
    }

    /**
     * Legacy fallback: builds [recurringByDay] using ad-hoc
     * [isRecurringExpected] matching for ALL patterns. Used when the occurrence
     * DAO is not available (e.g. in unit tests).
     */
    private fun buildRecurringByDayLegacy(
        recurringPatterns: List<RecurringPattern>,
        now: Long,
        daysInMonth: Int,
        dateCal: Calendar
    ): Map<Int, List<RecurringPattern>> {
        val result = mutableMapOf<Int, List<RecurringPattern>>()
        val anchorCal = Calendar.getInstance().apply { timeInMillis = now }
        for (day in 1..daysInMonth) {
            dateCal.set(Calendar.DAY_OF_MONTH, day)
            dateCal.set(Calendar.HOUR_OF_DAY, 12)
            val recurringOnDay = recurringPatterns.filter {
                isRecurringExpected(it, dateCal, anchorCal)
            }
            if (recurringOnDay.isNotEmpty()) {
                result[day] = recurringOnDay
            }
        }
        return result
    }

    private fun determineRiskLevel(
        pace: SpendingPace,
        budgets: List<BudgetStatusSnapshot>,
        discretionary: Double,
        limit: Double
    ): RiskLevel {
        val criticalBudgets = budgets.count { it.healthStatus == BudgetHealthStatus.CRITICAL || it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val overPace = pace.paceStatus == PaceStatus.OVER_PACE
        
        // Ratio of discretionary to total budget
        val bufferRatio = if (limit > 0) discretionary / limit else 0.0

        // If no budget is set, we can't really say it's CRITICAL based on ratio.
        // We should check if they are simply overspending their "pace"
        if (limit <= 0) {
            return if (overPace) RiskLevel.MEDIUM else RiskLevel.LOW
        }

        return when {
            // Priority 1: Critical Budget Issues or Severe Overspending with no buffer
            criticalBudgets > 0 -> RiskLevel.CRITICAL
            overPace && bufferRatio <= 0.05 -> RiskLevel.CRITICAL
            
            // Priority 2: High Risk (Overspending or Low Buffer)
            overPace -> RiskLevel.HIGH // If overPace but buffer > 0.05
            bufferRatio <= 0.1 -> RiskLevel.HIGH
            
            // Priority 3: Medium Risk
            bufferRatio <= 0.2 -> RiskLevel.MEDIUM
            
            // Priority 4: Low Risk
            else -> RiskLevel.LOW
        }
    }

    private fun buildInsights(
        risk: RiskLevel,
        budgets: List<BudgetStatusSnapshot>,
        pace: SpendingPace,
        planned: List<PlannedExpense>,
        goals: List<SavingsGoal>
    ): List<UiText> {
        val insights = mutableListOf<UiText>()
        if (pace.paceStatus == PaceStatus.OVER_PACE) {
            insights.add(UiText.fromKey(DomainTextKeys.SYNTHESIS_SPENDING_PACE_HIGHER))
        }
        val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        if (exceeded > 0) {
            insights.add(UiText.fromKey(DomainTextKeys.SYNTHESIS_BUDGETS_EXCEEDED_FORMAT, exceeded))
        }
        
        val strictGoalCount = goals.count { it.protectionLevel == GoalProtectionLevel.STRICT }
        if (strictGoalCount > 0) {
            insights.add(UiText.fromKey(DomainTextKeys.SYNTHESIS_STRICT_SAVINGS_GOALS_ACTIVE_FORMAT, strictGoalCount))
        }
        
        val mustPlannedCount = planned.count { it.priority == PlannedExpensePriority.MUST }
        if (mustPlannedCount > 0) {
            insights.add(UiText.fromKey(DomainTextKeys.SYNTHESIS_MUST_PAY_PLANNED_EXPENSES_FORMAT, mustPlannedCount))
        }
        
        return insights
    }

    private fun sanitizePastSumDaily(pastSumDaily: List<Double>): List<Double> {
        var lastFiniteValue = 0.0
        return pastSumDaily.map { point ->
            if (point.isFinite()) {
                lastFiniteValue = point
                point
            } else {
                lastFiniteValue
            }
        }
    }
}
