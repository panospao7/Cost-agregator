package com.yourname.expensetracker.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.domain.util.TimePeriodUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToRecurring: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()

    var showQuickSettings by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showAddPlannedExpenseDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(isActive = state.isServiceRunning)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "DASHBOARD", 
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            if (state.isEditMode) Icons.Rounded.Check else Icons.Rounded.EditAttributes, 
                            contentDescription = "Edit Layout",
                            tint = if (state.isEditMode) SemanticColors.SuccessGreen else SemanticColors.TextSecondary
                        )
                    }
                    IconButton(onClick = { showQuickSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings, 
                            contentDescription = "Settings",
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
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
            }
        } else {
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
                    WidgetWrapper(
                        widget = widget,
                        isEditMode = state.isEditMode,
                        onMoveUp = { viewModel.moveWidget(HomeViewModel.getWidgetId(widget), true) },
                        onMoveDown = { viewModel.moveWidget(HomeViewModel.getWidgetId(widget), false) },
                        onToggleVisibility = { viewModel.toggleWidgetVisibility(HomeViewModel.getWidgetId(widget)) }
                    ) {
                        when (widget) {
                            is DashboardWidget.SafeToSpend -> {
                                HeroBentoCard {
                                    Text(
                                        text = "SAFE TO SPEND",
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
                                            "${widget.daysRemaining} DAYS REMAINING",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SemanticColors.TextSecondary,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.BudgetBlockParty -> {
                                BudgetBlockPartyCard(
                                    days = widget.days,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is DashboardWidget.SpendingPaceWidget -> {
                                BentoCard {
                                    Text(
                                        "PACE", 
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
                                        "REVIEW", 
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
                                            "PENDING", 
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
                                        currentMonthData = widget.currentMonthData,
                                        previousMonthData = widget.previousMonthData
                                    )
                                }
                            }
                            is DashboardWidget.NaturalLanguageInsight -> {
                                BentoCard(
                                    containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = CircleShape,
                                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(widget.icon, fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = widget.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SemanticColors.TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.PeriodSummary -> {
                                BentoCard {
                                    Text(
                                        "PERIOD SUMMARY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatLabel("TODAY", "€${String.format("%.2f", widget.todaySpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("WEEK", "€${String.format("%.2f", widget.weekSpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("MONTH", "€${String.format("%.2f", widget.monthSpent)}", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            is DashboardWidget.BudgetHealthWidget -> {
                                BentoCard {
                                    Text(
                                        "BUDGET HEALTH", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        widget.summary ?: "ALL BUDGETS ON TRACK", 
                                        style = MaterialTheme.typography.titleMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = if (widget.summary?.contains("exceeded", ignoreCase = true) == true) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                                    )
                                }
                            }
                            is DashboardWidget.TopCategories -> {
                                BentoCard {
                                    Text(
                                        "TOP CATEGORIES", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.categories.forEach { CategorySpendingRow(it) }
                                }
                            }
                            is DashboardWidget.RecentTransactions -> {
                                BentoCard {
                                    Text(
                                        "RECENT ACTIVITY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.expenses.forEach { RecentExpenseRow(it) }
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
                        }
                    }
                }
            }
        }

        if (showQuickSettings) {
            QuickSettingsDialog(
                onDismiss = { showQuickSettings = false },
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

        if (showCategories) {
            com.yourname.expensetracker.ui.screens.categories.CategoryScreen(
                onDismiss = { showCategories = false }
            )
        }

        if (showDebug) {
            com.yourname.expensetracker.ui.screens.debug.DebugScreen(
                onDismiss = { showDebug = false }
            )
        }

        if (showAddPlannedExpenseDialog) {
            AddPlannedExpenseDialog(
                onDismiss = { showAddPlannedExpenseDialog = false },
                onConfirm = { desc, amount, date, catId, priority ->
                    viewModel.addPlannedExpense(desc, amount, date, catId, priority)
                    showAddPlannedExpenseDialog = false
                }
            )
        }
    }
}

@Composable
fun WidgetWrapper(
    widget: DashboardWidget,
    isEditMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
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
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Rounded.ArrowUpward, "Move Up", tint = Color.White)
                    }
                    IconButton(onClick = onToggleVisibility) {
                        Icon(Icons.Rounded.VisibilityOff, "Hide", tint = Color.White)
                    }
                    IconButton(onClick = onMoveDown) {
                        Icon(Icons.Rounded.ArrowDownward, "Move Down", tint = Color.White)
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
    onNavigateToCategories: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Categories") },
                    leadingContent = { Text("🏷️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToCategories() }
                )
                ListItem(
                    headlineContent = { Text("Debug Menu") },
                    leadingContent = { Text("🛠️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToDebug() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun PeriodCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "€${String.format("%.2f", amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CategorySpendingRow(item: CategorySpending) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
            Text(item.category.icon, fontSize = 18.sp)
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
fun RecentExpenseRow(expense: Expense) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
                if (expense.isManualEntry) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("✏️", fontSize = 12.sp)
                }
            }
            Text(
                dateFormat.format(Date(expense.date)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "€${String.format("%.2f", expense.amount)}",
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BudgetSummaryWidget(onTrack: Int, warning: Int, exceeded: Int, summary: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Budget Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatusBadge("On Track", onTrack, Color(0xFF4CAF50))
                StatusBadge("Warning", warning, Color(0xFFFFC107))
                StatusBadge("Exceeded", exceeded, Color(0xFFFF5722))
            }
            if (summary != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatusBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlannedExpenseDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Long, Long?, PlannedExpensePriority) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannedExpensePriority.LIKELY) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "PLAN AN EXPENSE", 
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
                    label = { Text("What are you planning?") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                Column {
                    Text(
                        "PRIORITY", 
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlannedExpensePriority.values().forEach { p ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text(p.name) }
                            )
                        }
                    }
                }

                // Date Selector
                DateSelector(
                    dateMs = date,
                    onDateSelected = { date = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (description.isNotBlank() && amt > 0) {
                        onConfirm(description, amt, date, null, priority)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
            ) {
                Text("ADD TO FORECAST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = SemanticColors.TextSecondary)
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
    val dateFormat = remember { java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()) }
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
            contentDescription = "Date",
            tint = SemanticColors.PrimaryIndigo
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                "Date",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = SemanticColors.TextSecondary
            )
            Text(
                dateFormat.format(java.util.Date(dateMs)),
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
                            // Preserve time of day (roughly, or just set to noon to avoid timezone issues/start of day)
                            // Here we just use the selected date (which is usually UTC midnight) + current time offset if needed?
                            // Material3 DatePicker returns UTC start of day. 
                            // Let's just use it as is, or add current time component if we cared about exact time.
                            // For forecast, date is most important.
                            onDateSelected(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK", color = SemanticColors.PrimaryIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = SemanticColors.TextSecondary)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
