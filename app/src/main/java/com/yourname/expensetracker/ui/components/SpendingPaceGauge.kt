package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yourname.expensetracker.R

@Composable
fun SpendingPaceGauge(
    pace: SpendingPace,
    modifier: Modifier = Modifier
) {
    val paceColor = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> SemanticColors.SuccessGreen
        PaceStatus.ON_PACE -> SemanticColors.PrimaryIndigo
        PaceStatus.OVER_PACE -> SemanticColors.WarningOrange
        PaceStatus.NO_BASELINE -> SemanticColors.TextMuted
    }

    // Animate the sweep angle (240 degree range)
    val maxPacePercent = 200f
    val targetSweep = (pace.pacePercentage / maxPacePercent).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(800), // More responsive
        label = "pace_sweep_${pace.paceStatus}"
    )

    val statusLabel = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "Calculating..."
    }

    val gaugeSummary = remember(pace.pacePercentage, pace.daysElapsed, pace.daysInMonth, statusLabel) {
        "Spending pace ${pace.pacePercentage.toInt()} percent, $statusLabel, day ${pace.daysElapsed} of ${pace.daysInMonth}."
    }

    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = gaugeSummary
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(130.dp), // Slightly larger
            contentAlignment = Alignment.Center
        ) {
            val trackColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)

            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val strokeWidth = 10.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                // Background arc
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground arc (Current Pace)
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

            // Center metric
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pace.pacePercentage.toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = SemanticColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.pace_day_format, pace.daysElapsed, pace.daysInMonth),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            color = paceColor.copy(alpha = 0.15f),
            shape = CircleShape
        ) {
            Text(
                text = statusLabel.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = paceColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}
