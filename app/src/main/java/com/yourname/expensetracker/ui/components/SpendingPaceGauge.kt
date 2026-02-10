package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun SpendingPaceGauge(
    pace: SpendingPace,
    modifier: Modifier = Modifier
) {
    val paceColor = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> SemanticColors.UnderPace
        PaceStatus.ON_PACE -> SemanticColors.OnPace
        PaceStatus.OVER_PACE -> SemanticColors.OverPace
        PaceStatus.NO_BASELINE -> SemanticColors.NeutralGray
    }

    // Animate the sweep angle
    val targetSweep = (pace.pacePercentage / 200f).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(1000),
        label = "pace_sweep"
    )

    val statusLabel = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "No data yet"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant

            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeWidth = 12.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                // Background arc (240° sweep, centered at bottom)
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground arc
                drawArc(
                    color = paceColor,
                    startAngle = 150f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Center text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pace.pacePercentage.toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = paceColor
                )
                Text(
                    text = "Day ${pace.daysElapsed}/${pace.daysInMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = paceColor,
            fontWeight = FontWeight.Medium
        )

        if (pace.projectedTotal > 0) {
            Text(
                text = "Projected: €${String.format("%.0f", pace.projectedTotal)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
