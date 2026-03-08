package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true,
    val isEditMode: Boolean = false,
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dashboardDataProvider: DashboardDataProvider,
    private val dashboardRepository: DashboardRepository,
    private val categoryRepository: CategoryRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val computeDashboardWidgetsUseCase: ComputeDashboardWidgetsUseCase
) : ViewModel() {

    private val isEditMode = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                categoryRepository.ensureDefaultCategories()
            } catch (e: Exception) {
                Timber.e(e, "Failed to ensure default categories")
            }
        }
    }

    private val processedDataFlow: Flow<CompiledDashboardData> =
        dashboardDataProvider.getProcessedDataFlow(analyticsRepository)
            .map { processedData ->
                computeDashboardWidgetsUseCase.compute(processedData)
            }
            .catch { e ->
                Timber.e(e, "Error processing dashboard data")
                emit(CompiledDashboardData(emptyList(), 0.0, 0))
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CompiledDashboardData(emptyList(), 0.0, 0)
            )

    val dashboard: StateFlow<DashboardState> = combine(
        processedDataFlow,
        isEditMode,
        dashboardRepository.configFlow
    ) { compiledData, editMode, configList ->
        val sortedWidgets = configList
            .filter { it.isVisible || editMode }
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

        dashboardRepository.saveDashboardConfigSync(currentConfig.sortedBy { it.order })
    }

    fun toggleWidgetVisibility(widgetId: String) {
        val currentConfig = dashboardRepository.getDashboardConfig().map {
            if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
        }
        dashboardRepository.saveDashboardConfigSync(currentConfig)
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
            is DashboardWidget.SafeToSpend          -> "safe_to_spend"
            is DashboardWidget.SpendingPaceWidget   -> "spending_pace"
            is DashboardWidget.PendingReviewAlert   -> "review_alert"
            is DashboardWidget.SpendingTrend        -> "spending_trend"
            is DashboardWidget.NaturalLanguageInsight -> "insight"
            is DashboardWidget.PeriodSummary        -> "period_summary"
            is DashboardWidget.BudgetHealthWidget   -> "budget_health"
            is DashboardWidget.TopCategories        -> "top_categories"
            is DashboardWidget.RecentTransactions   -> "recent_transactions"
            is DashboardWidget.FinancialWeatherWidget -> "financial_weather"
            is DashboardWidget.BudgetBlockParty     -> "budget_block_party"
            is DashboardWidget.FinancialRunway      -> "financial_runway"
            is DashboardWidget.MonteCarloForecast   -> "monte_carlo_forecast"
        }
    }
}
