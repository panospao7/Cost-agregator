package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.ui.theme.SemanticColors

enum class PeriodLevel {
    YEAR, MONTH, WEEK, DAY;

    fun toPeriodType(): PeriodType = when (this) {
        YEAR -> PeriodType.YEAR
        MONTH -> PeriodType.MONTH
        WEEK -> PeriodType.WEEK
        DAY -> PeriodType.DAY
    }

    companion object {
        fun fromPeriodType(type: PeriodType): PeriodLevel = when (type) {
            PeriodType.YEAR -> YEAR
            PeriodType.MONTH -> MONTH
            PeriodType.WEEK -> WEEK
            PeriodType.DAY -> DAY
        }
    }
}

@Composable
fun TotalsDashboardCard(
    periods: List<PeriodTotal>,
    currentLevel: PeriodLevel,
    selectedPeriod: PeriodTotal?,
    isLoading: Boolean,
    onPeriodSelected: (PeriodTotal) -> Unit,
    onLevelChanged: (PeriodLevel) -> Unit,
    onShowCategoryBreakdown: () -> Unit,
    modifier: Modifier = Modifier
) {
    BentoCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.totals_spending_totals),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextSecondary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        PeriodNavigationBar(
            currentLevel = currentLevel,
            onBack = if (currentLevel != PeriodLevel.YEAR) {
                { onLevelChanged(PeriodLevel.entries[currentLevel.ordinal - 1]) }
            } else null,
            onLevelChanged = onLevelChanged
        )

        Spacer(modifier = Modifier.height(16.dp))

        selectedPeriod?.let { period ->
            CurrentPeriodSummary(
                label = period.periodLabel,
                total = period.totalAmount,
                status = period.status
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        PeriodGridView(
            periods = periods,
            currentLevel = currentLevel,
            selectedPeriod = selectedPeriod,
            isLoading = isLoading,
            onPeriodSelected = onPeriodSelected
        )

        // Only show legend and button if we have data
        if (periods.isNotEmpty() && !isLoading) {
            Spacer(modifier = Modifier.height(12.dp))
            PeriodLegend()

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onShowCategoryBreakdown,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.GlassSurface,
                    contentColor = SemanticColors.TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SemanticColors.GlassBorder)
            ) {
                Text(stringResource(R.string.analytics_view_category_breakdown))
            }
        }
    }
}

@Composable
private fun CurrentPeriodSummary(
    label: String,
    total: Double,
    status: PeriodStatus
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val statusColor = when (status) {
        PeriodStatus.UNDER_AVERAGE -> SemanticColors.SuccessGreen
        PeriodStatus.OVER_AVERAGE -> SemanticColors.DangerRed
        PeriodStatus.CURRENT -> SemanticColors.PrimaryIndigo
        PeriodStatus.NO_DATA -> SemanticColors.TextSecondary
    }
    
    val statusText = when (status) {
        PeriodStatus.UNDER_AVERAGE -> stringResource(R.string.totals_status_under_average)
        PeriodStatus.OVER_AVERAGE -> stringResource(R.string.totals_status_over_average)
        PeriodStatus.CURRENT -> stringResource(R.string.totals_status_current)
        PeriodStatus.NO_DATA -> stringResource(R.string.totals_status_no_data)
    }

    Surface(
        color = statusColor.copy(alpha = 0.1f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary
                )
                Text(
                    text = "€${String.format("%.2f", total)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = statusColor
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
        }
    }
}

@Composable
private fun PeriodLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        LegendItem(color = SemanticColors.SuccessGreen, label = stringResource(R.string.totals_legend_under))
        LegendItem(color = SemanticColors.DangerRed, label = stringResource(R.string.totals_legend_over))
        LegendItem(color = SemanticColors.PrimaryIndigo, label = stringResource(R.string.totals_legend_current))
        LegendItem(color = SemanticColors.GlassBorder.copy(alpha = 0.5f), label = stringResource(R.string.totals_legend_no_data))
    }
}

@Composable
private fun LegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(10.dp)
        ) {
            drawRoundRect(
                color = color,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = SemanticColors.TextMuted
        )
    }
}
