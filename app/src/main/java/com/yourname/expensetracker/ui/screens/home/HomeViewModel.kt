package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
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
import com.yourname.expensetracker.data.database.entity.PlannedExpense
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
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val dashboardRepository: DashboardRepository,
    private val insightsEngine: InsightsEngine,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository
) : ViewModel() {

    private val isEditMode = MutableStateFlow(false)

    // distinct intermediate flow for data to avoid 5-arg limit
    init {
        // Recover from destructive migration items if needed
        viewModelScope.launch {
            categoryRepository.ensureDefaultCategories()
        }
    }

    private val dataFlow = combine(
        repository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) },
        repository.getPendingReviewCount().catch { emit(0) },
        financialWeatherRepository.getFinancialWeather().catch { 
            // Return a default "Unknown" state if the weather engine fails to prevent stalling the dashboard
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
    ) { expenses, categories, budgetStatuses, pendingCount, weather ->
        FiveData(expenses, categories, budgetStatuses, pendingCount, weather)
    }
    .debounce(300)

    // Optimized: Process heavy data separately and cache the result
    private val processedDataFlow = dataFlow.map { data ->
        val (expenses, categories, budgetStatuses, pendingCount, weather) = data

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Time boundaries
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        val weekStart = cal.timeInMillis - (daysToMonday * 86400000L)

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis

        // Single-pass aggregation
        var totalSpent = 0.0
        var monthSpent = 0.0
        var weekSpent = 0.0
        var todaySpent = 0.0
        var previousMonthTotal = 0.0
        val categoryTotalsMap = mutableMapOf<Long, Double>()
        val purchases = ArrayList<Expense>(expenses.size)

        val previousMonthStart = insightsEngine.getMonthPeriod(now, -1).startMs
        val previousMonthEnd = monthStart

        for (expense in expenses) {
            if (expense.transactionType != TransactionType.PURCHASE) continue
            
            val amount = expense.amount
            val date = expense.date
            
            purchases.add(expense)
            totalSpent += amount
            
            if (date >= monthStart) {
                monthSpent += amount
            } else if (date >= previousMonthStart && date < previousMonthEnd) {
                previousMonthTotal += amount
            }

            if (date >= weekStart) {
                weekSpent += amount
                if (date >= todayStart) {
                    todaySpent += amount
                }
            }
            
            expense.categoryId?.let { catId ->
                categoryTotalsMap[catId] = (categoryTotalsMap[catId] ?: 0.0) + amount
            }
        }

        val categoryMap = categories.associateBy { it.id }
        val txCount = purchases.size

        // Days remaining in month
        val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget (if set)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget 

        // Finalize Category totals
        val categoryTotals = categoryTotalsMap.entries
            .mapNotNull { (catId, catTotal) ->
                val cat = catId.let { categoryMap[it] } ?: return@mapNotNull null
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }
        
        val baseline = overallBudget?.budget?.amount ?: if (previousMonthTotal > 0) previousMonthTotal else null
        
        // Handle Day 1 Noise (LOG-005 Fix)
        // If it's the first day, we use the average of (baseline / daysInMonth) and current monthSpent 
        // to avoid extreme swings if a user makes a big purchase on Day 1.
        val dayOfMonthCoerced = dayOfMonth.coerceAtLeast(1)
        val projectedTotal = if (dayOfMonth == 1) {
            // Weighted average on day 1: 70% baseline, 30% current spend extrapolated
            if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
            else monthSpent * daysInMonth
        } else {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        }
            
        // Validated Pace Logic: Handle dayOfMonth=1 or 0 gracefully
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

        // Cumulative Spend Trend Data - Optimized single pass
        val calInstance = Calendar.getInstance()
        val previousMonthDays = calInstance.apply { 
            timeInMillis = previousMonthStart 
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        val currentAmountByDay = DoubleArray(dayOfMonth + 1)
        val previousAmountByDay = DoubleArray(previousMonthDays + 1)

        for (expense in purchases) {
            if (expense.date >= monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= dayOfMonth) currentAmountByDay[day] += expense.amount
            } else if (expense.date >= previousMonthStart && expense.date < monthStart) {
                calInstance.timeInMillis = expense.date
                val day = calInstance.get(Calendar.DAY_OF_MONTH)
                if (day <= previousMonthDays) previousAmountByDay[day] += expense.amount
            }
        }

        var runningTotalCur = 0.0
        val currentMonthDaily = (1..dayOfMonth).map { day ->
            runningTotalCur += currentAmountByDay[day]
            runningTotalCur.toFloat()
        }

        var runningTotalPrev = 0.0
        val previousMonthDaily = (1..previousMonthDays).map { day ->
            runningTotalPrev += previousAmountByDay[day]
            runningTotalPrev.toFloat()
        }
        
        val trend = DashboardWidget.SpendingTrend(
            currentMonthData = currentMonthDaily,
            previousMonthData = previousMonthDaily
        )

        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, purchases.size
        )

        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()

        // Financial Weather (Always added, visibility controlled by config)
        widgets.add(DashboardWidget.FinancialWeatherWidget(weather))

        // Hero: Safe-to-Spend (or total spent if no overall budget)
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )

        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }

        // Spending Trend
        widgets.add(trend)

        // Pending Review Alert
        if (pendingCount > 0) {
            widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        }

        // Natural language insight
        if (insightText != null) {
            widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        }

        // Period summary
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))

        // Budget health
        if (budgetStatuses.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        }

        // Top categories
        if (categoryTotals.isNotEmpty()) {
            widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        }

        // Recent transactions
        if (purchases.isNotEmpty()) {
            widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))
        }
        
        CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = totalSpent,
            txCount = txCount
        )
    }
    .flowOn(Dispatchers.Default) // Compuation on BG thread
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompiledDashboardData(emptyList(), 0.0, 0)) // Cache results

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
                PlannedExpense(
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
        }
    }
}

data class FiveData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather
)

data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int
)
