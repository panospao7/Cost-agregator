package com.yourname.expensetracker.ui.components.health

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.HealthFactorContribution
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Modern Financial Health Score V2 Widget.
 * Displays the comprehensive 0-100 health score with the four component breakdowns:
 * - Savings Rate (30%)
 * - Financial Runway (25%)
 * - Budget Adherence (25%)
 * - Bill Reliability (20%)
 */
@Composable
fun FinancialHealthScoreV2Widget(
    healthScore: FinancialHealthResult,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val overallColor = getHealthColor(healthScore.overallScore)
    val animatedColor by animateColorAsState(
        targetValue = overallColor,
        animationSpec = tween(500),
        label = "health_color"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.GlassSurface
        ),
        border = BorderStroke(1.dp, animatedColor.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with score
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon with health indicator
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = animatedColor.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (healthScore.overallScore >= 80) 
                                Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Health Score",
                            tint = animatedColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Financial Health",
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = getHealthStatusText(healthScore.overallScore),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = animatedColor
                    )
                }
                
                // Overall Score Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = animatedColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${healthScore.overallScore}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = animatedColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Trend indicator
            TrendIndicator(trend = healthScore.trend)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Four component mini bars
            ComponentBars(contributions = healthScore.factorContributions)
            
            // Expandable details
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = SemanticColors.GlassBorder)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Detailed breakdown
                DetailedBreakdown(contributions = healthScore.factorContributions)
                
                // Recommendation
                healthScore.recommendation?.let { rec ->
                    Spacer(modifier = Modifier.height(12.dp))
                    RecommendationCard(recommendation = rec)
                }
            }
            
            // Expand hint
            Text(
                text = if (isExpanded) "Tap to collapse" else "Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun TrendIndicator(trend: HealthTrend) {
    val (icon, color, text) = when (trend) {
        HealthTrend.IMPROVING -> Triple(
            Icons.Default.TrendingUp,
            SemanticColors.SuccessGreen,
            "Improving"
        )
        HealthTrend.STABLE -> Triple(
            Icons.Default.TrendingFlat,
            SemanticColors.WarningOrange,
            "Stable"
        )
        HealthTrend.DECLINING -> Triple(
            Icons.Default.TrendingDown,
            SemanticColors.DangerRed,
            "Needs Attention"
        )
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ComponentBars(contributions: List<HealthFactorContribution>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        contributions.forEach { contribution ->
            ComponentMiniBar(contribution = contribution)
        }
    }
}

@Composable
private fun ComponentMiniBar(contribution: HealthFactorContribution) {
    val color = getHealthColor(contribution.score)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Icon based on component type
        val icon = when (contribution.name) {
            "Savings Rate" -> Icons.Default.Savings
            "Financial Runway" -> Icons.Default.Timelapse
            "Budget Adherence" -> Icons.Default.AccountBalance
            "Bill Reliability" -> Icons.Default.Payment
            else -> Icons.Default.Star
        }
        
        Icon(
            imageVector = icon,
            contentDescription = contribution.name,
            tint = SemanticColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Label
        Text(
            text = contribution.name,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary,
            modifier = Modifier.width(100.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Progress bar
        LinearProgressIndicator(
            progress = { contribution.score / 100f },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = SemanticColors.SurfaceLight
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Score
        Text(
            text = "${contribution.score}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun DetailedBreakdown(contributions: List<HealthFactorContribution>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        contributions.forEach { contribution ->
            ComponentDetailCard(contribution = contribution)
        }
    }
}

@Composable
private fun ComponentDetailCard(contribution: HealthFactorContribution) {
    val color = getHealthColor(contribution.score)
    
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SemanticColors.SurfaceLight.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contribution.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "(${(contribution.weight * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted
                    )
                }
                Text(
                    text = contribution.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            
            Text(
                text = "${contribution.score}/100",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = "Tip",
                tint = SemanticColors.PrimaryIndigo,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = recommendation,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextPrimary
            )
        }
    }
}

private fun getHealthColor(score: Int): Color {
    return when (score) {
        in 85..100 -> SemanticColors.SuccessGreen
        in 70..84 -> Color(0xFF7CB342)  // Light Green
        in 50..69 -> SemanticColors.WarningOrange
        in 30..49 -> Color(0xFFFF7043)  // Deep Orange
        else -> SemanticColors.DangerRed
    }
}

private fun getHealthStatusText(score: Int): String {
    return when (score) {
        in 85..100 -> "Excellent"
        in 70..84 -> "Good"
        in 50..69 -> "Fair"
        in 30..49 -> "Needs Work"
        else -> "Critical"
    }
}
