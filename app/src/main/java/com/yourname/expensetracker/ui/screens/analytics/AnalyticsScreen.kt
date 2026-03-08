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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Info
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
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.core.chart.column.ColumnChart
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onNavigateToTransactions: ((TransactionFilter) -> Unit)? = null,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Financial Insights", fontWeight = FontWeight.Bold) }
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
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Period Selector (Top Level)
                item { PeriodSelector(state.selectedPeriod) { viewModel.selectPeriod(it) } }

                // 2. Main Hero Bento: Total Spent + Change
                item { TotalSpentHero(state) }

                // 3. Statistical Highlights (daily avg, largest spend, volatility)
                state.statisticalInsights?.let { stats ->
                    item { StatisticalHighlights(stats) }
                }

                // 4. AI Insights (Natural Language)
                if (state.insights.isNotEmpty()) {
                    item { NaturalLanguageInsightBento(state.insights.first()) }
                }

                // 5. Daily Spending Chart
                item { SpendingChartBento(state) }

                // 5b. Day-of-Week Spending Pattern (period-aware)
                if (state.dayOfWeekPattern.isNotEmpty()) {
                    item { DayOfWeekChartBento(state.dayOfWeekPattern) }
                }

                // 5c. Hour-of-Day Spending Chart (period-aware)
                if (state.hourOfDayPattern.isNotEmpty()) {
                    item { HourOfDayChartBento(state.hourOfDayPattern) }
                }

                // 5d. Spending Patterns (weekend vs weekday, detected behaviors)
                state.spendingPatterns?.let { patterns ->
                    item { AnalyticsSectionHeader("Spending Habits", "When & how you spend") }
                    item { SpendingPatternsCard(patterns) }
                }

                // 6. Category Breakdown (Enhanced if available, basic otherwise)
                if (state.enhancedCategories.isNotEmpty()) {
                    item { AnalyticsSectionHeader("Category Breakdown", "Budget vs Actual & Trends") }
                    item {
                        CategoryDonutChart(
                            categories = state.categoryBreakdown,
                            totalSpent = state.currentTotal
                        )
                    }
                    items(state.enhancedCategories) { cat ->
                        EnhancedCategoryItem(
                            item = cat,
                            onClick = {
                                onNavigateToTransactions?.invoke(
                                    TransactionFilter(
                                        categoryId = cat.category.id,
                                        dateRange = state.currentDateRange
                                    )
                                )
                            }
                        )
                    }
                } else if (state.categoryBreakdown.isNotEmpty()) {
                    item { SectionHeader("Breakdown by Category") }
                    item {
                        CategoryDonutChart(
                            categories = state.categoryBreakdown,
                            totalSpent = state.currentTotal
                        )
                    }
                    items(state.categoryBreakdown) { CategoryItem(it) }
                }

                // 6b. Budget vs Actual
                if (state.budgetVsActual.isNotEmpty()) {
                    item { SectionHeader("Budget vs Actual") }
                    item { BudgetVsActualChart(state.budgetVsActual) }
                }

                // 7. Deep Insights Carousel
                if (state.insights.size > 1) {
                    item { SectionHeader("Deep Insights") }
                    item { InsightsRow(state.insights.drop(1)) }
                }

                // 8. Merchant Breakdown (Enhanced if available)
                if (state.enhancedMerchants.isNotEmpty()) {
                    item { AnalyticsSectionHeader("Merchant Intelligence", "Top places & loyalty stats") }
                    items(state.enhancedMerchants) { merch ->
                        EnhancedMerchantItem(
                            item = merch,
                            onClick = {
                                onNavigateToTransactions?.invoke(
                                    TransactionFilter(
                                        merchantName = merch.merchant,
                                        dateRange = state.currentDateRange
                                    )
                                )
                            }
                        )
                    }
                } else if (state.merchantBreakdown.isNotEmpty()) {
                    item { SectionHeader("Top Merchants") }
                    items(state.merchantBreakdown.take(8)) { MerchantItem(it) }
                }

                // 9. Velocity Anomalies
                if (state.velocityAnomalies.isNotEmpty()) {
                    item { SectionHeader("Spending Velocity Anomalies") }
                    items(state.velocityAnomalies) { VelocityAnomalyCard(it) }
                }

                // 10. Year-over-Year Comparison
                state.yearOverYear?.let { yoy ->
                    item { SectionHeader("Year-over-Year Comparison") }
                    item { YearOverYearCard(yoy) }
                }

                // 11. Post-Salary Sequential Pattern
                state.postSalaryPattern?.let { pattern ->
                    item { SectionHeader("Post-Salary Spending Patterns") }
                    item { PostSalaryPatternCard(pattern) }
                }

                // 12. Suspect / Duplicate Transactions
                if (state.suspectTransactions.isNotEmpty()) {
                    item { SectionHeader("Possible Errors & Duplicates") }
                    items(state.suspectTransactions) { SuspectTransactionCard(it) }
                }

                // 13. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader("Subscription Detection") }
                    items(state.recurring) { RecurringItem(it) }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

