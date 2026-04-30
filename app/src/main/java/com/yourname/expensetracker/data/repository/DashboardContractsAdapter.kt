package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardBudgetRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardCategoryRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardExpenseRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardFinancialWeatherRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardReviewQueueRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardSavingsGoalRepository
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardContractsAdapter @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val savingsGoalRepository: DomainSavingsGoalRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val timeBoundaryTicker: TimeBoundaryTicker
) : DashboardExpenseRepository,
    DashboardCategoryRepository,
    DashboardBudgetRepository,
    DashboardReviewQueueRepository,
    DashboardFinancialWeatherRepository,
    DashboardSavingsGoalRepository,
    DashboardAnalyticsRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDashboardExpenses(): Flow<List<DashboardExpense>> {
        return timeBoundaryTicker.dayBoundaryTicks().flatMapLatest { now ->
            val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
            expenseRepository
                .getExpensesWithCategoryInPeriod(monthStart, monthEnd)
                .map { list -> list.map { it.expense.toDomainDashboard() } }
        }
    }

    override fun observeDashboardCategories(): Flow<List<DashboardCategory>> =
        categoryRepository.allCategories.map { list -> list.map { it.toDashboardCategory() } }

    override fun observeBudgetStatuses(): Flow<List<BudgetStatusSnapshot>> =
        budgetRepository.getBudgetStatuses().map { statuses ->
            statuses.map { status ->
                BudgetStatusSnapshot(
                    budgetCategoryId = status.budget.categoryId,
                    budgetAmount = status.effectiveLimit,
                    categoryName = status.category?.name,
                    spentAmount = status.spentAmount,
                    remainingAmount = status.remainingAmount,
                    percentUsed = status.percentUsed.toDouble(),
                    healthStatus = status.healthStatus,
                    periodStart = status.periodStart,
                    periodEnd = status.periodEnd
                )
            }
        }

    override fun observePendingReviewCount(): Flow<Int> =
        reviewQueueRepository.getPendingReviewCount()

    override fun observeFinancialWeather(): Flow<FinancialWeather> =
        financialWeatherRepository.getFinancialWeather()

    override fun observeRecurringPatterns(): Flow<List<RecurringPattern>> =
        financialWeatherRepository.getConfirmedRecurringPatterns()

    override fun observePlannedExpenses(): Flow<List<PlannedExpense>> =
        plannedExpenseRepository.getAllPlannedExpenses().map { list ->
            list.map { entity ->
                PlannedExpense(
                    id = entity.id,
                    description = entity.description,
                    amount = entity.amount,
                    date = entity.date,
                    categoryId = entity.categoryId,
                    isRecurring = entity.isRecurring,
                    priority = when (entity.priority) {
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST -> PlannedExpensePriority.MUST
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY -> PlannedExpensePriority.LIKELY
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL -> PlannedExpensePriority.OPTIONAL
                    }
                )
            }
        }

    override fun observeSavingsGoals(): Flow<List<SavingsGoal>> =
        savingsGoalRepository.observeSavingsGoals()

    override fun observeSpendingSummary(start: Long, end: Long): Flow<SpendingSummary> =
        analyticsRepository.getSpendingSummary(start, end).map { summary ->
        SpendingSummary(
            totalSpent = summary.totalSpent,
            previousTotalSpent = summary.previousTotalSpent,
            changePercent = summary.changePercent,
            dailyHistory = summary.dailyHistory,
            previousDailyHistory = summary.previousDailyHistory,
            transactionCount = summary.transactionCount,
            currency = summary.currency
        )
        }

    override fun observeCategoryBreakdown(start: Long, end: Long): Flow<List<DashboardCategoryBreakdown>> =
        analyticsRepository.getCategoryBreakdown(start, end).map { breakdown ->
            val periodLength = (end - start).coerceAtLeast(1L)
            val previousStart = start - periodLength
            val previousEnd = start
            val previousByCategoryId = analyticsRepository
                .getCategoryBreakdown(previousStart, previousEnd)
                .map { list -> list.associateBy { it.category.id } }
                .first()

            breakdown.map { item ->
                val previousAmount = previousByCategoryId[item.category.id]?.total ?: 0.0
                val changeFromLastPeriod = when {
                    previousAmount > 0.0 -> ((item.total - previousAmount) / previousAmount) * 100.0
                    item.total > 0.0 -> 100.0
                    else -> 0.0
                }
                DashboardCategoryBreakdown(
                    categoryId = item.category.id,
                    categoryName = item.category.name,
                    categoryIcon = item.category.icon,
                    categoryColor = item.category.color,
                    amount = item.total,
                    percentage = item.percentage.toDouble(),
                    changeFromLastPeriod = changeFromLastPeriod
                )
            }
        }

    private fun com.yourname.expensetracker.data.database.entity.Expense.toDomainDashboard(): DashboardExpense =
    DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        merchant = merchant,
        transactionType = transactionType.toDashboardType(),
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        currency = currency
    )

    private fun TransactionType.toDashboardType(): DashboardTransactionType =
        when (this) {
            TransactionType.PURCHASE -> DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DashboardTransactionType.UNKNOWN
        }

    private fun com.yourname.expensetracker.data.database.entity.Category.toDashboardCategory(): DashboardCategory =
        DashboardCategory(
            id = id,
            name = name,
            icon = icon,
            color = color
        )

}
