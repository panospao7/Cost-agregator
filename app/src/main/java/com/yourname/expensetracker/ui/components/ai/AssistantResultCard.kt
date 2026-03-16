package com.yourname.expensetracker.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun AssistantResultCard(
    result: FinancialQueryResult,
    canDrilldown: Boolean,
    onOpenTransactions: () -> Unit,
    onClarificationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = SemanticColors.PrimaryIndigo
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.labelLarge,
                    color = SemanticColors.PrimaryIndigo
                )
            }

            when (result) {
                is FinancialQueryResult.Summary -> {
                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                    Text(result.primaryText, style = MaterialTheme.typography.headlineSmall)
                    result.supportingText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is FinancialQueryResult.Breakdown -> {
                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                    result.rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                row.valueText ?: row.amount?.let { "%.2f EUR".format(it) } ?: (row.count?.toString() ?: "-"),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                is FinancialQueryResult.TransactionList -> {
                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Found ${result.previewCount} matching transactions.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is FinancialQueryResult.Clarification -> {
                    Text(result.prompt, style = MaterialTheme.typography.titleMedium)
                    if (result.options.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(result.options) { option ->
                                SuggestionChip(
                                    onClick = { onClarificationSelected(option) },
                                    label = { Text(option) }
                                )
                            }
                        }
                    }
                }

                is FinancialQueryResult.Unsupported -> {
                    Text("Unsupported", style = MaterialTheme.typography.titleMedium)
                    Text(result.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (canDrilldown) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = onOpenTransactions) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Transactions")
                }
            }
        }
    }
}
