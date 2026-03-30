package com.yourname.expensetracker.ui.components.health

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
import com.yourname.expensetracker.domain.health.HealthBreakdown
import com.yourname.expensetracker.domain.health.HealthScoreResult
import com.yourname.expensetracker.domain.health.HealthStatus
import com.yourname.expensetracker.domain.health.PeriodHealthScore
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun HealthScoreWidget(
    healthScore: HealthScoreResult,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val compositeStatus = healthScore.getCompositeStatus()
    val compositeColor = getHealthColor(compositeStatus)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.GlassSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with heart icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HealthHeartIcon(
                    healthStatus = compositeStatus,
                    modifier = Modifier.size(32.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = "Financial Health",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextPrimary
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Three period scores side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PeriodHealthIndicator(
                    label = "TODAY",
                    score = healthScore.today,
                    modifier = Modifier.weight(1f)
                )
                
                PeriodHealthIndicator(
                    label = "WEEK",
                    score = healthScore.week,
                    modifier = Modifier.weight(1f)
                )
                
                PeriodHealthIndicator(
                    label = "MONTH",
                    score = healthScore.month,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Composite score
            CompositeScoreDisplay(
                score = healthScore.composite,
                status = compositeStatus
            )
            
            // Expandable details
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HealthBreakdownDetails(healthScore = healthScore)
            }
            
            // Expand/collapse hint
            Text(
                text = if (isExpanded) "Tap to collapse ↑" else "Tap for breakdown ↓",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun HealthHeartIcon(
    healthStatus: HealthStatus,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    // Pulse animation intensity based on health status
    val pulseIntensity = when (healthStatus) {
        HealthStatus.EXCELLENT -> 0f
        HealthStatus.GOOD -> 0.05f
        HealthStatus.FAIR -> 0.1f
        HealthStatus.WARNING -> 0.15f
        HealthStatus.CRITICAL -> 0.2f
    }
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f + pulseIntensity,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val color = getHealthColor(healthStatus)
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(500),
        label = "color"
    )
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "Health Status: ${healthStatus.name}",
            tint = animatedColor,
            modifier = Modifier
                .size(28.dp * scale)
        )
    }
}

@Composable
private fun PeriodHealthIndicator(
    label: String,
    score: PeriodHealthScore,
    modifier: Modifier = Modifier
) {
    val status = getStatusFromScore(score.score)
    val color = getHealthColor(status)
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(500),
        label = "period_color"
    )
    
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Health bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SemanticColors.SurfaceLight.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(score.score / 100f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(animatedColor)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Score percentage
        Text(
            text = "${score.score}%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = animatedColor
        )
    }
}

@Composable
private fun CompositeScoreDisplay(
    score: Int,
    status: HealthStatus
) {
    val color = getHealthColor(status)
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(500),
        label = "composite_color"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Composite: ",
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary
        )
        
        Text(
            text = "$score%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = animatedColor
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Status indicator
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = animatedColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = status.name.replace("_", " "),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = animatedColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun HealthBreakdownDetails(
    healthScore: HealthScoreResult
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = SemanticColors.TextMuted.copy(alpha = 0.2f)
        )
        
        Text(
            text = "Score Breakdown",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Today breakdown
        PeriodBreakdownSection(
            period = "Today",
            breakdown = healthScore.today.breakdown
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Week breakdown
        PeriodBreakdownSection(
            period = "Week",
            breakdown = healthScore.week.breakdown
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Month breakdown
        PeriodBreakdownSection(
            period = "Month",
            breakdown = healthScore.month.breakdown
        )
    }
}

@Composable
private fun PeriodBreakdownSection(
    period: String,
    breakdown: HealthBreakdown
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SemanticColors.SurfaceLight.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = period,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextPrimary
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        BreakdownRow(label = "Budget Health", value = breakdown.budgetHealth, max = 25)
        BreakdownRow(label = "Spending Control", value = breakdown.spendingControl, max = 25)
        BreakdownRow(label = "Cleanliness", value = breakdown.cleanliness, max = 10)
        
        if (breakdown.bonusPoints > 0) {
            BreakdownRow(
                label = "Bonus Points",
                value = breakdown.bonusPoints,
                max = 15,
                isBonus = true
            )
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: Int,
    max: Int,
    isBonus: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.TextSecondary
        )
        
        val color = when {
            isBonus -> SemanticColors.SuccessGreen
            value >= max * 0.8 -> SemanticColors.SuccessGreen
            value >= max * 0.5 -> SemanticColors.WarningOrange
            else -> SemanticColors.DangerRed
        }
        
        Text(
            text = "$value/$max",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun getHealthColor(status: HealthStatus): Color {
    return when (status) {
        HealthStatus.EXCELLENT -> Color(0xFF4CAF50)  // Green
        HealthStatus.GOOD -> Color(0xFF8BC34A)       // Light Green
        HealthStatus.FAIR -> Color(0xFFFFC107)         // Yellow
        HealthStatus.WARNING -> Color(0xFFFF9800)    // Orange
        HealthStatus.CRITICAL -> Color(0xFFFF5722)   // Red
    }
}

private fun getStatusFromScore(score: Int): HealthStatus {
    return when (score) {
        in 85..100 -> HealthStatus.EXCELLENT
        in 70..84 -> HealthStatus.GOOD
        in 50..69 -> HealthStatus.FAIR
        in 30..49 -> HealthStatus.WARNING
        else -> HealthStatus.CRITICAL
    }
}
