package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.*
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SynthesisEngine @Inject constructor() {

    fun synthesize(
        pastSumDaily: List<Double>,
        recurringPatterns: List<RecurringPattern>,
        plannedExpenses: List<PlannedExpense>,
        savingsGoals: List<SavingsGoal>,
        budgetStatuses: List<BudgetStatus>,
        spendingPace: SpendingPace
    ): FinancialForecast {
        val now = System.currentTimeMillis()
        
        // Fix: Use single Calendar instance to avoid inconsistent dates if crossing midnight
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)

        val endOfMonthCal = (calendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, daysInMonth)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfMonth = endOfMonthCal.timeInMillis

        val startOfTodayCal = (calendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = startOfTodayCal.timeInMillis

        // 1. Calculate Committed (Highly likely/Automated/Must happen)
        val committedUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth 
        }.sumOf { it.averageAmount }
        
        val committedPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.MUST && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }

        val totalCommitted = committedUpcomingBills + committedPlanned

        // 2. Calculate Likely (Probable behavior)
        // Fix: Confidence Interval Gap (0.89-0.90 was missing)
        val likelyUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.70f && it.confidence < 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth
        }.sumOf { it.averageAmount }
        
        val likelyPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.LIKELY && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }
        
        val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
            when (pattern.frequency) {
                RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 7.0)
                RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (daysInMonth.toDouble() / 14.0)
                RecurrenceFrequency.MONTHLY -> pattern.averageAmount
                RecurrenceFrequency.QUARTERLY -> pattern.averageAmount / 3.0
                RecurrenceFrequency.SEMI_ANNUALLY -> pattern.averageAmount / 6.0
                RecurrenceFrequency.ANNUALLY -> pattern.averageAmount / 12.0
                else -> 0.0
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
                         val daysRemainingInGoal = (msRemaining / (24 * 60 * 60 * 1000.0)).coerceAtLeast(1.0)
                         val targetMonthsRemaining = (daysRemainingInGoal / daysInMonth.toDouble()).coerceAtLeast(1.0)
                         val remainingMonthly = remaining / targetMonthsRemaining
                         // For this month specifically
                         remainingMonthly
                     }
                 }
            }

        // 4. Calculate Projected Timeline Points
        val lastKnownTotal = pastSumDaily.lastOrNull() ?: 0.0
        val dailyProjectionRate = (totalLikely + predictedDiscretionary) / daysRemaining
        
        val projectedPoints = (1..daysRemaining).map { dayIndex ->
            lastKnownTotal + (dailyProjectionRate * dayIndex)
        }
        
        // 5. Calculate Discretionary (Available)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }?.budget?.amount ?: 0.0
        val categoryBudgetsSum = budgetStatuses.filter { it.budget.categoryId != null }.sumOf { it.budget.amount }
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
            generatedAt = Instant.now(),
            confidence = forecastConfidence.coerceIn(0.1, 0.95), 
            components = ForecastComponents(
                recurringExpenses = recurringPatterns,
                plannedExpenses = plannedExpenses,
                goalReserves = goalReserves,
                pastSpendingPoints = pastSumDaily,
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
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        dailySpending: List<Float>,
        budgetLimit: Double
    ): List<BlockPartyDay> {
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)
        
        val components = forecast.components
        
        // 1. Calculate Monthly Totals for pro-rating
        val totalMonthlyRecurring = components.recurringExpenses.sumOf { it.averageAmount }
        
        // Filter planned expenses for this month only
        val thisMonthPlanned = components.plannedExpenses.filter { 
            val pCal = Calendar.getInstance().apply { timeInMillis = it.date }
            pCal.get(Calendar.MONTH) == currentMonth && pCal.get(Calendar.YEAR) == currentYear
        }
        val totalMonthlyPlanned = thisMonthPlanned.sumOf { it.amount }
        
        // Centralized Logic Gain: Factoring in Goal Reserves (Savings)
        val goalReserves = components.goalReserves
        
        // LOG-021: Fix - Use Discretionary Pool Formula correctly
        val discretionaryTotal = (budgetLimit - totalMonthlyRecurring - totalMonthlyPlanned - goalReserves).coerceAtLeast(0.0)
        val baseDiscretionaryRate = if (budgetLimit > 0) discretionaryTotal / daysInMonth else 0.0

        // Optimization: Group raw expenses by day once O(N)
        val expensesByDay = expenses.filter { 
            val eCal = Calendar.getInstance().apply { timeInMillis = it.date }
            eCal.get(Calendar.MONTH) == currentMonth && eCal.get(Calendar.YEAR) == currentYear
        }.groupBy { 
            val resCal = Calendar.getInstance().apply { timeInMillis = it.date }
            resCal.get(Calendar.DAY_OF_MONTH)
        }

        // Optimization: Group planned expenses by day
        val plannedByDay = thisMonthPlanned.groupBy { 
            val resCal = Calendar.getInstance().apply { timeInMillis = it.date }
            resCal.get(Calendar.DAY_OF_MONTH)
        }

        val dateCal = Calendar.getInstance()
        val anchorCal = Calendar.getInstance()

        return (1..daysInMonth).map { day ->
            dateCal.set(Calendar.DAY_OF_MONTH, day)
            dateCal.set(Calendar.HOUR_OF_DAY, 12)
            val dateMs = dateCal.timeInMillis

            // 1. Identify Recurring on this day
            val recurringItemsOnDay = components.recurringExpenses.filter { 
                isRecurringExpected(it, dateCal, anchorCal) 
            }
            val recurringOnDay = recurringItemsOnDay.sumOf { it.averageAmount }
            val recurringNames = recurringItemsOnDay.map { it.merchantName }

            // 2. Identify Planned on this day
            val plannedItemsOnDay = plannedByDay[day] ?: emptyList()
            val plannedOnDay = plannedItemsOnDay.sumOf { it.amount }
            val plannedNames = plannedItemsOnDay.map { it.description }

            val dailyTarget = baseDiscretionaryRate + recurringOnDay + plannedOnDay
            val actual = if (day <= dailySpending.size) dailySpending[day - 1].toDouble() else 0.0

            val dayTransactions = (expensesByDay[day] ?: emptyList())
                .sortedByDescending { it.amount }
                .take(3)

            val status = when {
                day == dayOfMonth -> BlockPartyStatus.TODAY
                day > dayOfMonth -> {
                    if (recurringItemsOnDay.isNotEmpty()) BlockPartyStatus.BILL_DAY
                    else BlockPartyStatus.FUTURE
                }
                actual <= dailyTarget * 1.1 -> BlockPartyStatus.UNDER_BUDGET
                else -> BlockPartyStatus.OVER_BUDGET
            }

            BlockPartyDay(
                dayOfMonth = day,
                date = dateMs,
                actualSpent = actual,
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
                 val diff = dateCal.timeInMillis - anchor
                 val daysDiff = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)
                 (daysDiff % 14L == 0L)
            }
            RecurrenceFrequency.MONTHLY -> {
                dateCal.get(Calendar.DAY_OF_MONTH) == anchorCal.get(Calendar.DAY_OF_MONTH)
            }
            else -> false 
        }
    }

    private fun determineRiskLevel(
        pace: SpendingPace,
        budgets: List<BudgetStatus>,
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
            overPace && bufferRatio < 0.05 -> RiskLevel.CRITICAL
            
            // Priority 2: High Risk (Overspending or Low Buffer)
            overPace -> RiskLevel.HIGH // If overPace but buffer > 0.05
            bufferRatio < 0.1 -> RiskLevel.HIGH
            
            // Priority 3: Medium Risk
            bufferRatio < 0.2 -> RiskLevel.MEDIUM
            
            // Priority 4: Low Risk
            else -> RiskLevel.LOW
        }
    }

    private fun buildInsights(
        risk: RiskLevel,
        budgets: List<BudgetStatus>,
        pace: SpendingPace,
        planned: List<PlannedExpense>,
        goals: List<SavingsGoal>
    ): List<String> {
        val insights = mutableListOf<String>()
        if (pace.paceStatus == PaceStatus.OVER_PACE) insights.add("Spending pace is higher than usual.")
        val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        if (exceeded > 0) insights.add("$exceeded budgets exceeded.")
        
        val strictGoalCount = goals.count { it.protectionLevel == GoalProtectionLevel.STRICT }
        if (strictGoalCount > 0) insights.add("$strictGoalCount strict savings goals active.")
        
        val mustPlannedCount = planned.count { it.priority == PlannedExpensePriority.MUST }
        if (mustPlannedCount > 0) insights.add("$mustPlannedCount must-pay planned expenses this month.")
        
        return insights
    }
}
