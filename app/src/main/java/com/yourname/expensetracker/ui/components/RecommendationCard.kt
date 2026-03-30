package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun RecommendationCard(
    recommendation: DashboardFollowThroughRecommendation,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val priorityLabel = when (recommendation.priority) {
        RecommendationPriority.HIGH -> "High priority"
        RecommendationPriority.MEDIUM -> "Medium priority"
        RecommendationPriority.LOW -> "Low priority"
    }
    
    BentoCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$priorityLabel recommendation: ${recommendation.recommendationText}. Double tap to view details."
            },
        onClick = onClick,
        containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityDot(priority = recommendation.priority)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RECOMMENDATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss, 
                    modifier = Modifier
                        .size(Dimens.TouchTargetMin)
                        .semantics { contentDescription = "Dismiss recommendation" }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = SemanticColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = recommendation.recommendationText,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PriorityDot(priority: RecommendationPriority) {
    val color = when (priority) {
        RecommendationPriority.HIGH -> SemanticColors.DangerRed
        RecommendationPriority.MEDIUM -> SemanticColors.WarningOrange
        RecommendationPriority.LOW -> SemanticColors.SuccessGreen
    }
    Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = color)
    }
}
