package com.yourname.expensetracker.ui.screens.budget

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.domain.budget.BudgetRecommendation
import com.yourname.expensetracker.domain.budget.RecommendationPriority
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetForecastingScreen(
    budget: Budget,
    onNavigateBack: () -> Unit,
    viewModel: BudgetForecastingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    
    // Generate forecast on first load
    LaunchedEffect(budget) {
        viewModel.generateForecast(budget)
    }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.budget_forecast_title),
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshForecast() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.budget_forecast_refresh_cd),
                            tint = SemanticColors.TextPrimary
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.refreshForecast() }
                    )
                }
                uiState.forecast != null -> {
                    ForecastContent(
                        budget = uiState.budget!!,
                        forecast = uiState.forecast!!,
                        recommendations = uiState.recommendations,
                        homeCurrency = homeCurrency,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    EmptyState()
                }
            }
        }
    }
}

@Composable
private fun ForecastContent(
    budget: Budget,
    forecast: BudgetForecast,
    recommendations: List<BudgetRecommendation>,
    homeCurrency: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Risk Level Card
        item {
            RiskLevelCard(forecast = forecast)
        }
        
        // Forecast Details Card
        item {
            ForecastDetailsCard(budget = budget, forecast = forecast, homeCurrency = homeCurrency)
        }
        
        // Confidence Score Card
        item {
            ConfidenceCard(forecast = forecast)
        }
        
        // Recommendations Section
        if (recommendations.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.budget_forecast_ai_recommendations),
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            items(recommendations) { recommendation ->
                RecommendationCard(recommendation = recommendation, homeCurrency = homeCurrency)
            }
        }
    }
}

