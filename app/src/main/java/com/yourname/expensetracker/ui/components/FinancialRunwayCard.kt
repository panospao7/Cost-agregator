package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun FinancialRunwayCard(
    daysRemaining: Int,
    discretionaryRemaining: Double,
    averageDailyDiscretionarySpend: Double,
    monthlyIncome: Double,
    committedExpenses: Double,
    likelyExpenses: Double,
    status: DashboardWidget.RunwayStatus,
    modifier: Modifier = Modifier
) {
    // Guard negative days
    val safeDays = daysRemaining.coerceAtLeast(0)
    val isExhausted = daysRemaining <= 0 && status != DashboardWidget.RunwayStatus.NO_INCOME

    val (backgroundColor, accentColor) = when (status) {
        DashboardWidget.RunwayStatus.HEALTHY -> SemanticColors.SuccessGreen.copy(alpha = 0.15f) to SemanticColors.SuccessGreen
        DashboardWidget.RunwayStatus.CAUTION -> SemanticColors.WarningOrange.copy(alpha = 0.15f) to SemanticColors.WarningOrange
        DashboardWidget.RunwayStatus.CRITICAL -> SemanticColors.DangerRed.copy(alpha = 0.15f) to SemanticColors.DangerRed
        DashboardWidget.RunwayStatus.NO_INCOME -> SemanticColors.PrimaryIndigo.copy(alpha = 0.15f) to SemanticColors.PrimaryIndigo
    }

    val statusIcon = when (status) {
        DashboardWidget.RunwayStatus.HEALTHY -> "🟢"
        DashboardWidget.RunwayStatus.CAUTION -> "🟡"
        DashboardWidget.RunwayStatus.CRITICAL -> "🔴"
        DashboardWidget.RunwayStatus.NO_INCOME -> "⚪"
    }

    val statusText = when (status) {
        DashboardWidget.RunwayStatus.HEALTHY -> "Healthy"
        DashboardWidget.RunwayStatus.CAUTION -> "Caution"
        DashboardWidget.RunwayStatus.CRITICAL -> "Critical"
        DashboardWidget.RunwayStatus.NO_INCOME -> "No Income Data"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FINANCIAL RUNWAY",
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
                        Text(statusIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "$safeDays",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "days",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = SemanticColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Text(
                text = if (isExhausted) "Budget exhausted — discretionary funds depleted"
                       else "of discretionary spending remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isExhausted) SemanticColors.DangerRed else SemanticColors.TextSecondary
            )

            // Runway progress bar
            Spacer(modifier = Modifier.height(8.dp))
            val progress = (safeDays / 30f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.15f)
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = accentColor.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Discretionary",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        "€${String.format("%.0f", discretionaryRemaining)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.TextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Daily Rate",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        "€${String.format("%.1f", averageDailyDiscretionarySpend)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.TextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Income",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        "€${String.format("%.0f", monthlyIncome)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.SuccessGreen
                    )
                }
            }

            if (committedExpenses > 0 || likelyExpenses > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (committedExpenses > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SemanticColors.WarningOrange.copy(alpha = 0.1f)
                        ) {
                            Text(
                                "€${String.format("%.0f", committedExpenses)} committed",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.WarningOrange,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (likelyExpenses > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f)
                        ) {
                            Text(
                                "€${String.format("%.0f", likelyExpenses)} planned",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
