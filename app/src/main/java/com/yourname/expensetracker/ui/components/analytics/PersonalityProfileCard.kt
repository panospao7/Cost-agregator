package com.yourname.expensetracker.ui.components.analytics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityProfile
import com.yourname.expensetracker.domain.analytics.SpendingPersonalityType
import com.yourname.expensetracker.ui.components.BentoCard
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun PersonalityProfileCard(
    profile: SpendingPersonalityProfile,
    modifier: Modifier = Modifier
) {
    val accentColor = personalityColor(profile.personalityType)
    val emoji = personalityEmoji(profile.personalityType)
    val title = personalityTitle(profile.personalityType)
    val confidencePercent = (profile.confidence.coerceIn(0.0, 1.0) * 100).roundToInt()

    val explanationItems = profile.explanation
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    val tipItems = profile.coachingTips
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    BentoCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$title spending personality profile with $confidencePercent percent confidence"
            },
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        containerColor = SemanticColors.GlassSurface
    ) {
        PersonalityHeader(
            title = title,
            emoji = emoji,
            confidencePercent = confidencePercent,
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(14.dp))

        BulletSection(
            sectionTitle = "Why this matches",
            sectionIcon = { Icon(Icons.Default.Info, contentDescription = "Profile explanation section", tint = accentColor) },
            items = explanationItems,
            emptyMessage = "We need more spending history to explain this profile in detail.",
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        BulletSection(
            sectionTitle = "Coaching tips",
            sectionIcon = { Icon(Icons.Default.Lightbulb, contentDescription = "Coaching tips section", tint = accentColor) },
            items = tipItems,
            emptyMessage = "No coaching tips yet. Keep tracking to unlock personalized guidance.",
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        LastUpdatedFooter(timestamp = profile.lastUpdated)
    }
}

@Composable
private fun PersonalityHeader(
    title: String,
    emoji: String,
    confidencePercent: Int,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accentColor.copy(alpha = 0.16f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { contentDescription = "$title emoji" }
                    )
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Personality type icon",
                        tint = accentColor
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextPrimary
            )
        }

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = accentColor.copy(alpha = 0.2f)
        ) {
            Text(
                text = "$confidencePercent% confidence",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Confidence $confidencePercent percent" }
            )
        }
    }
}

@Composable
private fun BulletSection(
    sectionTitle: String,
    sectionIcon: @Composable () -> Unit,
    items: List<String>,
    emptyMessage: String,
    accentColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sectionIcon()
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SemanticColors.TextPrimary
            )
        }

        if (items.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        } else {
            items.forEach { item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Bullet point",
                        tint = accentColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastUpdatedFooter(timestamp: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Last updated ${formatTimestamp(timestamp)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.DateRange,
            contentDescription = "Last updated icon",
            tint = SemanticColors.TextSecondary
        )
        Text(
            text = "Last updated ${formatTimestamp(timestamp)}",
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
        )
    }
}

private fun personalityTitle(type: SpendingPersonalityType): String = when (type) {
    SpendingPersonalityType.PLANNER -> "Planner"
    SpendingPersonalityType.IMPULSE -> "Impulse"
    SpendingPersonalityType.OPTIMIZER -> "Optimizer"
    SpendingPersonalityType.SOCIAL_SPENDER -> "Social Spender"
    SpendingPersonalityType.MINIMALIST -> "Minimalist"
    SpendingPersonalityType.BALANCED -> "Balanced"
}

private fun personalityEmoji(type: SpendingPersonalityType): String = when (type) {
    SpendingPersonalityType.PLANNER -> "📋"
    SpendingPersonalityType.IMPULSE -> "⚡"
    SpendingPersonalityType.OPTIMIZER -> "🔍"
    SpendingPersonalityType.SOCIAL_SPENDER -> "🎉"
    SpendingPersonalityType.MINIMALIST -> "🌿"
    SpendingPersonalityType.BALANCED -> "⚖️"
}

private fun personalityColor(type: SpendingPersonalityType): Color = when (type) {
    SpendingPersonalityType.PLANNER -> Color(0xFF4CAF50)
    SpendingPersonalityType.IMPULSE -> Color(0xFFFF5722)
    SpendingPersonalityType.OPTIMIZER -> Color(0xFF2196F3)
    SpendingPersonalityType.SOCIAL_SPENDER -> Color(0xFF9C27B0)
    SpendingPersonalityType.MINIMALIST -> Color(0xFF607D8B)
    SpendingPersonalityType.BALANCED -> Color(0xFFFF9800)
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "just now"
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault("just now")
}
