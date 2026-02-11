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
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)

        val endOfMonthCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, daysInMonth)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfMonth = endOfMonthCal.timeInMillis

        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 1. Calculate Committed (Highly likely/Automated/Must happen)
        val committedUpcomingBills = recurringPatterns.filter { 
            it.confidence >= 0.90f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth 
        }.sumOf { it.averageAmount }
        
        val committedPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.MUST && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }

        val totalCommitted = committedUpcomingBills + committedPlanned

        // 2. Calculate Likely (Probable behavior)
        val likelyUpcomingBills = recurringPatterns.filter { 
            it.confidence in 0.70f..0.89f && it.nextExpectedDate >= startOfToday && it.nextExpectedDate <= endOfMonth
        }.sumOf { it.averageAmount }
        
        val likelyPlanned = plannedExpenses.filter {
            it.priority == PlannedExpensePriority.LIKELY && it.date >= startOfToday && it.date <= endOfMonth
        }.sumOf { it.amount }
        
        val monthlyRecurringTotal = recurringPatterns.sumOf { pattern ->
            when (pattern.frequency) {
                RecurrenceFrequency.WEEKLY -> pattern.averageAmount * (30.0 / 7.0)
                RecurrenceFrequency.BIWEEKLY -> pattern.averageAmount * (30.0 / 14.0)
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
                         val monthsRemaining = (msRemaining / (30.0 * 24 * 60 * 60 * 1000)).coerceAtLeast(1.0)
                         remaining / monthsRemaining
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

        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.now(),
            confidence = 0.85, 
            components = ForecastComponents(
                recurringExpenses = recurringPatterns,
                plannedExpenses = plannedExpenses,
                goalReserves = goalReserves,
                projectedCategorySpending = emptyMap(),
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
