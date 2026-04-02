package com.yourname.expensetracker.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReceiptAssistCard(
    suggestion: ReceiptAssistSuggestion,
    diagnostics: String?,
    onApplyMerchant: () -> Unit,
    onApplyTotal: () -> Unit,
    onApplyDate: () -> Unit,
    onApplyAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ai_receipt_assist_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.ai_receipt_assist_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                diagnostics?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (it.contains("google-ai-studio") && it.contains("gemini-2.5-flash")) {
                        Text(
                            text = stringResource(R.string.ai_receipt_assist_cloud_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            suggestion.merchant?.let { merchant ->
                SuggestionRow(
                    label = stringResource(R.string.label_merchant),
                    value = merchant.value,
                    rationale = merchant.rationale,
                    onApply = onApplyMerchant
                )
            }

            suggestion.total?.let { total ->
                SuggestionRow(
                    label = stringResource(R.string.label_total),
                    value = String.format(Locale.US, "%.2f", total.value),
                    rationale = total.rationale,
                    onApply = onApplyTotal
                )
            }

            suggestion.date?.let { date ->
                val context = LocalContext.current
                val dateFormat = remember { SimpleDateFormat(context.getString(R.string.receipt_date_format), Locale.getDefault()) }
                SuggestionRow(
                    label = stringResource(R.string.label_date),
                    value = dateFormat.format(Date(date.value)),
                    rationale = date.rationale,
                    onApply = onApplyDate
                )
            }

            suggestion.taxAmount?.let { tax ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(stringResource(R.string.ai_receipt_tax_hint_format, tax.value))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            if (suggestion.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.ai_notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    suggestion.notes.forEach { note ->
                        Text(
                            text = stringResource(R.string.ai_note_bullet_format, note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onApplyAll) {
                    Text(stringResource(R.string.ai_receipt_apply_all))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    label: String,
    value: String,
    rationale: String?,
    onApply: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium
                )
                rationale?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onApply) {
                Text(stringResource(R.string.ai_receipt_apply))
            }
        }
    }
}
