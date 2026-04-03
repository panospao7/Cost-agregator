package com.yourname.expensetracker.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.components.analytics.NoSpendStreakWidget
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.ErrorType
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import com.yourname.expensetracker.ui.components.health.HealthScoreWidget
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import com.yourname.expensetracker.ui.components.PeriodLevel
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.asString
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending as DomainCategorySpending
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.widget.model.WidgetStyle
import com.yourname.expensetracker.domain.widget.model.StyledWidgets
import com.yourname.expensetracker.service.NavigationAction
import com.yourname.expensetracker.ui.mappers.toUi
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.navigation.FeatureConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToRecurring: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    onNavigateToAnalytics: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToBudgetDetail: (String) -> Unit = {},
    // Unified Feature Navigation - handles all 22 features from FeatureConfig
    onNavigateToFeature: (NavigationDestination) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.navigationActions.collect { action ->
            when (action) {
                is NavigationAction.ToTransactionList -> onNavigateToTransactions(action.filter)
                is NavigationAction.ToAnalytics -> onNavigateToAnalytics()
                is NavigationAction.ToBudgetDetail -> onNavigateToBudgetDetail(action.category)
                is NavigationAction.ToMap -> onNavigateToMap()
                NavigationAction.NoOp -> Unit
            }
        }
    }

    LaunchedEffect(Unit) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        viewModel.loadTotalsForYear(currentYear)
    }

    var showQuickSettings by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showAddPlannedExpenseDialog by remember { mutableStateOf(false) }
    var showCategoryBreakdown by remember { mutableStateOf(false) }
    var showFeaturesMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(isActive = state.isServiceRunning)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.home_dashboard_title), 
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    val editModeExitDesc = stringResource(R.string.home_edit_mode_exit)
                    val editModeEnterDesc = stringResource(R.string.home_edit_mode_enter)
                    val featuresMenuDesc = stringResource(R.string.a11y_open_features_menu)
                    val settingsMenuDesc = stringResource(R.string.a11y_open_settings_menu)
                    
                    IconButton(
                        onClick = { viewModel.toggleEditMode() },
                        modifier = Modifier.semantics { 
                            contentDescription = if (state.isEditMode) editModeExitDesc else editModeEnterDesc
                        }
                    ) {
                        Icon(
                            if (state.isEditMode) Icons.Rounded.Check else Icons.Rounded.EditAttributes, 
                            contentDescription = null,
                            tint = if (state.isEditMode) SemanticColors.SuccessGreen else SemanticColors.TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { showFeaturesMenu = true },
                        modifier = Modifier.semantics { 
                            contentDescription = featuresMenuDesc
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Apps, 
                            contentDescription = null,
                            tint = SemanticColors.TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { showQuickSettings = true },
                        modifier = Modifier.semantics { 
                            contentDescription = settingsMenuDesc
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Settings, 
                            contentDescription = null,
                            tint = SemanticColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        when {
            state.error != null -> {
                val errorText = state.error?.asString() ?: ""
                ErrorState(
                    type = ErrorType.UNKNOWN,
                    title = stringResource(R.string.home_error_loading_dashboard),
                    message = errorText,
                    onRetry = { viewModel.reloadDashboard() },
                    modifier = Modifier.padding(padding)
                )
            }
            state.isLoading -> {
            // Show skeleton loading state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Hero card skeleton
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Grid of skeleton cards
                ListSkeleton(itemCount = 6)
            }
            }
            else -> {
                LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = state.widgets,
                    key = { HomeViewModel.getWidgetId(it) },
                    span = { widget ->
                        GridItemSpan(if (isFullSpan(widget)) 2 else 1)
                    },
                    contentType = { it.javaClass.simpleName }
                ) { widget ->
                    val widgetId = HomeViewModel.getWidgetId(widget)
                    val widgetStyle = if (widgetId in StyledWidgets.all) {
                        state.widgetStyles.getStyle(widgetId)
                    } else null
                    
                    WidgetWrapper(
                        widget = widget,
                        isEditMode = state.isEditMode,
                        widgetStyle = widgetStyle,
                        onMoveUp = { viewModel.moveWidget(widgetId, true) },
                        onMoveDown = { viewModel.moveWidget(widgetId, false) },
                        onToggleVisibility = { viewModel.toggleWidgetVisibility(widgetId) },
                        onToggleStyle = if (widgetId in StyledWidgets.all) {
                            { viewModel.toggleWidgetStyle(widgetId) }
                        } else null
                    ) {
                        when (widget) {
                            is DashboardWidget.SafeToSpend -> {
                                HeroBentoCard {
                                    Text(
                                        text = stringResource(R.string.widget_safe_to_spend),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.PrimaryLight,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    AmountText(
                                        amount = widget.amount,
                                        style = MaterialTheme.typography.displayMedium,
                                        color = SemanticColors.TextPrimary
                                    )
                                    if (widget.totalBudget != null) {
                                        LinearProgressIndicator(
                                            progress = { ((widget.totalBudget - widget.amount) / widget.totalBudget).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                                .height(8.dp)
                                                .clip(CircleShape),
                                            color = SemanticColors.PrimaryIndigo,
                                            trackColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        )
                                        Text(
                                            stringResource(R.string.widget_days_remaining_format, widget.daysRemaining),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SemanticColors.TextSecondary,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.BudgetBlockParty -> {
                                val widgetId = HomeViewModel.getWidgetId(widget)
                                val widgetStyle = state.widgetStyles.getStyle(widgetId)
                                
                                if (widgetStyle == WidgetStyle.RETRO) {
                                    RetroBudgetBlockPartyCard(
                                        days = widget.days.toUi(),
                                        modifier = Modifier.fillMaxWidth(),
                                        onNavigateToDay = { dateMs ->
                                            val cal = Calendar.getInstance().apply {
                                                timeInMillis = dateMs
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            val startOfDay = cal.timeInMillis
                                            cal.add(Calendar.DAY_OF_MONTH, 1)
                                            val nextDayStart = cal.timeInMillis
                                            onNavigateToTransactions(
                                                TransactionFilter(dateRange = startOfDay to nextDayStart)
                                            )
                                        }
                                    )
                                } else {
                                    BudgetBlockPartyCard(
                                        days = widget.days.toUi(),
                                        modifier = Modifier.fillMaxWidth(),
                                        onNavigateToDay = { dateMs ->
                                            val cal = Calendar.getInstance().apply {
                                                timeInMillis = dateMs
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            val startOfDay = cal.timeInMillis
                                            cal.add(Calendar.DAY_OF_MONTH, 1)
                                            val nextDayStart = cal.timeInMillis
                                            onNavigateToTransactions(
                                                TransactionFilter(dateRange = startOfDay to nextDayStart)
                                            )
                                        }
                                    )
                                }
                            }
                            is DashboardWidget.SpendingPaceWidget -> {
                                BentoCard {
                                    Text(
                                        stringResource(R.string.widget_pace), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    SpendingPaceGauge(pace = widget.pace)
                                }
                            }
                            is DashboardWidget.PendingReviewAlert -> {
                                val badgeColor = if (widget.count > 0) SemanticColors.WarningOrange else SemanticColors.TextMuted
                                BentoCard(
                                    containerColor = if (widget.count > 0) 
                                        SemanticColors.WarningOrange.copy(alpha = 0.05f) 
                                        else SemanticColors.GlassSurface,
                                    border = BorderStroke(
                                        1.dp, 
                                        if (widget.count > 0) SemanticColors.WarningOrange.copy(alpha = 0.3f) 
                                        else SemanticColors.GlassBorder
                                    ),
                                    onClick = onNavigateToReview
                                ) {
                                    Text(
                                        stringResource(R.string.widget_review), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "${widget.count}", 
                                            style = MaterialTheme.typography.displaySmall, 
                                            fontWeight = FontWeight.ExtraBold, 
                                            color = badgeColor
                                        )
                                        Text(
                                            stringResource(R.string.widget_pending), 
                                            style = MaterialTheme.typography.labelSmall, 
                                            color = badgeColor,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.SpendingTrend -> {
                                BentoCard(
                                    modifier = Modifier.clickable {
                                        onNavigateToTransactions(
                                            TransactionFilter(
                                                dateRange = TimePeriodUtils.getMonthRange(System.currentTimeMillis())
                                            )
                                        )
                                    }
                                ) {
                                    SpendingTrendChart(
                                        series = widget.series
                                    )
                                }
                            }
                            is DashboardWidget.NaturalLanguageInsight -> {
                                // When AI is ready, display the AI briefing text in this slot.
                                // Otherwise fall back to the deterministic insight text/icon.
                                val aiBriefing = state.aiBriefing
                                val displayText = if (aiBriefing is AiLoadState.Ready) {
                                    aiBriefing.value.text.asString()
                                } else {
                                    widget.text.asString()
                                }
                                val displayIcon = if (aiBriefing is AiLoadState.Ready) {
                                    aiBriefing.value.icon
                                } else {
                                    widget.icon
                                }
                                BentoCard(
                                    containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                modifier = Modifier.size(40.dp),
                                                shape = CircleShape,
                                                color = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(displayIcon, fontSize = 20.sp)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(
                                                text = displayText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = SemanticColors.TextPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        if (aiBriefing is AiLoadState.Ready && aiBriefing.value.runtimeStatusMessage != null) {
                                            Text(
                                                text = aiBriefing.value.runtimeStatusMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SemanticColors.TextSecondary
                                            )
                                        }

                                        if (aiBriefing is AiLoadState.Ready && aiBriefing.value.diagnostics != null) {
                                            Text(
                                                text = aiBriefing.value.diagnostics,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SemanticColors.TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                            is DashboardWidget.PeriodSummary -> {
                                BentoCard {
                                    Text(
                                        stringResource(R.string.widget_period_summary), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatLabel(stringResource(R.string.widget_today), "€${String.format("%.2f", widget.todaySpent)}", modifier = Modifier.weight(1f))
                                        StatLabel(stringResource(R.string.widget_week), "€${String.format("%.2f", widget.weekSpent)}", modifier = Modifier.weight(1f))
                                        StatLabel(stringResource(R.string.widget_month), "€${String.format("%.2f", widget.monthSpent)}", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            is DashboardWidget.BudgetHealthWidget -> {
                                BentoCard {
                                    Text(
                                        stringResource(R.string.widget_budget_health), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val summaryText = widget.summary?.asString() ?: stringResource(R.string.widget_all_budgets_on_track)
                                    val hasExceededBudgets = widget.summary != null
                                    Text(
                                        summaryText, 
                                        style = MaterialTheme.typography.titleMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = if (hasExceededBudgets) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                                    )
                                }
                            }
                            is DashboardWidget.TopCategories -> {
                                val widgetId = HomeViewModel.getWidgetId(widget)
                                val widgetStyle = state.widgetStyles.getStyle(widgetId)
                                
                                if (widgetStyle == WidgetStyle.RETRO) {
                                    // Get recent transactions for this month to show in category dialog
                                    val monthRange = TimePeriodUtils.getMonthRange(System.currentTimeMillis())
                                    val recentExpenses = remember { state.widgets.filterIsInstance<DashboardWidget.RecentTransactions>().firstOrNull()?.expenses ?: emptyList() }
                                    
                                    RetroTopCategoriesCard(
                                        categories = widget.categories,
                                        categoryTrends = state.categoryTrends,
                                        transactions = recentExpenses,
                                        modifier = Modifier.fillMaxWidth(),
                                        onViewAllTransactions = { 
                                            // Navigate to transactions filtered by top category
                                            if (widget.categories.isNotEmpty()) {
                                                onNavigateToTransactions(
                                                    TransactionFilter(
                                                        dateRange = monthRange,
                                                        categoryId = widget.categories.first().category.id
                                                    )
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    BentoCard {
                                        Text(
                                            stringResource(R.string.widget_top_categories), 
                                            style = MaterialTheme.typography.labelSmall, 
                                            fontWeight = FontWeight.Bold,
                                            color = SemanticColors.TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        widget.categories.forEach { CategorySpendingRow(it) }
                                    }
                                }
                            }
                            is DashboardWidget.RecentTransactions -> {
                                val categoryMap = remember(categories) {
                                    categories.associate { cat ->
                                        cat.id to try { Color(android.graphics.Color.parseColor(cat.color)) } catch (_: Exception) { Color.Gray }
                                    }
                                }
                                BentoCard {
                                    Text(
                                        stringResource(R.string.widget_recent_activity), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.expenses.forEach { expense ->
                                        RecentExpenseRow(
                                            expense = expense,
                                            categoryColor = expense.categoryId?.let { categoryMap[it] }
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.FinancialWeatherWidget -> {
                                FinancialWeatherCard(
                                    state = widget.weather.state,
                                    headline = widget.weather.headline,
                                    summary = widget.weather.summary,
                                    icon = widget.weather.icon,
                                    totalCommitted = widget.weather.totalCommitted,
                                    totalLikely = widget.weather.totalLikely,
                                    discretionaryBudget = widget.weather.discretionaryBudget,
                                    pastSpendingPoints = widget.weather.pastSpendingPoints,
                                    projectedSpendingPoints = widget.weather.projectedSpendingPoints,
                                    upcomingItems = widget.weather.upcomingItems,
                                    totalRecurringCount = widget.weather.totalRecurringCount,
                                    details = widget.weather.details,
                                    onManageClick = onNavigateToRecurring,
                                    onPlanClick = { showAddPlannedExpenseDialog = true }
                                )
                            }
                            is DashboardWidget.TotalsDashboard -> {
                                val widgetId = HomeViewModel.getWidgetId(widget)
                                val widgetStyle = state.widgetStyles.getStyle(widgetId)
                                val totalsState by viewModel.totalsDrillDownState.collectAsState()
                                
                                if (widgetStyle == WidgetStyle.RETRO) {
                                    RetroTotalsDashboardCard(
                                        periods = totalsState.periodTotals,
                                        currentLevel = totalsState.currentLevel.toPeriodLevel(),
                                        selectedPeriod = totalsState.selectedPeriod,
                                        isLoading = totalsState.isLoading,
                                        averageAmount = if (totalsState.periodTotals.isNotEmpty()) {
                                            totalsState.periodTotals.map { it.totalAmount }.average()
                                        } else 0.0,
                                        onPeriodSelected = { viewModel.drillDownToPeriod(it) },
                                        onLevelChanged = { if (it.ordinal < totalsState.currentLevel.ordinal) viewModel.drillUp() },
                                        onEnterStage = { period ->
                                            // Drill down into the selected period
                                            viewModel.drillDownToPeriod(period)
                                        },
                                        onViewAnalysis = { period ->
                                            // Load breakdown for this specific period
                                            viewModel.loadCategoryBreakdownForPeriod(period)
                                            showCategoryBreakdown = true 
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    TotalsDashboardCard(
                                        periods = totalsState.periodTotals,
                                        currentLevel = totalsState.currentLevel.toPeriodLevel(),
                                        selectedPeriod = totalsState.selectedPeriod,
                                        isLoading = totalsState.isLoading,
                                        onPeriodSelected = { viewModel.drillDownToPeriod(it) },
                                        onLevelChanged = { if (it.ordinal < totalsState.currentLevel.ordinal) viewModel.drillUp() },
                                        onShowCategoryBreakdown = { 
                                            viewModel.loadCategoryBreakdownForCurrentPeriod()
                                            showCategoryBreakdown = true 
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            is DashboardWidget.FinancialRunway -> {
                                FinancialRunwayCard(
                                    daysRemaining = widget.daysRemaining,
                                    discretionaryRemaining = widget.discretionaryRemaining,
                                    averageDailyDiscretionarySpend = widget.averageDailyDiscretionarySpend,
                                    monthlyIncome = widget.monthlyIncome,
                                    committedExpenses = widget.committedExpenses,
                                    likelyExpenses = widget.likelyExpenses,
                                    status = widget.status
                                )
                            }
                            is DashboardWidget.MonteCarloForecast -> {
                                MonteCarloForecastCard(
                                    result = widget.result
                                )
                            }
                            
                            is DashboardWidget.NoSpendStreak -> {
                                NoSpendStreakWidget(
                                    currentStreak = widget.currentStreak,
                                    personalBest = widget.personalBest,
                                    daysWithoutSpendingThisMonth = widget.daysWithoutSpendingThisMonth
                                )
                            }
                            is DashboardWidget.FinancialHealthScoreWidget -> {
                                var isExpanded by remember { mutableStateOf(false) }
                                com.yourname.expensetracker.ui.components.health.HealthScoreWidget(
                                    healthScore = widget.healthScore,
                                    isExpanded = isExpanded,
                                    onToggleExpand = { isExpanded = !isExpanded }
                                )
                            }
                        }
                    }
                }
                
                if (recommendations.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            recommendations.forEach { recommendation ->
                                RecommendationCard(
                                    recommendation = recommendation,
                                    onClick = { viewModel.navigateToRecommendation(recommendation) },
                                    onDismiss = { viewModel.dismissRecommendation(recommendation) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        if (showFeaturesMenu) {
            FeaturesMenu(
                onDismiss = { showFeaturesMenu = false },
                onNavigateToFeature = { destination ->
                    showFeaturesMenu = false
                    onNavigateToFeature(destination)
                }
            )
        }

        if (showQuickSettings) {
            QuickSettingsDialog(
                onDismiss = { showQuickSettings = false },
                onNavigateToAiSettings = {
                    showQuickSettings = false
                    showAiSettings = true
                },
                onNavigateToCategories = { 
                    showQuickSettings = false
                    showCategories = true 
                },
                onNavigateToDebug = {
                    showQuickSettings = false
                    showDebug = true
                }
            )
        }

                if (showAiSettings) {
                    // Navigate via NavigationDestination instead of direct overlay
                    LaunchedEffect(Unit) {
                        showAiSettings = false
                        onNavigateToFeature(NavigationDestination.AiSettings)
                    }
                }

                if (showCategories) {
                    // Navigate via NavigationDestination instead of direct overlay
                    LaunchedEffect(Unit) {
                        showCategories = false
                        onNavigateToFeature(NavigationDestination.CategoryManagement)
                    }
                }

                if (showDebug) {
                    // Debug screen remains as dev-only overlay (expected behavior)
                    com.yourname.expensetracker.ui.screens.debug.DebugScreen(
                        onDismiss = { showDebug = false }
                    )
                }

        if (showAddPlannedExpenseDialog) {
            AddPlannedExpenseDialog(
                categories = categories,
                onDismiss = { showAddPlannedExpenseDialog = false },
                onConfirm = { desc, amount, date, catId, priority ->
                    viewModel.addPlannedExpense(desc, amount, date, catId, priority)
                    showAddPlannedExpenseDialog = false
                }
            )
        }

        if (showCategoryBreakdown) {
            val totalsState by viewModel.totalsDrillDownState.collectAsState()
            // Check if any retro widget is active to determine breakdown style
            val totalsDashboardWidget = state.widgets.filterIsInstance<DashboardWidget.TotalsDashboard>().firstOrNull()
            val isRetroStyle = totalsDashboardWidget?.let { 
                state.widgetStyles.getStyle(HomeViewModel.getWidgetId(it)) == WidgetStyle.RETRO 
            } ?: false
            
            if (isRetroStyle) {
                RetroCategoryBreakdownSheet(
                    periodLabel = totalsState.selectedPeriod?.periodLabel ?: stringResource(R.string.label_period),
                    categories = totalsState.categoryBreakdown,
                    onDismiss = { showCategoryBreakdown = false }
                )
            } else {
                CategoryBreakdownSheet(
                    periodLabel = totalsState.selectedPeriod?.periodLabel ?: stringResource(R.string.label_period),
                    categories = totalsState.categoryBreakdown,
                    onDismiss = { showCategoryBreakdown = false }
                )
            }
        }
    }
}

@Composable
fun WidgetWrapper(
    widget: DashboardWidget,
    isEditMode: Boolean,
    widgetStyle: WidgetStyle?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleStyle: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        
        if (isEditMode) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val moveUpDesc = stringResource(R.string.a11y_move_widget_up)
                    val hideWidgetDesc = stringResource(R.string.a11y_hide_widget)
                    val moveDownDesc = stringResource(R.string.a11y_move_widget_down)
                    
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.semantics { contentDescription = moveUpDesc }
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = Color.White)
                    }
                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.semantics { contentDescription = hideWidgetDesc }
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = Color.White)
                    }
                    
                    // Style toggle button for styled widgets
                    if (onToggleStyle != null) {
                        val toggleStyleDesc = stringResource(
                            R.string.a11y_toggle_widget_style_format,
                            if (widgetStyle == WidgetStyle.MODERN) "retro" else "modern"
                        )
                        IconButton(
                            onClick = onToggleStyle,
                            modifier = Modifier.semantics { 
                                contentDescription = toggleStyleDesc
                            }
                        ) {
                            Icon(
                                imageVector = if (widgetStyle == WidgetStyle.MODERN) 
                                    Icons.Rounded.VideogameAsset 
                                else 
                                    Icons.Rounded.CropSquare,
                                contentDescription = null, 
                                tint = if (widgetStyle == WidgetStyle.RETRO) Color(0xFF39FF14) else Color.White
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.semantics { contentDescription = moveDownDesc }
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}

// getWidgetId removed - using HomeViewModel.getWidgetId instead

private fun isFullSpan(widget: DashboardWidget): Boolean = when (widget) {
    is DashboardWidget.SpendingPaceWidget,
    is DashboardWidget.PendingReviewAlert -> false
    else -> true
}

@Composable
fun QuickSettingsDialog(
    onDismiss: () -> Unit,
    onNavigateToAiSettings: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val aiSettingsDesc = stringResource(R.string.a11y_navigate_to_ai_settings)
    val categoriesDesc = stringResource(R.string.a11y_navigate_to_categories)
    val debugDesc = stringResource(R.string.a11y_navigate_to_debug)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_quick_settings_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_ai_settings)) },
                    leadingContent = { 
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = SemanticColors.PrimaryIndigo
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToAiSettings() }
                        .semantics { contentDescription = aiSettingsDesc }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_categories)) },
                    leadingContent = { 
                        Icon(
                            Icons.Rounded.Label,
                            contentDescription = null,
                            tint = SemanticColors.PrimaryIndigo
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToCategories() }
                        .semantics { contentDescription = categoriesDesc }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_debug_menu)) },
                    leadingContent = { 
                        Icon(
                            Icons.Rounded.Build,
                            contentDescription = null,
                            tint = SemanticColors.PrimaryIndigo
                        )
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToDebug() }
                        .semantics { contentDescription = debugDesc }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.a11y_close)) }
        }
    )
}

@Composable
fun CategorySpendingRow(item: DomainCategorySpending) {
    val spendingDesc = stringResource(
        R.string.a11y_category_spending_format,
        item.category.name,
        item.total,
        item.percentage
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = spendingDesc
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val categoryColor = remember(item.category.color) {
            try { Color(android.graphics.Color.parseColor(item.category.color)) } 
            catch (e: Exception) { Color.Gray }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val iconDesc = stringResource(R.string.a11y_category_icon_format, item.category.name)
            Text(
                item.category.icon, 
                fontSize = 18.sp,
                modifier = Modifier.semantics { contentDescription = iconDesc }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = categoryColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "€${String.format("%.2f", item.total)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${String.format("%.0f", item.percentage)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RecentExpenseRow(expense: Expense, categoryColor: Color? = null) {
    val manualEntryLabel = stringResource(R.string.a11y_expense_manual)
    val expenseDesc = stringResource(
        R.string.a11y_expense_item_format,
        expense.merchant,
        if (expense.isManualEntry) manualEntryLabel else "",
        expense.amount,
        DateFormatterUtils.monthDay().format(Date(expense.date))
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = expenseDesc
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Category color dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        categoryColor ?: SemanticColors.TextMuted,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
                    if (expense.isManualEntry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.a11y_expense_manual),
                            modifier = Modifier.size(12.dp),
                            tint = SemanticColors.TextSecondary
                        )
                    }
                }
                Text(
                    DateFormatterUtils.monthDay().format(Date(expense.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "€${String.format("%.2f", expense.amount)}",
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlannedExpenseDialog(
    categories: List<Category> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Long, Long?, PlannedExpensePriority) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannedExpensePriority.LIKELY) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                stringResource(R.string.dialog_plan_expense_title), 
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = SemanticColors.PrimaryIndigo
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dialog_plan_expense_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.dialog_plan_expense_amount_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                Column {
                    val priorityLabel = stringResource(R.string.dialog_plan_expense_priority)
                    val selectedLabel = stringResource(R.string.a11y_selected)
                    val notSelectedLabel = stringResource(R.string.a11y_not_selected)
                    Text(
                        priorityLabel, 
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlannedExpensePriority.values().forEach { p ->
                            val isSelected = priority == p
                            val priorityDesc = stringResource(R.string.a11y_priority_format, p.name, if (isSelected) selectedLabel else notSelectedLabel)
                            FilterChip(
                                selected = isSelected,
                                onClick = { priority = p },
                                label = { Text(p.name) },
                                modifier = Modifier.semantics { 
                                    contentDescription = priorityDesc
                                }
                            )
                        }
                    }
                }

                // Date Selector
                DateSelector(
                    dateMs = date,
                    onDateSelected = { date = it }
                )

                // Category selector
                if (categories.isNotEmpty()) {
                    val categoryLabel = stringResource(R.string.dialog_plan_expense_category)
                    val noneLabel = stringResource(R.string.dialog_plan_expense_none)
                    val noCategoryDesc = stringResource(R.string.a11y_no_category_selected)
                    val selectedLabel = stringResource(R.string.a11y_selected)
                    val notSelectedLabel = stringResource(R.string.a11y_not_selected)
                    
                    Column {
                        Text(
                            categoryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedCategoryId == null,
                                    onClick = { selectedCategoryId = null },
                                    label = { Text(noneLabel) },
                                    modifier = Modifier.semantics { contentDescription = noCategoryDesc }
                                )
                            }
                            items(categories.size) { idx ->
                                val cat = categories[idx]
                                val catSelected = selectedCategoryId == cat.id
                                val catIconDesc = stringResource(R.string.a11y_category_icon_format, cat.name)
                                val catDesc = stringResource(R.string.a11y_category_format, cat.name, if (catSelected) selectedLabel else notSelectedLabel)
                                FilterChip(
                                    selected = catSelected,
                                    onClick = { selectedCategoryId = cat.id },
                                    label = { Text(cat.name) },
                                    leadingIcon = {
                                        Text(
                                            text = cat.icon,
                                            fontSize = 16.sp,
                                            modifier = Modifier.semantics { contentDescription = catIconDesc }
                                        )
                                    },
                                    modifier = Modifier.semantics { 
                                        contentDescription = catDesc
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (description.isNotBlank() && amt > 0) {
                        onConfirm(description, amt, date, selectedCategoryId, priority)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
            ) {
                Text(stringResource(R.string.action_add_to_forecast))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel), color = SemanticColors.TextSecondary)
            }
        },
        containerColor = SemanticColors.BaseNavy,
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = stringResource(R.string.a11y_select_date),
            tint = SemanticColors.PrimaryIndigo
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                stringResource(R.string.label_date),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = SemanticColors.TextSecondary
            )
            Text(
                DateFormatterUtils.fullDate().format(java.util.Date(dateMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            onDateSelected(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok), color = SemanticColors.PrimaryIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel), color = SemanticColors.TextSecondary)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun FeaturesMenu(
    onDismiss: () -> Unit,
    onNavigateToFeature: (NavigationDestination) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = SemanticColors.BaseNavy
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_features),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                
                // Config-driven Feature Items - All 22 features from FeatureConfig
                // Wrapped in a scrollable column with max height to prevent overflow
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureConfig.allFeatures.forEach { feature ->
                        FeatureItem(
                            icon = feature.icon,
                            title = stringResource(feature.titleRes),
                            description = feature.descriptionRes?.let { stringResource(it) } ?: "",
                            color = feature.color,
                            isNew = feature.isNew,
                            isBeta = feature.isBeta,
                            onClick = { onNavigateToFeature(feature.destination) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.a11y_close), color = SemanticColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    color: Color,
    isNew: Boolean = false,
    isBeta: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.TextPrimary
                    )
                    if (isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(containerColor = Color(0xFF4CAF50)) {
                            Text(stringResource(R.string.label_new), fontSize = 10.sp)
                        }
                    }
                    if (isBeta) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(containerColor = Color(0xFFFF9800)) {
                            Text(stringResource(R.string.label_beta), fontSize = 10.sp)
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = SemanticColors.TextSecondary
            )
        }
    }
}

private fun com.yourname.expensetracker.domain.model.PeriodType.toPeriodLevel(): PeriodLevel = when (this) {
    com.yourname.expensetracker.domain.model.PeriodType.YEAR -> PeriodLevel.YEAR
    com.yourname.expensetracker.domain.model.PeriodType.MONTH -> PeriodLevel.MONTH
    com.yourname.expensetracker.domain.model.PeriodType.WEEK -> PeriodLevel.WEEK
    com.yourname.expensetracker.domain.model.PeriodType.DAY -> PeriodLevel.DAY
}
