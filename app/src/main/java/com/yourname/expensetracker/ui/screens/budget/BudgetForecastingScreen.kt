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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.domain.budget.BudgetRecommendation
import com.yourname.expensetracker.domain.budget.RecommendationPriority
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetForecastingScreen(
    budget: Budget,
    onNavigateBack: () -> Unit,
    viewModel: BudgetForecastingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
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
                        "Budget Forecast",
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshForecast() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Forecast",
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
            ForecastDetailsCard(budget = budget, forecast = forecast)
        }
        
        // Confidence Score Card
        item {
            ConfidenceCard(forecast = forecast)
        }
        
        // Recommendations Section
        if (recommendations.isNotEmpty()) {
            item {
                Text(
                    text = "AI Recommendations",
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            items(recommendations) { recommendation ->
                RecommendationCard(recommendation = recommendation)
            }
        }
    }
}

@Composable
private fun RiskLevelCard(forecast: BudgetForecast) {
    val (riskColor, riskEmoji, riskTitle, riskDescription) = when (forecast.riskLevel) {
        ForecastRiskLevel.LOW -> Quadruple(
            Color(0xFF4CAF50), "✅", "On Track", 
            "You're predicted to stay well under budget"
        )
        ForecastRiskLevel.MEDIUM -> Quadruple(
            Color(0xFFFF9800), "⚠️", "Caution", 
            "You might come close to your budget limit"
        )
        ForecastRiskLevel.HIGH -> Quadruple(
            Color(0xFFF44336), "🔴", "High Risk", 
            "High risk of exceeding your budget"
        )
        ForecastRiskLevel.CRITICAL -> Quadruple(
            Color(0xFFB71C1C), "🚨", "Critical", 
            "Very likely to exceed your budget"
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
                    text = "Overspend Probability: ${(forecast.overspendProbability * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = riskColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForecastDetailsCard(budget: Budget, forecast: BudgetForecast) {
    val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
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
                text = "Budget Forecast",
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Budget Amount
            DetailRow(
                label = "Budget Limit",
                value = currencyFormatter.format(budget.amount),
                icon = Icons.Default.AccountBalanceWallet
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Predicted Spending
            DetailRow(
                label = "Predicted Spending",
                value = currencyFormatter.format(forecast.predictedSpending),
                icon = Icons.Default.TrendingUp,
                valueColor = if (forecast.predictedSpending > budget.amount) 
                    SemanticColors.DangerRed else SemanticColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Predicted Remaining
            DetailRow(
                label = "Predicted Remaining",
                value = currencyFormatter.format(forecast.predictedRemaining),
                icon = if (forecast.predictedRemaining >= 0) 
                    Icons.Default.Savings else Icons.Default.Warning,
                valueColor = when {
                    forecast.predictedRemaining < 0 -> SemanticColors.DangerRed
                    forecast.predictedRemaining < budget.amount * 0.2 -> Color(0xFFFF9800)
                    else -> Color(0xFF4CAF50)
                }
            )
        }
    }
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
                text = "AI Confidence",
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
                    forecast.confidenceScore >= 0.8 -> Color(0xFF4CAF50)
                    forecast.confidenceScore >= 0.6 -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
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
                    forecast.confidenceScore >= 0.8 -> "High Confidence"
                    forecast.confidenceScore >= 0.6 -> "Medium Confidence"
                    else -> "Low Confidence"
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
                    text = "Keep tracking expenses for more accurate predictions",
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
private fun RecommendationCard(recommendation: BudgetRecommendation) {
    val priorityColor = when (recommendation.priority) {
        RecommendationPriority.CRITICAL -> Color(0xFFB71C1C)
        RecommendationPriority.HIGH -> Color(0xFFF44336)
        RecommendationPriority.MEDIUM -> Color(0xFFFF9800)
        RecommendationPriority.LOW -> Color(0xFF4CAF50)
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
                        text = recommendation.title,
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
                
                val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
                
                Text(
                    text = "Potential Savings: ${currencyFormatter.format(savings)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            if (recommendation.suggestedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Suggested Actions:",
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
            Text("Try Again")
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
            text = "No forecast available",
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