// ── Statistical Highlights (from AdvancedAnalyticsScreen) ─────────────
@Composable
fun StatisticalHighlights(stats: StatisticalInsights) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("DAILY AVERAGE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                AmountText(stats.averageDailySpend, style = MaterialTheme.typography.headlineMedium)
            }
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("LARGEST SPEND", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                stats.largestTransaction?.let {
                    AmountText(it.amount, style = MaterialTheme.typography.headlineMedium)
                    Text(it.merchant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
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

// ── Enhanced Category Item (from AdvancedAnalyticsScreen) ─────────────
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
                        color = when (item.budgetStatus) {
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

            // Sparkline mini chart
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

// ── Enhanced Merchant Item (from AdvancedAnalyticsScreen) ─────────────
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
                        "${item.visitFrequency.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }} visitor · ${item.consistencyRating.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }} spend",
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

// ── Spending Patterns Card (from AdvancedAnalyticsScreen) ─────────────
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

            // Day of Week mini bar chart
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val maxSpend = analysis.dayOfWeekStats.values.maxOfOrNull { it.totalSpent } ?: 1.0
                (0..6).forEach { dayIndex ->
                    val stat = analysis.dayOfWeekStats[dayIndex]
                    val heightRatio = ((stat?.totalSpent ?: 0.0) / maxSpend).toFloat().coerceAtLeast(0.05f)
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
                        Text(stat?.dayName?.take(1) ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Detected patterns
            if (analysis.detectedPatterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                analysis.detectedPatterns.forEach { pattern ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f), modifier = Modifier.size(6.dp)) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                pattern.type.name.replace("_", " ").lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(pattern.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ── Hour-of-Day Chart (new, Task D) ───────────────────────────────────
@Composable
fun HourOfDayChartBento(hourOfDayPattern: List<Pair<Int, Double>>) {
    BentoCard {
        Column {
            Text(
                "Spending by Hour of Day",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val allHours = (0..23).map { h ->
                hourOfDayPattern.find { it.first == h }?.second ?: 0.0
            }
            val chartEntryModel = remember(hourOfDayPattern) {
                entryModelOf(allHours.mapIndexed { i, v -> entryOf(i.toFloat(), v.toFloat()) })
            }
            val hourAxisFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                val h = value.toInt()
                when {
                    h == 0 -> "12a"
                    h < 12 -> "${h}a"
                    h == 12 -> "12p"
                    else -> "${h - 12}p"
                }
            }

            Chart(
                chart = columnChart(),
                model = chartEntryModel,
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(
                    valueFormatter = hourAxisFormatter,
                    labelRotationDegrees = -45f
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            )

            // Summary: peak hour
            val peakHour = hourOfDayPattern.maxByOrNull { it.second }
            peakHour?.let { (h, total) ->
                Spacer(modifier = Modifier.height(8.dp))
                val label = when {
                    h == 0 -> "midnight"
                    h < 12 -> "${h}am"
                    h == 12 -> "noon"
                    else -> "${h - 12}pm"
                }
                Text(
                    "Peak spending at $label (€${String.format("%.0f", total)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.PrimaryIndigo.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ── Shared helper composables ─────────────────────────────────────────
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
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TotalSpentHero(state: AnalyticsState) {
    HeroBentoCard {
        Column {
            Text(
                text = "${state.selectedPeriod.name} Total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            AmountText(
                amount = state.currentTotal,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            state.changePercent?.let { change ->
                val isIncrease = change > 0
                val color = if (isIncrease) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                val icon = if (isIncrease) "📈" else "📉"

                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}% vs last period",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Previous period comparison
            state.previousTotal?.let { prevTotal ->
                Text(
                    text = "vs. €${String.format("%.0f", prevTotal)} last period",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = "${state.transactionCount} transactions recorded in this period.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun NaturalLanguageInsightBento(insight: SpendingInsight) {
    BentoCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(insight.icon, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = insight.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun SpendingChartBento(state: AnalyticsState) {
    BentoCard {
        Column {
            Text(
                "Spending Distribution",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("Insufficient data for visualization", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val sortedEntries = remember(state.dailyTotals) {
                    state.dailyTotals.entries.sortedBy { it.key }
                }
                val chartEntryModel = remember(sortedEntries) {
                    val entries = sortedEntries.mapIndexed { index, entry ->
                        entryOf(index.toFloat(), entry.value.toFloat())
                    }
                    entryModelOf(entries)
                }
                val dateLabels = remember(sortedEntries) { sortedEntries.map { it.key } }
                val bottomAxisFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                    val idx = value.toInt()
                    if (idx in dateLabels.indices) {
                        val label = dateLabels[idx]
                        label.substringAfterLast("-").trimStart('0').ifEmpty { "0" }
                    } else ""
                }

                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = bottomAxisFormatter),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            }
        }
    }
}

@Composable
fun DayOfWeekChartBento(dayOfWeekPattern: List<DayOfWeekInsight>) {
    BentoCard {
        Column {
            Text(
                "Spending by Day of Week",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (dayOfWeekPattern.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("Insufficient data", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val sorted = remember(dayOfWeekPattern) {
                    dayOfWeekPattern.sortedBy { it.dayIndex }
                }
                val chartEntryModel = remember(sorted) {
                    val entries = sorted.mapIndexed { index, insight ->
                        entryOf(index.toFloat(), insight.totalSpent.toFloat())
                    }
                    entryModelOf(entries)
                }
                val dayLabels = remember(sorted) { sorted.map { it.dayName.take(3) } }
                val dowAxisFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                    val idx = value.toInt()
                    if (idx in dayLabels.indices) dayLabels[idx] else ""
                }

                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = dowAxisFormatter),
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )

                if (sorted.size >= 2) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val highest = sorted.maxByOrNull { it.totalSpent }
                    val lowest = sorted.filter { it.totalSpent > 0 }.minByOrNull { it.totalSpent }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        highest?.let {
                            Text(
                                "Most: ${it.dayName} (€${String.format("%.0f", it.totalSpent)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.DangerRed.copy(alpha = 0.8f)
                            )
                        }
                        lowest?.let {
                            Text(
                                "Least: ${it.dayName} (€${String.format("%.0f", it.totalSpent)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.SuccessGreen.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BudgetVsActualChart(items: List<BudgetVsActualItem>) {
    BentoCard {
        Column {
            Text(
                "BUDGET UTILIZATION",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            items.forEach { item ->
                val barColor = try {
                    Color(android.graphics.Color.parseColor(item.categoryColor))
                } catch (_: Exception) {
                    SemanticColors.PrimaryIndigo
                }
                val statusColor = when {
                    item.percentUsed > 1f -> SemanticColors.DangerRed
                    item.percentUsed > 0.75f -> SemanticColors.WarningOrange
                    else -> SemanticColors.SuccessGreen
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.categoryIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                item.categoryName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = SemanticColors.TextPrimary
                            )
                            Text(
                                "€${String.format("%.0f", item.actualSpent)} / €${String.format("%.0f", item.budgetAmount)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor.copy(alpha = 0.15f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(item.percentUsed.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor.copy(alpha = 0.8f))
                            )
                            if (item.percentUsed > 1f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SemanticColors.DangerRed.copy(alpha = 0.25f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth((1f / item.percentUsed).coerceIn(0f, 1f))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(barColor.copy(alpha = 0.8f))
                                )
                            }
                        }
                        Text(
                            "${(item.percentUsed * 100).toInt()}% used",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor.copy(alpha = 0.8f)
                        )
                    }
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
                label = { Text(period.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }) },
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun InsightsRow(insights: List<SpendingInsight>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        items(insights) { insight ->
            Card(
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(insight.icon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        insight.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryItem(item: CategoryBreakdown) {
    val categoryColor = remember(item.category.color) {
        try { Color(android.graphics.Color.parseColor(item.category.color)) }
        catch (e: Exception) { Color.Gray }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(categoryColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("€${String.format("%.2f", item.total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = categoryColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${item.percentage.toInt()}% of total spending  ·  ${item.count} transactions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MerchantItem(item: MerchantBreakdown) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${item.transactionCount} visits · avg €${String.format("%.2f", item.averageTransaction)}/visit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("€${String.format("%.2f", item.totalSpent)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RecurringItem(item: RecurringCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔄", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("Estimated every ${item.intervalDays} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.nextExpectedDate?.let { nextDate ->
                    val dateStr = remember(nextDate) {
                        java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(nextDate)
                    }
                    Text("Next expected: $dateStr", style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryLight)
                }
                Text("Seen ${item.occurrences} times", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("€${String.format("%.2f", item.amount)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(item.confidence.let { if (it > 0.8) "High confidence" else "Plausible" }, style = MaterialTheme.typography.labelSmall, color = if (item.confidence > 0.8) SemanticColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Feature 4: Spending Velocity Anomaly Card ──────────────────────────
@Composable
fun VelocityAnomalyCard(anomaly: VelocityAnomaly) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.DangerRed.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SemanticColors.DangerRed.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔥", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    anomaly.dayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (anomaly.topMerchants.isNotEmpty()) {
                    Text(
                        anomaly.topMerchants.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "€${String.format("%.2f", anomaly.dayTotal)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.DangerRed
                )
                Text(
                    "${String.format("%.1f", anomaly.deviationMultiple)}x avg",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.DangerRed.copy(alpha = 0.8f)
                )
                Text(
                    "vs. €${String.format("%.0f", anomaly.monthDailyAvg)}/day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ── Feature 3: Year-over-Year Comparison Card (fixed colors, Task B) ──
@Composable
fun YearOverYearCard(yoy: YearOverYearComparison) {
    val currentYearColor = MaterialTheme.colorScheme.primary
    val priorYearColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: year totals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "${yoy.currentYear}",
                        style = MaterialTheme.typography.labelMedium,
                        color = currentYearColor
                    )
                    Text(
                        "€${String.format("%.2f", yoy.currentYearTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = currentYearColor
                    )
                }
                yoy.changePercent?.let { pct ->
                    val isUp = pct > 0
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (isUp) SemanticColors.DangerRed else SemanticColors.SuccessGreen).copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "${if (isUp) "+" else ""}${String.format("%.1f", pct)}%",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isUp) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${yoy.priorYear}",
                        style = MaterialTheme.typography.labelMedium,
                        color = priorYearColor
                    )
                    Text(
                        "€${String.format("%.2f", yoy.priorYearTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = priorYearColor
                    )
                }
            }

            // Month delta grouped bar chart with explicit per-series colors
            if (yoy.deltaByMonth.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                val currentYearColorArgb = android.graphics.Color.argb(
                    255,
                    (currentYearColor.red * 255).toInt(),
                    (currentYearColor.green * 255).toInt(),
                    (currentYearColor.blue * 255).toInt()
                )
                val priorYearColorArgb = android.graphics.Color.argb(
                    (255 * 0.5f).toInt(),
                    (priorYearColor.red * 255).toInt(),
                    (priorYearColor.green * 255).toInt(),
                    (priorYearColor.blue * 255).toInt()
                )

                val yoyEntryModel = remember(yoy.deltaByMonth) {
                    val currentEntries = yoy.deltaByMonth.mapIndexed { i, (_, current, _) ->
                        entryOf(i.toFloat(), current.toFloat())
                    }
                    val priorEntries = yoy.deltaByMonth.mapIndexed { i, (_, _, prior) ->
                        entryOf(i.toFloat(), prior.toFloat())
                    }
                    entryModelOf(currentEntries, priorEntries)
                }
                val monthLabels = remember(yoy.deltaByMonth) {
                    yoy.deltaByMonth.map { it.first }
                }
                val yoyAxisFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                    val idx = value.toInt()
                    if (idx in monthLabels.indices) monthLabels[idx] else ""
                }

                Chart(
                    chart = columnChart(
                        columns = listOf(
                            LineComponent(color = currentYearColorArgb, thicknessDp = 8f),
                            LineComponent(color = priorYearColorArgb, thicknessDp = 8f)
                        )
                    ),
                    model = yoyEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = yoyAxisFormatter),
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )

                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(currentYearColor))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${yoy.currentYear}", style = MaterialTheme.typography.labelSmall, color = currentYearColor)
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(priorYearColor))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${yoy.priorYear}", style = MaterialTheme.typography.labelSmall, color = priorYearColor)
                }
            }
        }
    }
}

// ── Feature 5: Post-Salary Sequential Pattern Card ───────────────────
@Composable
fun PostSalaryPatternCard(pattern: PostSalaryPattern) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Based on ${pattern.salaryCount} salary cycles",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "€${String.format("%.0f", pattern.avgTotalSpentIn7Days)} in first 7 days",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        "${String.format("%.1f", pattern.avgDaysToFirstPurchase)}d to first purchase",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (pattern.topCategories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Where the money goes first:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val maxAvg = pattern.topCategories.maxOf { it.avgSpendAfterSalary }.coerceAtLeast(1.0)
                pattern.topCategories.forEach { cat ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(cat.categoryIcon, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(cat.categoryName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text("€${String.format("%.0f", cat.avgSpendAfterSalary)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { (cat.avgSpendAfterSalary / maxAvg).toFloat() },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Feature 6: Suspect / Duplicate Transaction Card ───────────────────
@Composable
fun SuspectTransactionCard(item: SuspectTransaction) {
    val (bgColor, iconEmoji) = when (item.reason) {
        SuspectReason.NEAR_DUPLICATE -> SemanticColors.DangerRed.copy(alpha = 0.08f) to "⚠️"
        SuspectReason.ROUND_AMOUNT   -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f) to "💰"
        SuspectReason.EXTREME_OUTLIER -> SemanticColors.DangerRed.copy(alpha = 0.08f) to "🚨"
    }
    val dateLabel = remember(item.dateMs) {
        java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(item.dateMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(iconEmoji, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(item.reasonLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Text(
                "€${String.format("%.2f", item.amount)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
