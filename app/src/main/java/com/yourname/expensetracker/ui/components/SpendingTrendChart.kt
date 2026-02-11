package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun SpendingTrendChart(
    currentMonthData: List<Float>,
    previousMonthData: List<Float>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "TREND", 
                    style = MaterialTheme.typography.labelSmall, 
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    "This month vs Last", 
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (currentMonthData.isEmpty() && previousMonthData.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No data", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val chartEntryModel = remember(currentMonthData, previousMonthData) {
                entryModelOf(
                    currentMonthData.mapIndexed { index, value -> entryOf(index, value) }, 
                    previousMonthData.mapIndexed { index, value -> entryOf(index, value) }
                )
            }

            Chart(
                chart = lineChart(
                    lines = listOf(
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.PrimaryIndigo),
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.TextMuted.copy(alpha = 0.5f))
                    )
                ),
                model = chartEntryModel,
                startAxis = rememberStartAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                bottomAxis = rememberBottomAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        }
    }
}
