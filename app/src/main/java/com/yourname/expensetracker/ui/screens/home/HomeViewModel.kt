package com.yourname.expensetracker.ui.screens.home

import android.app.Application
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
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.toDiagnosticsOrNull
import com.yourname.expensetracker.domain.ai.model.toDisplayText
import com.yourname.expensetracker.domain.ai.model.toRuntimeStatusMessage
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.analytics.TotalsAggregationEngine
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.model.PeriodDrillDownState
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidgetRegistry
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.NavigationAction
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.RecommendationDismissalHandler
import com.yourname.expensetracker.service.RecommendationStateManager
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.widget.model.StyledWidgets
import com.yourname.expensetracker.domain.widget.model.WidgetStyle
import com.yourname.expensetracker.domain.widget.model.WidgetStyleConfig
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val title: UiText,
    val text: UiText,
    val icon: String,
    /** True when this text came from an AI artifact rather than deterministic logic. */
    val isAi: Boolean,
    val runtimeStatusMessage: String? = null,
    val diagnostics: String? = null
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
    val error: UiText? = null,
    val aiBriefing: AiLoadState<DashboardBriefingUi> = AiLoadState.Disabled,
    val widgetStyles: WidgetStyleConfig = WidgetStyleConfig(),
    val categoryTrends: Map<Long, com.yourname.expensetracker.ui.components.CategoryTrendInfo> = emptyMap(),
    val referenceNowMillis: Long = 0L,
    /** S4-006: true when any currency conversion failed for dashboard totals */
    val isPartial: Boolean = false
)

private sealed interface ProcessedDashboardUiState {
    data object Loading : ProcessedDashboardUiState
    data class Ready(val data: CompiledDashboardData) : ProcessedDashboardUiState
    data class Error(val error: UiText) : ProcessedDashboardUiState
}

/** S4-004R: Typed home-currency loading state exposed to UI. */
sealed interface HomeCurrencyUiState {
    data object Loading : HomeCurrencyUiState
    data class Ready(val code: String) : HomeCurrencyUiState
    data class Error(val message: UiText) : HomeCurrencyUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val dashboardDataProvider: DashboardDataProvider,
    private val dashboardRepository: DashboardRepository,
    private val categoryRepository: CategoryRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val analyticsRepository: DashboardAnalyticsRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val computeDashboardWidgetsUseCase: ComputeDashboardWidgetsUseCase,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val aiEnvironmentMonitor: AiEnvironmentMonitor,
    private val aiEngagementRepository: AiEngagementRepository,
    private val widgetStyleRepository: WidgetStyleRepository,
    private val timeProvider: TimeProvider,
    private val recommendationStateManager: RecommendationStateManager,
    private val navigationTargetResolver: NavigationTargetResolver,
    private val recommendationDismissalHandler: RecommendationDismissalHandler,
    private val totalsAggregationEngine: TotalsAggregationEngine,
    private val advancedAnalyticsEngine: com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine,
    private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _totalsDrillDownState = MutableStateFlow(PeriodDrillDownState(
        currentLevel = PeriodType.MONTH,
        selectedPeriod = null,
        breadcrumb = emptyList(),
        periodTotals = emptyList(),
        categoryBreakdown = emptyList(),
        isLoading = true
    ))

    val totalsDrillDownState: StateFlow<PeriodDrillDownState> = 
        _totalsDrillDownState.asStateFlow()

