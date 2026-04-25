package com.yourname.expensetracker.ui.components.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.usecase.dashboard.*
import com.yourname.expensetracker.ui.components.BentoCard
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.mappers.MonteCarloBudgetImpactUiMapper
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * Money Radar Widget - A unified dashboard card combining due bills,
 * anomaly alerts, and budget risk into a single urgency visualization.
 */
@Composable
fun MoneyRadarWidget(
    data: MoneyRadarData,
    onActionClick: (MoneyRadarAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val budgetImpactMapper = remember(context) { MonteCarloBudgetImpactUiMapper(context) }
    val urgencyColor = when (data.urgencyLevel) {
        UrgencyLevel.GREEN -> SemanticColors.SuccessGreen
        UrgencyLevel.YELLOW -> SemanticColors.WarningOrange
        UrgencyLevel.RED -> SemanticColors.DangerRed
    }
    
    val urgencyLabel = when (data.urgencyLevel) {
        UrgencyLevel.GREEN -> "All Clear"
        UrgencyLevel.YELLOW -> "Attention Needed"
        UrgencyLevel.RED -> "Action Required"
    }
    
    val animatedProgress by animateFloatAsState(
        targetValue = data.urgencyScore / 100f,
        label = "urgency_progress"
    )
    
    val animatedColor by animateColorAsState(
        targetValue = urgencyColor,
        label = "urgency_color"
    )

    val budgetImpactTitle = data.budgetRisk?.let { riskInfo ->
        budgetImpactMapper.map(
            com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact(
                budgetAmount = 0.0,
                p50Forecast = 0.0,
                expectedOverrun = riskInfo.expectedOverrun,
                probabilityOfOverrun = riskInfo.probabilityOfOverrun,
                riskTier = riskInfo.riskTier
            )
        ).title
    }

    val topConcernText = budgetImpactTitle ?: data.topReasons.firstOrNull()?.asString()
    
    val widgetDesc = buildString {
        append("Money Radar: $urgencyLabel. Score ${data.urgencyScore} out of 100. ")
        topConcernText?.let { append("Top concern: $it. ") }
        append("Tap for details.")
    }
    
    BentoCard(
        modifier = modifier.semantics { contentDescription = widgetDesc },
        containerColor = when (data.urgencyLevel) {
            UrgencyLevel.GREEN -> SemanticColors.SuccessGreen.copy(alpha = 0.05f)
            UrgencyLevel.YELLOW -> SemanticColors.WarningOrange.copy(alpha = 0.05f)
            UrgencyLevel.RED -> SemanticColors.DangerRed.copy(alpha = 0.05f)
        },
        border = BorderStroke(
            1.dp,
            when (data.urgencyLevel) {
                UrgencyLevel.GREEN -> SemanticColors.SuccessGreen.copy(alpha = 0.3f)
                UrgencyLevel.YELLOW -> SemanticColors.WarningOrange.copy(alpha = 0.3f)
                UrgencyLevel.RED -> SemanticColors.DangerRed.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with Radar Icon and Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Animated radar dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(animatedColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Money Radar",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }
                
                // Urgency badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = urgencyColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = urgencyLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = urgencyColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        letterSpacing = 0.5.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Urgency Score Visualization
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Circular progress indicator
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = SemanticColors.TextMuted.copy(alpha = 0.2f),
                        strokeWidth = 6.dp,
                        trackColor = Color.Transparent
                    )
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = animatedColor,
                        strokeWidth = 6.dp,
                        trackColor = Color.Transparent
                    )
                    Text(
                        text = "${data.urgencyScore}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = SemanticColors.TextPrimary
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Top reasons list
                Column(modifier = Modifier.weight(1f)) {
                    data.topReasons.forEachIndexed { index, reason ->
                        if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bulletColor = when (index) {
                                0 -> urgencyColor
                                else -> SemanticColors.TextMuted
                            }
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(bulletColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = reason.asString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (index == 0) SemanticColors.TextPrimary else SemanticColors.TextSecondary,
                                fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Summary chips for each category
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Due Bills Chip
                if (data.dueBills.isNotEmpty()) {
                    RadarSummaryChip(
                        icon = Icons.Rounded.Event,
                        count = data.dueBills.size,
                        label = "Bills",
                        isUrgent = data.dueBills.isNotEmpty() && data.urgencyScore > 30,
                        onClick = { onActionClick(MoneyRadarAction.ViewBills(data.dueBills)) }
                    )
                }
                
                // Anomalies Chip
                if (data.anomalyAlerts.isNotEmpty()) {
                    RadarSummaryChip(
                        icon = Icons.Rounded.Warning,
                        count = data.anomalyAlerts.size,
                        label = "Alerts",
                        isUrgent = data.anomalyAlerts.isNotEmpty() && data.urgencyScore > 30,
                        onClick = { onActionClick(MoneyRadarAction.ReviewAnomalies(data.anomalyAlerts)) }
                    )
                }
                
                // Budget Risk Chip
                data.budgetRisk?.let { risk ->
                    if (risk.probabilityOfOverrun >= 0.25) {
                        RadarSummaryChip(
                            icon = Icons.Rounded.TrendingUp,
                            count = null,
                            label = when (risk.riskTier) {
                                RiskTier.CRITICAL -> "Critical"
                                RiskTier.HIGH -> "High Risk"
                                RiskTier.MEDIUM -> "Medium Risk"
                                RiskTier.LOW -> "On Track"
                            },
                            isUrgent = risk.riskTier == RiskTier.CRITICAL || risk.riskTier == RiskTier.HIGH,
                            onClick = { onActionClick(MoneyRadarAction.AdjustBudget(risk)) }
                        )
                    }
                }
            }
            
            // Primary CTA if available
            data.primaryCta?.let { action ->
                Spacer(modifier = Modifier.height(12.dp))
                RadarPrimaryAction(
                    action = action,
                    onClick = { onActionClick(action) }
                )
            }
        }
    }
}

/**
 * Summary chip showing count and label for a category.
 */
@Composable
private fun RadarSummaryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    label: String,
    isUrgent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isUrgent) {
        SemanticColors.WarningOrange.copy(alpha = 0.1f)
    } else {
        SemanticColors.GlassSurface
    }
    
    val contentColor = if (isUrgent) {
        SemanticColors.WarningOrange
    } else {
        SemanticColors.TextSecondary
    }
    
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        onClick = onClick,
        border = if (isUrgent) {
            BorderStroke(1.dp, SemanticColors.WarningOrange.copy(alpha = 0.3f))
        } else null,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            count?.let {
                Text(
                    text = "$it",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

/**
 * Primary action button based on the most urgent item.
 */
@Composable
private fun RadarPrimaryAction(
    action: MoneyRadarAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val budgetImpactMapper = remember(context) { MonteCarloBudgetImpactUiMapper(context) }
    val (icon, text, color) = when (action) {
        is MoneyRadarAction.ViewBills -> Triple(
            Icons.Rounded.Event,
            "View ${action.bills.size} Upcoming Bill${if (action.bills.size > 1) "s" else ""}",
            SemanticColors.PrimaryIndigo
        )
        is MoneyRadarAction.ReviewAnomalies -> Triple(
            Icons.Rounded.Warning,
            "Review ${action.alerts.size} Unusual Charge${if (action.alerts.size > 1) "s" else ""}",
            SemanticColors.WarningOrange
        )
        is MoneyRadarAction.AdjustBudget -> {
            val riskColor = when (action.riskInfo.riskTier) {
                RiskTier.CRITICAL -> SemanticColors.DangerRed
                RiskTier.HIGH -> SemanticColors.WarningOrange
                RiskTier.MEDIUM -> SemanticColors.WarningOrange
                RiskTier.LOW -> SemanticColors.SuccessGreen
            }
            val budgetText = budgetImpactMapper.map(
                com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact(
                    budgetAmount = 0.0,
                    p50Forecast = 0.0,
                    expectedOverrun = action.riskInfo.expectedOverrun,
                    probabilityOfOverrun = action.riskInfo.probabilityOfOverrun,
                    riskTier = action.riskInfo.riskTier
                )
            ).title
            Triple(
                Icons.Rounded.TrendingUp,
                budgetText,
                riskColor
            )
        }
    }
    
    val actionDesc = when (action) {
        is MoneyRadarAction.ViewBills -> "View upcoming bills"
        is MoneyRadarAction.ReviewAnomalies -> "Review unusual charges"
        is MoneyRadarAction.AdjustBudget -> "Review budget risk and adjust"
    }
    
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        onClick = onClick,
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = actionDesc }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

/**
 * Helper to format amount with currency.
 */
private fun formatAmount(amount: Double): String {
    return CurrencyFormatter.format(amount)
}

/**
 * Helper to format date for display.
 */
private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}
