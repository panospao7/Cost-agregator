package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun ForecastTimeline(
 pastPoints: List<Double>,
 projectedPoints: List<Double>,
 budgetLimit: Double,
 modifier: Modifier = Modifier,
 /** Placeholder default. Production callers should pass explicit currency. */
 currency: String = "EUR"
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.forecast_trajectory_title),
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (pastPoints.isEmpty() && projectedPoints.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.forecast_no_data), style = MaterialTheme.typography.labelSmall)
            }
            return
        }

        val hasValidBudget = budgetLimit > 0.0
        val noBudgetSetText = stringResource(R.string.forecast_no_budget_set)

        // Vico model creation - Connect past to projected (no gap)
        val chartEntryModel: ChartEntryModel = remember(pastPoints, projectedPoints, budgetLimit, hasValidBudget) {
            val pastEntries = pastPoints.mapIndexed { index, value -> 
                FloatEntry(index.toFloat(), value.toFloat()) 
            }
            
            // Connect: projected starts from last past point (seamless connection)
            val projectionEntries = if (pastPoints.isNotEmpty() && projectedPoints.isNotEmpty()) {
                val lastPastX = (pastPoints.size - 1).toFloat()
                val lastPastY = pastPoints.last().toFloat()
                
                // Add connector point then projected points
                listOf(FloatEntry(lastPastX, lastPastY)) + 
                projectedPoints.mapIndexed { index, value -> 
                    FloatEntry((lastPastX + 1 + index).toFloat(), value.toFloat()) 
                }
            } else {
                projectedPoints.mapIndexed { index, value -> 
                    FloatEntry((pastPoints.size + index).toFloat(), value.toFloat()) 
                }
            }
            
            if (hasValidBudget) {
                val budgetLimitEntries = listOf(
                    FloatEntry(0f, budgetLimit.toFloat()),
                    FloatEntry(
                        listOfNotNull(
                            pastEntries.maxOfOrNull { it.x },
                            projectionEntries.maxOfOrNull { it.x }
                        ).maxOrNull() ?: 0f,
                        budgetLimit.toFloat()
                    )
                )
                entryModelOf(pastEntries, projectionEntries, budgetLimitEntries)
            } else {
                entryModelOf(pastEntries, projectionEntries)
            }
        }

        val lineSpecs = remember(hasValidBudget) {
            buildList {
                add(
                    LineChart.LineSpec(
                        lineColor = SemanticColors.PrimaryIndigo.toArgb(),
                    )
                )
                add(
                    LineChart.LineSpec(
                        lineColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f).toArgb(),
                    )
                )
                if (hasValidBudget) {
                    add(
                        LineChart.LineSpec(
                            lineColor = SemanticColors.WarningOrange.copy(alpha = 0.5f).toArgb(),
                            lineThicknessDp = 1f
                        )
                    )
                }
            }
        }

        val allPoints = remember(pastPoints, projectedPoints) { pastPoints + projectedPoints }
        val minPoint = allPoints.minOrNull() ?: 0.0
        val maxPoint = allPoints.maxOrNull() ?: 0.0
        val currentPoint = pastPoints.lastOrNull() ?: 0.0
        val projectedEnd = projectedPoints.lastOrNull() ?: currentPoint
        val chartSummary = if (hasValidBudget) {
 stringResource(
 R.string.forecast_timeline_summary_with_budget,
 CurrencyFormatter.format(currentPoint, currency, showCents = false),
 CurrencyFormatter.format(projectedEnd, currency, showCents = false),
 CurrencyFormatter.format(minPoint, currency, showCents = false),
 CurrencyFormatter.format(maxPoint, currency, showCents = false),
 CurrencyFormatter.format(budgetLimit, currency, showCents = false)
 )
 } else {
 stringResource(
 R.string.forecast_timeline_summary_without_budget,
 CurrencyFormatter.format(currentPoint, currency, showCents = false),
 CurrencyFormatter.format(projectedEnd, currency, showCents = false),
 CurrencyFormatter.format(minPoint, currency, showCents = false),
 CurrencyFormatter.format(maxPoint, currency, showCents = false),
                noBudgetSetText
            )
        }

        Chart(
            chart = lineChart(lines = lineSpecs),
            model = chartEntryModel,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = true),
            marker = rememberMarker(),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = chartSummary }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
              LegendItem(stringResource(R.string.chart_legend_actual), SemanticColors.PrimaryIndigo)
              Spacer(modifier = Modifier.width(16.dp))
              LegendItem(stringResource(R.string.chart_legend_projected), SemanticColors.PrimaryIndigo.copy(alpha = 0.3f))
              if (hasValidBudget) {
                  Spacer(modifier = Modifier.width(16.dp))
                  LegendItem(stringResource(R.string.chart_legend_budget_limit), SemanticColors.WarningOrange.copy(alpha = 0.5f))
              }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextSecondary)
    }
}
