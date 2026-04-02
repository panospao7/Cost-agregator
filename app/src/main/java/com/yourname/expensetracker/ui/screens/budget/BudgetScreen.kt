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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Analytics
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.budgetScale
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    onNavigateToForecast: ((Budget) -> Unit)? = null,
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetStatus?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }

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
                    IconButton(onClick = { showAddDialog = true }) {
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

                if (uiState.budgets.isEmpty()) {
                    item { EmptyBudgetsState { showAddDialog = true } }
                } else {
                    items(uiState.budgets, key = { it.budget.id }) { budgetStatus ->
                        BudgetCard(
                            status = budgetStatus,
                            dateFormat = dateFormat,
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
                onDismiss = { showAddDialog = false },
                onConfirm = { viewModel.addBudget(it) },
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
    dateFormat: SimpleDateFormat,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (Budget) -> Unit,
    onViewForecast: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val budgetActiveString = stringResource(R.string.budget_status_active)
    val budgetInactiveString = stringResource(R.string.budget_status_inactive)
    
    // Remember expensive calculations
    val progressColor = remember(status.healthStatus) {
        when (status.healthStatus) {
            BudgetHealthStatus.ON_TRACK -> SemanticColors.SuccessGreen
            BudgetHealthStatus.WARNING -> SemanticColors.WarningOrange
            BudgetHealthStatus.CRITICAL -> SemanticColors.WarningOrange
            BudgetHealthStatus.EXCEEDED -> SemanticColors.DangerRed
        }
    }

    val cardDescription = remember(status.spentAmount, status.budget.amount, status.healthStatus) {
        val statusText = if (status.budget.isActive) budgetActiveString else budgetInactiveString
        context.getString(
            R.string.budget_cd_format,
            status.category?.name ?: "Overall Budget",
            statusText,
            status.spentAmount,
            status.budget.amount,
            (status.percentUsed * 100).toInt(),
            status.healthStatus.name.lowercase().replaceFirstChar { it.titlecase() }
        )
    }
    
    val formattedDate = remember(status.budget.startDate) {
        DateFormatterUtils.monthDay().format(Date(status.budget.startDate))
    }
    
    val formattedPeriod = remember(status.budget.period) {
        status.budget.period.name.lowercase().replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    }

    val periodDateText = stringResource(R.string.budget_period_date_format, formattedPeriod, formattedDate)

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
                        periodDateText,
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
                Text(
                    text = stringResource(R.string.budget_spent_format, status.spentAmount),
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
                progress = { status.percentUsed.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )

            if (status.percentUsed > 1f) {
                Text(
                    text = stringResource(R.string.budget_over_format, status.spentAmount - status.budget.amount),
                    color = SemanticColors.DangerRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.budget_remaining_format, status.remainingAmount),
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
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var amount by remember { mutableStateOf(initialBudget?.amount?.toString() ?: "") }
    var selectedCategory by remember { mutableStateOf(initialBudget?.categoryId) }
    var period by remember { mutableStateOf(initialBudget?.period ?: BudgetPeriod.MONTHLY) }
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
                            rollover = rollover
                        ) ?: Budget(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
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
