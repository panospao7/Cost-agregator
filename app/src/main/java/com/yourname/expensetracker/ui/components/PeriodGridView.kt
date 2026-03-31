package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.model.PeriodTotal
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun PeriodGridView(
    periods: List<PeriodTotal>,
    currentLevel: PeriodLevel,
    selectedPeriod: PeriodTotal?,
    isLoading: Boolean,
    onPeriodSelected: (PeriodTotal) -> Unit,
    modifier: Modifier = Modifier
) {
    val columns = when (currentLevel) {
        PeriodLevel.YEAR -> 4
        PeriodLevel.MONTH -> 4
        PeriodLevel.WEEK -> 5
        PeriodLevel.DAY -> 7
    }

    when {
        isLoading -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = SemanticColors.PrimaryIndigo,
                    strokeWidth = 2.dp
                )
            }
        }
        periods.isEmpty() -> {
            // Empty state - no data yet
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📊",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = stringResource(R.string.totals_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.totals_empty_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted
                    )
                }
            }
        }
        else -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                periods.chunked(columns).forEach { rowPeriods ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowPeriods.forEach { period ->
                            Box(modifier = Modifier.weight(1f)) {
                                PeriodBlock(
                                    period = period,
                                    isSelected = selectedPeriod?.periodKey == period.periodKey,
                                    onClick = { onPeriodSelected(period) }
                                )
                            }
                        }
                        if (rowPeriods.size < columns) {
                            repeat(columns - rowPeriods.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
