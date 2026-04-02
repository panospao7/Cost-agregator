package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

/**
 * Donut chart visualizing category breakdown as proportional arcs.
 * Uses Compose Canvas — no external library needed.
 *
 * Shows:
 * - Colored arc per category, proportional to spending percentage
 * - Center label with total amount
 * - Compact legend below the chart
 */
@Composable
fun CategoryDonutChart(
    categories: List<CategoryBreakdown>,
    totalSpent: Double,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return

    // Animate sweep on first composition
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(categories) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    val categoryColors = remember(categories) {
        categories.map { item ->
            try {
                Color(android.graphics.Color.parseColor(item.category.color))
            } catch (_: Exception) {
                Color.Gray
            }
        }
    }

    BentoCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "CATEGORY SPLIT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Donut + center text
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 28.dp.toPx()
                    val padding = strokeWidth / 2
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(padding, padding)

                    var startAngle = -90f // start from top
                    categories.forEachIndexed { index, item ->
                        val sweepAngle = (item.percentage / 100f) * 360f * animationProgress.value
                        drawArc(
                            color = categoryColors[index],
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                        startAngle += sweepAngle
                    }
                }

                // Center label
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.chart_total),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = "\u20AC${String.format("%.0f", totalSpent)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compact legend (2-column grid)
            val legendItems = categories.take(8) // cap at 8 for readability
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                legendItems.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { item ->
                            val color = try {
                                Color(android.graphics.Color.parseColor(item.category.color))
                            } catch (_: Exception) {
                                Color.Gray
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${item.category.icon} ${item.category.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SemanticColors.TextSecondary,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${item.percentage.toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SemanticColors.TextPrimary
                                )
                            }
                        }
                        // Fill remaining if odd count
                        if (row.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
