package com.yourname.expensetracker.ui.screens.lifestyle

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.ErrorType
import com.yourname.expensetracker.ui.components.common.InlineErrorBanner
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import java.text.NumberFormat
import java.util.Locale

internal const val MONTHLY_TREND_MIN_SEGMENT_WEIGHT = 0.001f

internal enum class LifestyleInflationContentState {
    FULL_SCREEN_LOADING,
    FULL_SCREEN_ERROR,
    CONTENT
}

internal fun resolveLifestyleInflationContentState(
    hasReport: Boolean,
    isLoading: Boolean,
    hasError: Boolean
): LifestyleInflationContentState {
    if (isLoading && !hasReport && !hasError) {
        return LifestyleInflationContentState.FULL_SCREEN_LOADING
    }

    if (hasError && !hasReport) {
        return LifestyleInflationContentState.FULL_SCREEN_ERROR
    }

    return LifestyleInflationContentState.CONTENT
}

internal data class MonthlyTrendBarWeights(
    val essential: Float?,
    val discretionary: Float?,
    val savings: Float?
)

internal fun calculateMonthlyTrendBarWeights(
    month: LifestyleInflationDetector.MonthlyLifestyleData,
    maxReferenceAmount: Double
): MonthlyTrendBarWeights {
    val safeReferenceAmount = maxReferenceAmount.coerceAtLeast(1.0)

    fun weightOrNull(amount: Double): Float? {
        if (amount <= 0.0) return null
        return (amount / safeReferenceAmount).toFloat().coerceAtLeast(MONTHLY_TREND_MIN_SEGMENT_WEIGHT)
    }

    val savings = (month.income - month.totalSpending).coerceAtLeast(0.0)

    return MonthlyTrendBarWeights(
        essential = weightOrNull(month.essentialSpending),
        discretionary = weightOrNull(month.discretionarySpending),
        savings = weightOrNull(savings)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifestyleInflationScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSavings: () -> Unit = {},
    viewModel: LifestyleInflationViewModel = hiltViewModel(),
    actionRegistry: ContextualActionRegistry
) {
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    var selectedPeriod by rememberSaveable { mutableStateOf(12) }
    
    LaunchedEffect(selectedPeriod) {
        viewModel.analyze(selectedPeriod)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lifestyle_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(stringResource(R.string.lifestyle_period_format, selectedPeriod))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            listOf(6, 12, 24).forEach { months ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lifestyle_period_format, months)) },
                                    onClick = {
                                        selectedPeriod = months
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        val hasReport = report != null
        val hasError = !error.isNullOrBlank()

        when (
            resolveLifestyleInflationContentState(
                hasReport = hasReport,
                isLoading = isLoading,
                hasError = hasError
            )
        ) {
            LifestyleInflationContentState.FULL_SCREEN_LOADING -> {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            }

            LifestyleInflationContentState.FULL_SCREEN_ERROR -> {
            ErrorState(
                type = ErrorType.UNKNOWN,
                message = stringResource(R.string.error_load_failed),
                isRetrying = isLoading,
                onRetry = { viewModel.analyze(selectedPeriod) },
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
            }

            LifestyleInflationContentState.CONTENT -> {
                report?.let { data ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (isLoading) {
                            item {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        if (hasError) {
                            item {
                                InlineErrorBanner(
                                    message = stringResource(R.string.error_load_failed),
                                    onRetry = { viewModel.analyze(selectedPeriod) },
                                    isRetrying = isLoading,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Overview Card
                        item {
                            LifestyleOverviewCard(report = data)
                        }
                    
                    // Correlation & Elasticity
                    item {
                        CorrelationMetricsCard(report = data)
                    }
                    
                    // Alerts
                    if (data.lifestyleCreepAlerts.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.lifestyle_creep_alerts_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        
                        items(data.lifestyleCreepAlerts) { alert ->
                            CreepAlertCard(alert = alert)
                        }
                    }
                    
                    // Monthly Trend Chart
                    item {
                        MonthlyTrendCard(monthlyData = data.monthlyData, homeCurrency = homeCurrency)
                    }
                    
                    // Recommendations
                    if (data.recommendations.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.lifestyle_recommendations_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        items(data.recommendations) { recommendation ->
                            RecommendationCard(recommendation = recommendation)
                        }
                    }
                    
                    // Savings CTA when inflation detected
                    if (data.lifestyleInflationRate > 0.05 || data.lifestyleCreepDetected) {
                        item {
                            SavingsPromptCard(
                                inflationRate = data.lifestyleInflationRate,
                                onIncreaseSavings = onNavigateToSavings
                            )
                        }
                    }
                    
                    // Hedonic Adaptation
                    item {
                        HedonicAdaptationCard(score = data.hedonicAdaptationScore)
                    }
                    }
                } ?: run {
                    // Enhanced empty state with contextual actions
                    val emptyStateActions by remember(completedActionKeys) {
                        derivedStateOf {
                            actionRegistry.getActions(EmptyStateScreenKeys.LIFESTYLE)
                        }
                    }

                    EnhancedEmptyState(
                        type = EmptyStateType.GENERIC,
                        title = stringResource(R.string.lifestyle_no_data_title),
                        message = stringResource(R.string.lifestyle_no_data_subtitle),
                        actions = emptyStateActions,
                        onActionClick = { action ->
                            when (val actionType = action.action) {
                                is EmptyStateActionType.NavigateToDestination -> {
                                    // Handle navigation
                                }
                                is EmptyStateActionType.ExecuteAction -> actionType.action.invoke()
                                is EmptyStateActionType.OpenFeature -> {
                                    // Handle opening features
                                }
                            }
                        },
                        onDismissAction = { actionId ->
                            actionRegistry.markCompleted(EmptyStateScreenKeys.LIFESTYLE, actionId)
                        },
                        actionLabel = stringResource(R.string.lifestyle_retry_button),
                        onPrimaryClick = { viewModel.analyze(selectedPeriod) },
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun LifestyleOverviewCard(report: LifestyleInflationDetector.LifestyleInflationReport) {
    val numberFormat = NumberFormat.getPercentInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 1
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (report.lifestyleInflationRate > 0.05)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.lifestyle_analysis_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = numberFormat.format(report.lifestyleInflationRate),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = if (report.lifestyleInflationRate > 0) stringResource(R.string.lifestyle_inflation_rate) else stringResource(R.string.lifestyle_deflation_rate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = when {
                    report.lifestyleInflationRate > 0.1 -> 
                        stringResource(R.string.lifestyle_status_high_inflation)
                    report.lifestyleInflationRate > 0.05 -> 
                        stringResource(R.string.lifestyle_status_moderate_inflation)
                    report.lifestyleInflationRate > -0.05 -> 
                        stringResource(R.string.lifestyle_status_controlled)
                    else -> 
                        stringResource(R.string.lifestyle_status_deflation)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(
                    label = stringResource(R.string.lifestyle_income_growth),
                    value = numberFormat.format(report.incomeGrowthRate),
                    isPositive = report.incomeGrowthRate > 0
                )
                
                MetricItem(
                    label = stringResource(R.string.lifestyle_spending_growth),
                    value = numberFormat.format(report.spendingGrowthRate),
                    isPositive = report.spendingGrowthRate < report.incomeGrowthRate
                )
                
                MetricItem(
                    label = stringResource(R.string.lifestyle_savings_rate),
                    value = report.monthlyData.lastOrNull()?.let { 
                        numberFormat.format(it.savingsRate / 100) 
                    } ?: stringResource(R.string.label_no_data),
                    isPositive = (report.monthlyData.lastOrNull()?.savingsRate ?: 0.0) > 20
                )
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, isPositive: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isPositive) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.error
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CorrelationMetricsCard(report: LifestyleInflationDetector.LifestyleInflationReport) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.lifestyle_relationship_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Correlation Gauge
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lifestyle_correlation),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format("%.2f", report.incomeSpendingCorrelation),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                LinearProgressIndicator(
                    progress = { (report.incomeSpendingCorrelation.coerceIn(-1.0, 1.0).toFloat() + 1) / 2 },
                    modifier = Modifier
                        .weight(2f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        report.incomeSpendingCorrelation > 0.7 -> MaterialTheme.colorScheme.error
                        report.incomeSpendingCorrelation > 0.5 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Elasticity
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lifestyle_income_elasticity),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format("%.2f", report.incomeElasticity),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            report.incomeElasticity > 1.2 -> MaterialTheme.colorScheme.error
                            report.incomeElasticity > 1.0 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
                
                Box(
                    modifier = Modifier.weight(2f)
                ) {
                    val elasticityPercent = String.format("%.0f", report.incomeElasticity * 100)
                    Text(
                        text = when {
                            report.incomeElasticity > 1.5 -> stringResource(R.string.lifestyle_elasticity_high_format, elasticityPercent)
                            report.incomeElasticity > 1.0 -> stringResource(R.string.lifestyle_elasticity_moderate)
                            else -> stringResource(R.string.lifestyle_elasticity_controlled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun CreepAlertCard(alert: LifestyleInflationDetector.LifestyleCreepAlert) {
    val cardColor = when (alert.severity) {
        LifestyleInflationDetector.CreepSeverity.HIGH -> MaterialTheme.colorScheme.errorContainer
        LifestyleInflationDetector.CreepSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer
        LifestyleInflationDetector.CreepSeverity.LOW -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = when (alert.severity) {
                        LifestyleInflationDetector.CreepSeverity.HIGH -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = alert.month,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (alert.severity) {
                                LifestyleInflationDetector.CreepSeverity.HIGH -> R.string.lifestyle_creep_severity_high
                                LifestyleInflationDetector.CreepSeverity.MEDIUM -> R.string.lifestyle_creep_severity_medium
                                LifestyleInflationDetector.CreepSeverity.LOW -> R.string.lifestyle_creep_severity_low
                            }
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = alert.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.lifestyle_income_format, alert.incomeGrowthPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.lifestyle_spending_format, alert.spendingGrowthPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.lifestyle_discretionary_format, alert.discretionaryGrowthPercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
fun MonthlyTrendCard(monthlyData: List<LifestyleInflationDetector.MonthlyLifestyleData>, homeCurrency: String) {
    val maxReferenceAmount = monthlyData
        .maxOfOrNull { maxOf(it.income, it.totalSpending) }
        ?.coerceAtLeast(1.0)
        ?: 1.0
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.lifestyle_monthly_breakdown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Simple trend visualization
            monthlyData.takeLast(6).forEach { month ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = month.month,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(80.dp)
                    )
                    
                    // Income bar
                    val barWeights = calculateMonthlyTrendBarWeights(
                        month = month,
                        maxReferenceAmount = maxReferenceAmount
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Essential spending (gray)
                            barWeights.essential?.let { essentialWeight ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(essentialWeight)
                                        .background(MaterialTheme.colorScheme.outline)
                                )
                            }
                            // Discretionary spending (orange)
                            barWeights.discretionary?.let { discretionaryWeight ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(discretionaryWeight)
                                        .background(MaterialTheme.colorScheme.tertiary)
                                )
                            }
                            // Savings (green)
                            barWeights.savings?.let { savingsWeight ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(savingsWeight)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "${String.format("%.0f", month.savingsRate)}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LegendItem(color = MaterialTheme.colorScheme.outline, label = stringResource(R.string.lifestyle_legend_essential))
                LegendItem(color = MaterialTheme.colorScheme.tertiary, label = stringResource(R.string.lifestyle_legend_discretionary))
                LegendItem(color = MaterialTheme.colorScheme.primary, label = stringResource(R.string.lifestyle_legend_savings))
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun RecommendationCard(recommendation: LifestyleInflationDetector.LifestyleRecommendation) {
    val borderColor = when (recommendation.priority) {
        LifestyleInflationDetector.RecommendationPriority.HIGH -> MaterialTheme.colorScheme.error
        LifestyleInflationDetector.RecommendationPriority.MEDIUM -> MaterialTheme.colorScheme.tertiary
        LifestyleInflationDetector.RecommendationPriority.LOW -> MaterialTheme.colorScheme.outline
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (recommendation.priority) {
                        LifestyleInflationDetector.RecommendationPriority.HIGH -> Icons.Rounded.Error
                        LifestyleInflationDetector.RecommendationPriority.MEDIUM -> Icons.Rounded.Warning
                        LifestyleInflationDetector.RecommendationPriority.LOW -> Icons.Rounded.Info
                    },
                    contentDescription = null,
                    tint = borderColor
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = recommendation.title.asString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Surface(
                    color = borderColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (recommendation.priority) {
                                LifestyleInflationDetector.RecommendationPriority.HIGH -> R.string.lifestyle_recommendation_priority_high
                                LifestyleInflationDetector.RecommendationPriority.MEDIUM -> R.string.lifestyle_recommendation_priority_medium
                                LifestyleInflationDetector.RecommendationPriority.LOW -> R.string.lifestyle_recommendation_priority_low
                            }
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = recommendation.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            if (recommendation.actionItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.lifestyle_action_items),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                recommendation.actionItems.forEach { action ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(
                            text = action,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HedonicAdaptationCard(score: Double) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = stringResource(R.string.lifestyle_hedonic_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "${String.format("%.0f", score)}/100",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    score > 70 -> MaterialTheme.colorScheme.error
                    score > 40 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = when {
                    score > 70 -> stringResource(R.string.lifestyle_hedonic_high)
                    score > 40 -> stringResource(R.string.lifestyle_hedonic_moderate)
                    else -> stringResource(R.string.lifestyle_hedonic_controlled)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LinearProgressIndicator(
                progress = { (score / 100).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    score > 70 -> MaterialTheme.colorScheme.error
                    score > 40 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

@Composable
fun SavingsPromptCard(
    inflationRate: Double,
    onIncreaseSavings: () -> Unit
) {
    val numberFormat = NumberFormat.getPercentInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 1
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = stringResource(R.string.lifestyle_savings_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(
                    R.string.lifestyle_savings_prompt_body_format,
                    numberFormat.format(inflationRate)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
        Button(
            onClick = onIncreaseSavings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.lifestyle_savings_prompt_action))
        }
        }
    }
}
