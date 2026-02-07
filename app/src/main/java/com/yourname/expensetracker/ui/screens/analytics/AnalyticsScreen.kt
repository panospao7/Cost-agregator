package com.yourname.expensetracker.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Total Spent Header
                item { TotalSpentHeader(state) }

                // 2. Period Selector
                item { PeriodSelector(state.selectedPeriod) { viewModel.selectPeriod(it) } }

                // 3. Main Chart
                item { AnalyticsChart(state) }

                // 4. Insights Section
                if (state.insights.isNotEmpty()) {
                    item { SectionHeader("Insights") }
                    item { InsightsRow(state.insights) }
                }

                // 5. Category Breakdown
                if (state.categoryBreakdown.isNotEmpty()) {
                    item { SectionHeader("By Category") }
                    items(state.categoryBreakdown) { CategoryItem(it) }
                }

                // 6. Merchant Breakdown
                if (state.merchantBreakdown.isNotEmpty()) {
                    item { SectionHeader("Top Merchants") }
                    items(state.merchantBreakdown.take(10)) { MerchantItem(it) }
                }
                
                // 7. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader("Detected Recurring") }
                    items(state.recurring) { RecurringItem(it) }
                }
                
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun TotalSpentHeader(state: AnalyticsState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = state.selectedPeriod.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Text(
                text = "€${String.format("%.2f", state.currentTotal)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            state.changePercent?.let { change ->
                val isIncrease = change > 0
                val color = if (isIncrease) Color(0xFFE57373) else Color(0xFF81C784)
                val arrow = if (isIncrease) "▲" else "▼"
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$arrow ${String.format("%.1f", Math.abs(change))}%",
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = " vs previous period",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(selected: TimePeriod, onSelect: (TimePeriod) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(TimePeriod.values()) { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.name) }
            )
        }
    }
}

@Composable
fun AnalyticsChart(state: AnalyticsState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Daily Spending",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data for this period", color = Color.Gray)
                }
            } else {
                val entries = state.dailyTotals.values.map { it.toFloat() }
                val chartEntryModel = entryModelOf(*entries.toTypedArray())
                
                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun InsightsRow(insights: List<SpendingInsight>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(insights) { insight ->
            Card(
                modifier = Modifier.width(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(insight.icon, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            insight.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryItem(item: CategoryBreakdown) {
    // Optimize color parsing: remember the color based on the category's hex string
    val categoryColor = remember(item.category.color) {
        try {
            Color(android.graphics.Color.parseColor(item.category.color))
        } catch (e: Exception) {
            Color.Gray
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = item.percentage / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = categoryColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("€${String.format("%.2f", item.total)}", fontWeight = FontWeight.Bold)
            Text("${item.percentage.toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MerchantItem(item: MerchantBreakdown) {
    ListItem(
        headlineContent = { Text(item.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text("${item.transactionCount} transactions") },
        trailingContent = { Text("€${String.format("%.2f", item.totalSpent)}", fontWeight = FontWeight.Bold) },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
fun RecurringItem(item: RecurringCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, fontWeight = FontWeight.Bold)
                Text("~every ${item.intervalDays} days", style = MaterialTheme.typography.bodySmall)
            }
            Text("€${String.format("%.2f", item.amount)}", fontWeight = FontWeight.Bold)
        }
    }
}
