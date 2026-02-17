package com.yourname.expensetracker.ui.screens.analytics

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Locale

import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedAnalyticsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    viewModel: AdvancedAnalyticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    androidx.activity.compose.BackHandler {
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Column {
                            Text("Advanced Analytics", fontWeight = FontWeight.Bold)
                            Text(
                                text = when(uiState.selectedPeriod) {
                                    AnalyticsPeriod.WEEK -> "Weekly Analysis"
                                    AnalyticsPeriod.MONTH -> "Monthly Deep Dive"
                                    AnalyticsPeriod.QUARTER -> "Quarterly Review"
                                    AnalyticsPeriod.YEAR -> "Yearly Overview"
                                    AnalyticsPeriod.CUSTOM -> "Custom Range"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    )
                )
                // Linear loader that doesn't push content down
                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = SemanticColors.PrimaryIndigo,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        // Helper for refresh state
        val pullRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Error banner as first item so it scrolls
                    if (uiState.error != null) {
                        item(key = "error_banner") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SemanticColors.DangerRed.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(uiState.error ?: "", color = SemanticColors.DangerRed, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // 1. Period Selector
                    item(key = "period_selector") { 
                        PeriodSelector(
                            selected = uiState.selectedPeriod, 
                            onSelect = { viewModel.setPeriod(it) }
                        ) 
                    }

                    // 2. Statistical Highlights (Bento Grid)
                    uiState.statisticalInsights?.let { stats ->
                        item(key = "stats_highlights") { StatisticalHighlights(stats) }
                    }

                    // 3. Category Deep Dive
                    if (uiState.categoryAnalytics.isNotEmpty()) {
                        item(key = "header_category") { AnalyticsSectionHeader("Category Breakdown", "Budget vs Actual & Trends") }
                        items(
                            items = uiState.categoryAnalytics,
                            key = { it.category.id },
                            contentType = { "CategoryItem" }
                        ) { item ->
                            EnhancedCategoryItem(
                                item = item,
                                onClick = {
                                    onNavigateToTransactions(
                                        TransactionFilter(
                                            categoryId = item.category.id,
                                            dateRange = viewModel.getCurrentDateRange()
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // 4. Spending Patterns
                    uiState.spendingPatterns?.let { patterns ->
                        item(key = "header_patterns") { AnalyticsSectionHeader("Spending Habits", "When & how you spend") }
                        item(key = "card_patterns") { SpendingPatternsCard(patterns) }
                    }

                    // 5. Merchant Intelligence
                    if (uiState.merchantAnalytics.isNotEmpty()) {
                        item(key = "header_merchant") { AnalyticsSectionHeader("Merchant Intelligence", "Top places & loyalty stats") }
                        items(
                            items = uiState.merchantAnalytics,
                            key = { it.merchant },
                            contentType = { "MerchantItem" }
                        ) { item ->
                            EnhancedMerchantItem(
                                item = item,
                                onClick = {
                                    onNavigateToTransactions(
                                        TransactionFilter(
                                            merchantName = item.merchant,
                                            dateRange = viewModel.getCurrentDateRange()
                                        )
                                    )
                                }
                            )
                        }
                    }
                    
                    item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(selected: AnalyticsPeriod, onSelect: (AnalyticsPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AnalyticsPeriod.values().forEach { period ->
            if (period != AnalyticsPeriod.CUSTOM) { // Skip custom for now as UI complexity is higher
                val isSelected = selected == period
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(period) }
                        .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.name.lowercase().titleCase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatisticalHighlights(stats: StatisticalInsights) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Daily Average Card
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("DAILY AVERAGE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                AmountText(stats.averageDailySpend, style = MaterialTheme.typography.headlineMedium)
            }
            
            // Largest Transaction Card
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("LARGEST SPEND", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                stats.largestTransaction?.let { 
                    AmountText(it.amount, style = MaterialTheme.typography.headlineMedium)
                    Text(it.merchant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        
        // Volatility / Consistency
        BentoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SPENDING CONSISTENCY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            stats.volatilityIndex < 30 -> "Very Consistent"
                            stats.volatilityIndex < 60 -> "Normal Variable"
                            else -> "Highly Volatile"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                CircularProgressIndicator(
                    progress = { stats.volatilityIndex / 100f },
                    modifier = Modifier.size(48.dp),
                    color = if (stats.volatilityIndex < 30) SemanticColors.SuccessGreen else SemanticColors.WarningOrange,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun EnhancedCategoryItem(
    item: EnhancedCategoryAnalytics,
    onClick: () -> Unit
) {
    val categoryColor = remember(item.category.color) {
        try { Color(android.graphics.Color.parseColor(item.category.color)) } 
        catch (e: Exception) { Color.Gray }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(categoryColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.category.icon, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.category.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("${item.transactionCount} transactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, style = MaterialTheme.typography.titleMedium)
                    item.changePercent?.let { change ->
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (change > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                        )
                    }
                }
            }
            
            // Budget bar if exists
            item.budgetAmount?.let { budget ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (item.totalSpent / budget).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when(item.budgetStatus) {
                            BudgetHealthStatus.EXCEEDED -> SemanticColors.DangerRed
                            BudgetHealthStatus.CRITICAL -> SemanticColors.WarningOrange
                            else -> categoryColor
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${item.budgetUtilizationPercent?.toInt()}% of budget",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Sparkline (Mini chart)
            if (item.sparklineData.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                val chartModel = remember(item.category.id, item.sparklineData) {
                    entryModelOf(item.sparklineData.mapIndexed { index, value -> FloatEntry(index.toFloat(), value.toFloat()) })
                }
                Chart(
                    chart = lineChart(),
                    model = chartModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                )
            }
        }
    }
}

@Composable
fun EnhancedMerchantItem(
    item: EnhancedMerchantAnalytics,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(item.merchant.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${item.visitFrequency} visitor • ${item.consistencyRating} spend", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, style = MaterialTheme.typography.titleMedium)
                    item.priceChangePercent?.let { change ->
                        Text(
                            text = "Prices: ${if (change > 0) "+" else ""}${String.format("%.1f", change)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (change > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMicro("Avg Visit", item.averagePerVisit)
                StatMicro("Loyalty", "${item.loyaltyScore.toInt()}/100")
                item.predictedNextVisitDate?.let { 
                    val daysUntil = ((it - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                    StatMicro("Next Expected", if (daysUntil <= 0) "Soon" else "$daysUntil days")
                }
            }
        }
    }
}

@Composable
fun SpendingPatternsCard(analysis: SpendingPatternAnalysis) {
    BentoCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = SemanticColors.PrimaryIndigo, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "You spend ${String.format("%.1fx", analysis.weekendVsWeekday.weekendToWeekdayRatio)} more on weekends",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Day of Week Bar Chart (Simplified)
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val maxSpend = analysis.dayOfWeekStats.values.maxOfOrNull { it.totalSpent } ?: 1.0
                
                (0..6).forEach { dayIndex ->
                    val stat = analysis.dayOfWeekStats[dayIndex]
                    val heightRatio = ((stat?.totalSpent ?: 0.0) / maxSpend).toFloat().coerceAtLeast(0.1f)
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .weight(1f, fill = false)
                                .fillMaxHeight(heightRatio)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (dayIndex == analysis.mostActiveDayIndex) SemanticColors.PrimaryIndigo 
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stat?.dayName?.take(1) ?: "",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            
            // Detected Patterns List
            if (analysis.detectedPatterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                
                analysis.detectedPatterns.forEach { pattern ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                pattern.type.name.replace("_", " ").lowercase().titleCase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                pattern.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatMicro(label: String, value: Any) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value is Double) {
            AmountText(value, style = MaterialTheme.typography.labelMedium)
        } else {
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AnalyticsSectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ... (other code)

// Extension for capitalizing string
fun String.titleCase() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
