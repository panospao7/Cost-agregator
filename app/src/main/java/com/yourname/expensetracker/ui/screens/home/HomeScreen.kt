package com.yourname.expensetracker.ui.screens.home

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()

    var showQuickSettings by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(isActive = state.isServiceRunning)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dashboard", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { showQuickSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.widgets.forEach { widget ->
                    when (widget) {
                        is DashboardWidget.SafeToSpend -> {
                            item(span = { GridItemSpan(2) }) {
                                HeroBentoCard {
                                    Text(
                                        text = if (widget.totalBudget != null) "Safe to spend" else "Spent this month",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    AmountText(
                                        amount = widget.amount,
                                        style = MaterialTheme.typography.displayMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    if (widget.totalBudget != null) {
                                        LinearProgressIndicator(
                                            progress = { ( (widget.totalBudget - widget.amount) / widget.totalBudget ).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).clip(CircleShape),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                                        )
                                        Text(
                                            "${widget.daysRemaining} days remaining in month",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }

                        is DashboardWidget.SpendingPaceWidget -> {
                            item {
                                BentoCard {
                                    Text("Spending Pace", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SpendingPaceGauge(pace = widget.pace)
                                }
                            }
                        }

                        is DashboardWidget.NaturalLanguageInsight -> {
                            item(span = { GridItemSpan(2) }) {
                                BentoCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(widget.icon, fontSize = 24.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = widget.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        is DashboardWidget.PeriodSummary -> {
                            item {
                                BentoCard {
                                    Text("Overview", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    StatLabel("Today", "€${String.format("%.2f", widget.todaySpent)}")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    StatLabel("This Week", "€${String.format("%.2f", widget.weekSpent)}")
                                }
                            }
                        }

                        is DashboardWidget.PendingReviewAlert -> {
                            item {
                                BentoCard(
                                    containerColor = SemanticColors.WarningOrange.copy(alpha = 0.15f),
                                    onClick = { /* Navigate to review */ }
                                ) {
                                    Text("Pending Review", style = MaterialTheme.typography.labelMedium, color = SemanticColors.WarningOrange)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${widget.count}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = SemanticColors.WarningOrange)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("items", style = MaterialTheme.typography.bodySmall, color = SemanticColors.WarningOrange)
                                    }
                                    Text("Tap to verify", style = MaterialTheme.typography.labelSmall, color = SemanticColors.WarningOrange.copy(alpha = 0.8f))
                                }
                            }
                        }

                        is DashboardWidget.BudgetHealthWidget -> {
                            item(span = { GridItemSpan(2) }) {
                                BentoCard {
                                    Text("Budget Health", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(widget.summary ?: "All good", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    // Could add a small bar or dots here
                                }
                            }
                        }

                        is DashboardWidget.TopCategories -> {
                            item(span = { GridItemSpan(2) }) {
                                BentoCard {
                                    Text("Top Categories", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    widget.categories.forEach { CategorySpendingRow(it) }
                                }
                            }
                        }

                        is DashboardWidget.RecentTransactions -> {
                            item(span = { GridItemSpan(2) }) {
                                BentoCard {
                                    Text("Recent Transactions", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    widget.expenses.forEach { RecentExpenseRow(it) }
                                }
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
    }
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
