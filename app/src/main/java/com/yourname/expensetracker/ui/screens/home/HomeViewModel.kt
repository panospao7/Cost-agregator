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
    private val dataFlow = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories,
        budgetRepository.getBudgetStatuses(),
        repository.getPendingReviewCount(),
        financialWeatherRepository.getFinancialWeather()
    ) { expenses, categories, budgetStatuses, pendingCount, weather ->
        FiveData(expenses, categories, budgetStatuses, pendingCount, weather)
    }

    val dashboard: StateFlow<DashboardState> = combine(
        dataFlow,
        isEditMode,
        dashboardRepository.configFlow
    ) { data, editMode, configList ->
        val (expenses, categories, budgetStatuses, pendingCount, weather) = data

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Time boundaries
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        val tempCal = cal.clone() as Calendar
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (tempCal.timeInMillis > todayStart) tempCal.add(Calendar.DAY_OF_YEAR, -7)
        val weekStart = tempCal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis

        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val categoryMap = categories.associateBy { it.id }
        val totalSpent = purchases.sumOf { it.amount }
        val monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount }
        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount }
        val todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount }

        // Days remaining in month
        val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget (if set)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget 

        // Category totals
        val categoryTotals = purchases
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                val catTotal = exps.sumOf { it.amount }
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }

        // Spending pace logic (adapted for flow combine)
        val previousMonthStart = insightsEngine.getMonthPeriod(now, -1).startMs
        val previousMonthEnd = monthStart
        val previousMonthTotal = purchases
            .filter { it.date >= previousMonthStart && it.date < previousMonthEnd }
            .sumOf { it.amount }
        
        val baseline = overallBudget?.budget?.amount ?: if (previousMonthTotal > 0) previousMonthTotal else null
        
        val projectedTotal = if (dayOfMonth > 0)
            monthSpent * daysInMonth.toDouble() / dayOfMonth else monthSpent
            
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expected = baseline * dayOfMonth / daysInMonth
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

        // Cumulative Spend Trend Data
        val currentMonthDaily = (1..dayOfMonth).map { day ->
            val dayStart = monthStart + (day - 1) * 24 * 60 * 60 * 1000L
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L
            purchases.filter { it.date >= monthStart && it.date < dayEnd }.sumOf { it.amount }.toFloat()
        }

        val previousMonthDays = Calendar.getInstance().apply {
            timeInMillis = previousMonthStart
        }.getActualMaximum(Calendar.DAY_OF_MONTH)

        val previousMonthDaily = (1..previousMonthDays).map { day ->
            val pMonthStart = previousMonthStart
            val pDayEnd = pMonthStart + day * 24 * 60 * 60 * 1000L
            purchases.filter { it.date >= pMonthStart && it.date < pDayEnd }.sumOf { it.amount }.toFloat()
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

        // === Apply Custom Layout ===
        val sortedWidgets = configList
            .filter { it.isVisible || editMode } // Show all in edit mode, otherwise filter
            .mapNotNull { conf ->
                widgets.find { w -> getWidgetId(w) == conf.id }
            }

        DashboardState(
            widgets = sortedWidgets,
            totalSpent = totalSpent,
            transactionCount = purchases.size,
            isEditMode = editMode,
            isLoading = false
        )
    }
        .debounce(300)
        .flowOn(Dispatchers.Default)
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
        isEditMode.value = isEditMode.value 
    }

    fun toggleWidgetVisibility(widgetId: String) {
        val currentConfig = dashboardRepository.getDashboardConfig().map {
            if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
        }
        dashboardRepository.saveDashboardConfig(currentConfig)
        isEditMode.value = isEditMode.value
    }

    private fun getWidgetId(widget: DashboardWidget): String = when (widget) {
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
}
data class FiveData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather
)
