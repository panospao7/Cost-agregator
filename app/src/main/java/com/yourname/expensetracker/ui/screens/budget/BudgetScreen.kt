package com.yourname.expensetracker.ui.screens.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import com.yourname.expensetracker.data.database.entity.BudgetTrend
import com.yourname.expensetracker.domain.budget.CategoryBudgetRecommendation
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.budgetScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    initialCategoryId: Long? = null,
    initialCategoryName: String? = null,
    onNavigateToForecast: ((Budget) -> Unit)? = null,
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetStatus?>(null) }
    var preselectedCategoryIdForAdd by remember { mutableStateOf<Long?>(null) }
    var hasHandledInitialCategoryContext by rememberSaveable(initialCategoryId, initialCategoryName) {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshBudgets()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBudgets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        initialCategoryId,
        initialCategoryName,
        uiState.isLoading,
        uiState.budgets,
        categories,
        hasHandledInitialCategoryContext
    ) {
        if (hasHandledInitialCategoryContext || uiState.isLoading) return@LaunchedEffect

        val hasContext = initialCategoryId != null || !initialCategoryName.isNullOrBlank()
        if (!hasContext) {
            hasHandledInitialCategoryContext = true
            return@LaunchedEffect
        }

        val normalizedName = initialCategoryName?.trim().orEmpty()
        val isOverallTarget = normalizedName.equals("GENERAL", ignoreCase = true) ||
            normalizedName.equals("OVERALL", ignoreCase = true)

        val matchingBudget = uiState.budgets.firstOrNull { status ->
            when {
                initialCategoryId != null -> status.budget.categoryId == initialCategoryId
                isOverallTarget -> status.budget.categoryId == null
                normalizedName.isNotBlank() -> status.category?.name.equals(normalizedName, ignoreCase = true)
                else -> false
            }
        }

        if (matchingBudget != null) {
            editingBudget = matchingBudget
        } else {
            preselectedCategoryIdForAdd = when {
                initialCategoryId != null -> initialCategoryId
                isOverallTarget -> null
                normalizedName.isNotBlank() -> categories.firstOrNull {
                    it.name.equals(normalizedName, ignoreCase = true)
                }?.id
                else -> null
            }
            showAddDialog = true
        }

        hasHandledInitialCategoryContext = true
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.budget_title),
                        color = SemanticColors.TextPrimary
                    ) 
                },
                actions = {
                    IconButton(onClick = {
                        preselectedCategoryIdForAdd = null
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.budget_add_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        // Error banner - shows when ViewModel has an error
        if (uiState.error != null) {
            ErrorBanner(
                message = uiState.error!!,
                onDismiss = { viewModel.clearError() },
                onRetry = { viewModel.refreshBudgets() }
            )
        }
        
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Summary card skeleton
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Budget cards skeleton
                ListSkeleton(itemCount = 4)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { BudgetSummaryCard(uiState.budgets) }

                if (uiState.suggestions.isNotEmpty()) {
                    item { SuggestionsBanner(uiState.suggestions, categories, onAdd = { viewModel.addBudget(it) }) }
                }

                // F9: AI Budget Autopilot Banner
                item {
        AutopilotBanner(
            recommendations = uiState.autopilotRecommendations,
            isLoading = uiState.autopilotLoading,
            onGenerate = { viewModel.generateAutopilotRecommendations() },
            onApply = { recommendation ->
                viewModel.applyAutopilotRecommendation(recommendation)
            },
            onApplyAll = { viewModel.applyAllAutopilotRecommendations() },
            onDismiss = { viewModel.dismissAllAutopilotRecommendations() },
            homeCurrency = uiState.homeCurrency
        )
                }

                if (uiState.budgets.isEmpty()) {
                    item {
                        EmptyBudgetsState {
                            preselectedCategoryIdForAdd = null
                            showAddDialog = true
                        }
                    }
                } else {
            items(uiState.budgets, key = { it.budget.id }) { budgetStatus ->
                        BudgetCard(
                            status = budgetStatus,
                            onEdit = { editingBudget = budgetStatus },
                            onToggle = { isActive -> viewModel.toggleBudget(budgetStatus.budget.id, isActive) },
                            onDelete = { viewModel.deleteBudget(it) },
                            onViewForecast = onNavigateToForecast?.let { navigate ->
                                { navigate(budgetStatus.budget) }
                            }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        if (showAddDialog) {
            AddEditBudgetDialog(
                initialCategoryId = preselectedCategoryIdForAdd,
                onDismiss = {
                    showAddDialog = false
                    preselectedCategoryIdForAdd = null
                },
                onConfirm = {
                    viewModel.addBudget(it)
                    preselectedCategoryIdForAdd = null
                },
                categories = categories
            )
        }

        editingBudget?.let { status ->
            AddEditBudgetDialog(
                initialBudget = status.budget,
                onDismiss = { editingBudget = null },
                onConfirm = { viewModel.updateBudget(it) },
                categories = categories
            )
        }
    }
}

/**
 * Error banner component for displaying BudgetViewModel errors.
 * Provides dismiss and retry actions.
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = SemanticColors.DangerRed.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SemanticColors.DangerRed.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                        contentDescription = null,
                        tint = SemanticColors.DangerRed
                    )
                    Text(
                        text = stringResource(R.string.error),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.DangerRed
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dismiss_error),
                        tint = SemanticColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SemanticColors.DangerRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
fun BudgetSummaryCard(budgets: List<BudgetStatus>) {
    val onTrack = budgets.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
    val warning = budgets.count { it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL }
    val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            SummaryItem(stringResource(R.string.budget_summary_on_track), onTrack, SemanticColors.SuccessGreen)
            SummaryItem(stringResource(R.string.budget_summary_warning), warning, SemanticColors.WarningOrange)
            SummaryItem(stringResource(R.string.budget_summary_exceeded), exceeded, SemanticColors.DangerRed)
        }
    }
}

@Composable
fun SummaryItem(label: String, count: Int, color: Color) {
    val summaryCd = stringResource(R.string.budget_summary_cd_format, count, label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics {
            contentDescription = summaryCd
        }
    ) {
        Text(
            text = count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BudgetCard(
    status: BudgetStatus,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (Budget) -> Unit,
    onViewForecast: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val budgetActiveString = stringResource(R.string.budget_status_active)
    val budgetInactiveString = stringResource(R.string.budget_status_inactive)
    val adjustedSpend = status.adjustedSpendBreakdown
    val displaySpend = adjustedSpend?.effectiveSpend ?: status.spentAmount
    val displayPercentUsed = if (status.budget.amount > 0.0) {
        (displaySpend / status.budget.amount).toFloat()
    } else {
        0f
    }
    val displayRemainingAmount = status.budget.amount - displaySpend
    val hasPendingReimbursements = adjustedSpend?.pendingReimbursements?.let { it > 0.01 } == true
    val displayHealthStatus = when {
        displayPercentUsed >= 1.0f -> BudgetHealthStatus.EXCEEDED
        displayPercentUsed >= status.budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
        displayPercentUsed >= status.budget.notifyAtWarning -> BudgetHealthStatus.WARNING
        else -> BudgetHealthStatus.ON_TRACK
    }
    
    // Remember expensive calculations
    val progressColor = remember(displayHealthStatus) {
        when (displayHealthStatus) {
            BudgetHealthStatus.ON_TRACK -> SemanticColors.SuccessGreen
            BudgetHealthStatus.WARNING -> SemanticColors.WarningOrange
            BudgetHealthStatus.CRITICAL -> SemanticColors.WarningOrange
            BudgetHealthStatus.EXCEEDED -> SemanticColors.DangerRed
        }
    }

    val cardDescription = remember(displaySpend, status.budget.amount, displayPercentUsed, displayHealthStatus) {
        val statusText = if (status.budget.isActive) budgetActiveString else budgetInactiveString
        context.getString(
            R.string.budget_cd_format,
            status.category?.name ?: "Overall Budget",
            statusText,
            displaySpend,
            status.budget.amount,
            (displayPercentUsed * 100).toInt(),
            displayHealthStatus.name.lowercase().replaceFirstChar { it.titlecase() }
        )
    }
    
    val monthYearFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val periodMode = remember(status.budget.periodMode) { status.budget.periodMode.uppercase(Locale.getDefault()) }
    val periodLabel = remember(status.budget.period) {
        status.budget.period.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    val rangeStartLabel = remember(status.periodStart) {
        DateFormatterUtils.formatTimestampJavaTime(status.periodStart, "MMM dd")
    }
    val displayEnd = remember(status.periodStart, status.periodEnd) {
        (status.periodEnd - 1L).coerceAtLeast(status.periodStart)
    }
    val rangeEndLabel = remember(displayEnd) {
        DateFormatterUtils.formatTimestampJavaTime(displayEnd, "MMM dd")
    }
    val periodDescription = remember(status.periodStart, status.periodEnd, status.budget.period, periodMode) {
        if (periodMode == "CALENDAR" && status.budget.period == BudgetPeriod.MONTHLY) {
            "${monthYearFormat.format(Date(status.periodStart))} (Calendar)"
        } else if (periodMode == "ROLLING") {
            val rollingLabel = when (status.budget.period) {
                BudgetPeriod.MONTHLY -> "Rolling 30d"
                BudgetPeriod.WEEKLY -> "Rolling 7d"
                else -> "Rolling $periodLabel"
            }
            "$rangeStartLabel - $rangeEndLabel ($rollingLabel)"
        } else {
            "$rangeStartLabel - $rangeEndLabel (Calendar $periodLabel)"
        }
    }

    val nowMs = System.currentTimeMillis()
    val totalPeriodMs = remember(status.periodStart, status.periodEnd) {
        (status.periodEnd - status.periodStart).coerceAtLeast(1L)
    }
    val elapsedPeriodMs = remember(status.periodStart, status.periodEnd, nowMs) {
        (nowMs - status.periodStart).coerceIn(0L, totalPeriodMs)
    }
    val periodProgress = remember(totalPeriodMs, elapsedPeriodMs) {
        (elapsedPeriodMs.toFloat() / totalPeriodMs.toFloat()).coerceIn(0f, 1f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardDescription },
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Replace emoji with Material icon
                if (status.category?.icon != null) {
                    val categoryIconDesc = stringResource(R.string.budget_category_icon_cd, status.category.name)
                    Text(
                        status.category.icon,
                        fontSize = 24.sp,
                        modifier = Modifier.semantics { contentDescription = categoryIconDesc }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = stringResource(R.string.budget_icon_cd),
                        modifier = Modifier.size(24.dp),
                        tint = SemanticColors.PrimaryIndigo
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.category?.name ?: "Overall Budget",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        periodDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val toggleText = if (status.budget.isActive) 
                    stringResource(R.string.budget_toggle_enabled) 
                else 
                    stringResource(R.string.budget_toggle_disabled)
                val toggleCd = stringResource(R.string.budget_toggle_cd_format, toggleText)
                Switch(
                    checked = status.budget.isActive,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .budgetScale(0.8f)
                        .semantics { 
                            contentDescription = toggleCd
                        }
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                // F11: Show adjusted spend if available
                val spendText = if (adjustedSpend != null) {
                    stringResource(R.string.budget_adjusted_spent_format, displaySpend)
                } else {
                    stringResource(R.string.budget_spent_format, status.spentAmount)
                }
                Text(
                    text = spendText,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    text = stringResource(R.string.budget_limit_format, status.budget.amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { displayPercentUsed.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { periodProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = SemanticColors.PrimaryIndigo,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = rangeStartLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = rangeEndLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // F11: Show adjusted spend breakdown if available
            if (adjustedSpend != null && adjustedSpend.pendingReimbursements > 0.01) {
                Spacer(Modifier.height(8.dp))
                AdjustedSpendBreakdownRow(adjustedSpend)
            }

            if (displayPercentUsed > 1f) {
                Text(
                    text = stringResource(R.string.budget_over_format, displaySpend - status.budget.amount),
                    color = SemanticColors.DangerRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.budget_remaining_format, displayRemainingAmount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // AI Forecast Button
            if (onViewForecast != null) {
                Spacer(Modifier.height(12.dp))
                
                OutlinedButton(
                    onClick = onViewForecast,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.budget_view_ai_forecast),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * F11: Display the adjusted spend breakdown with pending reimbursements info.
 */
@Composable
fun AdjustedSpendBreakdownRow(breakdown: com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(
                R.string.budget_pending_reimbursement_format,
                breakdown.pendingReimbursements
            ),
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.8f),
            fontSize = 11.sp
        )
    }
}

@Composable
fun SuggestionsBanner(
    suggestions: List<BudgetSuggestion>,
    categories: List<Category>,
    onAdd: (Budget) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val suggestion = suggestions.getOrNull(currentIndex) ?: return
    val context = LocalContext.current

    val bannerDescription = stringResource(
        R.string.budget_suggestion_cd_format,
        suggestion.categoryName,
        suggestion.suggestedAmount
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = bannerDescription },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(R.string.budget_suggestion_icon_cd),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.budget_suggestion_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.budget_suggestion_message_format,
                    suggestion.categoryName,
                    suggestion.suggestedAmount
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                val skipCd = stringResource(R.string.budget_suggestion_skip_cd)
                TextButton(
                    onClick = { if (currentIndex < suggestions.size - 1) currentIndex++ else currentIndex = 0 },
                    modifier = Modifier.semantics { 
                        contentDescription = skipCd
                    }
                ) {
                    Text(stringResource(R.string.budget_suggestion_skip))
                }
                val createCd = stringResource(R.string.budget_suggestion_create_cd_format, suggestion.categoryName)
                Button(
                    onClick = {
                        onAdd(Budget(
                            categoryId = suggestion.categoryId,
                            amount = suggestion.suggestedAmount,
                            period = BudgetPeriod.MONTHLY,
                            startDate = System.currentTimeMillis()
                        ))
                    },
                    modifier = Modifier.semantics { 
                        contentDescription = createCd
                    }
                ) {
                    Text(stringResource(R.string.budget_suggestion_create))
                }
            }
        }
    }
}

