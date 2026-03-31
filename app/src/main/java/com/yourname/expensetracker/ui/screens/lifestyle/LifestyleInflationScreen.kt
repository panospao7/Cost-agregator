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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifestyleInflationScreen(
    onNavigateBack: () -> Unit,
    viewModel: LifestyleInflationViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedPeriod by remember { mutableStateOf(12) }
    
    LaunchedEffect(selectedPeriod) {
        viewModel.analyze(selectedPeriod)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lifestyle Inflation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text("$selectedPeriod months")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            listOf(6, 12, 24).forEach { months ->
                                DropdownMenuItem(
                                    text = { Text("$months months") },
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
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            report?.let { data ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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
                                text = "⚠️ Lifestyle Creep Alerts",
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
                        MonthlyTrendCard(monthlyData = data.monthlyData)
                    }
                    
                    // Recommendations
                    if (data.recommendations.isNotEmpty()) {
                        item {
                            Text(
                                text = "💡 Recommendations",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        items(data.recommendations) { recommendation ->
                            RecommendationCard(recommendation = recommendation)
                        }
                    }
                    
                    // Hedonic Adaptation
                    item {
                        HedonicAdaptationCard(score = data.hedonicAdaptationScore)
                    }
                }
            } ?: run {
                EmptyState(
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { viewModel.analyze(selectedPeriod) }
                )
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
                text = "Lifestyle Inflation Analysis",
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
                    text = if (report.lifestyleInflationRate > 0) "inflation rate" else "deflation rate",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = when {
                    report.lifestyleInflationRate > 0.1 -> 
                        "⚠️ Your lifestyle is inflating faster than your income"
                    report.lifestyleInflationRate > 0.05 -> 
                        "⚡ Spending is growing slightly faster than income"
                    report.lifestyleInflationRate > -0.05 -> 
                        "✅ Your lifestyle inflation is well controlled"
                    else -> 
                        "🎉 Great! You're spending less even with stable/increased income"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(
                    label = "Income Growth",
                    value = numberFormat.format(report.incomeGrowthRate),
                    isPositive = report.incomeGrowthRate > 0
                )
                
                MetricItem(
                    label = "Spending Growth",
                    value = numberFormat.format(report.spendingGrowthRate),
                    isPositive = report.spendingGrowthRate < report.incomeGrowthRate
                )
                
                MetricItem(
                    label = "Savings Rate",
                    value = report.monthlyData.lastOrNull()?.let { 
                        numberFormat.format(it.savingsRate / 100) 
                    } ?: "N/A",
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
                text = "Income-Spending Relationship",
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
                        text = "Correlation",
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
                        text = "Income Elasticity",
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
                    Text(
                        text = when {
                            report.incomeElasticity > 1.5 -> "⚠️ For every 1% income ↑, spending ↑ ${String.format("%.0f", report.incomeElasticity * 100)}%"
                            report.incomeElasticity > 1.0 -> "⚡ Spending grows faster than income"
                            else -> "✅ Spending growth is under control"
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
                        text = alert.severity.name,
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
                    text = "Income: +${String.format("%.1f", alert.incomeGrowthPercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Spending: +${String.format("%.1f", alert.spendingGrowthPercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Discretionary: +${String.format("%.1f", alert.discretionaryGrowthPercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
fun MonthlyTrendCard(monthlyData: List<LifestyleInflationDetector.MonthlyLifestyleData>) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Monthly Breakdown",
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
                    val maxIncome = monthlyData.maxOf { it.income }.coerceAtLeast(1.0)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row {
                            // Essential spending (gray)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(month.essentialSpending.toFloat() / maxIncome.toFloat())
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                            // Discretionary spending (orange)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(month.discretionarySpending.toFloat() / maxIncome.toFloat())
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                            // Savings (green)
                            val savings = month.income - month.totalSpending
                            if (savings > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(savings.toFloat() / maxIncome.toFloat())
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
                LegendItem(color = MaterialTheme.colorScheme.outline, label = "Essential")
                LegendItem(color = MaterialTheme.colorScheme.tertiary, label = "Discretionary")
                LegendItem(color = MaterialTheme.colorScheme.primary, label = "Savings")
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
                    text = recommendation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Surface(
                    color = borderColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = recommendation.priority.name,
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
                    text = "Action Items:",
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
                    text = "Hedonic Adaptation Score",
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
                    score > 70 -> "High volatility in discretionary spending suggests hedonic treadmill - each purchase brings less satisfaction"
                    score > 40 -> "Moderate spending variation - watch for lifestyle inflation patterns"
                    else -> "Stable discretionary spending habits - good control over lifestyle inflation"
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
fun EmptyState(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "No Data Available",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Add income and spending transactions to see lifestyle inflation analysis",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onRetry) {
            Text("Retry Analysis")
        }
    }
}
