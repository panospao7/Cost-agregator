package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calculates financial health scores across multiple time periods.
 * Combines budget health, spending stability, pace, and cleanliness metrics.
 */
@Singleton
class FinancialHealthCalculator @Inject constructor(
    private val timeProvider: TimeProvider
) {

    companion object {
        // Weights for composite score calculation
        private const val TODAY_WEIGHT = 0.20
        private const val WEEK_WEIGHT = 0.30
        private const val MONTH_WEIGHT = 0.50
        
        // Max bonus points per period
        private const val MAX_BONUS_POINTS = 15
    }

    /**
     * Calculates health scores for all three time periods.
     */
    fun calculateHealthScores(
        expenses: List<Expense>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        pendingReviews: Int,
        todayStreak: Int,
        weekStreak: Int,
        monthStreak: Int,
        noSpendStreak: Int
    ): HealthScoreResult {
        val todayScore = calculateTodayScore(
            expenses = expenses,
            budgetStatuses = budgetStatuses,
            pendingReviews = pendingReviews,
            streak = todayStreak,
            noSpendStreak = noSpendStreak
        )
        
        val weekScore = calculateWeekScore(
            expenses = expenses,
            budgetStatuses = budgetStatuses,
            pendingReviews = pendingReviews,
            streak = weekStreak,
            noSpendStreak = noSpendStreak
        )
        
        val monthScore = calculateMonthScore(
            expenses = expenses,
            budgetStatuses = budgetStatuses,
            pendingReviews = pendingReviews,
            streak = monthStreak,
            noSpendStreak = noSpendStreak
        )
        
        val compositeScore = calculateCompositeScore(todayScore, weekScore, monthScore)
        
        return HealthScoreResult(
            today = todayScore,
            week = weekScore,
            month = monthScore,
            composite = compositeScore
        )
    }

    private fun calculateTodayScore(
        expenses: List<Expense>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        pendingReviews: Int,
        streak: Int,
        noSpendStreak: Int
    ): PeriodHealthScore {
        val now = timeProvider.now()
        val (todayStart, todayEnd) = TimePeriodUtils.getDayRange(now)
        
        val todayExpenses = expenses.filter { 
            TimePeriodUtils.isInRange(it.date, todayStart, todayEnd) 
        }
        
        val todaySpending = spendingOnly(todayExpenses)
        val spentToday = todaySpending.sumOf { it.effectiveAmount }
        
        // Calculate base score components
        val budgetHealth = calculateBudgetHealthScore(budgetStatuses, todayExpenses)
        val spendingControl = calculateDailySpendingControl(
            spentToday = spentToday,
            budgetStatuses = budgetStatuses,
            periodStart = todayStart,
            periodEnd = todayEnd
        )
        val cleanliness = calculateCleanlinessScore(pendingReviews)
        
        // Calculate bonus points
        val bonusPoints = calculateBonusPoints(
            noSpendStreak = if (spentToday == 0.0) noSpendStreak + 1 else 0,
            streak = streak,
            allBudgetsOnTrack = budgetStatuses.all { it.healthStatus == BudgetHealthStatus.ON_TRACK }
        )
        
        val baseScore = budgetHealth + spendingControl + cleanliness
        val finalScore = (baseScore + bonusPoints).coerceIn(0, 100)
        
        return PeriodHealthScore(
            score = finalScore,
            breakdown = HealthBreakdown(
                budgetHealth = budgetHealth,
                spendingControl = spendingControl,
                cleanliness = cleanliness,
                bonusPoints = bonusPoints
            )
        )
    }

    private fun calculateWeekScore(
        expenses: List<Expense>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        pendingReviews: Int,
        streak: Int,
        noSpendStreak: Int
    ): PeriodHealthScore {
        val now = timeProvider.now()
        val (weekStart, weekEnd) = TimePeriodUtils.getWeekRange(now)
        
        val weekExpenses = expenses.filter { 
            TimePeriodUtils.isInRange(it.date, weekStart, weekEnd) 
        }
        
        val weekSpending = spendingOnly(weekExpenses)
        val spentThisWeek = weekSpending.sumOf { it.effectiveAmount }
        
        // Calculate volatility (coefficient of variation)
        val dailySpending = weekSpending.groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .map { (_, exps) -> exps.sumOf { it.effectiveAmount } }
        
        val volatility = calculateVolatility(dailySpending)
        
        val budgetHealth = calculateBudgetHealthScore(budgetStatuses, weekExpenses)
        val spendingControl = calculateWeeklySpendingControl(
            spentThisWeek = spentThisWeek,
            budgetStatuses = budgetStatuses,
            periodStart = weekStart,
            periodEnd = weekEnd,
            volatility = volatility
        )
        val cleanliness = calculateCleanlinessScore(pendingReviews)
        
        val bonusPoints = calculateBonusPoints(
            noSpendStreak = noSpendStreak,
            streak = streak,
            allBudgetsOnTrack = budgetStatuses.all { it.healthStatus == BudgetHealthStatus.ON_TRACK }
        )
        
        val baseScore = budgetHealth + spendingControl + cleanliness
        val finalScore = (baseScore + bonusPoints).coerceIn(0, 100)
        
        return PeriodHealthScore(
            score = finalScore,
            breakdown = HealthBreakdown(
                budgetHealth = budgetHealth,
                spendingControl = spendingControl,
                cleanliness = cleanliness,
                bonusPoints = bonusPoints
            )
        )
    }

    private fun calculateMonthScore(
        expenses: List<Expense>,
        budgetStatuses: List<BudgetStatusSnapshot>,
        pendingReviews: Int,
        streak: Int,
        noSpendStreak: Int
    ): PeriodHealthScore {
        val now = timeProvider.now()
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
        
        val monthExpenses = expenses.filter { 
            TimePeriodUtils.isInRange(it.date, monthStart, monthEnd) 
        }
        
        val monthSpending = spendingOnly(monthExpenses)
        val spentThisMonth = monthSpending.sumOf { it.effectiveAmount }
        
        // Calculate volatility
        val dailySpending = monthSpending.groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .map { (_, exps) -> exps.sumOf { it.effectiveAmount } }
        
        val volatility = calculateVolatility(dailySpending)
        
        val budgetHealth = calculateBudgetHealthScore(budgetStatuses, monthExpenses)
        val spendingControl = calculateMonthlySpendingControl(
            spentThisMonth = spentThisMonth,
            budgetStatuses = budgetStatuses,
            periodStart = monthStart,
            periodEnd = monthEnd,
            volatility = volatility
        )
        val cleanliness = calculateCleanlinessScore(pendingReviews)
        
        val bonusPoints = calculateBonusPoints(
            noSpendStreak = noSpendStreak,
            streak = streak,
            allBudgetsOnTrack = budgetStatuses.all { it.healthStatus == BudgetHealthStatus.ON_TRACK }
        )
        
        val baseScore = budgetHealth + spendingControl + cleanliness
        val finalScore = (baseScore + bonusPoints).coerceIn(0, 100)
        
        return PeriodHealthScore(
            score = finalScore,
            breakdown = HealthBreakdown(
                budgetHealth = budgetHealth,
                spendingControl = spendingControl,
                cleanliness = cleanliness,
                bonusPoints = bonusPoints
            )
        )
    }

    private fun calculateBudgetHealthScore(
        budgetStatuses: List<BudgetStatusSnapshot>,
        periodExpenses: List<Expense>
    ): Int {
        if (budgetStatuses.isEmpty()) return 25 // Default if no budgets set
        
        val onTrackCount = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
        val warningCount = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.WARNING }
        val criticalCount = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.CRITICAL }
        val exceededCount = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        
        val totalBudgets = budgetStatuses.size
        
        return when {
            exceededCount > 0 -> 5
            criticalCount > 0 -> 10
            warningCount > totalBudgets / 3 -> 15
            onTrackCount == totalBudgets -> 25
            else -> 20
        }
    }

    private fun calculateDailySpendingControl(
        spentToday: Double,
        budgetStatuses: List<BudgetStatusSnapshot>,
        periodStart: Long,
        periodEnd: Long
    ): Int {
        val dailyBudget = calculateNormalizedBudgetTarget(
            budgetStatuses = budgetStatuses,
            periodStart = periodStart,
            periodEnd = periodEnd,
            defaultTarget = 50.0
        )
        
        val ratio = spentToday / dailyBudget
        
        return when {
            ratio <= 0.8 -> 25 // Under budget
            ratio <= 1.0 -> 20 // On budget
            ratio <= 1.2 -> 15 // Slightly over
            ratio <= 1.5 -> 10 // Moderately over
            else -> 5 // Way over budget
        }
    }

    private fun calculateWeeklySpendingControl(
        spentThisWeek: Double,
        budgetStatuses: List<BudgetStatusSnapshot>,
        periodStart: Long,
        periodEnd: Long,
        volatility: Double
    ): Int {
        val weeklyBudget = calculateNormalizedBudgetTarget(
            budgetStatuses = budgetStatuses,
            periodStart = periodStart,
            periodEnd = periodEnd,
            defaultTarget = 350.0
        )
        
        val ratio = spentThisWeek / weeklyBudget
        val volatilityPenalty = if (volatility > 50) 5 else 0 // Penalty for high volatility
        
        val baseScore = when {
            ratio <= 0.8 -> 25
            ratio <= 1.0 -> 20
            ratio <= 1.2 -> 15
            ratio <= 1.5 -> 10
            else -> 5
        }
        
        return (baseScore - volatilityPenalty).coerceAtLeast(5)
    }

    private fun calculateMonthlySpendingControl(
        spentThisMonth: Double,
        budgetStatuses: List<BudgetStatusSnapshot>,
        periodStart: Long,
        periodEnd: Long,
        volatility: Double
    ): Int {
        val monthlyBudget = calculateNormalizedBudgetTarget(
            budgetStatuses = budgetStatuses,
            periodStart = periodStart,
            periodEnd = periodEnd,
            defaultTarget = 1500.0
        )
        
        val ratio = spentThisMonth / monthlyBudget
        val volatilityPenalty = if (volatility > 50) 5 else 0
        
        val baseScore = when {
            ratio <= 0.7 -> 25 // Great month
            ratio <= 0.9 -> 25 // Good month
            ratio <= 1.0 -> 20 // On target
            ratio <= 1.1 -> 15 // Slightly over
            ratio <= 1.3 -> 10 // Moderately over
            else -> 5 // Significantly over
        }
        
        return (baseScore - volatilityPenalty).coerceAtLeast(5)
    }

    private fun calculateCleanlinessScore(pendingReviews: Int): Int {
        return when {
            pendingReviews == 0 -> 10
            pendingReviews < 5 -> 7
            pendingReviews < 20 -> 4
            else -> 0
        }
    }

    private fun calculateBonusPoints(
        noSpendStreak: Int,
        streak: Int,
        allBudgetsOnTrack: Boolean
    ): Int {
        var bonus = 0
        
        // No-spend streak bonus
        when {
            noSpendStreak >= 7 -> bonus += 5
            noSpendStreak >= 3 -> bonus += 3
        }
        
        // Perfect streak bonus
        if (streak >= 7) {
            bonus += 3
        }
        
        // All budgets on track
        if (allBudgetsOnTrack) {
            bonus += 2
        }
        
        return bonus.coerceAtMost(MAX_BONUS_POINTS)
    }

    /**
     * Returns only the canonical spending rows from the given list.
     * Non-spend transaction types (DEPOSIT, TRANSFER, WITHDRAWAL, UNKNOWN)
     * are excluded so they do not inflate spend totals or distort volatility.
     */
    private fun spendingOnly(expenses: List<Expense>): List<Expense> =
        expenses.filter { it.transactionType.toDomain().isSpending }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }

    private fun calculateNormalizedBudgetTarget(
        budgetStatuses: List<BudgetStatusSnapshot>,
        periodStart: Long,
        periodEnd: Long,
        defaultTarget: Double
    ): Double {
        val scopedBudgets = budgetStatuses
            .filter { it.budgetAmount > 0.0 }
            .filter { rangesOverlap(it.periodStart, it.periodEnd, periodStart, periodEnd) }
            .let { overlapping ->
                val overallBudgets = overlapping.filter { it.budgetCategoryId == null }
                if (overallBudgets.isNotEmpty()) overallBudgets else overlapping
            }

        if (scopedBudgets.isEmpty()) {
            return defaultTarget
        }

        val normalizedTarget = scopedBudgets.sumOf { status ->
            val overlapStart = maxOf(periodStart, status.periodStart)
            val overlapEnd = minOf(periodEnd, status.periodEnd)
            val overlapDuration = (overlapEnd - overlapStart).coerceAtLeast(0L)
            val budgetWindowDuration = (status.periodEnd - status.periodStart).coerceAtLeast(1L)
            status.budgetAmount * (overlapDuration.toDouble() / budgetWindowDuration.toDouble())
        }

        return normalizedTarget.takeIf { it > 0.0 } ?: defaultTarget
    }

    private fun rangesOverlap(
        firstStart: Long,
        firstEnd: Long,
        secondStart: Long,
        secondEnd: Long
    ): Boolean {
        return firstStart < secondEnd && secondStart < firstEnd
    }

    private fun calculateVolatility(dailySpending: List<Double>): Double {
        if (dailySpending.isEmpty() || dailySpending.size < 2) return 0.0
        
        val mean = dailySpending.average()
        if (mean == 0.0) return 0.0
        
        val variance = dailySpending.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        
        // Coefficient of variation as percentage
        return (stdDev / mean) * 100
    }

    private fun calculateCompositeScore(
        today: PeriodHealthScore,
        week: PeriodHealthScore,
        month: PeriodHealthScore
    ): Int {
        val weightedScore = (
            today.score * TODAY_WEIGHT +
            week.score * WEEK_WEIGHT +
            month.score * MONTH_WEIGHT
        ).toInt()
        
        return weightedScore.coerceIn(0, 100)
    }

}

/**
 * Complete health score result for all time periods.
 */
data class HealthScoreResult(
    val today: PeriodHealthScore,
    val week: PeriodHealthScore,
    val month: PeriodHealthScore,
    val composite: Int
) {
    fun getCompositeStatus(): HealthStatus {
        return when (composite) {
            in 85..100 -> HealthStatus.EXCELLENT
            in 70..84 -> HealthStatus.GOOD
            in 50..69 -> HealthStatus.FAIR
            in 30..49 -> HealthStatus.WARNING
            else -> HealthStatus.CRITICAL
        }
    }
}

data class PeriodHealthScore(
    val score: Int,
    val breakdown: HealthBreakdown
)

data class HealthBreakdown(
    val budgetHealth: Int,
    val spendingControl: Int,
    val cleanliness: Int,
    val bonusPoints: Int
)

enum class HealthStatus {
    EXCELLENT,  // 85-100
    GOOD,       // 70-84
    FAIR,       // 50-69
    WARNING,    // 30-49
    CRITICAL    // 0-29
}
