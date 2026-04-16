package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
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

data class GoalSavingsRecommendation(
    val goal: SavingsGoal,
    val recommendation: SavingsRecommendation
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
    private val categoryRepository: CategoryRepository,
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
        return calculatePortfolioRecommendations(listOf(goal), timeHorizon)
            .firstOrNull()
            ?.recommendation
            ?: SavingsRecommendation(
                safeAmount = 0.0,
                confidence = 0.40,
                impact = generateImpactMessage(0.0, goal, timeHorizon),
                source = RecommendationSource.BUDGET_SURPLUS
            )
    }

    suspend fun calculatePortfolioRecommendations(
        goals: List<SavingsGoal>,
        timeHorizon: TimeHorizon = TimeHorizon.MONTH
    ): List<GoalSavingsRecommendation> {
        val incompleteGoals = goals.filter { goal ->
            (goal.targetAmount - goal.currentAmount) > 0.0
        }

        if (incompleteGoals.isEmpty()) {
            return emptyList()
        }

        val portfolioRecommendation = calculatePortfolioSafeToSaveAmount(
            sampleGoal = incompleteGoals.first(),
            timeHorizon = timeHorizon
        )
        val allocations = allocateAcrossGoals(incompleteGoals, portfolioRecommendation.safeAmount)

        return incompleteGoals.mapIndexed { index, goal ->
            val allocatedAmount = allocations.getOrElse(index) { 0.0 }
            GoalSavingsRecommendation(
                goal = goal,
                recommendation = portfolioRecommendation.copy(
                    safeAmount = allocatedAmount,
                    impact = generateImpactMessage(allocatedAmount, goal, timeHorizon)
                )
            )
        }
    }
    
    private fun calculateBudgetSurplus(budgetStatuses: List<BudgetStatus>): Double {
        val scopedStatuses = budgetStatuses
            .let { allStatuses ->
                val overallStatuses = allStatuses.filter { it.budget.categoryId == null }
                if (overallStatuses.isNotEmpty()) overallStatuses else allStatuses
            }
            .filter { it.remainingAmount > 0.0 }

        var totalSurplus = 0.0
        for (status in scopedStatuses) {
            // Only count 50% of surplus to be conservative
            totalSurplus += status.remainingAmount * 0.5
        }
        return totalSurplus
    }

    private suspend fun calculatePortfolioSafeToSaveAmount(
        sampleGoal: SavingsGoal,
        timeHorizon: TimeHorizon
    ): SavingsRecommendation {
        val now = timeProvider.now()
        val budgetStatuses = budgetRepository.getBudgetStatuses().first()

        val budgetSurplus = calculateBudgetSurplus(budgetStatuses)
        val spendingPace = analyzeSpendingPace(timeHorizon)
        val monteCarloResult = runMonteCarloSimulation(sampleGoal, now, timeHorizon)
        val safeAmount = calculateWeightedSafeAmount(
            budgetSurplus = budgetSurplus,
            spendingPace = spendingPace,
            monteCarloResult = monteCarloResult,
            timeHorizon = timeHorizon
        )

        return SavingsRecommendation(
            safeAmount = safeAmount,
            confidence = calculateConfidence(budgetSurplus, spendingPace, monteCarloResult),
            impact = "Portfolio recommendation ready",
            source = determinePrimarySource(budgetSurplus, spendingPace, monteCarloResult)
        )
    }

    private fun allocateAcrossGoals(
        goals: List<SavingsGoal>,
        totalSafeAmount: Double
    ): List<Double> {
        if (goals.isEmpty() || totalSafeAmount <= 0.0) {
            return List(goals.size) { 0.0 }
        }

        val allocations = MutableList(goals.size) { 0.0 }
        val remainingGaps = goals
            .map { (it.targetAmount - it.currentAmount).coerceAtLeast(0.0) }
            .toMutableList()
        val weights = goals.map { calculateAllocationWeight(it) }
        var remainingSafeAmount = totalSafeAmount
        val activeIndexes = goals.indices.toMutableSet()

        while (remainingSafeAmount > 0.01 && activeIndexes.isNotEmpty()) {
            val totalWeight = activeIndexes.sumOf { index -> weights[index] }
            if (totalWeight <= 0.0) {
                break
            }

            var allocatedThisRound = 0.0
            val filledIndexes = mutableSetOf<Int>()
            for (index in activeIndexes) {
                val proposedAmount = remainingSafeAmount * (weights[index] / totalWeight)
                val cappedAmount = minOf(proposedAmount, remainingGaps[index])
                if (cappedAmount > 0.0) {
                    allocations[index] += cappedAmount
                    remainingGaps[index] -= cappedAmount
                    allocatedThisRound += cappedAmount
                }
                if (remainingGaps[index] <= 0.01) {
                    filledIndexes += index
                }
            }

            if (allocatedThisRound <= 0.0) {
                break
            }

            remainingSafeAmount -= allocatedThisRound
            activeIndexes.removeAll(filledIndexes)
        }

        return allocations.mapIndexed { index, amount ->
            amount.coerceAtMost((goals[index].targetAmount - goals[index].currentAmount).coerceAtLeast(0.0))
        }
    }

    private fun calculateAllocationWeight(goal: SavingsGoal): Double {
        val remainingGap = (goal.targetAmount - goal.currentAmount).coerceAtLeast(0.0)
        if (remainingGap <= 0.0) {
            return 0.0
        }

        val urgencyMultiplier = goal.targetDate?.let { targetDate ->
            val millisUntilTarget = targetDate - timeProvider.now()
            val daysUntilTarget = millisUntilTarget / (24.0 * 60 * 60 * 1000)
            when {
                daysUntilTarget <= 30.0 -> 2.0
                daysUntilTarget <= 90.0 -> 1.5
                daysUntilTarget <= 180.0 -> 1.25
                else -> 1.0
            }
        } ?: 1.0

        return remainingGap * urgencyMultiplier
    }
    
    private suspend fun analyzeSpendingPace(timeHorizon: TimeHorizon): Double {
        val now = timeProvider.now()
        val dayOfMonth = TimePeriodUtils.getDayOfMonth(now).coerceAtLeast(1)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
        
        // Get current month's spending
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        
        val expenses = expenseRepository.getExpensesBetween(monthStart, now)
        val totalSpent = expenses
            .asSequence()
            .filter { it.transactionType.toDomain() == DomainTransactionType.PURCHASE && !it.isNotMine }
            .sumOf { it.effectiveAmount }
        
        // Calculate days elapsed and month length
        
        // If spending slower than average pace, suggest saving the difference
        val averageDailySpending = totalSpent / dayOfMonth
        val projectedMonthTotal = averageDailySpending * daysInMonth
        
        // Get typical monthly spending from history
        val historyDays = when (timeHorizon) {
            TimeHorizon.WEEK -> 28L
            TimeHorizon.MONTH -> 90L
            TimeHorizon.QUARTER -> 365L
        }
        val horizonMonths = when (timeHorizon) {
            TimeHorizon.WEEK -> 0.25
            TimeHorizon.MONTH -> 1.0
            TimeHorizon.QUARTER -> 3.0
        }

        val historyStart = now - (historyDays * 24 * 60 * 60 * 1000)
        val historyExpenses = expenseRepository.getExpensesBetween(historyStart, now)
            .asSequence()
            .filter { it.transactionType.toDomain() == DomainTransactionType.PURCHASE && !it.isNotMine }
            .toList()

        val totalHistorySpending = historyExpenses.sumOf { it.effectiveAmount }
        val monthlyBaselineSpending = if (historyDays > 0) {
            totalHistorySpending / historyDays.toDouble() * 30.0
        } else {
            0.0
        }
        val horizonBaselineSpending = monthlyBaselineSpending * horizonMonths
        val projectedHorizonTotal = projectedMonthTotal * horizonMonths
        
        // If projected spending is less than average, we can save the difference
        return if (projectedHorizonTotal < horizonBaselineSpending) {
            (horizonBaselineSpending - projectedHorizonTotal) * 0.3 // Conservative 30%
        } else {
            0.0
        }
    }
    
    private suspend fun runMonteCarloSimulation(
        goal: SavingsGoal,
        now: Long,
        timeHorizon: TimeHorizon
    ): Double {
        // Calculate current spending to date
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        
        val expenses = expenseRepository.getExpensesBetween(monthStart, now)
        val spentToDate = expenses
            .asSequence()
            .filter { it.transactionType.toDomain() == DomainTransactionType.PURCHASE && !it.isNotMine }
            .sumOf { it.effectiveAmount }

        // Derive monthly discretionary baseline from recent real spending
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val categoriesById = categoryRepository.getAll().associateBy { it.id }
        val historicalPurchases = expenseRepository.getExpensesBetween(threeMonthsAgo, now)
            .asSequence()
            .filter { it.transactionType.toDomain() == DomainTransactionType.PURCHASE && !it.isNotMine }
            .toList()
        val discretionaryHistoricalTotal = historicalPurchases
            .asSequence()
            .filter {
                isDiscretionaryCategory(it.categoryId, categoriesById)
            }
            .sumOf { it.effectiveAmount }
        val monthlyDiscretionary = if (historicalPurchases.isNotEmpty()) {
            discretionaryHistoricalTotal / 3.0
        } else {
            500.0
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
            val horizonMultiplier = when (timeHorizon) {
                TimeHorizon.WEEK -> 0.25
                TimeHorizon.MONTH -> 1.0
                TimeHorizon.QUARTER -> 3.0
            }

            // If projected spending is under control, suggest saving 20% of discretionary
            val discretionary = monthlyDiscretionary * horizonMultiplier
            val projectedHorizonSpending = projectedSpending * horizonMultiplier
            val remaining = discretionary - (projectedHorizonSpending * 0.3) // 30% of projected horizon spending
            if (remaining > 0) remaining * 0.2 else 0.0
        } ?: 0.0
    }
    
    private fun calculateWeightedSafeAmount(
        budgetSurplus: Double,
        spendingPace: Double,
        monteCarloResult: Double,
        timeHorizon: TimeHorizon
    ): Double {
        // Weighted combination adjusted by time horizon
        val (budgetWeight, paceWeight, monteCarloWeight, cap) = when (timeHorizon) {
            TimeHorizon.WEEK -> HorizonWeights(0.30, 0.45, 0.25, 75.0)
            TimeHorizon.MONTH -> HorizonWeights(0.40, 0.30, 0.30, 200.0)
            TimeHorizon.QUARTER -> HorizonWeights(0.35, 0.20, 0.45, 500.0)
        }

        val weighted =
            budgetSurplus * budgetWeight +
            spendingPace * paceWeight +
            monteCarloResult * monteCarloWeight
        
        return minOf(weighted, cap).coerceAtLeast(0.0)
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
    
    private fun generateImpactMessage(
        amount: Double,
        goal: SavingsGoal,
        timeHorizon: TimeHorizon
    ): String {
        val remaining = goal.targetAmount - goal.currentAmount
        if (remaining <= 0) return "Goal already reached"

        val horizonDays = when (timeHorizon) {
            TimeHorizon.WEEK -> 7.0
            TimeHorizon.MONTH -> 30.0
            TimeHorizon.QUARTER -> 90.0
        }
        val daysToGoal = if (amount > 0) {
            kotlin.math.ceil((remaining / amount) * horizonDays).toInt()
        } else {
            Int.MAX_VALUE
        }
        
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

    private fun isDiscretionaryCategory(
        categoryId: Long?,
        categoriesById: Map<Long, Category>
    ): Boolean {
        val essentialCategories = setOf(
            "groceries", "rent", "utilities", "transport",
            "insurance", "healthcare", "bills", "mortgage", "education", "loan"
        )

        val categoryName = categoryId
            ?.let { categoriesById[it]?.name }
            ?.trim()
            ?.lowercase()

        // Treat uncategorized entries as discretionary by default.
        return categoryName == null || categoryName !in essentialCategories
    }

    private data class HorizonWeights(
        val budgetWeight: Double,
        val paceWeight: Double,
        val monteCarloWeight: Double,
        val cap: Double
    )
    
    enum class TimeHorizon {
        WEEK, MONTH, QUARTER
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
}
