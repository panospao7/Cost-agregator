package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.FinancialWeather
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.data.repository.SpendingSummary
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import kotlin.Triple
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// === State Widget sealed class for Bento Grid ===
sealed class DashboardWidget {
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()

    data class BudgetBlockParty(
        val days: List<com.yourname.expensetracker.ui.components.DayBudgetStatus>
    ) : DashboardWidget()

    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()

    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()

    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double
    ) : DashboardWidget()

    data class TopCategories(
        val categories: List<CategorySpending>
    ) : DashboardWidget()

    data class BudgetHealthWidget(
        val statuses: List<BudgetStatus>,
        val summary: String?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<Expense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: String,
        val icon: String
    ) : DashboardWidget()

    data class SpendingTrend(
        val currentMonthData: List<Float>,
        val previousMonthData: List<Float>
    ) : DashboardWidget()

    data class FinancialWeatherWidget(
        val weather: FinancialWeather
    ) : DashboardWidget()

    data class FinancialRunway(
        val daysRemaining: Int,
        val totalBudget: Double,
        val discretionaryRemaining: Double,
        val averageDailyDiscretionarySpend: Double,
        val monthlyIncome: Double,
        val committedExpenses: Double,   // Recurring bills
        val likelyExpenses: Double,     // Planned expenses
        val status: RunwayStatus
    ) : DashboardWidget()

    enum class RunwayStatus {
        HEALTHY,      // 14+ days
        CAUTION,     // 7-13 days
        CRITICAL,    // < 7 days
        NO_INCOME    // No deposits detected
    }
}

data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)

