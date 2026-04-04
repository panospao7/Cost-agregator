package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardDataProvider @Inject constructor(
    private val expenseRepository: DashboardExpenseRepository,
    private val categoryRepository: DashboardCategoryRepository,
    private val budgetRepository: DashboardBudgetRepository,
    private val reviewQueueRepository: DashboardReviewQueueRepository,
    private val financialWeatherRepository: DashboardFinancialWeatherRepository,
    private val savingsGoalRepository: DashboardSavingsGoalRepository,
    private val insightsEngine: InsightsEngine,
    private val synthesisEngine: SynthesisEngine,
    private val timeProvider: TimeProvider
) {
    private val timePeriodUtils = TimePeriodUtils

    fun getBaseDataFlow() = combine(
        expenseRepository.observeDashboardExpenses().catch { emit(emptyList()) },
        categoryRepository.observeDashboardCategories().catch { emit(emptyList()) },
        budgetRepository.observeBudgetStatuses().catch { emit(emptyList()) }
    ) { expenses, categories, budgetStatuses ->
        BaseData(expenses, categories, budgetStatuses)
    }

    fun getPlanningDataFlow() = combine(
        reviewQueueRepository.observePendingReviewCount().catch { emit(0) },
        getFinancialWeatherWithDefaults(),
        getRecurringPatterns(),
        getPlannedExpenses()
    ) { pendingCount, weather, recurring, planned ->
        PlanningData(pendingCount, weather, recurring, planned)
    }

    fun getAllDataFlow() = combine(
        getBaseDataFlow(),
        getPlanningDataFlow(),
        savingsGoalRepository.observeSavingsGoals().catch { emit(emptyList()) }
    ) { base, planning, goals ->
        DashboardData(
            expenses = base.expenses,
            categories = base.categories,
            budgetStatuses = base.budgetStatuses,
            pendingCount = planning.pendingCount,
            weather = planning.weather,
            recurringPatterns = planning.recurringPatterns,
            plannedExpenses = planning.plannedExpenses,
            goals = goals
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getProcessedDataFlow(
        analyticsRepository: DashboardAnalyticsRepository
    ): Flow<ProcessedDashboardData> {
        return getAllDataFlow()
            .flatMapLatest { data: DashboardData ->
                // Recompute time boundaries on every emission so month roll-overs
                // are always reflected without requiring app restart.
                val now = timeProvider.now()
                val monthStart = timePeriodUtils.getStartOfMonth(now)
                val monthEnd = timePeriodUtils.getEndOfMonth(now)

                combine(
                    analyticsRepository.observeSpendingSummary(monthStart, monthEnd)
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
                    analyticsRepository.observeCategoryBreakdown(monthStart, monthEnd)
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
        financialWeatherRepository.observeFinancialWeather()
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
        financialWeatherRepository.observeRecurringPatterns()
            .catch { emit(emptyList()) }

    private fun getPlannedExpenses(): Flow<List<PlannedExpense>> =
        financialWeatherRepository.observePlannedExpenses()
            .catch { emit(emptyList()) }

    fun getInsightsEngine() = insightsEngine
    fun getSynthesisEngine() = synthesisEngine
    fun getTimeProvider() = timeProvider
    fun getTimePeriodUtils() = timePeriodUtils
}

data class BaseData(
    val expenses: List<DashboardExpense>,
    val categories: List<DashboardCategory>,
    val budgetStatuses: List<BudgetStatusSnapshot>
)

data class PlanningData(
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>
)

data class DashboardData(
    val expenses: List<DashboardExpense>,
    val categories: List<DashboardCategory>,
    val budgetStatuses: List<BudgetStatusSnapshot>,
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>,
    val goals: List<SavingsGoal>
)

data class ProcessedDashboardData(
    val data: DashboardData,
    val summary: SpendingSummary,
    val categoryBreakdown: List<DashboardCategoryBreakdown>
)
