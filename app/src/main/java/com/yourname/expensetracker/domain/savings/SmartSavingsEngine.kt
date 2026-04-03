package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SavingsRecommendation(
    val safeAmount: Double,
    val confidence: Double,
    val impact: String,
    val source: RecommendationSource
)

enum class RecommendationSource {
    BUDGET_SURPLUS,
    SPENDING_PACE,
    MONTE_CARLO,
    INCOME_DEPOSIT,
    ROUND_UP
}

@Singleton
class SmartSavingsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val budgetCalculator: BudgetCalculator,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun calculateSafeToSaveAmount(
        goal: SavingsGoal,
        timeHorizon: TimeHorizon = TimeHorizon.MONTH
    ): SavingsRecommendation {
        // Get current financial state
        val now = timeProvider.now()
        val budgetStatuses = budgetRepository.getBudgetStatuses().first()
        
        // Calculate 1: Budget surplus
        val budgetSurplus = calculateBudgetSurplus(budgetStatuses)
        
        // Calculate 2: Spending pace analysis
        val spendingPace = analyzeSpendingPace()
        
        // Calculate 3: Monte Carlo simulation
        val monteCarloResult = runMonteCarloSimulation(goal, now)
        
        // Combine recommendations with weights
        val safeAmount = calculateWeightedSafeAmount(
            budgetSurplus = budgetSurplus,
            spendingPace = spendingPace,
            monteCarloResult = monteCarloResult
        )
        
        return SavingsRecommendation(
            safeAmount = safeAmount,
            confidence = calculateConfidence(budgetSurplus, spendingPace, monteCarloResult),
            impact = generateImpactMessage(safeAmount, goal),
            source = determinePrimarySource(budgetSurplus, spendingPace, monteCarloResult)
        )
    }
    
    private fun calculateBudgetSurplus(budgetStatuses: List<BudgetStatus>): Double {
        var totalSurplus = 0.0
        for (status in budgetStatuses) {
            if (status.remainingAmount > 0) {
                // Only count 50% of surplus to be conservative
                totalSurplus += status.remainingAmount * 0.5
            }
        }
        return totalSurplus
    }
    
    private suspend fun analyzeSpendingPace(): Double {
        val now = timeProvider.now()
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        
        // Get current month's spending
        val monthStart = (calendar.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val expenses = expenseRepository.getExpensesBetween(monthStart, now)
        var totalSpent = 0.0
        for (expense in expenses) {
            totalSpent += expense.amount
        }
        
        // Calculate days elapsed and month length
        
        // If spending slower than average pace, suggest saving the difference
        val averageDailySpending = totalSpent / dayOfMonth
        val projectedMonthTotal = averageDailySpending * daysInMonth
        
        // Get typical monthly spending from history
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val last3MonthsExpenses = expenseRepository.getExpensesBetween(threeMonthsAgo, now)
        var total3MonthSpending = 0.0
        for (expense in last3MonthsExpenses) {
            total3MonthSpending += expense.amount
        }
        val avgMonthlySpending = total3MonthSpending / 3.0
        
        // If projected spending is less than average, we can save the difference
        return if (projectedMonthTotal < avgMonthlySpending) {
            (avgMonthlySpending - projectedMonthTotal) * 0.3 // Conservative 30%
        } else {
            0.0
        }
    }
    
    private suspend fun runMonteCarloSimulation(goal: SavingsGoal, now: Long): Double {
        // Calculate current spending to date
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        val monthStart = calendar.apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val expenses = expenseRepository.getExpensesBetween(monthStart, now)
        var spentToDate = 0.0
        for (expense in expenses) {
            spentToDate += expense.amount
        }
        
        // Assume no known upcoming expenses for this calculation
        val knownUpcoming = 0.0
        
        // Run Monte Carlo
        val result = monteCarloSimulator.simulate(
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            budgetAmount = null // No budget constraint
        )
        
        return result?.percentile50?.let { projectedSpending ->
            // If projected spending is under control, suggest saving 20% of discretionary
            val discretionary = 500.0 // Assume €500 discretionary (customizable)
            val remaining = discretionary - (projectedSpending * 0.3) // 30% of projected
            if (remaining > 0) remaining * 0.2 else 0.0
        } ?: 0.0
    }
    
    private fun calculateWeightedSafeAmount(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double
    ): Double {
        // Weighted combination (conservative approach)
        // Budget surplus: 40%
        // Spending pace: 30%
        // Monte Carlo: 30%
        val weighted = budgetSurplus * 0.4 + spendingPace * 0.3 + monteCarloResult * 0.3
        
        // Cap at €200 per recommendation to be safe
        return minOf(weighted, 200.0).coerceAtLeast(0.0)
    }
    
    private fun calculateConfidence(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double
    ): Double {
        // Higher confidence if multiple sources agree
        val sources = listOf(budgetSurplus, spendingPace, monteCarloResult).count { it > 10.0 }
        return when (sources) {
            3 -> 0.95
            2 -> 0.80
            1 -> 0.60
            else -> 0.40
        }
    }
    
    private fun generateImpactMessage(amount: Double, goal: SavingsGoal): String {
        val remaining = goal.targetAmount - goal.currentAmount
        val daysToGoal = if (amount > 0) (remaining / amount * 30).toInt() else Int.MAX_VALUE
        
        return when {
            daysToGoal <= 30 -> "You'll reach your goal in $daysToGoal days!"
            daysToGoal <= 90 -> "On track to reach goal in ${daysToGoal / 30} months"
            else -> "Steady progress toward your €${String.format("%.0f", goal.targetAmount)} goal"
        }
    }
    
    private fun determinePrimarySource(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double
    ): RecommendationSource {
        return when {
            budgetSurplus > spendingPace && budgetSurplus > monteCarloResult -> RecommendationSource.BUDGET_SURPLUS
            spendingPace > budgetSurplus && spendingPace > monteCarloResult -> RecommendationSource.SPENDING_PACE
            else -> RecommendationSource.MONTE_CARLO
        }
    }
    
    enum class TimeHorizon {
        WEEK, MONTH, QUARTER
    }
}
