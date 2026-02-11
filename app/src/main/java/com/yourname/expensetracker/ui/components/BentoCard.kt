package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Atomic BentoCard — the building block for the Bento Grid layout.
 * Features: Glassmorphism (semi-transparency + hairline border).
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SemanticColors.GlassSurface,
    cornerRadius: Dp = 24.dp, // Modern, rounder look
    contentPadding: PaddingValues = PaddingValues(16.dp),
    border: BorderStroke = BorderStroke(1.dp, SemanticColors.GlassBorder),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

/**
 * Hero BentoCard — larger, primary-colored gradient, for the main metric.
 */
@Composable
fun HeroBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Gradient for a more vibrant Hero card
    val heroGradient = Brush.linearGradient(
        colors = listOf(
            SemanticColors.PrimaryIndigo.copy(alpha = 0.4f),
            SemanticColors.PrimaryLight.copy(alpha = 0.2f)
        )
    )

    BentoCard(
        modifier = modifier,
        containerColor = Color.Transparent, // Overridden by custom modifier or nested content if needed
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(24.dp),
        border = BorderStroke(1.dp, SemanticColors.PrimaryLight.copy(alpha = 0.2f))
    ) {
        // We use a Surface/Box inside if we want a complex gradient background, 
        // but for now, the BentoCard's containerColor is our base.
        // Let's refine the BentoCard to support custom backgrounds better or just use containerColor.
        content()
    }
}

/**
 * Compact stat label used inside BentoCards.
 */
@Composable
fun StatLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SemanticColors.TextPrimary
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            color = valueColor
        )
    }
}

/**
 * Amount text with tabular figures and premium weights.
 */
@Composable
fun AmountText(
    amount: Double,
    currency: String = "€",
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = SemanticColors.TextPrimary
) {
    Text(
        text = "$currency${String.format("%.2f", amount)}",
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.ExtraBold, // More premium weight
        color = color,
        modifier = modifier
    )
}
