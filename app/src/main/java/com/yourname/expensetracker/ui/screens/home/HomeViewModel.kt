package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.toRuntimeStatusMessage
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI model for a dashboard AI briefing surface
// ---------------------------------------------------------------------------

/**
 * UI-layer representation of an AI dashboard briefing.
 *
 * Separate from the domain [DashboardBriefing] so the ViewModel can enrich it
 * with display hints (icon, isAi flag) without coupling the domain to UI concerns.
 */
data class DashboardBriefingUi(
    val title: String,
    val text: String,
    val icon: String,
    /** True when this text came from an AI artifact rather than deterministic logic. */
    val isAi: Boolean,
    val runtimeStatusMessage: String? = null
)

// ---------------------------------------------------------------------------
// Dashboard screen state
// ---------------------------------------------------------------------------

data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true,
    val isEditMode: Boolean = false,
    val isLoading: Boolean = true,
    /**
     * AI briefing surface state.
     * [AiLoadState.Disabled] when AI is off — the screen shows deterministic fallback.
     * [AiLoadState.Ready] when a READY artifact exists — overrides the deterministic widget.
     */
    val aiBriefing: AiLoadState<DashboardBriefingUi> = AiLoadState.Disabled
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dashboardDataProvider: DashboardDataProvider,
    private val dashboardRepository: DashboardRepository,
    private val categoryRepository: CategoryRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val computeDashboardWidgetsUseCase: ComputeDashboardWidgetsUseCase,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiEnvironmentMonitor: AiEnvironmentMonitor,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val isEditMode = MutableStateFlow(false)
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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

    /**
     * Combined AI briefing state: merges settings + artifact into a single
     * [AiLoadState<DashboardBriefingUi>] so the outer [combine] stays at 4 sources.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val aiBriefingFlow: Flow<AiLoadState<DashboardBriefingUi>> =
        aiSettingsRepository.settings()
            .flatMapLatest { settings ->
                if (!settings.aiEnabled || !settings.dashboardBriefingEnabled) {
                    flowOf(AiLoadState.Disabled)
                } else {
                    val targetKey = "dashboard_home:${dateKeyFormat.format(Date(timeProvider.now()))}"
                    val runtimeStatus = aiEnvironmentMonitor
                        .getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
                        .toRuntimeStatusMessage(capabilityLabel = "briefing")
                    aiArtifactRepository.observeLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
                        .map { entity ->
                            when {
                                entity == null -> AiLoadState.Idle
                                entity.status == AiArtifactStatus.RUNNING -> AiLoadState.Loading
                                entity.status == AiArtifactStatus.READY && entity.summaryText != null -> {
                                    AiLoadState.Ready(
                                        DashboardBriefingUi(
                                            title = "AI Briefing",
                                            text  = entity.summaryText,
                                            icon  = "✨",
                                            isAi  = true,
                                            runtimeStatusMessage = runtimeStatus
                                        )
                                    )
                                }
                                entity.status == AiArtifactStatus.FAILED ->
                                    AiLoadState.Error(runtimeStatus ?: entity.errorMessage ?: "Generation failed")
                                else -> AiLoadState.Idle
                            }
                        }
                }
            }
            .catch { e ->
                Timber.e(e, "Error in aiBriefingFlow")
                emit(AiLoadState.Disabled)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AiLoadState.Disabled
            )

    val dashboard: StateFlow<DashboardState> = combine(
        processedDataFlow,
        isEditMode,
        dashboardRepository.configFlow,
        aiBriefingFlow
    ) { compiledData, editMode, configList, aiBriefing ->
        val sortedWidgets = configList
            .filter { it.isVisible || editMode }
            .mapNotNull { conf ->
                compiledData.allWidgets.find { w -> getWidgetId(w) == conf.id }
            }

        DashboardState(
            widgets          = sortedWidgets,
            totalSpent       = compiledData.totalSpent,
            transactionCount = compiledData.txCount,
            isEditMode       = editMode,
            isLoading        = false,
            aiBriefing       = aiBriefing
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
