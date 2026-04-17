package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FinancialStressForecastCard(
    result: StressForecastResult,
    onActionClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val overallRisk = result.overallRiskLevel
    val (backgroundColor, accentColor, riskIcon) = getRiskColors(overallRisk)
    
    var selectedHorizon by remember(result.horizons) { mutableIntStateOf(0) }
    LaunchedEffect(result.horizons, selectedHorizon) {
        val clampedIndex = if (result.horizons.isEmpty()) {
            0
        } else {
            selectedHorizon.coerceIn(0, result.horizons.lastIndex)
        }

        if (clampedIndex != selectedHorizon) {
            selectedHorizon = clampedIndex
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with overall risk
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stress Forecast",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 1.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(riskIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            overallRisk.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Subtitle
            val subtitle = if (result.earliestCrunchDate != null) {
                val dateStr = formatDate(result.earliestCrunchDate)
                "Potential crunch: $dateStr"
            } else {
                "Cash flow looks healthy"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))
            
            // Horizon tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                result.horizons.forEachIndexed { index, horizon ->
                    val isSelected = selectedHorizon == index
                    val tabColor = if (isSelected) accentColor else SemanticColors.TextMuted
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.2f) else tabColor.copy(alpha = 0.1f),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 48.dp),
                        onClick = { selectedHorizon = index }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${horizon.daysAhead}d",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = tabColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = getRiskEmoji(horizon.riskLevel),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Selected horizon details
            val currentHorizon = result.horizons.getOrNull(selectedHorizon)
            currentHorizon?.let { horizon ->
                HorizonDetailView(horizon, accentColor)
            }

            // Recommendations
            if (result.recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = accentColor.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Recommendations",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                result.recommendations.take(2).forEach { recommendation ->
                    RecommendationChip(
                        text = recommendation,
                        onClick = { onActionClick?.invoke(recommendation) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun HorizonDetailView(
    horizon: StressHorizon,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Column {
        // Balance row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Projected Balance",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = CurrencyFormatter.format(horizon.projectedBalance, showCents = false),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (horizon.projectedBalance < 0) SemanticColors.DangerRed else SemanticColors.TextPrimary
                )
            }
            
            // Probability badge
            val probColor = when {
                horizon.probabilityOfCrunch < 0.10 -> SemanticColors.SuccessGreen
                horizon.probabilityOfCrunch < 0.25 -> SemanticColors.WarningOrange
                else -> SemanticColors.DangerRed
            }
            
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = probColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${String.format("%.0f", horizon.probabilityOfCrunch * 100)}% risk",
                    style = MaterialTheme.typography.labelSmall,
                    color = probColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Min balance warning
        if (horizon.minProjectedBalance < 0) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SemanticColors.DangerRed.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Min projected: ${CurrencyFormatter.format(horizon.minProjectedBalance, showCents = false)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.DangerRed
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Breakdown row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BreakdownItem(
                label = "Recurring",
                value = "-${CurrencyFormatter.format(horizon.recurringObligations, showCents = false)}",
                color = SemanticColors.WarningOrange
            )
            BreakdownItem(
                label = "Income",
                value = "+${CurrencyFormatter.format(horizon.expectedIncome, showCents = false)}",
                color = SemanticColors.SuccessGreen
            )
            BreakdownItem(
                label = "Buffer",
                value = CurrencyFormatter.format(horizon.discretionaryBuffer, showCents = false),
                color = SemanticColors.PrimaryIndigo
            )
        }
    }
}

@Composable
private fun BreakdownItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun RecommendationChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💡",
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun getRiskColors(riskLevel: StressRiskLevel): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, String> {
    return when (riskLevel) {
        StressRiskLevel.LOW -> Triple(
            SemanticColors.SuccessGreen.copy(alpha = 0.08f),
            SemanticColors.SuccessGreen,
            "✅"
        )
        StressRiskLevel.MODERATE -> Triple(
            SemanticColors.WarningOrange.copy(alpha = 0.08f),
            SemanticColors.WarningOrange,
            "⚠️"
        )
        StressRiskLevel.ELEVATED -> Triple(
            SemanticColors.WarningOrange.copy(alpha = 0.12f),
            SemanticColors.WarningOrange,
            "⚠️"
        )
        StressRiskLevel.HIGH -> Triple(
            SemanticColors.DangerRed.copy(alpha = 0.12f),
            SemanticColors.DangerRed,
            "🚨"
        )
        StressRiskLevel.CRITICAL -> Triple(
            SemanticColors.DangerRed.copy(alpha = 0.18f),
            SemanticColors.DangerRed,
            "🔴"
        )
    }
}

private fun getRiskEmoji(riskLevel: StressRiskLevel): String {
    return when (riskLevel) {
        StressRiskLevel.LOW -> "🟢"
        StressRiskLevel.MODERATE -> "🟡"
        StressRiskLevel.ELEVATED -> "🟠"
        StressRiskLevel.HIGH -> "🔴"
        StressRiskLevel.CRITICAL -> "⚫"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
