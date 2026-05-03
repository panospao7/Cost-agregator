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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
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
import com.yourname.expensetracker.domain.analytics.EnhancedCategoryAnalytics
import com.yourname.expensetracker.domain.analytics.EnhancedMerchantAnalytics
import com.yourname.expensetracker.domain.analytics.SpendingPatternAnalysis
import com.yourname.expensetracker.domain.analytics.StatisticalInsights
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.components.BentoCard
import com.yourname.expensetracker.ui.components.analytics.*
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.location.AreaSpending
import com.yourname.expensetracker.domain.location.TravelInsight
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.components.analytics.PersonalityProfileCard
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import kotlin.math.roundToInt
import java.util.Locale

private fun formatAmount(amount: Double, currency: String, showCents: Boolean = true): String =
    CurrencyFormatter.format(amount, currency, showCents)

private fun Double.toSafeChartAmount(): Float {
    if (!isFinite()) return 0f
    val rounded = ((this * 100.0).roundToInt() / 100.0).toFloat()
    return if (rounded.isFinite()) rounded else 0f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    initialPeriod: String? = null,
    onNavigateToTransactions: ((TransactionFilter) -> Unit)? = null,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(initialPeriod) {
        initialPeriod
            ?.let(::parseTimePeriodOrNull)
            ?.takeIf { it != state.selectedPeriod }
            ?.let(viewModel::selectPeriod)
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.analytics_screen_title), 
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            // Skeleton loading state
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
                        .height(160.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Analytics cards skeleton
                ListSkeleton(itemCount = 6)
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

                if (state.conversionWarnings.isNotEmpty()) {
                    item { AnalyticsWarningsCard(state.conversionWarnings) }
                }

                // 3. Statistical Highlights (daily avg, largest spend, volatility)
                state.statisticalInsights?.let { stats ->
                    item { StatisticalHighlights(stats) }
                    
                    // NEW: Percentile Grid - Shows P10, P25, P50, P75, P90
                    item { PercentileGridCard(percentiles = stats.percentiles, currency = state.homeCurrency) }
                    
                    // NEW: Transaction Histogram - Visual distribution of transaction sizes
                    if (stats.histogramBins.isNotEmpty()) {
                        item { TransactionHistogramChart(bins = stats.histogramBins, currency = state.homeCurrency) }
                    }
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
                    item { HourOfDayChartBento(state.hourOfDayPattern, state.homeCurrency) }
                }

                // 5d. Spending Patterns (weekend vs weekday, detected behaviors)
                state.spendingPatterns?.let { patterns ->
                    item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_spending_habits), stringResource(R.string.analytics_section_spending_habits_subtitle)) }
                    item { SpendingPatternsCard(patterns) }
                }

                // 6. Category Breakdown (Enhanced if available, basic otherwise)
                if (state.enhancedCategories.isNotEmpty()) {
                    item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_category_breakdown), stringResource(R.string.analytics_section_category_breakdown_subtitle)) }
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
                    item { SectionHeader(stringResource(R.string.analytics_section_breakdown_category)) }
                    item {
                        CategoryDonutChart(
                            categories = state.categoryBreakdown,
                            totalSpent = state.currentTotal
                        )
                    }
                    items(state.categoryBreakdown) { CategoryItem(it, homeCurrency = state.homeCurrency) }
                }

                // 6b. Budget vs Actual
                if (state.budgetVsActual.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analytics_section_budget_vs_actual)) }
                    item { BudgetVsActualChart(state.budgetVsActual) }
                }

                // 7. Deep Insights Carousel
                if (state.insights.size > 1) {
                    item { SectionHeader(stringResource(R.string.analytics_section_deep_insights)) }
                    item { InsightsRow(state.insights.drop(1)) }
                }

                // 8. Merchant Breakdown (Enhanced if available)
                if (state.enhancedMerchants.isNotEmpty()) {
                    item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_merchant_intelligence), stringResource(R.string.analytics_section_merchant_intelligence_subtitle)) }
                    items(state.enhancedMerchants) { merch ->
                        // NEW: Rich merchant card with loyalty, streak, consistency, and price trends
                        RichMerchantCard(
                            merchant = merch.merchant,
                            totalSpent = merch.totalSpent,
                            transactionCount = merch.transactionCount,
                            averagePerVisit = merch.averagePerVisit,
                            currency = merch.displayCurrency,
                            loyaltyScore = merch.loyaltyScore,
                            consecutiveMonthsVisited = merch.consecutiveMonthsVisited,
                            consistencyRating = merch.consistencyRating.name,
                            priceChangePercent = merch.priceChangePercent,
                            predictedNextVisitDate = merch.predictedNextVisitDate,
                            nowMs = state.referenceNowMillis,
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
                    item { SectionHeader(stringResource(R.string.analytics_section_top_merchants)) }
                    items(state.merchantBreakdown.take(8)) { MerchantItem(it, homeCurrency = state.homeCurrency) }
                }

                // 8.5. Top Spending Places (B5 — LocationInsightsEngine)
                if (state.locationInsights.isNotEmpty()) {
                    item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_top_places), stringResource(R.string.analytics_section_top_places_subtitle)) }
                    items(state.locationInsights.take(5)) { insight ->
                        PlaceInsightCard(insight, homeCurrency = state.homeCurrency)
                    }
                }

                // 8.6. Spending by Area (B1 — AreaSpendingEngine)
                if (state.areaSpending.isNotEmpty()) {
                    item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_spending_by_area), stringResource(R.string.analytics_section_spending_by_area_subtitle)) }
                    items(state.areaSpending.take(6)) { area ->
                        AreaSpendingItem(area, homeCurrency = state.homeCurrency)
                    }
                }

                // 8.7. Travel vs Home Spending (B2 — TravelDetectionEngine)
                state.travelInsight?.let { travel ->
                    if (travel.travelSpend > 0 || travel.localSpend > 0) {
                        item { AnalyticsSectionHeader(stringResource(R.string.analytics_section_travel_vs_home), stringResource(R.string.analytics_section_travel_vs_home_subtitle)) }
                        item { TravelInsightCard(travel, homeCurrency = state.homeCurrency) }
                    }
                }

                // 9. Velocity Anomalies
                if (state.velocityAnomalies.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analytics_section_velocity_anomalies)) }
                    items(state.velocityAnomalies) { VelocityAnomalyCard(it, homeCurrency = state.homeCurrency) }
                }

                // 10. Year-over-Year Comparison
                state.yearOverYear?.let { yoy ->
                    item { SectionHeader(stringResource(R.string.analytics_section_yoy_comparison)) }
                    item { YearOverYearCard(yoy, homeCurrency = state.homeCurrency) }
                }

                // 11. Post-Salary Sequential Pattern
                state.postSalaryPattern?.let { pattern ->
                    item { SectionHeader(stringResource(R.string.analytics_section_post_salary)) }
                    item { PostSalaryPatternCard(pattern, homeCurrency = state.homeCurrency) }
                }

                // 12. Suspect / Duplicate Transactions
                if (state.suspectTransactions.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analytics_section_suspect_duplicates)) }
                    items(state.suspectTransactions) { SuspectTransactionCard(it, homeCurrency = state.homeCurrency) }
                }

                // 13. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.analytics_section_subscription_detection)) }
                    items(state.recurring) { RecurringItem(it, homeCurrency = state.homeCurrency) }
                }

                // 14. Spending Personality Profile (F13)
                state.personalityProfile?.let { profile ->
                    item { SectionHeader(stringResource(R.string.analytics_section_personality_profile)) }
                    item { PersonalityProfileCard(profile) }
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
                Text(stringResource(R.string.analytics_stat_daily_average), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                AmountText(stats.averageDailySpend, currency = stats.displayCurrency, style = MaterialTheme.typography.headlineMedium)
            }
            BentoCard(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.analytics_stat_largest_spend), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                stats.largestTransaction?.let {
                    AmountText(it.amount, currency = it.currency, style = MaterialTheme.typography.headlineMedium)
                    Text(it.merchant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        BentoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.analytics_stat_consistency), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            stats.volatilityIndex < 30 -> stringResource(R.string.analytics_consistency_very_consistent)
                            stats.volatilityIndex < 60 -> stringResource(R.string.analytics_consistency_normal)
                            else -> stringResource(R.string.analytics_consistency_highly_volatile)
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
                    Text(stringResource(R.string.analytics_transactions_format, item.transactionCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, currency = item.displayCurrency, style = MaterialTheme.typography.titleMedium)
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
                val hasValidBudget = budget > 0.0
                val budgetProgress = if (hasValidBudget) {
                    (item.totalSpent / budget).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { budgetProgress },
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
                        text = if (hasValidBudget) {
                            stringResource(R.string.analytics_budget_percent_format, item.budgetUtilizationPercent?.toInt() ?: 0)
                        } else {
                            stringResource(R.string.forecast_no_budget_set)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sparkline mini chart
            if (item.sparklineData.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                val chartModel = remember(item.category.id, item.sparklineData) {
                    entryModelOf(
                        item.sparklineData.mapIndexed { index, value ->
                            FloatEntry(index.toFloat(), value.toSafeChartAmount())
                        }
                    )
                }
                Chart(
                    chart = lineChart(),
                    model = chartModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                )
            }
            
            // NEW: Percentile and velocity badges
            Spacer(modifier = Modifier.height(8.dp))
            CategoryPercentileBadge(
                percentile25 = item.percentile25,
                percentile75 = item.percentile75,
                velocity = item.velocity,
                currency = item.displayCurrency
            )
        }
    }
}

// ── Enhanced Merchant Item (from AdvancedAnalyticsScreen) ─────────────
@Composable
private fun EnhancedMerchantItem(
    item: EnhancedMerchantAnalytics,
    nowMs: Long,
    onClick: () -> Unit
) {
    // Remember expensive string calculations
    val merchantInitial by remember(item.merchant) {
        derivedStateOf { item.merchant.take(1).uppercase() }
    }
    val visitorType by remember(item.visitFrequency) {
        derivedStateOf { 
            item.visitFrequency.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
    val consistencyText by remember(item.consistencyRating) {
        derivedStateOf {
            item.consistencyRating.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
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
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(merchantInitial, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.analytics_visitor_type_format, visitorType, consistencyText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, currency = item.displayCurrency, style = MaterialTheme.typography.titleMedium)
                    item.priceChangePercent?.let { change ->
                        val priceChangeStr = stringResource(
                            R.string.analytics_merchant_prices_format,
                            if (change > 0) stringResource(R.string.analytics_change_increase) else "",
                            change
                        )
                        Text(
                            text = priceChangeStr,
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
                StatMicro(stringResource(R.string.analytics_stat_micro_avg_visit), item.averagePerVisit, item.displayCurrency)
                StatMicro(stringResource(R.string.analytics_stat_micro_loyalty), "${item.loyaltyScore.toInt()}/100")
                item.predictedNextVisitDate?.let {
                    val daysUntil = TimePeriodUtils.daysBetween(nowMs, it)
                    val nextExpectedText = if (daysUntil <= 0) 
                        stringResource(R.string.analytics_next_expected_soon) 
                    else 
                        stringResource(R.string.analytics_next_expected_days_format, daysUntil)
                    StatMicro(stringResource(R.string.analytics_stat_micro_next_expected), nextExpectedText)
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
                    stringResource(R.string.analytics_spending_weekend_format, analysis.weekendVsWeekday.weekendToWeekdayRatio),
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
/**
 * @param currency ISO-4217 currency code. Default "EUR" is a placeholder;
 *                 callers should pass the actual home currency from settings.
 */
fun HourOfDayChartBento(hourOfDayPattern: List<Pair<Int, Double>>, currency: String = "EUR") {
    BentoCard {
        Column {
            Text(
                stringResource(R.string.analytics_spending_by_hour),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val allHours = (0..23).map { h ->
                hourOfDayPattern.find { it.first == h }?.second ?: 0.0
            }
            val chartEntryModel = remember(hourOfDayPattern) {
                entryModelOf(allHours.mapIndexed { i, v -> entryOf(i.toFloat(), v.toSafeChartAmount()) })
            }
            val string12am = stringResource(R.string.analytics_chart_12am)
            val string12pm = stringResource(R.string.analytics_chart_12pm)
            val hourAxisFormatter = AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
                val h = value.toInt()
                when {
                    h == 0 -> string12am
                    h < 12 -> "${h}a"
                    h == 12 -> string12pm
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
                    h == 0 -> stringResource(R.string.analytics_hour_midnight)
                    h < 12 -> "${h}am"
                    h == 12 -> stringResource(R.string.analytics_hour_noon)
                    else -> "${h - 12}pm"
                }
                Text(
                    text = stringResource(R.string.analytics_peak_spending_format, label, formatAmount(total, currency, showCents = false)),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.PrimaryIndigo.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ── Shared helper composables ─────────────────────────────────────────
/**
 * @param currency ISO-4217 currency code. Default "EUR" is a placeholder;
 *                 callers should pass the actual home currency from settings.
 */
@Composable
fun StatMicro(label: String, value: Any, currency: String = "EUR") {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value is Double) {
            AmountText(value, currency = currency, style = MaterialTheme.typography.labelMedium)
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
                currency = state.homeCurrency,
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
                    text = stringResource(
                        R.string.analytics_vs_last_period_format,
                        CurrencyFormatter.format(prevTotal, state.homeCurrency, showCents = false)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = stringResource(R.string.analytics_total_transactions_format, state.transactionCount),
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
                    text = insight.title.asString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = insight.description.asString(),
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
                stringResource(R.string.analytics_spending_distribution),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.analytics_insufficient_data), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val sortedEntries = remember(state.dailyTotals) {
                    state.dailyTotals.entries.sortedBy { it.key }
                }
                val chartEntryModel = remember(sortedEntries) {
                    val values = sortedEntries.map { it.value.toSafeChartAmount() }
                    val safeValues = if (values.size < 2) values + 0f else values
                    val entries = safeValues.mapIndexed { index, value ->
                        entryOf(index.toFloat(), value)
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
                stringResource(R.string.analytics_spending_by_dow),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (dayOfWeekPattern.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.analytics_insufficient_data_short), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val sorted = remember(dayOfWeekPattern) {
                    dayOfWeekPattern.sortedBy { it.dayIndex }
                }
                val chartEntryModel = remember(sorted) {
                    val values = sorted.map { it.totalSpent.toSafeChartAmount() }
                    val safeValues = if (values.size < 2) values + 0f else values
                    val entries = safeValues.mapIndexed { index, value ->
                        entryOf(index.toFloat(), value)
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
                                stringResource(R.string.analytics_peak_most_format, it.dayName, formatAmount(it.totalSpent, it.displayCurrency, showCents = false)),
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.DangerRed.copy(alpha = 0.8f)
                            )
                        }
                        lowest?.let {
                            Text(
                                stringResource(R.string.analytics_peak_least_format, it.dayName, formatAmount(it.totalSpent, it.displayCurrency, showCents = false)),
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
                stringResource(R.string.analytics_budget_utilization),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            items.forEach { item ->
                val hasValidBudget = item.budgetAmount > 0
                val safePercentUsed = if (hasValidBudget) {
                    item.percentUsed.takeIf { it.isFinite() && it >= 0f } ?: 0f
                } else {
                    0f
                }
                val barColor = try {
                    Color(android.graphics.Color.parseColor(item.categoryColor))
                } catch (_: Exception) {
                    SemanticColors.PrimaryIndigo
                }
                val statusColor = when {
                    safePercentUsed > 1f -> SemanticColors.DangerRed
                    safePercentUsed > 0.75f -> SemanticColors.WarningOrange
                    else -> SemanticColors.SuccessGreen
                }
                val budgetTextColor = if (hasValidBudget) statusColor else MaterialTheme.colorScheme.onSurfaceVariant

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
                                text = if (hasValidBudget) {
                                    stringResource(
                                        R.string.analytics_budget_range_format,
                                        formatAmount(item.actualSpent, item.displayCurrency, showCents = false),
                                        formatAmount(item.budgetAmount, item.displayCurrency, showCents = false)
                                    )
                                } else {
                                    stringResource(R.string.forecast_no_budget_set)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = budgetTextColor,
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
                                    .fillMaxWidth(safePercentUsed.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor.copy(alpha = 0.8f))
                            )
                            if (safePercentUsed > 1f) {
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
                                        .fillMaxWidth((1f / safePercentUsed).coerceIn(0f, 1f))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(barColor.copy(alpha = 0.8f))
                                )
                            }
                        }
                        Text(
                            text = if (hasValidBudget) {
                                stringResource(R.string.analytics_percent_used_format, (safePercentUsed * 100).toInt())
                            } else {
                                stringResource(R.string.forecast_no_budget_set)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = budgetTextColor.copy(alpha = 0.8f)
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
            val label = period.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val isSelected = selected == period
            val periodStatus = if (isSelected) 
                stringResource(R.string.recurring_tab_selected) 
            else 
                stringResource(R.string.recurring_tab_not_selected)
            val periodCd = stringResource(R.string.analytics_period_selector_cd_format, label, periodStatus)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(period) },
                label = { Text(label) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics {
                    contentDescription = periodCd
                }
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
                            insight.title.asString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        insight.description.asString(),
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
fun CategoryItem(item: AnalyticsCategoryBreakdown, homeCurrency: String = item.displayCurrency) {
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
                Text(CurrencyFormatter.format(item.total, homeCurrency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                stringResource(R.string.analytics_percent_of_total_format, item.percentage.toInt(), item.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MerchantItem(item: MerchantBreakdown, homeCurrency: String = item.displayCurrency) {
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
                Text(
                    stringResource(
                        R.string.analytics_visits_avg_format,
                        item.transactionCount,
                        formatAmount(item.averageTransaction, item.displayCurrency)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(CurrencyFormatter.format(item.totalSpent, homeCurrency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RecurringItem(item: RecurringCandidate, homeCurrency: String = item.displayCurrency) {
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
                if (item.intervalDays > 0) {
                    Text("Estimated every ${item.intervalDays} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item.nextExpectedDate?.let { nextDate ->
                    val dateStr = remember(nextDate) {
                        java.time.format.DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.getDefault()).format(java.time.Instant.ofEpochMilli(nextDate).atZone(java.time.ZoneId.systemDefault()))
                    }
                    Text("Next expected: $dateStr", style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryLight)
                }
                if (item.occurrences > 0) {
                    Text("Seen ${item.occurrences} times", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(CurrencyFormatter.format(item.amount, homeCurrency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(item.confidence.let { if (it > 0.8) "High confidence" else "Plausible" }, style = MaterialTheme.typography.labelSmall, color = if (item.confidence > 0.8) SemanticColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Feature 4: Spending Velocity Anomaly Card ──────────────────────────
@Composable
fun VelocityAnomalyCard(anomaly: VelocityAnomaly, homeCurrency: String = anomaly.displayCurrency) {
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
                    CurrencyFormatter.format(anomaly.dayTotal, homeCurrency),
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
                    "vs. ${CurrencyFormatter.format(anomaly.monthDailyAvg, homeCurrency, showCents = false)}/day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ── Feature 3: Year-over-Year Comparison Card (fixed colors, Task B) ──
@Composable
fun YearOverYearCard(yoy: YearOverYearComparison, homeCurrency: String = yoy.displayCurrency) {
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
                        CurrencyFormatter.format(yoy.currentYearTotal, homeCurrency),
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
                        CurrencyFormatter.format(yoy.priorYearTotal, homeCurrency),
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
                    val safeData = if (yoy.deltaByMonth.size < 2) {
                        yoy.deltaByMonth + Triple("", 0.0, 0.0)
                    } else {
                        yoy.deltaByMonth
                    }

                    val currentEntries = safeData.mapIndexed { i, (_, current, _) ->
                        FloatEntry(x = i.toFloat(), y = current.toSafeChartAmount())
                    }
                    val priorEntries = safeData.mapIndexed { i, (_, _, prior) ->
                        FloatEntry(x = i.toFloat(), y = prior.toSafeChartAmount())
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
fun PostSalaryPatternCard(pattern: PostSalaryPattern, homeCurrency: String = pattern.displayCurrency) {
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
                        "${CurrencyFormatter.format(pattern.avgTotalSpentIn7Days, homeCurrency, showCents = false)} in first 7 days",
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
                                Text(CurrencyFormatter.format(cat.avgSpendAfterSalary, homeCurrency, showCents = false), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
fun SuspectTransactionCard(item: SuspectTransaction, homeCurrency: String = item.currency) {
    val (bgColor, iconEmoji) = when (item.reason) {
        SuspectReason.NEAR_DUPLICATE -> SemanticColors.DangerRed.copy(alpha = 0.08f) to "⚠️"
        SuspectReason.ROUND_AMOUNT   -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f) to "💰"
        SuspectReason.EXTREME_OUTLIER -> SemanticColors.DangerRed.copy(alpha = 0.08f) to "🚨"
    }
    val dateLabel = remember(item.dateMs) {
        java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm", java.util.Locale.getDefault())
            .format(java.time.Instant.ofEpochMilli(item.dateMs).atZone(java.time.ZoneId.systemDefault()))
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
                CurrencyFormatter.format(item.amount, homeCurrency),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ── Location: Area Spending Item (B1) ─────────────────────────────────
/**
 * @param homeCurrency ISO-4217 currency code. Default "EUR" is a placeholder;
 *                     callers should pass the actual home currency.
 */
@Composable
fun AreaSpendingItem(area: AreaSpending, homeCurrency: String = "EUR") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("📍", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(area.areaName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${area.transactionCount} transactions · avg ${CurrencyFormatter.format(area.avgTransaction, homeCurrency, showCents = false)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                CurrencyFormatter.format(area.totalSpend, homeCurrency),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Location: Travel vs Home Card (B2) ────────────────────────────────
/**
 * @param homeCurrency ISO-4217 currency code. Default "EUR" is a placeholder;
 *                     callers should pass the actual home currency.
 */
@Composable
fun TravelInsightCard(travel: TravelInsight, homeCurrency: String = "EUR") {
    val totalSpend = travel.homeSpend + travel.localSpend + travel.travelSpend

    BentoCard {
        Column {
            Text(
                "SPENDING ZONES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Three zone bars
            listOf(
                Triple("Home", travel.homeSpend, SemanticColors.SuccessGreen),
                Triple("Local", travel.localSpend, SemanticColors.WarningOrange),
                Triple("Travel", travel.travelSpend, SemanticColors.PrimaryIndigo)
            ).forEach { (label, spend, color) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { if (totalSpend > 0) (spend / totalSpend).toFloat().coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        CurrencyFormatter.format(spend, homeCurrency, showCents = false),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }

            // Trip list
            if (travel.travelTrips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${travel.travelTrips.size} trip${if (travel.travelTrips.size != 1) "s" else ""} detected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                val tripFmt = java.time.format.DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.getDefault())
                travel.travelTrips.take(3).forEach { trip ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val dateRange = "${tripFmt.format(java.time.Instant.ofEpochMilli(trip.startDate).atZone(java.time.ZoneId.systemDefault()))} – ${tripFmt.format(java.time.Instant.ofEpochMilli(trip.endDate).atZone(java.time.ZoneId.systemDefault()))}"
                        Text(
                            trip.destinationHint?.let { "$it ($dateRange)" } ?: dateRange,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            CurrencyFormatter.format(trip.totalSpend, homeCurrency, showCents = false),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnalyticsWarningsCard(warnings: List<AnalyticsConversionWarning>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.analytics_conversion_warning_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            warnings.forEach { warning ->
                Text(
                    text = buildString {
                        append(warning.message)
                        append(" ")
                        append(stringResource(R.string.analytics_conversion_warning_count_format, warning.affectedTransactionCount))
                        if (warning.sourceCurrencies.isNotEmpty()) {
                            append(" (")
                            append(warning.sourceCurrencies.joinToString())
                            append(")")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

private fun parseTimePeriodOrNull(value: String): TimePeriod? {
    return when (value.trim().lowercase()) {
        "today" -> TimePeriod.TODAY
        "week" -> TimePeriod.WEEK
        "month" -> TimePeriod.MONTH
        "quarter" -> TimePeriod.QUARTER
        "year" -> TimePeriod.YEAR
        "all" -> TimePeriod.ALL
        else -> null
    }
}
