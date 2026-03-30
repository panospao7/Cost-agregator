package com.yourname.expensetracker.ui.components.analytics

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Gamified widget showing consecutive days without spending.
 * Encourages mindful spending habits through streak tracking.
 */
@Composable
fun NoSpendStreakWidget(
    currentStreak: Int,
    personalBest: Int,
    daysWithoutSpendingThisMonth: Int,
    modifier: Modifier = Modifier
) {
    val progressToBest = if (personalBest > 0) currentStreak.toFloat() / personalBest else 0f
    val isNearBest = currentStreak >= personalBest * 0.8f && currentStreak > 0
    val isNewRecord = currentStreak > 0 && currentStreak >= personalBest
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isNewRecord) 
                SemanticColors.SuccessGreen.copy(alpha = 0.1f) 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with fire emoji
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (currentStreak > 0) "🔥".repeat((currentStreak.coerceAtMost(5))) else "💰",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                if (isNewRecord) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SemanticColors.SuccessGreen
                    ) {
                        Text(
                            text = "NEW RECORD! 🏆",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            
            // Big streak number
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "$currentStreak",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (currentStreak > 0) 
                        SemanticColors.WarningOrange 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (currentStreak == 1) "day" else "days",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            
            Text(
                text = if (currentStreak > 0) 
                    "without spending" 
                else 
                    "Start a no-spend streak today!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            // Progress bar toward personal best
            if (personalBest > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    LinearProgressIndicator(
                        progress = { progressToBest.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when {
                            isNewRecord -> SemanticColors.SuccessGreen
                            isNearBest -> SemanticColors.WarningOrange
                            currentStreak > 0 -> SemanticColors.PrimaryIndigo
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    
                    Text(
                        text = "$personalBest",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = if (isNewRecord) 
                        "You've beaten your personal best!" 
                    else if (isNearBest) 
                        "Almost at your personal best! 💪" 
                    else 
                        "Personal best: $personalBest days",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNearBest) SemanticColors.WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontWeight = if (isNearBest) FontWeight.Medium else FontWeight.Normal
                )
            }
            
            // Monthly stats
            Spacer(modifier = Modifier.height(8.dp))
            
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$daysWithoutSpendingThisMonth",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "dry days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (currentStreak > 0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${(currentStreak * 100 / 30)}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "of month saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    if (personalBest > 0) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$personalBest",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = SemanticColors.PrimaryIndigo
                            )
                            Text(
                                text = "best streak",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Motivational message
            if (currentStreak > 0) {
                val motivationalMessages = listOf(
                    "Keep it up! 🌟",
                    "You're crushing it! 💪",
                    "Great discipline! 🎯",
                    "Savings momentum! 📈",
                    "Mindful spending! 🧘"
                )
                
                Text(
                    text = motivationalMessages[currentStreak % motivationalMessages.size],
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.SuccessGreen,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Compact version of the streak widget for dashboard use.
 */
@Composable
fun NoSpendStreakWidgetCompact(
    currentStreak: Int,
    personalBest: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (currentStreak > 0) "🔥" else "💰",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Column {
                    Text(
                        text = if (currentStreak > 0) 
                            "$currentStreak day${if (currentStreak == 1) "" else "s"} no spend" 
                        else 
                            "Start a streak!",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (currentStreak > 0 && personalBest > 0) {
                        Text(
                            text = if (currentStreak >= personalBest) 
                                "New record! 🏆" 
                            else 
                                "Best: $personalBest days",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (currentStreak >= personalBest) 
                                SemanticColors.SuccessGreen 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (currentStreak > 0 && personalBest > 0) {
                val progress = currentStreak.toFloat() / personalBest
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(36.dp),
                    color = if (currentStreak >= personalBest) 
                        SemanticColors.SuccessGreen 
                    else 
                        SemanticColors.WarningOrange,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
