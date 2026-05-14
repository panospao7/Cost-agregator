package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Dashboard card displaying Monte Carlo spending forecast.
 *
 * Shows:
 * - Median projected month-end total (P50)
 * - Percentile spread band (P10–P90)
 * - Probability of staying under budget (if budget is set)
 * - Confidence indicator
 */
@Composable
fun MonteCarloForecastCard(
    result: MonteCarloResult,
    modifier: Modifier = Modifier,
    /** Placeholder default. Production callers should pass explicit currency. */
    currency: String = "EUR"
) {
    val confidenceColor = when (result.confidence.level) {
        ConfidenceLevel.HIGH -> SemanticColors.SuccessGreen
        ConfidenceLevel.MODERATE -> SemanticColors.WarningOrange
        ConfidenceLevel.LOW -> SemanticColors.DangerRed
    }

    val probabilityColor = result.probabilityUnderBudget?.let { prob ->
        when {
            prob >= 0.75 -> SemanticColors.SuccessGreen
            prob >= 0.50 -> SemanticColors.WarningOrange
            else -> SemanticColors.DangerRed
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.10f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // ── Header row ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.forecast_month_end_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.PrimaryLight,
                    letterSpacing = 1.sp
                )
                // Confidence badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = confidenceColor.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(confidenceColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = result.confidence.level.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = confidenceColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Median projected total (P50) ─────────────────────────────
                        Text(
                            text = stringResource(R.string.forecast_likely_total),
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextSecondary
                        )
            Text(
                text = CurrencyFormatter.formatMoney(result.percentile50, currency, showCents = false),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = SemanticColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Percentile spread ────────────────────────────────────────
            Text(
                text = stringResource(R.string.forecast_range_format, result.percentile10, result.percentile90),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Percentile band visual ───────────────────────────────────
            PercentileBand(result)

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))

            // ── Bottom row: budget probability + breakdown ───────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (result.probabilityUnderBudget != null && result.budgetAmount != null) {
                    Column {
                        Text(
                            stringResource(R.string.forecast_under_budget),
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            "${String.format("%.0f", result.probabilityUnderBudget * 100)}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = probabilityColor ?: SemanticColors.TextPrimary
                        )
                        Text(
                            "of ${CurrencyFormatter.formatMoney(result.budgetAmount, currency, showCents = false)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextMuted
                        )
                    }
                } else {
                    Column {
                        Text(
                            stringResource(R.string.forecast_no_budget_set),
                            style = MaterialTheme.typography.labelSmall,
                            color = SemanticColors.TextMuted
                        )
                    }
                }

                // Center: spent so far
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.forecast_spent),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        CurrencyFormatter.formatMoney(result.spentToDate, currency, showCents = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.TextPrimary
                    )
                }

                // Right: upcoming known
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.forecast_upcoming),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        CurrencyFormatter.formatMoney(result.knownUpcoming, currency, showCents = false),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.WarningOrange
                    )
                }
            }

            // ── Confidence explanation ────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.confidence.reason,
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.TextMuted
            )
        }
    }
}

/**
 * Visual percentile band showing P10, P25, P50, P75, P90 as a horizontal bar.
 * Uses BoxWithConstraints to compute marker positions from actual measured width
 * instead of assuming a fixed 300dp width.
 */
@Composable
private fun PercentileBand(result: MonteCarloResult) {
    val range = (result.percentile90 - result.percentile10).coerceAtLeast(1.0)

    Column {
        // Labels row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.forecast_percentile_p10), style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextMuted)
            Text(stringResource(R.string.forecast_percentile_p50), style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryLight)
            Text(stringResource(R.string.forecast_percentile_p90), style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextMuted)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Bar: full width = P10 to P90, using BoxWithConstraints for real width
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.15f))
        ) {
            val barWidthDp = maxWidth

            // P25–P75 inner band (interquartile range)
            val p25Frac = ((result.percentile25 - result.percentile10) / range).toFloat().coerceIn(0f, 1f)
            val p75Frac = ((result.percentile75 - result.percentile10) / range).toFloat().coerceIn(0f, 1f)
            val iqrWidth = (p75Frac - p25Frac).coerceAtLeast(0.02f)
            val markerWidth = 3.dp

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(barWidthDp * iqrWidth)
                    .offset(x = barWidthDp * p25Frac)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.4f))
            )

            // P50 marker (median line)
            val p50Frac = ((result.percentile50 - result.percentile10) / range).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(markerWidth)
                    .offset(x = (barWidthDp * p50Frac - markerWidth / 2).coerceAtLeast(0.dp))
                    .background(SemanticColors.PrimaryLight)
            )
        }

        // Budget line indicator (if applicable)
        if (result.budgetAmount != null && result.budgetAmount > result.percentile10 && result.budgetAmount < result.percentile90) {
            val budgetFrac = ((result.budgetAmount - result.percentile10) / range).toFloat().coerceIn(0f, 1f)

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val containerWidth = maxWidth
                val dotWidth = 6.dp
                Box(
                    modifier = Modifier
                        .offset(x = (containerWidth * budgetFrac - dotWidth / 2).coerceAtLeast(0.dp))
                        .width(dotWidth)
                        .height(4.dp)
                        .background(SemanticColors.DangerRed, RoundedCornerShape(2.dp))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Spacer(modifier = Modifier.weight(budgetFrac.coerceAtLeast(0.01f)))
                Text(
                    stringResource(R.string.forecast_budget_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.DangerRed,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.weight((1f - budgetFrac).coerceAtLeast(0.01f)))
            }
        }
    }
}
