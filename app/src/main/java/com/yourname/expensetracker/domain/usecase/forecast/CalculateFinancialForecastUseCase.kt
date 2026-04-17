package com.yourname.expensetracker.domain.usecase.forecast

import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority as EntityPlannedExpensePriority
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.PlannedExpensePriority as DomainPlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class CalculateFinancialForecastUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val budgetRepository: BudgetRepository,
    private val synthesisEngine: SynthesisEngine,
    private val timeBoundaryTicker: TimeBoundaryTicker,
    private val timeProvider: TimeProvider
) {
    operator fun invoke(): Flow<FinancialForecast> {
        val sourceData = combine(
            expenseRepository.getAllExpenses(),
            budgetRepository.getBudgetStatuses(),
            recurringExpenseRepository.getAllFlow(),
            plannedExpenseRepository.getAllPlannedExpenses(),
            savingsGoalRepository.getAllGoals()
        ) { expenses, budgetStatuses, recurringEntities, plannedEntities, goalEntities ->
            ForecastSourceData(
                expenses = expenses,
                budgetStatuses = budgetStatuses,
                recurringEntities = recurringEntities,
                plannedEntities = plannedEntities,
                goalEntities = goalEntities
            )
        }

        return combine(sourceData, timeBoundaryTicker.dayBoundaryTicks()) { source, _ ->
            synthesizeForecast(source)
        }
    }

    private fun synthesizeForecast(source: ForecastSourceData): FinancialForecast {
        val expenses = source.expenses
        val budgetStatuses = source.budgetStatuses
        val recurringEntities = source.recurringEntities
        val plannedEntities = source.plannedEntities
        val goalEntities = source.goalEntities

        val budgetSnapshots = budgetStatuses.map { status ->
            BudgetStatusSnapshot(
                budgetCategoryId = status.budget.categoryId,
                budgetAmount = status.budget.amount,
                categoryName = status.category?.name,
                spentAmount = status.spentAmount,
                remainingAmount = status.remainingAmount,
                percentUsed = status.percentUsed.toDouble(),
                healthStatus = status.healthStatus,
                periodStart = status.periodStart,
                periodEnd = status.periodEnd
            )
        }

        
        val recurringPatterns = recurringEntities.map { entity ->
            RecurringPattern(
                id = entity.id,
                merchantName = entity.merchant,
                averageAmount = entity.amount,
                currency = entity.currency,
                frequency = entity.frequency,
                nextExpectedDate = entity.nextDate,
                confidence = 1.0f,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                previousDates = emptyList()
            )
        }
        
        val plannedExpenses = plannedEntities.map { entity ->
            com.yourname.expensetracker.domain.model.PlannedExpense(
                id = entity.id,
                description = entity.description,
                amount = entity.amount,
                date = entity.date,
                categoryId = entity.categoryId,
                isRecurring = entity.isRecurring,
                priority = entity.priority.toDomain()
            )
        }
        
        val savingsGoals = goalEntities.map { entity ->
            com.yourname.expensetracker.domain.model.SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = entity.protectionLevel.toDomain(),
                createdAt = entity.createdAt
            )
        }
        
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val monthPurchases = expenses.filter { expense ->
            expense.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
                !expense.isNotMine &&
                expense.date in monthStart..now
        }
        val pastSumDaily = buildPastSumDaily(monthPurchases, monthStart, now)
        val spendingPace = buildSpendingPace(expenses, now)
        
        return synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetSnapshots,
            spendingPace = spendingPace
        )
    }

    private data class ForecastSourceData(
        val expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        val budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>,
        val recurringEntities: List<com.yourname.expensetracker.data.database.entity.ManualRecurringExpense>,
        val plannedEntities: List<com.yourname.expensetracker.data.database.entity.PlannedExpense>,
        val goalEntities: List<com.yourname.expensetracker.data.database.entity.SavingsGoal>
    )

    private fun buildPastSumDaily(
        monthPurchases: List<com.yourname.expensetracker.data.database.entity.Expense>,
        monthStart: Long,
        now: Long
    ): List<Double> {
        val currentDayIndex = TimePeriodUtils.daysBetween(monthStart, now).coerceAtLeast(0)
        val amountByDay = DoubleArray(currentDayIndex + 1)

        monthPurchases.forEach { expense ->
            val dayIndex = TimePeriodUtils.daysBetween(monthStart, expense.date)
            if (dayIndex in amountByDay.indices) {
                amountByDay[dayIndex] += expense.effectiveAmount
            }
        }

        var runningTotal = 0.0
        return amountByDay.map { amount ->
            runningTotal += amount
            runningTotal
        }
    }

    private fun buildSpendingPace(
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        now: Long
    ): SpendingPace {
        val currentMonthStart = TimePeriodUtils.getStartOfMonth(now)
        val previousMonthStart = TimePeriodUtils.getStartOfMonth(TimePeriodUtils.addMonths(now, -1))
        val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
        val daysElapsed = (TimePeriodUtils.daysBetween(currentMonthStart, now) + 1).coerceAtLeast(1)

        val monthSpent = expenses.sumOfIfOwnedPurchase { it.date in currentMonthStart..now }
        val previousMonthSpent = expenses.sumOfIfOwnedPurchase {
            it.date >= previousMonthStart && it.date < currentMonthStart
        }
        val averageMonthlyTotal = expenses
            .filter {
                it.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    it.date < currentMonthStart
            }
            .groupBy { "${TimePeriodUtils.getYear(it.date)}-${TimePeriodUtils.getMonth(it.date)}" }
            .values
            .map { monthExpenses -> monthExpenses.sumOf { it.effectiveAmount } }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val projectedTotal = calculateProjectedTotal(monthSpent, daysElapsed, daysInMonth)
        val previousMonthDays = TimePeriodUtils.getDaysInMonth(previousMonthStart)
        val baselineDailyRate = if (previousMonthSpent > 0.0 && previousMonthDays > 0) {
            previousMonthSpent / previousMonthDays
        } else {
            0.0
        }
        val currentDailyRate = monthSpent / daysElapsed
        val pacePercentage = if (baselineDailyRate > 0.0) {
            (currentDailyRate / baselineDailyRate * 100.0).toFloat()
        } else {
            0f
        }
        val paceStatus = when {
            baselineDailyRate <= 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < PACE_UNDER_THRESHOLD -> PaceStatus.UNDER_PACE
            pacePercentage > PACE_OVER_THRESHOLD -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        return SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = daysElapsed,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = previousMonthSpent.takeIf { it > 0.0 },
            averageMonthlyTotal = averageMonthlyTotal,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }

    private fun List<com.yourname.expensetracker.data.database.entity.Expense>.sumOfIfOwnedPurchase(
        predicate: (com.yourname.expensetracker.data.database.entity.Expense) -> Boolean
    ): Double = filter {
        it.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
            !it.isNotMine &&
            predicate(it)
    }.sumOf { it.effectiveAmount }

    private fun calculateProjectedTotal(monthSpent: Double, daysElapsed: Int, daysInMonth: Int): Double {
        if (daysElapsed <= 0) return monthSpent

        val weight = (daysElapsed.toDouble() / 7.0).coerceIn(0.0, 1.0)
        val linearProjection = monthSpent * daysInMonth.toDouble() / daysElapsed
        val conservativeEstimate = monthSpent * 3.0
        return (weight * linearProjection) + ((1.0 - weight) * conservativeEstimate)
    }

    private fun com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.toDomain(): DomainPlannedExpensePriority =
        when (this) {
            EntityPlannedExpensePriority.MUST -> DomainPlannedExpensePriority.MUST
            EntityPlannedExpensePriority.LIKELY -> DomainPlannedExpensePriority.LIKELY
            EntityPlannedExpensePriority.OPTIONAL -> DomainPlannedExpensePriority.OPTIONAL
        }

    private fun com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.toDomain():
        com.yourname.expensetracker.domain.model.GoalProtectionLevel = when (this) {
        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT ->
            com.yourname.expensetracker.domain.model.GoalProtectionLevel.STRICT
        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING ->
            com.yourname.expensetracker.domain.model.GoalProtectionLevel.WARNING
        com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING ->
            com.yourname.expensetracker.domain.model.GoalProtectionLevel.TRACKING
    }

    companion object {
        private const val PACE_UNDER_THRESHOLD = 90f
        private const val PACE_OVER_THRESHOLD = 110f
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
