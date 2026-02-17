package com.yourname.expensetracker.ui.components

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
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun ForecastTimeline(
    pastPoints: List<Double>,
    projectedPoints: List<Double>,
    budgetLimit: Double,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "FORECAST TRAJECTORY",
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (pastPoints.isEmpty() && projectedPoints.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("No data available", style = MaterialTheme.typography.labelSmall)
            }
            return
        }

        // Vico model creation - Optimized: wrap in remember to avoid allocation spikes
        val chartEntryModel: ChartEntryModel = remember(pastPoints, projectedPoints, budgetLimit) {
            val pastEntries = pastPoints.mapIndexed { index, value -> 
                FloatEntry(index.toFloat(), value.toFloat()) 
            }
            val projectionEntries = projectedPoints.mapIndexed { index, value -> 
                FloatEntry((pastPoints.size + index).toFloat(), value.toFloat()) 
            }
            val budgetLimitEntries = listOf(
                FloatEntry(0f, budgetLimit.toFloat()),
                FloatEntry((pastPoints.size + projectionEntries.size).toFloat(), budgetLimit.toFloat())
            )
            entryModelOf(pastEntries, projectionEntries, budgetLimitEntries)
        }

        val lineSpecs = remember {
            listOf(
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f).toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.WarningOrange.copy(alpha = 0.5f).toArgb(),
                    lineThicknessDp = 1f
                )
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
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
             LegendItem("Actual", SemanticColors.PrimaryIndigo)
             Spacer(modifier = Modifier.width(16.dp))
             LegendItem("Projected", SemanticColors.PrimaryIndigo.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextSecondary)
    }
}