    private val isEditMode = MutableStateFlow(false)
    private val dashboardReloadTrigger = MutableStateFlow(0)
    private val _categoryTrends = MutableStateFlow<Map<Long, com.yourname.expensetracker.ui.components.CategoryTrendInfo>>(emptyMap())
    /** S4-004R: Typed currency loading state for UI loading/error rendering. */
    val homeCurrencyState: StateFlow<HomeCurrencyUiState> = currencySettingsRepository.homeCurrency()
        .map<String, HomeCurrencyUiState> { HomeCurrencyUiState.Ready(it) }
        .catch { emit(HomeCurrencyUiState.Error(UiText.StringResource(R.string.home_error_currency_unavailable))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeCurrencyUiState.Loading)

    /** S4-D914-004: Derived from homeCurrencyState — cannot diverge. */
    val homeCurrency: StateFlow<String?> = homeCurrencyState
        .map { (it as? HomeCurrencyUiState.Ready)?.code }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val dateKeyFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private fun dashboardBriefingKeyForToday(): String =
        "dashboard_home:${Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).format(dateKeyFormat)}"
    // TODO: Replace with actual UserSessionProvider
    private val defaultRecommendationUserId = "default_user"

    val recommendations: StateFlow<List<DashboardFollowThroughRecommendation>> =
        recommendationStateManager.recommendations

    private val _selectedRecommendation = MutableStateFlow<DashboardFollowThroughRecommendation?>(null)
    val selectedRecommendation: StateFlow<DashboardFollowThroughRecommendation?> =
        _selectedRecommendation.asStateFlow()

    private val _navigationActions = MutableSharedFlow<NavigationAction>(extraBufferCapacity = 1)
    val navigationActions: SharedFlow<NavigationAction> = _navigationActions.asSharedFlow()

    /** S4-003: Serializes widget config read-modify-write operations */
    private val widgetConfigMutex = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch {
            try {
                categoryRepository.ensureDefaultCategories()
            } catch (e: Exception) {
                Timber.e(e, "Failed to ensure default categories")
            }
        }

        recommendationStateManager.refreshForUser(defaultRecommendationUserId)

        // S4-009: Load initial totals in ViewModel using injected TimeProvider — no Calendar.getInstance() fallback
        loadTotalsForYear(com.yourname.expensetracker.domain.util.TimePeriodUtils.getYear(timeProvider.now()))

        // S4-010: Refresh dashboard at local midnight so time-sensitive widgets stay current
        viewModelScope.launch {
            while (true) {
                val now = timeProvider.now()
                val nextMidnight = java.time.Instant.ofEpochMilli(now)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                val delayMs = (nextMidnight - now).coerceAtLeast(60_000L)
                kotlinx.coroutines.delay(delayMs)
                // Trigger dashboard reload by bumping the refresh trigger
                reloadDashboard()
            }
        }

        // S4-011R: collectLatest cancels the suspend body directly — no inner launch
        viewModelScope.launch {
            homeCurrency.filterNotNull().collectLatest { currency ->
                loadCategoryTrendsForCurrency(currency)
            }
        }
    }

    private val processedDataFlow: StateFlow<ProcessedDashboardUiState> =
        dashboardReloadTrigger
            .flatMapLatest {
                dashboardDataProvider.getProcessedDataFlow(analyticsRepository)
                    .map { processedData ->
                        ProcessedDashboardUiState.Ready(
                            computeDashboardWidgetsUseCase.compute(processedData)
                        )
                    }
                    .catch<ProcessedDashboardUiState> { e ->
                        Timber.e(e, "Error processing dashboard data")
                        emit(
                            ProcessedDashboardUiState.Error(
                                UiText.StringResource(R.string.home_error_unable_to_load_dashboard)
                            )
                        )
                    }
                    .onStart {
                        emit(ProcessedDashboardUiState.Loading)
                    }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ProcessedDashboardUiState.Loading
            )