@Composable
fun EmptyBudgetsState(onAdd: () -> Unit) {
    val emptyCd = stringResource(R.string.budget_empty_cd)
    val setFirstCd = stringResource(R.string.budget_set_first_cd)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp)
            .semantics { contentDescription = emptyCd },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.budget_empty_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.budget_empty_subtitle),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.semantics { contentDescription = setFirstCd }
        ) {
            Text(stringResource(R.string.home_set_first_budget))
        }
    }
}

@Composable
fun AddEditBudgetDialog(
    initialBudget: Budget? = null,
    initialCategoryId: Long? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var amount by remember { mutableStateOf(initialBudget?.amount?.toString() ?: "") }
    var selectedCategory by remember { mutableStateOf(initialBudget?.categoryId ?: initialCategoryId) }
    var period by remember { mutableStateOf(initialBudget?.period ?: BudgetPeriod.MONTHLY) }
    var periodMode by remember { mutableStateOf(initialBudget?.periodMode ?: "ROLLING") }
    var rollover by remember { mutableStateOf(initialBudget?.rollover ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBudget == null) stringResource(R.string.budget_dialog_create_title) else stringResource(R.string.budget_dialog_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { newValue ->
                        // Only allow valid decimal numbers
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                            amount = newValue
                        }
                    },
                    label = { Text(stringResource(R.string.budget_amount_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = amount.isNotEmpty() && (amount.toDoubleOrNull() ?: 0.0) <= 0,
                    supportingText = { 
                        if (amount.isNotEmpty() && (amount.toDoubleOrNull() ?: 0.0) <= 0) {
                            Text(stringResource(R.string.budget_error_invalid_amount))
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                Text(stringResource(R.string.budget_category_label), style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = categories,
                    selectedId = selectedCategory,
                    onSelect = { selectedCategory = it }
                )

                Text(stringResource(R.string.budget_period_label), style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BudgetPeriod.values().forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(p.name.lowercase().replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }, fontSize = 12.sp) }
                        )
                    }
                }

                Text("Period mode", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = periodMode == "ROLLING",
                        onClick = { periodMode = "ROLLING" },
                        label = { Text("Rolling") }
                    )
                    FilterChip(
                        selected = periodMode == "CALENDAR",
                        onClick = { periodMode = "CALENDAR" },
                        label = { Text("Calendar") }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rollover, onCheckedChange = { rollover = it })
                    Text(stringResource(R.string.budget_rollover_label), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        val budgetToSave = initialBudget?.copy(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            periodMode = periodMode,
                            rollover = rollover
                        ) ?: Budget(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            periodMode = periodMode,
                            startDate = System.currentTimeMillis(),
                            rollover = rollover
                        )
                        onConfirm(budgetToSave)
                        onDismiss()
                    }
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.budget_overall_category)) }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text("${category.icon} ${category.name}") }
            )
        }
    }
}

