package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.location.PlaceInsight
import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * A compact card that displays spending data for a single location cluster.
 *
 * Used both on the Map tab (SpendingMapScreen) and the Analytics tab.
 */
@Composable
fun PlaceInsightCard(insight: PlaceInsight, modifier: Modifier = Modifier, homeCurrency: String = "EUR") {

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = insight.placeName, style = MaterialTheme.typography.bodyLarge)
                if (insight.merchantNames.size > 1) {
                    Text(
                        text = insight.merchantNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${insight.transactionCount} transactions · avg ${CurrencyFormatter.format(insight.avgTransaction, homeCurrency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = CurrencyFormatter.format(insight.totalSpend, homeCurrency),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
