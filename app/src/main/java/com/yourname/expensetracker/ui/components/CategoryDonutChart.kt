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
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryBreakdown
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    categories: List<AnalyticsCategoryBreakdown>,
    totalSpent: Double,
    /** S9-004: Required — no default EUR */
    currency: String,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty() || totalSpent <= 0.0) {
        BentoCard(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.chart_no_category_data_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SemanticColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chart_no_category_data_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
        }
        return
    }

    // S9-005: Sanitize percentages — drop NaN/Infinite/negative, normalize if sum > 100
    val sanitizedCategories = categories.filter { it.percentage.isFinite() && it.percentage > 0f }
    val percentageSum = sanitizedCategories.sumOf { it.percentage.toDouble() }.toFloat()
    val normalizedCategories = if (percentageSum > 0f && sanitizedCategories.isNotEmpty()) {
        if (percentageSum > 105f) {
            // Re-normalize relative to sum
            sanitizedCategories.map { it.copy(percentage = (it.percentage / percentageSum) * 100f) }
        } else {
            sanitizedCategories
        }
    } else {
        emptyList()
    }

    if (normalizedCategories.isEmpty()) {
        BentoCard(modifier = modifier) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.chart_no_category_data_title), style = MaterialTheme.typography.titleMedium, color = SemanticColors.TextPrimary)
            }
        }
        return
    }

    // Animate sweep on first composition
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(normalizedCategories) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    val categoryColors = remember(normalizedCategories) {
        normalizedCategories.map { item ->
            try {
                Color(android.graphics.Color.parseColor(item.category.color))
            } catch (_: Exception) {
                Color.Gray
            }
        }
    }
    val categoryColorByKey = remember(normalizedCategories, categoryColors) {
        normalizedCategories
            .mapIndexed { index, item ->
                (item.category.id to item.category.name) to categoryColors[index]
            }
            .toMap()
    }

    val topCategorySummary = remember(normalizedCategories) {
        normalizedCategories
            .sortedByDescending { it.percentage }
            .take(3)
            .joinToString(", ") { "${it.category.name} ${it.percentage.toInt()}%" }
    }
    // S9-004: Use CurrencyFormatter instead of hardcoded euro symbol
    val formattedTotal = remember(totalSpent, currency) {
        CurrencyFormatter.formatMoney(totalSpent, currency, showCents = false)
    }
    val chartSummary = remember(formattedTotal, topCategorySummary) {
        "Category split. Total $formattedTotal. Top categories: $topCategorySummary."
    }
    val legendSummary = remember(normalizedCategories) {
        normalizedCategories
            .take(8)
            .joinToString(", ") { "${it.category.name} ${it.percentage.toInt()}%" }
    }

    BentoCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.chart_category_split_label),
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
                    .align(Alignment.CenterHorizontally)
                    .semantics { contentDescription = chartSummary },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 28.dp.toPx()
                    val padding = strokeWidth / 2
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(padding, padding)

                    var startAngle = -90f // start from top
                    normalizedCategories.forEachIndexed { index, item ->
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
                        text = formattedTotal,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compact legend (2-column grid)
            val legendItems = normalizedCategories.take(8) // cap at 8 for readability
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.semantics {
                    contentDescription = legendSummary
                }
            ) {
                legendItems.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { item ->
                            val color = categoryColorByKey[item.category.id to item.category.name] ?: Color.Gray
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
