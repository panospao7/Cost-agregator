package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeather
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.data.repository.SpendingSummary
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardDataProvider @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val insightsEngine: InsightsEngine,
    private val synthesisEngine: SynthesisEngine,
    private val timeProvider: TimeProvider
) {
    private val timePeriodUtils = TimePeriodUtils

    fun getBaseDataFlow() = combine(
        expenseRepository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) }
    ) { expenses, categories, budgetStatuses ->
        BaseData(expenses, categories, budgetStatuses)
    }

    fun getPlanningDataFlow() = combine(
        reviewQueueRepository.getPendingReviewCount().catch { emit(0) },
        getFinancialWeatherWithDefaults(),
        getRecurringPatterns(),
        getPlannedExpenses()
    ) { pendingCount, weather, recurring, planned ->
        PlanningData(pendingCount, weather, recurring, planned)
    }

    fun getAllDataFlow() = combine(
        getBaseDataFlow(),
        getPlanningDataFlow(),
        savingsGoalRepository.getAllGoals().catch { emit(emptyList()) }
    ) { base, planning, goals ->
        DashboardData(
            expenses = base.expenses,
            categories = base.categories,
            budgetStatuses = base.budgetStatuses,
            pendingCount = planning.pendingCount,
            weather = planning.weather,
            recurringPatterns = planning.recurringPatterns,
            plannedExpenses = planning.plannedExpenses,
            goals = goals.map { it.toDomain() }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getProcessedDataFlow(
        analyticsRepository: AnalyticsRepository
    ): Flow<ProcessedDashboardData> {
        return getAllDataFlow()
            .flatMapLatest { data: DashboardData ->
                // Recompute time boundaries on every emission so month roll-overs
                // are always reflected without requiring app restart.
                val now = timeProvider.now()
                val monthStart = timePeriodUtils.getStartOfMonth(now)
                val monthEnd = timePeriodUtils.getEndOfMonth(now)

                combine(
                    analyticsRepository.getSpendingSummary(monthStart, monthEnd)
                        .catch {
                            emit(SpendingSummary(
                                totalSpent = 0.0,
                                previousTotalSpent = null,
                                changePercent = null,
                                dailyHistory = emptyList(),
                                previousDailyHistory = emptyList(),
                                transactionCount = 0
                            ))
                        },
                    analyticsRepository.getCategoryBreakdown(monthStart, monthEnd)
                        .catch { emit(emptyList()) }
                ) { summary, categoryBreakdown ->
                    ProcessedDashboardData(
                        data = data,
                        summary = summary,
                        categoryBreakdown = categoryBreakdown
                    )
                }
            }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
    }

    private fun getFinancialWeatherWithDefaults(): Flow<FinancialWeather> =
        financialWeatherRepository.getFinancialWeather()
            .catch {
                emit(FinancialWeather(
                    state = WeatherState.UNKNOWN,
                    headline = "Weather Unavailable",
                    summary = "We couldn't calculate your financial outlook right now.",
                    icon = "❓",
                    riskLevel = 0,
                    totalCommitted = 0.0,
                    totalLikely = 0.0,
                    predictedDiscretionary = 0.0,
                    discretionaryBudget = 0.0
                ))
            }

    private fun getRecurringPatterns(): Flow<List<RecurringPattern>> =
        financialWeatherRepository.getAllRecurringPatterns()
            .map { entities ->
                entities.map { entity ->
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
            }
            .catch { emit(emptyList()) }

    private fun getPlannedExpenses(): Flow<List<PlannedExpense>> =
        financialWeatherRepository.getAllPlannedExpenses()
            .map { entities ->
                entities.map { it.toDomain() }
            }
            .catch { emit(emptyList()) }

    private fun com.yourname.expensetracker.data.database.entity.PlannedExpense.toDomain(): PlannedExpense {
        return PlannedExpense(
            id = this.id,
            description = this.description,
            amount = this.amount,
            date = this.date,
            categoryId = this.categoryId,
            isRecurring = this.isRecurring,
            priority = com.yourname.expensetracker.domain.model.PlannedExpensePriority.valueOf(this.priority.name)
        )
    }

    private fun com.yourname.expensetracker.data.database.entity.SavingsGoal.toDomain(): SavingsGoal {
        return SavingsGoal(
            id = this.id,
            name = this.name,
            targetAmount = this.targetAmount,
            currentAmount = this.currentAmount,
            targetDate = this.targetDate,
            protectionLevel = com.yourname.expensetracker.domain.model.GoalProtectionLevel.valueOf(this.protectionLevel.name)
        )
    }

    fun getInsightsEngine() = insightsEngine
    fun getSynthesisEngine() = synthesisEngine
    fun getTimeProvider() = timeProvider
    fun getTimePeriodUtils() = timePeriodUtils
}

data class BaseData(
    val expenses: List<Expense>,
    val categories: List<com.yourname.expensetracker.data.database.entity.Category>,
    val budgetStatuses: List<BudgetStatus>
)

data class PlanningData(
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>
)

data class DashboardData(
    val expenses: List<Expense>,
    val categories: List<com.yourname.expensetracker.data.database.entity.Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>,
    val goals: List<SavingsGoal>
)

data class ProcessedDashboardData(
    val data: DashboardData,
    val summary: SpendingSummary,
    val categoryBreakdown: List<CategoryBreakdown>
)
