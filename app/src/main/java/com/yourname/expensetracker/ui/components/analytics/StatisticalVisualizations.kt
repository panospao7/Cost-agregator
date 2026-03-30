package com.yourname.expensetracker.ui.components.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.analytics.HistogramBin
import com.yourname.expensetracker.domain.analytics.TransactionPercentiles
import com.yourname.expensetracker.ui.components.AmountText
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Displays a percentile grid showing the user's transaction size distribution.
 * Highlights where the user's typical transaction falls in the spectrum.
 */
@Composable
fun PercentileGridCard(
    percentiles: TransactionPercentiles,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Transaction size profile. Most transactions fall between €${String.format("%.0f", percentiles.p25)} and €${String.format("%.0f", percentiles.p75)}" },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Transaction Size Profile",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            // Main percentile row with P50 highlighted
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PercentileColumn("Small", percentiles.p10, isSmall = true)
                PercentileColumn("Low", percentiles.p25, isSecondary = true)
                PercentileColumn("Typical", percentiles.p50, isPrimary = true)
                PercentileColumn("High", percentiles.p75, isSecondary = true)
                PercentileColumn("Large", percentiles.p90, isLarge = true)
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Range indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    SemanticColors.PrimaryIndigo.copy(alpha = 0.3f),
                                    SemanticColors.PrimaryIndigo,
                                    SemanticColors.PrimaryIndigo.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        )
                )
            }
            
            Text(
                text = "Most transactions fall between €${String.format("%.0f", percentiles.p25)} - €${String.format("%.0f", percentiles.p75)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PercentileColumn(
    label: String,
    value: Double,
    isPrimary: Boolean = false,
    isSecondary: Boolean = false,
    isSmall: Boolean = false,
    isLarge: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = if (isPrimary) 
                MaterialTheme.typography.labelMedium 
            else 
                MaterialTheme.typography.labelSmall,
            color = when {
                isPrimary -> SemanticColors.PrimaryIndigo
                isSmall -> MaterialTheme.colorScheme.onSurfaceVariant
                isLarge -> SemanticColors.DangerRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
        
        Text(
            text = "€${String.format("%.0f", value)}",
            style = when {
                isPrimary -> MaterialTheme.typography.titleMedium
                isSecondary -> MaterialTheme.typography.bodyLarge
                else -> MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isPrimary -> MaterialTheme.colorScheme.onSurface
                isSmall -> MaterialTheme.colorScheme.onSurfaceVariant
                isLarge -> SemanticColors.DangerRed
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * Visual histogram showing transaction size distribution across 10 bins.
 */
@Composable
fun TransactionHistogramChart(
    bins: List<HistogramBin>,
    modifier: Modifier = Modifier
) {
    if (bins.isEmpty()) return
    
    val maxCount = bins.maxOfOrNull { it.count } ?: 1
    val totalTransactions = bins.sumOf { it.count }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Transaction distribution histogram showing $totalTransactions total transactions across ${bins.size} size ranges" },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Transaction Distribution",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$totalTransactions total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Histogram bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                bins.forEachIndexed { index, bin ->
                    val heightFraction = if (maxCount > 0) bin.count.toFloat() / maxCount else 0f
                    
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Count label for significant bins
                        if (bin.percentage > 10f) {
                            Text(
                                text = "${bin.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        
                        // Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight(heightFraction.coerceAtLeast(0.05f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (heightFraction > 0.5f) 
                                        SemanticColors.PrimaryIndigo 
                                    else 
                                        SemanticColors.PrimaryIndigo.copy(alpha = 0.5f)
                                )
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // X-axis label (amount range)
                        Text(
                            text = "€${String.format("%.0f", bin.rangeStart)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            // Insight text about peak bin
            val peakBin = bins.maxByOrNull { it.count }
            peakBin?.let {
                Text(
                    text = "Peak: ${it.percentage.toInt()}% of transactions are €${String.format("%.0f", it.rangeStart)}-€${String.format("%.0f", it.rangeEnd)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Badge showing category percentiles and velocity indicator.
 */
@Composable
fun CategoryPercentileBadge(
    percentile25: Double,
    percentile75: Double,
    velocity: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Percentile range
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = "P25: €${String.format("%.0f", percentile25)} · P75: €${String.format("%.0f", percentile75)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Velocity indicator
        val (velocityText, velocityColor) = when {
            velocity > 1.2 -> "Accelerating 🚀" to SemanticColors.WarningOrange
            velocity < 0.8 -> "Slowing 🐢" to MaterialTheme.colorScheme.primary
            else -> "Steady ➡️" to SemanticColors.SuccessGreen
        }
        
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = velocityColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = velocityText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = velocityColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Enhanced merchant card with rich loyalty and consistency data.
 */
@Composable
fun RichMerchantCard(
    merchant: String,
    totalSpent: Double,
    transactionCount: Int,
    averagePerVisit: Double,
    loyaltyScore: Float,
    consecutiveMonthsVisited: Int,
    consistencyRating: String,
    priceChangePercent: Float?,
    predictedNextVisitDate: Long?,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = merchant.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = merchant,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$transactionCount visits · Avg €${String.format("%.2f", averagePerVisit)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                AmountText(
                    amount = totalSpent,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            // Loyalty bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Loyalty",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(60.dp)
                )
                
                LinearProgressIndicator(
                    progress = { loyaltyScore / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        loyaltyScore >= 80f -> SemanticColors.SuccessGreen
                        loyaltyScore >= 50f -> SemanticColors.WarningOrange
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Stars
                Row {
                    repeat(5) { index ->
                        Text(
                            text = if (index < (loyaltyScore / 20).toInt()) "⭐" else "☆",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(4.dp))
                
                Text(
                    text = "${loyaltyScore.toInt()}/100",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Streak
                if (consecutiveMonthsVisited > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🔥",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$consecutiveMonthsVisited months",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "streak",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Consistency
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            consistencyRating.contains("HIGHLY") -> "🟢"
                            consistencyRating.contains("CONSISTENT") -> "🟡"
                            else -> "🔴"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = consistencyRating.replace("_", " ").lowercase()
                            .replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
                
                // Price trend
                priceChangePercent?.let { change ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (change > 0) "📈" else "📉",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (change > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "vs last quarter",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Predicted visit
                predictedNextVisitDate?.let { date ->
                    val daysUntil = ((date - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "📅",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (daysUntil <= 0) "Soon" else "$daysUntil days",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "predicted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