// ==================== AUTOPILOT UI ====================

@Composable
fun AutopilotBanner(
    recommendations: List<CategoryBudgetRecommendation>,
    isLoading: Boolean,
    onGenerate: () -> Unit,
    onApply: (CategoryBudgetRecommendation) -> Unit,
    onApplyAll: () -> Unit,
    onDismiss: () -> Unit,
    homeCurrency: String = "EUR"
) {
    var expanded by remember { mutableStateOf(false) }
    val hasRecommendations = recommendations.isNotEmpty()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasRecommendations) 
                SemanticColors.PrimaryIndigo.copy(alpha = 0.1f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (hasRecommendations) 
            androidx.compose.foundation.BorderStroke(
                1.dp, 
                SemanticColors.PrimaryIndigo.copy(alpha = 0.3f)
            ) 
        else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = if (hasRecommendations) 
                            SemanticColors.PrimaryIndigo 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "AI Budget Autopilot",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasRecommendations) 
                                SemanticColors.PrimaryIndigo 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                        if (hasRecommendations) {
                            Text(
                                text = "${recommendations.size} adjustment${if (recommendations.size > 1) "s" else ""} recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else if (hasRecommendations) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) 
                                Icons.Rounded.Close 
                            else 
                                androidx.compose.material.icons.Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                } else {
                    Button(
                        onClick = onGenerate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SemanticColors.PrimaryIndigo
                        )
                    ) {
                        Text("Analyze")
                    }
                }
            }
            
            if (hasRecommendations && expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                recommendations.take(3).forEach { recommendation ->
        AutopilotRecommendationItem(
            recommendation = recommendation,
            onApply = { onApply(recommendation) },
            homeCurrency = homeCurrency
        )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (recommendations.size > 3) {
                    Text(
                        text = "+${recommendations.size - 3} more recommendations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Dismiss All")
                    }
                    Button(
                        onClick = onApplyAll,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SemanticColors.SuccessGreen
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Apply All")
                    }
                }
            }
        }
    }
}

