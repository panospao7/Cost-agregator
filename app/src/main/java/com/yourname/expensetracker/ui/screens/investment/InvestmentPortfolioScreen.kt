package com.yourname.expensetracker.ui.screens.investment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.investment.InvestmentPerformance
import com.yourname.expensetracker.domain.investment.PortfolioSummary
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentPortfolioScreen(
    onNavigateBack: () -> Unit,
    onAddInvestment: () -> Unit,
    viewModel: InvestmentViewModel = hiltViewModel()
) {
    val portfolioSummary by viewModel.portfolioSummary.collectAsState()
    val investments by viewModel.investments.collectAsState()
    val homeCurrency = viewModel.homeCurrency.collectAsState().value ?: ""
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_investment_portfolio)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onAddInvestment) {
                        Icon(Icons.Default.Add, stringResource(R.string.a11y_add_expense))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PortfolioSummaryCard(portfolioSummary, homeCurrency)
            }
            
            item {
                Text(
                    text = stringResource(R.string.header_your_investments),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            items(investments) { investment ->
                InvestmentCard(investment, homeCurrency)
            }
        }
    }
}

@Composable
private fun PortfolioSummaryCard(summary: PortfolioSummary, homeCurrency: String) {
    val percentFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 2 }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.label_total_portfolio_value),
                style = MaterialTheme.typography.labelLarge
            )
            
            Text(
                text = CurrencyFormatter.formatMoney(summary.totalValue, homeCurrency),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem(
                    label = stringResource(R.string.label_invested),
                    value = CurrencyFormatter.formatMoney(summary.totalInvested, homeCurrency)
                )
                SummaryItem(
                    label = stringResource(R.string.label_gain_loss),
                    value = CurrencyFormatter.formatMoney(summary.totalGainLoss, homeCurrency),
                    isPositive = summary.totalGainLoss >= 0
                )
                SummaryItem(
                    label = stringResource(R.string.label_return_percent),
                    value = percentFormat.format(summary.totalGainLossPercent / 100),
                    isPositive = summary.totalGainLossPercent >= 0
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    isPositive: Boolean? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = when {
                isPositive == true -> MaterialTheme.colorScheme.tertiary
                isPositive == false -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun InvestmentCard(performance: InvestmentPerformance, homeCurrency: String) {
    val percentFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 2 }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = performance.investment.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = performance.investment.symbol,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CurrencyFormatter.formatMoney(performance.currentValue, homeCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (performance.gainLoss >= 0) 
                                Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = if (performance.gainLoss >= 0) 
                                MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = percentFormat.format(performance.gainLossPercent / 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (performance.gainLoss >= 0) 
                                MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinearProgressIndicator(
                progress = { 
                    val result = (performance.gainLossPercent.toDouble() + 100.0)
                        .coerceIn(0.0, 200.0) / 200.0
                    result.toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
                color = if (performance.gainLoss >= 0) 
                    MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            )
        }
    }
}
