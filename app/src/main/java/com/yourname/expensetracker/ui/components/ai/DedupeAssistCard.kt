package com.yourname.expensetracker.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict

@Composable
fun DedupeAssistCard(
    suggestion: DedupeJudgeSuggestion,
    diagnostics: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (suggestion.verdict) {
        DuplicateVerdict.LIKELY_DUPLICATE -> stringResource(R.string.ai_dedupe_verdict_duplicate)
        DuplicateVerdict.LIKELY_DISTINCT -> stringResource(R.string.ai_dedupe_verdict_distinct)
        DuplicateVerdict.UNCERTAIN -> stringResource(R.string.ai_dedupe_verdict_uncertain)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_dedupe_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            suggestion.rationale?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            diagnostics?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss))
                }
                suggestion.confidence?.let {
                    Text(
                        text = stringResource(R.string.ai_dedupe_confidence_format, (it * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}
