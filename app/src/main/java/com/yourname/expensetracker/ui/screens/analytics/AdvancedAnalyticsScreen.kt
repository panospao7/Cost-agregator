package com.yourname.expensetracker.ui.screens.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.analytics.AnalyticsDashboardData
import com.yourname.expensetracker.domain.analytics.DashboardInsight
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.asString
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedAnalyticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AdvancedAnalyticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.advanced_analytics_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                AnalyticsUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is AnalyticsUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.label_error),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                is AnalyticsUiState.Success -> {
                    val data = state.data
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            CashflowOverviewCard(data)
                        }
                        
                        if (data.insights.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.analytics_insights),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                            
                            items(data.insights) { insight ->
                                InsightCard(insight)
                            }
                        }
                        
                        if (data.topCategories.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.analytics_top_categories),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                            
                            items(data.topCategories) { category ->
                                CategoryCard(category)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CashflowOverviewCard(data: AnalyticsDashboardData) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.analytics_cashflow_overview),
                style = MaterialTheme.typography.headlineSmall
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CashflowItem(
                    label = stringResource(R.string.analytics_income),
                    value = currencyFormat.format(data.totalIncome),
                    color = MaterialTheme.colorScheme.tertiary
                )
                CashflowItem(
                    label = stringResource(R.string.analytics_spent),
                    value = currencyFormat.format(data.totalSpent),
                    color = MaterialTheme.colorScheme.error
                )
                CashflowItem(
                    label = stringResource(R.string.analytics_net),
                    value = currencyFormat.format(data.netCashflow),
                    color = if (data.netCashflow >= 0) 
                        MaterialTheme.colorScheme.tertiary 
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CashflowItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = color
        )
    }
}

@Composable
private fun InsightCard(insight: DashboardInsight) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (insight.severity) {
                com.yourname.expensetracker.domain.analytics.DashboardInsightSeverity.ALERT -> 
                    MaterialTheme.colorScheme.errorContainer
                com.yourname.expensetracker.domain.analytics.DashboardInsightSeverity.WARNING -> 
                    MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TrendingUp,
                contentDescription = null,
                tint = when (insight.severity) {
                    com.yourname.expensetracker.domain.analytics.DashboardInsightSeverity.ALERT -> 
                        MaterialTheme.colorScheme.error
                    com.yourname.expensetracker.domain.analytics.DashboardInsightSeverity.WARNING -> 
                        MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = insight.title.asString(),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = insight.description.asString(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(category: com.yourname.expensetracker.domain.analytics.AnalyticsDashboardCategoryBreakdown) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = category.categoryName.asString(),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.analytics_percent_of_total_format, String.format("%.1f", category.percentage)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Text(
                text = currencyFormat.format(category.amount),
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