@Composable
fun AutopilotRecommendationItem(
    recommendation: CategoryBudgetRecommendation,
    onApply: () -> Unit,
    homeCurrency: String = "EUR"
) {
    val trendColor = when (recommendation.trend) {
        BudgetTrend.INCREASING -> SemanticColors.DangerRed
        BudgetTrend.DECREASING -> SemanticColors.SuccessGreen
        BudgetTrend.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val trendIcon = when (recommendation.trend) {
        BudgetTrend.INCREASING -> "↑"
        BudgetTrend.DECREASING -> "↓"
        BudgetTrend.STABLE -> "→"
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recommendation.categoryName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = recommendation.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${trendIcon} ${String.format("%.0f", recommendation.confidence * 100)}% confidence",
                        style = MaterialTheme.typography.labelSmall,
                        color = trendColor
                    )
                }
            }
            
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = CurrencyFormatter.format(recommendation.currentBudget, homeCurrency, showCents = false),
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = CurrencyFormatter.format(recommendation.recommendedBudget, homeCurrency, showCents = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (recommendation.delta > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                    )
                }
                Text(
                    text = "${if (recommendation.delta > 0) "+" else ""}${String.format("%.1f", recommendation.deltaPercentage)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recommendation.delta > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                TextButton(
                    onClick = onApply,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Apply",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