@Composable
private fun RiskLevelCard(forecast: BudgetForecast) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (riskColor, riskEmoji, riskTitle, riskDescription) = when (forecast.riskLevel) {
        ForecastRiskLevel.LOW -> Quadruple(
            SemanticColors.StatusGreen, "✅", context.getString(R.string.budget_forecast_risk_on_track), 
            context.getString(R.string.budget_forecast_desc_on_track)
        )
        ForecastRiskLevel.MEDIUM -> Quadruple(
            SemanticColors.StatusYellow, "⚠️", context.getString(R.string.budget_forecast_risk_caution), 
            context.getString(R.string.budget_forecast_desc_caution)
        )
        ForecastRiskLevel.HIGH -> Quadruple(
            SemanticColors.StatusRed, "🔴", context.getString(R.string.budget_forecast_risk_high), 
            context.getString(R.string.budget_forecast_desc_high)
        )
        ForecastRiskLevel.CRITICAL -> Quadruple(
            SemanticColors.StatusDarkRed, "🚨", context.getString(R.string.budget_forecast_risk_critical), 
            context.getString(R.string.budget_forecast_desc_critical)
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = riskColor.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$riskEmoji $riskTitle",
                style = MaterialTheme.typography.headlineSmall,
                color = riskColor,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = riskDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            
            if (forecast.overspendProbability > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.budget_forecast_overspend_probability_format, (forecast.overspendProbability * 100).toInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = riskColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForecastDetailsCard(budget: Budget, forecast: BudgetForecast, homeCurrency: String) {
    val (lowForecast, baseForecast, highForecast) = remember(forecast.predictedSpending, forecast.confidenceScore) {
        calculateForecastBounds(
            predictedSpending = forecast.predictedSpending,
            confidenceScore = forecast.confidenceScore
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.budget_forecast_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Budget Amount
            DetailRow(
                label = stringResource(R.string.budget_forecast_limit_label),
                value = CurrencyFormatter.formatMoney(budget.amount, homeCurrency),
                icon = Icons.Default.AccountBalanceWallet
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Predicted Spending
            DetailRow(
                label = stringResource(R.string.budget_forecast_predicted_spending),
                value = CurrencyFormatter.formatMoney(forecast.predictedSpending, homeCurrency),
                icon = Icons.Default.TrendingUp,
                valueColor = if (forecast.predictedSpending > budget.amount) 
                    SemanticColors.DangerRed else SemanticColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Predicted Remaining
            DetailRow(
                label = stringResource(R.string.budget_forecast_predicted_remaining),
                value = CurrencyFormatter.formatMoney(forecast.predictedRemaining, homeCurrency),
                icon = if (forecast.predictedRemaining >= 0) 
                    Icons.Default.Savings else Icons.Default.Warning,
                valueColor = when {
                    forecast.predictedRemaining < 0 -> SemanticColors.DangerRed
                    forecast.predictedRemaining < budget.amount * 0.2 -> SemanticColors.StatusYellow
                    else -> SemanticColors.StatusGreen
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            ConfidenceIntervalSection(
                budgetAmount = budget.amount,
                lowForecast = lowForecast,
                baseForecast = baseForecast,
                highForecast = highForecast,
                confidenceScore = forecast.confidenceScore,
                homeCurrency = homeCurrency
            )
        }
    }
}

@Composable
private fun ConfidenceIntervalSection(
    budgetAmount: Double,
    lowForecast: Double,
    baseForecast: Double,
    highForecast: Double,
    confidenceScore: Double,
    homeCurrency: String
) {
    Text(
        text = "Forecast range (low / base / high)",
        style = MaterialTheme.typography.labelLarge,
        color = SemanticColors.TextPrimary,
        fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(8.dp))

    RangeRow(
        label = "Low",
        amount = lowForecast,
        budgetAmount = budgetAmount,
        color = SemanticColors.StatusGreen,
        homeCurrency = homeCurrency
    )
    RangeRow(
        label = "Base",
        amount = baseForecast,
        budgetAmount = budgetAmount,
        color = SemanticColors.PrimaryIndigo,
        homeCurrency = homeCurrency
    )
    RangeRow(
        label = "High",
        amount = highForecast,
        budgetAmount = budgetAmount,
        color = SemanticColors.StatusRed,
        homeCurrency = homeCurrency
    )

    Spacer(modifier = Modifier.height(8.dp))

    val confidencePercent = (confidenceScore * 100).toInt()
    Text(
        text = "Range width reflects model uncertainty: higher confidence ($confidencePercent%) gives a tighter interval.",
        style = MaterialTheme.typography.bodySmall,
        color = SemanticColors.TextSecondary
    )
}

@Composable
private fun RangeRow(
    label: String,
    amount: Double,
    budgetAmount: Double,
    color: Color,
    homeCurrency: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            Text(
                text = CurrencyFormatter.formatMoney(amount, homeCurrency),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = {
                if (budgetAmount > 0) {
                    (amount / budgetAmount).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.2f)
        )

        Spacer(modifier = Modifier.height(6.dp))
    }
}

private fun calculateForecastBounds(predictedSpending: Double, confidenceScore: Double): Triple<Double, Double, Double> {
    val uncertaintyRatio = ((1.0 - confidenceScore).coerceIn(0.0, 1.0) * 0.55) + 0.10
    val spread = predictedSpending * uncertaintyRatio
    val low = max(0.0, predictedSpending - spread)
    val high = predictedSpending + spread
    return Triple(low, predictedSpending, high)
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = SemanticColors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SemanticColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
        }
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ConfidenceCard(forecast: BudgetForecast) {
    val confidencePercent = (forecast.confidenceScore * 100).toInt()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.budget_forecast_ai_confidence),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Confidence Bar
            LinearProgressIndicator(
                progress = { forecast.confidenceScore.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    forecast.confidenceScore >= 0.8 -> SemanticColors.StatusGreen
                    forecast.confidenceScore >= 0.6 -> SemanticColors.StatusYellow
                    else -> SemanticColors.StatusRed
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$confidencePercent%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                val confidenceText = when {
                    forecast.confidenceScore >= 0.8 -> stringResource(R.string.budget_forecast_confidence_high)
                    forecast.confidenceScore >= 0.6 -> stringResource(R.string.budget_forecast_confidence_medium)
                    else -> stringResource(R.string.budget_forecast_confidence_low)
                }
                
                Text(
                    text = confidenceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.TextSecondary
                )
            }
            
            if (forecast.confidenceScore < 0.6) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.budget_forecast_tracking_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: BudgetRecommendation, homeCurrency: String) {
    val priorityColor = when (recommendation.priority) {
        RecommendationPriority.CRITICAL -> SemanticColors.StatusDarkRed
        RecommendationPriority.HIGH -> SemanticColors.StatusRed
        RecommendationPriority.MEDIUM -> SemanticColors.StatusYellow
        RecommendationPriority.LOW -> SemanticColors.StatusGreen
    }
    
    val priorityIcon = when (recommendation.priority) {
        RecommendationPriority.CRITICAL -> Icons.Rounded.Error
        RecommendationPriority.HIGH -> Icons.Rounded.Warning
        RecommendationPriority.MEDIUM -> Icons.Rounded.Info
        RecommendationPriority.LOW -> Icons.Rounded.CheckCircle
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = priorityIcon,
                    contentDescription = null,
                    tint = priorityColor,
                    modifier = Modifier.size(28.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recommendation.title.asString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = recommendation.priority.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = priorityColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = recommendation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
            
            recommendation.potentialSavings?.let { savings ->
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.budget_forecast_potential_savings_format, CurrencyFormatter.formatMoney(savings, homeCurrency)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.StatusGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            if (recommendation.suggestedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.budget_forecast_suggested_actions),
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                recommendation.suggestedActions.forEach { action ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•",
                            color = SemanticColors.PrimaryIndigo,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = action,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Error,
            contentDescription = null,
            tint = SemanticColors.DangerRed,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = SemanticColors.PrimaryIndigo
            )
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Analytics,
            contentDescription = null,
            tint = SemanticColors.TextSecondary,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.budget_forecast_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary
        )
    }
}

// Helper data class for Quadruple
private data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