    /**
     * Combined AI briefing state: merges settings + artifact into a single
     * [AiLoadState<DashboardBriefingUi>] so the outer [combine] stays at 4 sources.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val aiBriefingFlow: Flow<AiLoadState<DashboardBriefingUi>> =
        aiSettingsRepository.settings()
            .flatMapLatest { settings ->
                aiEngagementRepository.engagementState().flatMapLatest { engagementState ->
                    if (!settings.aiEnabled || !settings.dashboardBriefingEnabled) {
                        flowOf(AiLoadState.Disabled)
                    } else {
                        val targetKey = engagementState.lastOpenedDashboardBriefingKey ?: dashboardBriefingKeyForToday()
                        aiArtifactRepository.observeLatest(targetKey, AiCapability.DASHBOARD_BRIEFING)
                            .map { entity ->
                                val runtimeStatus = when {
                                    entity?.mode == AiMode.CLOUD -> null
                                    entity?.mode == AiMode.ON_DEVICE -> aiEnvironmentMonitor
                                        .getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
                                        .toRuntimeStatusMessage(capabilityLabel = "briefing")
                                    settings.preferredMode == AiMode.ON_DEVICE -> aiEnvironmentMonitor
                                        .getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
                                        .toRuntimeStatusMessage(capabilityLabel = "briefing")
                                    !settings.allowCloudAi && settings.allowOnDeviceAi -> aiEnvironmentMonitor
                                        .getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
                                        .toRuntimeStatusMessage(capabilityLabel = "briefing")
                                    else -> null
                                }

                                when {
                                    entity == null -> AiLoadState.Idle
                                    entity.status == AiArtifactStatus.RUNNING -> AiLoadState.Loading
                                    entity.status == AiArtifactStatus.READY && entity.summaryText != null -> {
                                    AiLoadState.Ready(
                                        DashboardBriefingUi(
                                            title = UiText.StringResource(R.string.home_ai_briefing_title),
                                            text  = UiText.from(entity.summaryText!!),
                                            icon  = "✨",
                                            isAi  = true,
                                            runtimeStatusMessage = runtimeStatus,
                                            diagnostics = entity.toDiagnosticsOrNull()?.toDisplayText()
                                        )
                                    )
                                    }
                                    entity.status == AiArtifactStatus.FAILED ->
                                        AiLoadState.Error(runtimeStatus ?: entity.errorMessage ?: application.getString(R.string.home_error_generation_failed))
                                    else -> AiLoadState.Idle
                                }
                            }
                    }
                }
            }
            .catch { e ->
                Timber.e(e, "Error in aiBriefingFlow")
                // S4-020: Emit Error so UI can distinguish failure from intentionally disabled
                emit(AiLoadState.Error(application.getString(R.string.home_ai_briefing_unavailable)))
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
        aiBriefingFlow,
        widgetStyleRepository.config(),
        _categoryTrends
    ) { params: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val processedDataState = params[0] as ProcessedDashboardUiState
        val editMode = params[1] as Boolean
        val configList = params[2] as List<com.yourname.expensetracker.data.database.model.DashboardWidgetConfig>
        val aiBriefing = params[3] as AiLoadState<DashboardBriefingUi>
        val widgetStyles = params[4] as WidgetStyleConfig
        val categoryTrends = params[5] as Map<Long, com.yourname.expensetracker.ui.components.CategoryTrendInfo>
        val referenceNowMillis = timeProvider.now()

        if (processedDataState is ProcessedDashboardUiState.Loading) {
            return@combine DashboardState(
                isEditMode = editMode,
                isLoading = true,
                error = null,
                aiBriefing = aiBriefing,
                widgetStyles = widgetStyles,
                categoryTrends = categoryTrends,
                referenceNowMillis = referenceNowMillis
            )
        }

        if (processedDataState is ProcessedDashboardUiState.Error) {
            return@combine DashboardState(
                isEditMode = editMode,
                isLoading = false,
                error = processedDataState.error,
                aiBriefing = aiBriefing,
                widgetStyles = widgetStyles,
                categoryTrends = categoryTrends,
                referenceNowMillis = referenceNowMillis
            )
        }

        val compiledData = (processedDataState as ProcessedDashboardUiState.Ready).data
        
        val sortedWidgets = configList
            .filter { it.isVisible || editMode }
            .mapNotNull { conf ->
                val widget = compiledData.allWidgets.find { w -> DashboardWidgetRegistry.idFor(w) == conf.id }
                when {
                    // S4-D914-002: Truly unknown ID (not in registry at all)
                    conf.id !in DashboardWidgetRegistry.allIds ->
                        Timber.w("Unknown widget config ID '${conf.id}' — not in registry")
                    // Known widget that is conditionally absent (no data) — silent, not an error
                    widget == null -> { /* known-unavailable: no log */ }
                }
                widget
            }

        DashboardState(
            widgets          = sortedWidgets,
            totalSpent       = compiledData.totalSpent,
            transactionCount = compiledData.txCount,
            isEditMode       = editMode,
            isLoading        = false,
            error            = null,
            aiBriefing       = aiBriefing,
            widgetStyles     = widgetStyles,
            categoryTrends   = categoryTrends,
            referenceNowMillis = referenceNowMillis,
            isPartial        = compiledData.isPartial
        )
    }.catch { e ->
        Timber.e(e, "Error loading dashboard data")
        emit(DashboardState(isLoading = false, error = UiText.StringResource(R.string.home_error_unable_to_load_dashboard)))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleEditMode() {
        isEditMode.value = !isEditMode.value
    }

    /**
     * Reloads the dashboard data by re-triggering the data flows.
     * Used for retry actions after errors.
     */
    fun reloadDashboard() {
        viewModelScope.launch {
            dashboardReloadTrigger.update { it + 1 }
            // Refresh recommendations
            recommendationStateManager.refreshForUser(defaultRecommendationUserId)
            // Reload category trends
            loadCategoryTrends()
            // Trigger a totals reload if we have drill-down state
            if (_totalsDrillDownState.value.periodTotals.isNotEmpty()) {
                val currentYear = TimePeriodUtils.getYear(timeProvider.now())
                loadTotalsForYear(currentYear)
            }
        }
    }

    fun moveWidget(widgetId: String, moveUp: Boolean) {
        viewModelScope.launch {
            widgetConfigMutex.withLock {
                val currentConfig = dashboardRepository.getDashboardConfig().toMutableList()
                // S4-D914-003: Move among rendered widget IDs only.
                // Unavailable (conditionally absent) widgets are skipped so moves are never no-ops.
                val renderedIds = dashboard.value.widgets.map { DashboardWidgetRegistry.idFor(it) }
                val orderedIndex = renderedIds.indexOf(widgetId)
                if (orderedIndex == -1) return@withLock

                val targetOrderedIndex = if (moveUp) orderedIndex - 1 else orderedIndex + 1
                if (targetOrderedIndex !in renderedIds.indices) return@withLock

                val targetId = renderedIds[targetOrderedIndex]
                val fromIndex = currentConfig.indexOfFirst { it.id == widgetId }
                val toIndex = currentConfig.indexOfFirst { it.id == targetId }
                if (fromIndex == -1 || toIndex == -1) return@withLock

                val temp = currentConfig[fromIndex]
                currentConfig[fromIndex] = currentConfig[toIndex].copy(order = fromIndex)
                currentConfig[toIndex] = temp.copy(order = toIndex)

                dashboardRepository.saveDashboardConfig(currentConfig.sortedBy { it.order })
            }
        }
    }

    fun toggleWidgetVisibility(widgetId: String) {
        viewModelScope.launch {
            // S4-003: Serialize config mutations
            widgetConfigMutex.withLock {
                val currentConfig = dashboardRepository.getDashboardConfig().map {
                    if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
                }
                dashboardRepository.saveDashboardConfig(currentConfig)
            }
        }
    }

    /**
     * Toggle the visual style (MODERN/RETRO) for a specific widget.
     * Only works for widgets defined in [StyledWidgets].
     */
    fun toggleWidgetStyle(widgetId: String) {
        if (widgetId !in StyledWidgets.all) return
        
        viewModelScope.launch {
            widgetStyleRepository.toggleWidgetStyle(widgetId)
        }
    }

    /**
     * Get the current style for a specific widget.
     */
    fun getWidgetStyle(widgetId: String): WidgetStyle {
        return dashboard.value.widgetStyles.getStyle(widgetId)
    }

    /**
     * S4-011R: Suspend — called directly inside collectLatest so cancellation propagates.
     * S4-012R: Typed state: emptyMap on error is replaced by explicit error logging.
     */
    private suspend fun loadCategoryTrendsForCurrency(currency: String) {
        try {
            val period = advancedAnalyticsEngine.getPeriodRange(
                com.yourname.expensetracker.domain.analytics.AnalyticsPeriod.MONTH
            )
            val (analytics, _) = advancedAnalyticsEngine.getCategoryAnalytics(period, displayCurrency = currency)
            _categoryTrends.value = analytics.associate { analytic ->
                analytic.category.id to com.yourname.expensetracker.ui.components.CategoryTrendInfo(
                    previousTotal = analytic.previousPeriodTotal,
                    changePercent = analytic.changePercent,
                    direction = analytic.trendDirection,
                    averageOverMonths = null,
                    monthsOfData = 1
                )
            }
            Timber.d("Loaded ${_categoryTrends.value.size} category trends for $currency")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S4-D914-010: never swallow cancellation
        } catch (e: Exception) {
            Timber.e(e, "Error loading category trends for $currency")
            // S4-012R: Keep existing trends rather than wiping to emptyMap on transient error
        }
    }

    /** Public entry point for manual reload (e.g. retry button). */
    fun loadCategoryTrends() {
        viewModelScope.launch {
            val currency = homeCurrency.value ?: return@launch
            loadCategoryTrendsForCurrency(currency)
        }
    }

    /**
     * Navigate to transaction list filtered by category.
     */
    fun navigateToCategoryTransactions(categoryId: Long) {
        viewModelScope.launch {
            val action = NavigationAction.ToTransactionList(
                filter = com.yourname.expensetracker.ui.screens.transactions.TransactionFilter(
                    categoryId = categoryId
                )
            )
            _navigationActions.emit(action)
        }
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

    fun navigateToRecommendation(rec: DashboardFollowThroughRecommendation) {
        _selectedRecommendation.value = rec
        viewModelScope.launch {
            try {
                val action = navigationTargetResolver.resolve(rec.navigationTarget, rec.filterCriteria)
                if (action is NavigationAction.NoOp) {
                    _selectedRecommendation.value = null
                    Timber.w("Recommendation navigation resolved to NoOp for target: ${rec.navigationTarget}")
                } else {
                    _navigationActions.emit(action)
                    // S4-013: Clear selection after successful navigation
                    _selectedRecommendation.value = null
                }
            } catch (e: Exception) {
                // S4-014: On error, clear selection and log
                _selectedRecommendation.value = null
                Timber.e(e, "Failed to navigate to recommendation: ${rec.navigationTarget}")
            }
        }
    }

    fun dismissRecommendation(rec: DashboardFollowThroughRecommendation) {
        viewModelScope.launch {
            recommendationDismissalHandler.dismiss(rec)
            if (_selectedRecommendation.value?.id == rec.id) {
                _selectedRecommendation.value = null
            }
        }
    }

    fun reloadCurrentTotalsLevel() {
        val state = _totalsDrillDownState.value
        val year = com.yourname.expensetracker.ui.util.UiTimeUtils.getYear(timeProvider.now())
        loadTotalsForYear(year)
    }

    fun loadTotalsForYear(year: Int) {
        viewModelScope.launch {
            _totalsDrillDownState.update { it.copy(isLoading = true) }
            try {
                Timber.d("Loading totals for year $year")
                val totals = totalsAggregationEngine.getMonthlyTotals(year).first()
                Timber.d("Got ${totals.size} monthly totals for year $year")
                val average = totalsAggregationEngine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)
                Timber.d("Average for month: $average")
                
                val updatedTotals = totals.map { period ->
                    period.copy(status = totalsAggregationEngine.getPeriodStatus(period.totalAmount, average))
                }
                
                _totalsDrillDownState.update { state ->
                    state.copy(
                        currentLevel = PeriodType.MONTH,
                        periodTotals = updatedTotals,
                        isLoading = false,
                        error = null
                    )
                }
                Timber.d("Totals loaded successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error loading totals for year $year")
                _totalsDrillDownState.update { it.copy(isLoading = false, error = UiText.StringResource(R.string.home_error_unable_to_load_totals)) }
            }
        }
    }

    fun drillDownToPeriod(period: PeriodTotal) {
        viewModelScope.launch {
            _totalsDrillDownState.update { it.copy(isLoading = true) }
            try {
                @Suppress("UNCHECKED_CAST")
                val whenResult = when (period.periodType) {
                    PeriodType.YEAR -> {
                        val totals = totalsAggregationEngine.getMonthlyTotals(parseYear(period.periodKey)).first()
                        arrayOf(PeriodType.MONTH, totals, emptyList<CategoryBreakdown>())
                    }
                    PeriodType.MONTH -> {
                        val (year, month) = parseYearMonth(period.periodKey)
                        val totals = totalsAggregationEngine.getWeeklyTotals(year, month).first()
                        arrayOf(PeriodType.WEEK, totals, emptyList<CategoryBreakdown>())
                    }
                    PeriodType.WEEK -> {
                        // Use actual stored date range instead of recalculating from weekKey
                        // This prevents duplicate days from mismatched week boundaries
                        Timber.d("Drilling down from WEEK to DAY using stored range: ${period.startDateMs} to ${period.endDateMs}")
                        val dailyTotals = totalsAggregationEngine.getDailyTotalsForRange(
                            period.startDateMs, 
                            period.endDateMs
                        ).first()
                        Timber.d("Got ${dailyTotals.size} daily totals for range")
                        arrayOf(PeriodType.DAY, dailyTotals, emptyList<CategoryBreakdown>())
                    }
                    PeriodType.DAY -> {
                        // S4-007: DAY is a leaf node — update selectedPeriod only, do NOT append breadcrumb
                        _totalsDrillDownState.update { state ->
                            state.copy(selectedPeriod = period, isLoading = false)
                        }
                        return@launch
                    }
                }
                
                val newLevel = whenResult[0] as PeriodType
                val newTotals = whenResult[1] as List<PeriodTotal>
                val categories = whenResult[2] as List<CategoryBreakdown>
                
                val average = totalsAggregationEngine.getAverageForPeriodType(newLevel, excludeCurrent = false)
                val updatedTotals = newTotals.map { p ->
                    p.copy(status = totalsAggregationEngine.getPeriodStatus(p.totalAmount, average))
                }

                _totalsDrillDownState.update { state ->
                    state.copy(
                        currentLevel = newLevel,
                        selectedPeriod = period,
                        breadcrumb = state.breadcrumb + listOfNotNull(state.selectedPeriod),
                        periodTotals = updatedTotals,
                        categoryBreakdown = categories,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _totalsDrillDownState.update { it.copy(isLoading = false, error = UiText.StringResource(R.string.home_error_unable_to_load_breakdown)) }
            }
        }
    }

    fun drillUp() {
        val state = _totalsDrillDownState.value
        
        // Calculate new level (go up one level)
        val newLevel = when (state.currentLevel) {
            PeriodType.DAY -> PeriodType.WEEK
            PeriodType.WEEK -> PeriodType.MONTH
            PeriodType.MONTH -> PeriodType.YEAR
            PeriodType.YEAR -> return // Already at top, can't go up
        }

        viewModelScope.launch {
            _totalsDrillDownState.update { it.copy(isLoading = true) }
            try {
                // Load data for the new level
                val (newTotals, newSelectedPeriod, newParentPeriod) = when (newLevel) {
                    PeriodType.YEAR -> {
                        val years = totalsAggregationEngine.getYearlyTotals().first()
                        val avg = totalsAggregationEngine.getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)
                        val updatedYears = years.map { it.copy(status = totalsAggregationEngine.getPeriodStatus(it.totalAmount, avg)) }
                        Triple(updatedYears, null, null)
                    }
                    PeriodType.MONTH -> {
                        // Going from WEEK to MONTH or from DAY to MONTH via parent
                        val parent = state.parentPeriod
                        if (parent != null) {
                            val year = parseYear(parent.periodKey)
                            val months = totalsAggregationEngine.getMonthlyTotals(year).first()
                            val avg = totalsAggregationEngine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)
                            val updatedMonths = months.map { it.copy(status = totalsAggregationEngine.getPeriodStatus(it.totalAmount, avg)) }
                            // Find grandparent (year) for the month
                            val years = totalsAggregationEngine.getYearlyTotals().first()
                            val grandparent = years.find { it.periodKey == year.toString() }
                            Triple(updatedMonths, parent, grandparent)
                        } else {
                            // Fallback: show all months of current year
                            val currentYear = TimePeriodUtils.getYear(timeProvider.now())
                            val months = totalsAggregationEngine.getMonthlyTotals(currentYear).first()
                            val avg = totalsAggregationEngine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)
                            val updatedMonths = months.map { it.copy(status = totalsAggregationEngine.getPeriodStatus(it.totalAmount, avg)) }
                            Triple(updatedMonths, null, null)
                        }
                    }
                    PeriodType.WEEK -> {
                        // Going from DAY to WEEK
                        val parent = state.parentPeriod
                        if (parent != null) {
                            val (year, month) = parseYearMonth(parent.periodKey)
                            val weeks = totalsAggregationEngine.getWeeklyTotals(year, month).first()
                            val avg = totalsAggregationEngine.getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)
                            val updatedWeeks = weeks.map { it.copy(status = totalsAggregationEngine.getPeriodStatus(it.totalAmount, avg)) }
                            // Find grandparent (month) for the week
                            val months = totalsAggregationEngine.getMonthlyTotals(year).first()
                            val grandparent = months.find { it.periodKey == parent.periodKey }
                            Triple(updatedWeeks, parent, grandparent)
                        } else {
                            Triple(emptyList(), null, null)
                        }
                    }
                    PeriodType.DAY -> Triple(emptyList(), null, null) // Should never happen
                }

                _totalsDrillDownState.update {
                    it.copy(
                        currentLevel = newLevel,
                        selectedPeriod = newSelectedPeriod,
                        breadcrumb = if (it.breadcrumb.isNotEmpty()) it.breadcrumb.dropLast(1) else emptyList(),
                        periodTotals = newTotals,
                        categoryBreakdown = emptyList(),
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error drilling up from ${state.currentLevel} to $newLevel")
                _totalsDrillDownState.update { it.copy(isLoading = false, error = UiText.StringResource(R.string.home_error_unable_to_go_back)) }
            }
        }
    }

    private fun parseYear(key: String): Int = key.split("-").first().toInt()

    private fun parseYearMonth(key: String): Pair<Int, Int> {
        val parts = key.split("-")
        return Pair(parts[0].toInt(), parts[1].toInt())
    }

    private fun parseYearWeek(key: String): Pair<Int, Int> {
        // Handle both formats: "2024-W3" and "2024-03" (from SQL strftime)
        val parts = if (key.contains("-W")) {
            key.split("-W")
        } else {
            key.split("-")
        }
        return if (parts.size >= 2) {
            Pair(parts[0].toInt(), parts[1].toInt())
        } else {
            // Fallback: return current year/week if parsing fails
            val now = timeProvider.now()
            Pair(TimePeriodUtils.getYear(now), TimePeriodUtils.getWeekOfYear(now))
        }
    }

    /**
     * Load category breakdown for a specific period.
     */
    fun loadCategoryBreakdownForPeriod(period: PeriodTotal) {
        viewModelScope.launch {
            try {
                val categories = totalsAggregationEngine.getCategoryBreakdown(
                    period.startDateMs, 
                    period.endDateMs, 
                    period.periodLabel
                ).first()
                
                _totalsDrillDownState.update { 
                    it.copy(categoryBreakdown = categories)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading category breakdown for period ${period.periodLabel}")
            }
        }
    }

    /**
     * Load category breakdown for the current period.
     * If a period is selected, load breakdown for that period.
     * Otherwise, load breakdown for the current view level (all periods combined).
     */
    fun loadCategoryBreakdownForCurrentPeriod() {
        viewModelScope.launch {
            try {
                val state = _totalsDrillDownState.value
                val (startMs, endMs, label) = if (state.selectedPeriod != null) {
                    Triple(
                        state.selectedPeriod.startDateMs,
                        state.selectedPeriod.endDateMs,
                        state.selectedPeriod.periodLabel
                    )
                } else {
                    // Calculate range for all visible periods combined
                    if (state.periodTotals.isNotEmpty()) {
                        val start = state.periodTotals.minOf { it.startDateMs }
                        val end = state.periodTotals.maxOf { it.endDateMs }
                        val labelRes = when (state.currentLevel) {
                            PeriodType.YEAR -> R.string.period_overview_year
                            PeriodType.MONTH -> R.string.period_overview_month
                            PeriodType.WEEK -> R.string.period_overview_week
                            PeriodType.DAY -> R.string.period_overview_day
                        }
                        val label = application.getString(labelRes)
                        Triple(start, end, label)
                    } else {
                        // Fallback to current month
                        val now = timeProvider.now()
                        val (startOfMonth, endOfMonth) = TimePeriodUtils.getMonthRange(now)
                        
                        val monthLabel = DateFormatterUtils.formatTimestampJavaTime(now, "MMM yyyy")
                        Triple(startOfMonth, endOfMonth, monthLabel)
                    }
                }
                
                val categories = totalsAggregationEngine.getCategoryBreakdown(startMs, endMs, label).first()
                
                _totalsDrillDownState.update { 
                    it.copy(categoryBreakdown = categories)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading category breakdown")
            }
        }
    }

    companion object {
        /** S4-001: Deprecated — use [DashboardWidgetRegistry.idFor] directly. */
        @Deprecated("Use DashboardWidgetRegistry.idFor(widget)", ReplaceWith("DashboardWidgetRegistry.idFor(widget)"))
        fun getWidgetId(widget: DashboardWidget): String = DashboardWidgetRegistry.idFor(widget)
    }
}
