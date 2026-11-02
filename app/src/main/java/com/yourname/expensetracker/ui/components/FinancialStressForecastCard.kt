package com.yourname.expensetracker.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRecommendation
import com.yourname.expensetracker.domain.forecasting.StressRecommendationAction
import com.yourname.expensetracker.domain.forecasting.StressRecommendationKind
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/** Maximum number of recommendation rows the card shows (INV-11); domain order decides which. */
internal const val STRESS_RECOMMENDATION_VISIBLE_CAP = 2

/** The recommendations the card actually renders: the first [STRESS_RECOMMENDATION_VISIBLE_CAP], in domain order. */
internal fun visibleStressRecommendations(recommendations: List<StressRecommendation>): List<StressRecommendation> =
    recommendations.take(STRESS_RECOMMENDATION_VISIBLE_CAP)

@Composable
fun FinancialStressForecastCard(
 result: StressForecastResult,
 onActionClick: ((StressRecommendationAction) -> Unit)? = null,
 modifier: Modifier = Modifier,
 currency: String
) {
    val overallRisk = result.overallRiskLevel
    if (overallRisk == null || result.failure != null) {
        Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Stress Forecast", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    androidx.compose.ui.res.stringResource(com.yourname.expensetracker.R.string.stress_forecast_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.TextSecondary
                )
            }
        }
        return
    }
    val (backgroundColor, accentColor, riskIcon) = getRiskColors(overallRisk)
    
    var selectedHorizon by remember(result.horizons) { mutableIntStateOf(0) }
    LaunchedEffect(result.horizons, selectedHorizon) {
        val clampedIndex = if (result.horizons.isEmpty()) {
            0
        } else {
            selectedHorizon.coerceIn(0, result.horizons.lastIndex)
        }

        if (clampedIndex != selectedHorizon) {
            selectedHorizon = clampedIndex
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with overall risk
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stress Forecast",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 1.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(riskIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            overallRisk.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Subtitle
            val subtitle = if (result.earliestCrunchDate != null) {
                val dateStr = formatDate(result.earliestCrunchDate)
                "Potential crunch: $dateStr"
            } else {
                "Cash flow looks healthy"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )

            // HZ01-B: recurring obligations (or other inputs) were excluded because their
            // currency could not be converted — the figures below are incomplete.
            // Gated on excludedCount (not isPartial): isPartial also covers the DBG-06
            // restore-barrier case, which is not a currency-conversion problem.
            if (result.excludedCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(com.yourname.expensetracker.R.string.dashboard_partial_currency_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Horizon tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                result.horizons.forEachIndexed { index, horizon ->
                    val isSelected = selectedHorizon == index
                    val tabColor = if (isSelected) accentColor else SemanticColors.TextMuted
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.2f) else tabColor.copy(alpha = 0.1f),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 48.dp),
                        onClick = { selectedHorizon = index }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${horizon.daysAhead}d",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = tabColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = getRiskEmoji(horizon.riskLevel),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Selected horizon details
            val currentHorizon = result.horizons.getOrNull(selectedHorizon)
            currentHorizon?.let { horizon ->
                HorizonDetailView(horizon, accentColor, currency)
            }

            // Recommendations
            if (result.recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = accentColor.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.stress_recs_header),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    modifier = Modifier.semantics { heading() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                visibleStressRecommendations(result.recommendations).forEach { recommendation ->
                    val text = stressRecommendationText(recommendation)
                    if (text != null) {
                        val action = recommendation.kind.action
                        val clickLabelRes = stressRecommendationClickLabelRes(action)
                        if (clickLabelRes == null) {
                            InformationalRecommendationRow(text = text)
                        } else {
                            RecommendationChip(
                                text = text,
                                onClickLabel = stringResource(clickLabelRes),
                                onClick = { onActionClick?.invoke(action) }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizonDetailView(
    horizon: StressHorizon,
    accentColor: androidx.compose.ui.graphics.Color,
    currency: String
) {
    Column {
        // Balance row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Projected Balance",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = CurrencyFormatter.formatMoney(horizon.projectedBalance, currency, showCents = false),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (horizon.projectedBalance < 0) SemanticColors.DangerRed else SemanticColors.TextPrimary
                )
            }
            
            // Probability badge
            val probColor = when {
                horizon.probabilityOfCrunch < 0.10 -> SemanticColors.SuccessGreen
                horizon.probabilityOfCrunch < 0.25 -> SemanticColors.WarningOrange
                else -> SemanticColors.DangerRed
            }
            
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = probColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = stringResource(
                        R.string.stress_forecast_risk_percent_format,
                        String.format(Locale.getDefault(), "%.0f", horizon.probabilityOfCrunch * 100)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = probColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Min balance warning
        if (horizon.minProjectedBalance < 0) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SemanticColors.DangerRed.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Min projected: ${CurrencyFormatter.formatMoney(horizon.minProjectedBalance, currency, showCents = false)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.DangerRed
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Breakdown row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BreakdownItem(
                label = "Recurring (${horizon.daysAhead}d total)",
                value = "-${CurrencyFormatter.formatMoney(horizon.recurringObligations, currency, showCents = false)}",
                color = SemanticColors.WarningOrange
            )
            BreakdownItem(
                label = "Income",
                value = "+${CurrencyFormatter.formatMoney(horizon.expectedIncome, currency, showCents = false)}",
                color = SemanticColors.SuccessGreen
            )
            BreakdownItem(
                label = "Buffer",
                value = CurrencyFormatter.formatMoney(horizon.discretionaryBuffer, currency, showCents = false),
                color = SemanticColors.PrimaryIndigo
            )
        }
    }
}

@Composable
private fun BreakdownItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Resolves the localized text of a recommendation from its kind and parameters (INV-4/INV-8).
 * Returns null only when a kind's required parameter is missing (defensive; the engine always
 * supplies it), in which case the row is omitted rather than rendered with a guessed value.
 */
@Composable
private fun stressRecommendationText(recommendation: StressRecommendation): String? =
    when (recommendation.kind) {
        StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS ->
            stringResource(R.string.stress_rec_review_recurring_obligations)
        StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING -> {
            val daysAhead = recommendation.daysAhead
            if (daysAhead != null) {
                pluralStringResource(R.plurals.stress_rec_reduce_discretionary_spending, daysAhead, daysAhead)
            } else {
                null
            }
        }
        StressRecommendationKind.BUILD_EMERGENCY_BUFFER -> {
            val bufferTarget = recommendation.bufferTarget
            val bufferCurrency = recommendation.bufferCurrency
            if (bufferTarget != null && !bufferCurrency.isNullOrBlank()) {
                stringResource(
                    R.string.stress_rec_build_emergency_buffer,
                    CurrencyFormatter.formatMoney(bufferTarget, bufferCurrency, showCents = false)
                )
            } else {
                stringResource(R.string.stress_rec_build_emergency_buffer_no_amount)
            }
        }
        StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES ->
            stringResource(R.string.stress_rec_delay_non_essential_purchases)
        StressRecommendationKind.HEALTHY_OUTLOOK ->
            stringResource(R.string.stress_rec_healthy_outlook)
        StressRecommendationKind.NO_IMMEDIATE_CONCERNS ->
            stringResource(R.string.stress_rec_no_immediate_concerns)
    }

/** Click label (TalkBack "double tap to ...") for an actionable row; null for informational ([StressRecommendationAction.None]) rows. */
@StringRes
private fun stressRecommendationClickLabelRes(action: StressRecommendationAction): Int? =
    when (action) {
        StressRecommendationAction.OpenRecurring -> R.string.stress_rec_action_open_recurring
        StressRecommendationAction.OpenTransactionsThisMonth -> R.string.stress_rec_action_open_transactions
        StressRecommendationAction.OpenSavingsGoals -> R.string.stress_rec_action_open_savings_goals
        StressRecommendationAction.None -> null
    }

@Composable
private fun RecommendationChip(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💡",
                fontSize = 14.sp,
                modifier = Modifier.clearAndSetSemantics { }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = SemanticColors.TextSecondary
            )
        }
    }
}

/** Non-interactive recommendation row: neutral tint, no border, chevron, click handler, role or 48dp minimum. */
@Composable
private fun InformationalRecommendationRow(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SemanticColors.TextMuted.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💡",
                fontSize = 14.sp,
                modifier = Modifier.clearAndSetSemantics { }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun getRiskColors(riskLevel: StressRiskLevel): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, String> {
    return when (riskLevel) {
        StressRiskLevel.LOW -> Triple(
            SemanticColors.SuccessGreen.copy(alpha = 0.08f),
            SemanticColors.SuccessGreen,
            "✅"
        )
        StressRiskLevel.MODERATE -> Triple(
            SemanticColors.WarningOrange.copy(alpha = 0.08f),
            SemanticColors.WarningOrange,
            "⚠️"
        )
        StressRiskLevel.ELEVATED -> Triple(
            SemanticColors.WarningOrange.copy(alpha = 0.12f),
            SemanticColors.WarningOrange,
            "⚠️"
        )
        StressRiskLevel.HIGH -> Triple(
            SemanticColors.DangerRed.copy(alpha = 0.12f),
            SemanticColors.DangerRed,
            "🚨"
        )
        StressRiskLevel.CRITICAL -> Triple(
            SemanticColors.DangerRed.copy(alpha = 0.18f),
            SemanticColors.DangerRed,
            "🔴"
        )
    }
}

private fun getRiskEmoji(riskLevel: StressRiskLevel): String {
    return when (riskLevel) {
        StressRiskLevel.LOW -> "🟢"
        StressRiskLevel.MODERATE -> "🟡"
        StressRiskLevel.ELEVATED -> "🟠"
        StressRiskLevel.HIGH -> "🔴"
        StressRiskLevel.CRITICAL -> "⚫"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return sdf.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}
