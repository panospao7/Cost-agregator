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
    private val timeProvider: TimeProvider
) {
    operator fun invoke(): Flow<FinancialForecast> = combine(
        expenseRepository.getAllExpenses(),
        budgetRepository.getBudgetStatuses(),
        recurringExpenseRepository.getAllFlow(),
        plannedExpenseRepository.getAllPlannedExpenses(),
        savingsGoalRepository.getAllGoals()
    ) { expenses, budgetStatuses, recurringEntities, plannedEntities, goalEntities ->
        val budgetSnapshots = budgetStatuses.map { status ->
            BudgetStatusSnapshot(
                budgetCategoryId = status.budget.categoryId,
                budgetAmount = status.budget.amount,
                categoryName = status.category?.name,
                spentAmount = status.spentAmount,
                remainingAmount = status.remainingAmount,
                percentUsed = status.percentUsed,
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
                priority = when (entity.priority) {
                    EntityPlannedExpensePriority.MUST -> DomainPlannedExpensePriority.MUST
                    EntityPlannedExpensePriority.LIKELY -> DomainPlannedExpensePriority.LIKELY
                    EntityPlannedExpensePriority.OPTIONAL -> DomainPlannedExpensePriority.OPTIONAL
                }
            )
        }
        
        val savingsGoals = goalEntities.map { entity ->
            com.yourname.expensetracker.domain.model.SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = com.yourname.expensetracker.domain.model.GoalProtectionLevel.TRACKING,
                createdAt = entity.createdAt
            )
        }
        
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
        val currentDay = (((now - monthStart) / 86400000L).toInt() + 1).coerceAtLeast(1)
        
        val monthSpent = expenses
            .filter { expense ->
                expense.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
                    !expense.isNotMine &&
                    expense.date in monthStart..now
            }
            .sumOf { it.effectiveAmount }
        
        val spendingPace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = currentDay,
            daysInMonth = daysInMonth,
            projectedTotal = monthSpent,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100f,
            paceStatus = PaceStatus.ON_PACE
        )
        
        synthesisEngine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetSnapshots,
            spendingPace = spendingPace
        )
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