data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true, // For pulse dot
    val isEditMode: Boolean = false,
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val reviewQueueRepository: com.yourname.expensetracker.data.repository.ReviewQueueRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val dashboardRepository: DashboardRepository,
    private val insightsEngine: InsightsEngine,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val synthesisEngine: SynthesisEngine,
    private val savingsGoalRepository: com.yourname.expensetracker.data.repository.SavingsGoalRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val isEditMode = MutableStateFlow(false)

    init {
        // Recover from destructive migration items if needed
        viewModelScope.launch {
            try {
                categoryRepository.ensureDefaultCategories()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to ensure default categories", e)
            }
        }
    }

    // Split flows to avoid 5-arg limit
    private val baseDataFlow = combine(
        expenseRepository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) }
    ) { expenses, categories, budgetStatuses ->
        Triple(expenses, categories, budgetStatuses)
    }

    private val planningDataFlow = combine(
        reviewQueueRepository.getPendingReviewCount().catch { emit(0) },
        financialWeatherRepository.getFinancialWeather().catch { 
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
        },
        financialWeatherRepository.getAllRecurringPatterns()
            .map { list -> 
                list.map { entity -> 
                    com.yourname.expensetracker.domain.model.RecurringPattern(
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
            .catch { emit(emptyList<com.yourname.expensetracker.domain.model.RecurringPattern>()) },
        financialWeatherRepository.getAllPlannedExpenses()
            .map { list -> 
                list.map { entity -> 
                    com.yourname.expensetracker.domain.model.PlannedExpense(
                        id = entity.id,
                        description = entity.description,
                        amount = entity.amount,
                        date = entity.date,
                        categoryId = entity.categoryId,
                        isRecurring = entity.isRecurring,
                        priority = when(entity.priority) {
                             com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.MUST
                             com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.LIKELY
                             com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.OPTIONAL
                        }
                    ) 
                } 
            }
            .catch { emit(emptyList<com.yourname.expensetracker.domain.model.PlannedExpense>()) }
    ) { pendingCount: Int, weather: FinancialWeather, recurring: List<com.yourname.expensetracker.domain.model.RecurringPattern>, planned: List<com.yourname.expensetracker.domain.model.PlannedExpense> ->
        Quadruple(pendingCount, weather, recurring, planned)
    }

    private val dataFlow = combine(
        baseDataFlow,
        planningDataFlow,
        savingsGoalRepository.getAllGoals().catch { emit(emptyList()) }
    ) { base, planning, goalEntities ->
        val goals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = when(entity.protectionLevel) {
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT -> GoalProtectionLevel.STRICT
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING -> GoalProtectionLevel.WARNING
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING -> GoalProtectionLevel.TRACKING
                }
            )
        }
        
        EightData(
            expenses = base.first,
            categories = base.second,
            budgetStatuses = base.third,
            pendingCount = planning.first,
            weather = planning.second,
            recurringPatterns = planning.third,
            plannedExpenses = planning.fourth,
            goals = goals
        )
    }
    .debounce(300)

    // Optimized: Process heavy data via AnalyticsRepository
    private val processedDataFlow: Flow<CompiledDashboardData> = combine(
        dataFlow,
        analyticsRepository.getSpendingSummary(
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(timeProvider.now()),
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfMonth(timeProvider.now())
        )
    ) { data, summary -> Pair(data, summary) }
    .combine(
        analyticsRepository.getCategoryBreakdown(
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(timeProvider.now()),
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfMonth(timeProvider.now())
        )
    ) { (data, summary), categoryBreakdown -> 
         Triple(data, summary, categoryBreakdown)
    }.map { triple: Triple<EightData, SpendingSummary, List<CategoryBreakdown>> ->
        val data = triple.first
        val summary = triple.second
        val categoryBreakdown = triple.third
        
        val (expenses, categories, budgetStatuses, pendingCount, weather, recurringPatterns, plannedExpenses, goals) = data
        
        val now = timeProvider.now()
        val todayStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val weekStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfWeek(now)

        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val deposits = expenses.filter { it.transactionType == TransactionType.DEPOSIT }
        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount }
        val todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount }

        val totalSpent = summary.totalSpent
        val monthSpent = totalSpent
        val txCount = summary.transactionCount
        val previousMonthTotal = summary.previousTotalSpent ?: 0.0

        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget 
        
        val totalBudgetAmount = overallBudget?.budget?.amount ?: 0.0

        // === Financial Runway Calculation ===
        // First, calculate the forecast (needed for accurate discretionary spend)
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val currentDayIdx = ((now - monthStart) / 86400000L).toInt().coerceAtLeast(0)
        
        val currentPace = insightsEngine.getSpendingPaceSuspend(expenses)
        
        val purchasesThisMonth = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE && it.date >= monthStart
        }
        val amountByDay = DoubleArray(currentDayIdx + 1)
        purchasesThisMonth.forEach { exp ->
            val dayIndex = ((exp.date - monthStart) / 86400000L).toInt()
            if (dayIndex in amountByDay.indices) amountByDay[dayIndex] += exp.amount
        }
        var runningTotal = 0.0
        val pastSumDaily = amountByDay.map { runningTotal += it; runningTotal }
        
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = goals,
            budgetStatuses = budgetStatuses,
            spendingPace = currentPace
        )
        
        // Get forecast components including upcoming committed and likely expenses
        val totalCommitted = forecast.components?.totalCommitted ?: 0.0
        val totalLikely = forecast.components?.totalLikely ?: 0.0
        
        // Use forecast's projected total at end of month (includes committed + likely + discretionary)
        // This accounts for known bills that haven't hit yet
        val projectedSpendingPoints = forecast.components?.projectedSpendingPoints ?: emptyList()
        val projectedMonthlyTotal = projectedSpendingPoints.lastOrNull() ?: monthSpent
        
        // Average daily burn based on PROJECTED total (more accurate - includes future obligations)
        val averageDailyBurn = if (dayOfMonth > 0) projectedMonthlyTotal / dayOfMonth else 0.0
        
        // Monthly income from deposits
        val monthlyIncome = deposits
            .filter { it.date >= monthStart }
            .sumOf { it.amount }
        
        // Total remaining budget (discretionary pool)
        val totalRemaining = weather.discretionaryBudget.coerceAtLeast(0.0)
        
        // Calculate runway: total remaining / projected daily burn
        val runwayDays = if (averageDailyBurn > 0 && totalRemaining > 0) {
            (totalRemaining / averageDailyBurn).toInt().coerceAtLeast(0)
        } else {
            0
        }
        
        val runwayStatus = when {
            monthlyIncome == 0.0 -> DashboardWidget.RunwayStatus.NO_INCOME
            runwayDays >= 14 -> DashboardWidget.RunwayStatus.HEALTHY
            runwayDays >= 7 -> DashboardWidget.RunwayStatus.CAUTION
            else -> DashboardWidget.RunwayStatus.CRITICAL
        }
        
        val financialRunway = DashboardWidget.FinancialRunway(
            daysRemaining = runwayDays,
            totalBudget = totalBudgetAmount,
            discretionaryRemaining = totalRemaining,
            averageDailyDiscretionarySpend = averageDailyBurn,
            monthlyIncome = monthlyIncome,
            committedExpenses = totalCommitted,
            likelyExpenses = totalLikely,
            status = runwayStatus
        )
        
        // Call centralized Block Party logic
        val domainBlocks = synthesisEngine.calculateBlockPartyData(
            forecast = forecast,
            expenses = expenses,
            dailySpending = summary.dailyHistory,
            budgetLimit = totalBudgetAmount
        )

        // Map domain to UI models
        val blockPartyDays = domainBlocks.map { domain ->
            com.yourname.expensetracker.ui.components.DayBudgetStatus(
                dayOfMonth = domain.dayOfMonth,
                date = domain.date,
                actualSpent = domain.actualSpent,
                targetBudget = domain.targetBudget,
                isToday = domain.isToday,
                status = when(domain.status) {
                    BlockPartyStatus.UNDER_BUDGET -> com.yourname.expensetracker.ui.components.BlockStatus.UNDER_BUDGET
                    BlockPartyStatus.OVER_BUDGET -> com.yourname.expensetracker.ui.components.BlockStatus.OVER_BUDGET
                    BlockPartyStatus.FUTURE -> com.yourname.expensetracker.ui.components.BlockStatus.FUTURE
                    BlockPartyStatus.TODAY -> com.yourname.expensetracker.ui.components.BlockStatus.TODAY
                    BlockPartyStatus.BILL_DAY -> com.yourname.expensetracker.ui.components.BlockStatus.BILL_DAY
                    BlockPartyStatus.NO_DATA -> com.yourname.expensetracker.ui.components.BlockStatus.NO_DATA
                },
                baseTarget = domain.baseTarget,
                recurringImpact = domain.recurringImpact,
                plannedImpact = domain.plannedImpact,
                recurringItems = domain.recurringItems,
                plannedItems = domain.plannedItems,
                topTransactions = domain.topTransactions
            )
        }

        // Category Totals
        val categoryTotals = categoryBreakdown.map { 
             CategorySpending(it.category, it.total, it.percentage) 
        }

        val baseline = overallBudget?.budget?.amount ?: if (previousMonthTotal > 0) previousMonthTotal else null
        
        // Handle Day 1 Noise (LOG-005 Fix)
        val dayOfMonthCoerced = dayOfMonth.coerceAtLeast(1)
        val projectedTotal = if (dayOfMonth == 1) {
            if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
            else monthSpent * daysInMonth
        } else {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        }
            
        // Pace Percentage
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expected = baseline * dayOfMonthCoerced / daysInMonth
            val calculated = (monthSpent / expected * 100).toFloat()
            if (calculated.isFinite()) calculated else 0f
        } else 0f

        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = pacePercentage,
            paceStatus = when {
                baseline == null || baseline <= 0 -> PaceStatus.NO_BASELINE
                pacePercentage < 90f -> PaceStatus.UNDER_PACE
                pacePercentage > 110f -> PaceStatus.OVER_PACE
                else -> PaceStatus.ON_PACE
            }
        )

        // Trend
        val trend = DashboardWidget.SpendingTrend(
            currentMonthData = summary.dailyHistory,
            previousMonthData = summary.previousDailyHistory
        )

        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, summary.transactionCount
        )

        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()

        widgets.add(DashboardWidget.FinancialWeatherWidget(weather))

        // Hero
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )

        // Financial Runway - show based on budget availability, not income
        if (totalRemaining > 0 || totalBudgetAmount > 0) {
            widgets.add(financialRunway)
        }
        
        // Block Party (New)
        if (blockPartyDays.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetBlockParty(blockPartyDays))
        }

        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }

        // Spending Trend
        widgets.add(trend)

        if (pendingCount > 0) widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        if (insightText != null) widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))
        if (budgetStatuses.isNotEmpty()) widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        if (categoryTotals.isNotEmpty()) widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        if (purchases.isNotEmpty()) widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))
        
        CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = totalSpent,
            txCount = txCount
        )
    }
    .catch { e ->
        android.util.Log.e("HomeViewModel", "Error processing dashboard data", e)
        emit(CompiledDashboardData(emptyList(), 0.0, 0))
    }
    .flowOn(Dispatchers.Default) 
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompiledDashboardData(emptyList(), 0.0, 0))

    val dashboard: StateFlow<DashboardState> = combine(
        processedDataFlow,
        isEditMode,
        dashboardRepository.configFlow
    ) { compiledData, editMode, configList ->

        // === Apply Custom Layout ===
        val sortedWidgets = configList
            .filter { it.isVisible || editMode } // Show all in edit mode, otherwise filter
            .mapNotNull { conf ->
                compiledData.allWidgets.find { w -> getWidgetId(w) == conf.id }
            }

        DashboardState(
            widgets = sortedWidgets,
            totalSpent = compiledData.totalSpent,
            transactionCount = compiledData.txCount,
            isEditMode = editMode,
            isLoading = false
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    fun toggleEditMode() {
        isEditMode.value = !isEditMode.value
    }

    fun moveWidget(widgetId: String, moveUp: Boolean) {
        val currentConfig = dashboardRepository.getDashboardConfig().toMutableList()
        val index = currentConfig.indexOfFirst { it.id == widgetId }
        if (index == -1) return

        val newIndex = if (moveUp) index - 1 else index + 1
        if (newIndex !in currentConfig.indices) return

        val temp = currentConfig[index]
        currentConfig[index] = currentConfig[newIndex].copy(order = index)
        currentConfig[newIndex] = temp.copy(order = newIndex)
        
        dashboardRepository.saveDashboardConfig(currentConfig.sortedBy { it.order })
        // Trigger recomposition by refreshing dashboard flow (implicitly via combining with a triggered state if needed)
        // Here we can just nudge the isEditMode or use a dedicated Refresh trigger
        // isEditMode.value = isEditMode.value 
    }

    fun toggleWidgetVisibility(widgetId: String) {
        val currentConfig = dashboardRepository.getDashboardConfig().map {
            if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
        }
        dashboardRepository.saveDashboardConfig(currentConfig)
        // isEditMode.value = isEditMode.value
    }


    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<String, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return if (diff < 0) {
                Pair(
                    "You've spent €${String.format("%.0f", -diff)} less than last month so far.",
                    "📉"
                )
            } else if (diff > previousMonthTotal * 0.2) {
                Pair(
                    "Spending is €${String.format("%.0f", diff)} higher than last month.",
                    "📈"
                )
            } else null
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                "You've spent €${String.format("%.2f", todaySpent)} today across $txCount transactions.",
                "💡"
            )
        }
        return null
    }

    fun addPlannedExpense(
        description: String,
        amount: Double,
        date: Long,
        categoryId: Long?,
        priority: PlannedExpensePriority
    ) {
        viewModelScope.launch {
            plannedExpenseRepository.addPlannedExpense(
                com.yourname.expensetracker.data.database.entity.PlannedExpense(
                    description = description,
                    amount = amount,
                    date = date,
                    categoryId = categoryId,
                    priority = priority
                )
            )
        }
    }

    companion object {
        fun getWidgetId(widget: DashboardWidget): String = when (widget) {
            is DashboardWidget.SafeToSpend -> "safe_to_spend"
            is DashboardWidget.SpendingPaceWidget -> "spending_pace"
            is DashboardWidget.PendingReviewAlert -> "review_alert"
            is DashboardWidget.SpendingTrend -> "spending_trend"
            is DashboardWidget.NaturalLanguageInsight -> "insight"
            is DashboardWidget.PeriodSummary -> "period_summary"
            is DashboardWidget.BudgetHealthWidget -> "budget_health"
            is DashboardWidget.TopCategories -> "top_categories"
            is DashboardWidget.RecentTransactions -> "recent_transactions"
            is DashboardWidget.FinancialWeatherWidget -> "financial_weather"
            is DashboardWidget.BudgetBlockParty -> "budget_block_party"
            is DashboardWidget.FinancialRunway -> "financial_runway"
        }
    }
}


data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

data class EightData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>,
    val goals: List<SavingsGoal>
)

data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int
)
