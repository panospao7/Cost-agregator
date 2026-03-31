package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun PeriodNavigationBar(
    currentLevel: PeriodLevel,
    onBack: (() -> Unit)?,
    onLevelChanged: (PeriodLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.totals_nav_title),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary
            )

            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.totals_nav_back),
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.PrimaryIndigo
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        FilterChips(
            currentLevel = currentLevel,
            onLevelChanged = onLevelChanged
        )
    }
}

@Composable
private fun FilterChips(
    currentLevel: PeriodLevel,
    onLevelChanged: (PeriodLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Only show levels that are accessible from current level (can go back)
        val accessibleLevels = PeriodLevel.entries.filter { it.ordinal <= currentLevel.ordinal }
        
        accessibleLevels.forEach { level ->
            FilterChip(
                selected = currentLevel == level,
                onClick = { 
                    if (level.ordinal < currentLevel.ordinal) {
                        onLevelChanged(level)
                    }
                },
                label = {
                    Text(
                        text = level.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (currentLevel == level) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SemanticColors.PrimaryIndigo,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                    containerColor = SemanticColors.GlassSurface,
                    labelColor = SemanticColors.TextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = SemanticColors.GlassBorder,
                    selectedBorderColor = SemanticColors.PrimaryIndigo,
                    enabled = true,
                    selected = currentLevel == level
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
