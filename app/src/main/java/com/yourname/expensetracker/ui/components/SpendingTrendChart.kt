package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import com.yourname.expensetracker.domain.usecase.dashboard.SpendingTrendSeries
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Renders a multi-series cumulative spending curve.
 * The current month is drawn bold in PrimaryIndigo; historical months are
 * progressively more transparent grays (oldest = most transparent).
 */
@Composable
fun SpendingTrendChart(
 series: List<SpendingTrendSeries>,
 modifier: Modifier = Modifier,
 currency: String = "EUR"
) {
    val currentSeries = series.filter { it.isCurrentMonth }
    val historicalSeries = series.filter { !it.isCurrentMonth }
    val allSeries = historicalSeries + currentSeries   // current month drawn last (on top)

    // Legend label
    val subtitle = when {
        allSeries.size >= 2 -> "${allSeries.first().label} – ${allSeries.last().label}"
        allSeries.size == 1 -> allSeries.first().label
        else -> "No data"
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    stringResource(R.string.chart_trend_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted
                )
            }

            // Mini legend dots
            if (allSeries.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    allSeries.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (s.isCurrentMonth)
                                    SemanticColors.PrimaryIndigo
                                else
                                    SemanticColors.TextMuted.copy(alpha = 0.5f)
                            ) {}
                            Text(
                                s.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = SemanticColors.TextMuted
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (allSeries.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.chart_no_data), style = MaterialTheme.typography.bodySmall, color = SemanticColors.TextMuted)
            }
        } else {
            val historicalCount = historicalSeries.size

            // Build line specs: historical (gray, progressively transparent) then current (indigo, bold)
            val lineSpecs = allSeries.mapIndexed { idx, s ->
                if (s.isCurrentMonth) {
                    lineSpec(lineColor = SemanticColors.PrimaryIndigo)
                } else {
                    // oldest = index 0 → most transparent; newest historical = least transparent
                    val alpha = if (historicalCount <= 1) 0.35f
                    else 0.15f + (idx.toFloat() / (historicalCount - 1)) * 0.25f
                    lineSpec(lineColor = SemanticColors.TextMuted.copy(alpha = alpha))
                }
            }

            val chartEntryModel = remember(allSeries) {
                entryModelOf(
                    *allSeries.map { s ->
                        s.data.mapIndexed { i, v -> entryOf(i, v) }
                    }.toTypedArray()
                )
            }

            val startAxisFormatter = AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ ->
                CurrencyFormatter.formatCompact(value.toDouble(), currency)
            }

            val currentValues = currentSeries.flatMap { it.data }
            val allValues = allSeries.flatMap { it.data }
            val minValue = (allValues.minOrNull() ?: 0.0).toDouble()
            val maxValue = (allValues.maxOrNull() ?: 0.0).toDouble()
            val currentValue = (currentValues.lastOrNull() ?: 0.0).toDouble()
            val chartSummary = remember(subtitle, minValue, maxValue, currentValue) {
                "Spending trend chart for $subtitle. Current value ${CurrencyFormatter.format(currentValue, currency, showCents = false)}, minimum ${CurrencyFormatter.format(minValue, currency, showCents = false)}, maximum ${CurrencyFormatter.format(maxValue, currency, showCents = false)}."
            }

            Chart(
                chart = lineChart(lines = lineSpecs),
                model = chartEntryModel,
                startAxis = rememberStartAxis(
                    valueFormatter = startAxisFormatter,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                bottomAxis = rememberBottomAxis(
                    label = null, tick = null, guideline = null, axis = null
                ),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = true),
                marker = rememberMarker(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = chartSummary }
            )
        }
    }
}
