package com.yourname.expensetracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.util.*
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.EventNote

@Composable
fun FinancialWeatherCard(
    state: WeatherState,
    headline: UiText,
    summary: UiText,
    icon: String,
    totalCommitted: Double,
    totalLikely: Double,
    discretionaryBudget: Double,
    pastSpendingPoints: List<Double> = emptyList(),
    projectedSpendingPoints: List<Double> = emptyList(),
    upcomingItems: List<UpcomingItem> = emptyList(),
    referenceNowMillis: Long,
    details: List<com.yourname.expensetracker.domain.model.NarrativeSection> = emptyList(),
    totalRecurringCount: Int = 0,
    onManageClick: () -> Unit = {},
    onPlanClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val gradient = when (state) {
        WeatherState.CLEAR_SKIES -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.1f),
                Color(0xFF4CAF50).copy(alpha = 0.05f)
            )
        )
        WeatherState.PARTLY_CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF8BC34A).copy(alpha = 0.1f),
                Color(0xFF8BC34A).copy(alpha = 0.05f)
            )
        )
        WeatherState.CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFC107).copy(alpha = 0.1f),
                Color(0xFFFFC107).copy(alpha = 0.05f)
            )
        )
        WeatherState.RAINY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF9800).copy(alpha = 0.12f),
                Color(0xFFFF9800).copy(alpha = 0.06f)
            )
        )
        WeatherState.STORMY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFFF5722).copy(alpha = 0.05f)
            )
        )
        WeatherState.UNKNOWN -> Brush.verticalGradient(
            colors = listOf(
                SemanticColors.GlassSurface,
                SemanticColors.GlassSurface
            )
        )
    }

    val textColor = when (state) {
        WeatherState.CLEAR_SKIES -> SemanticColors.SuccessGreen
        WeatherState.PARTLY_CLOUDY -> Color(0xFF8BC34A)
        WeatherState.CLOUDY -> SemanticColors.WarningOrange
        WeatherState.RAINY -> Color(0xFFFF9800)
        WeatherState.STORMY -> SemanticColors.DangerRed
        WeatherState.UNKNOWN -> SemanticColors.TextSecondary
    }

    BentoCard(
        modifier = modifier.background(gradient, RoundedCornerShape(24.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Weather Icon Circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(textColor.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 28.sp)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                Text(
                    text = stringResource(R.string.financial_weather_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 1.sp
                )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = headline.asString().uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = textColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = summary.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Forecast Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ForecastMetric("COMMITTED", totalCommitted, SemanticColors.TextSecondary)
                ForecastMetric("LIKELY", totalLikely, SemanticColors.TextSecondary)
                ForecastMetric("AVAILABLE", discretionaryBudget, textColor)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Forecast Trajectory Chart (Full Width)
            ForecastTimeline(
                pastPoints = pastSpendingPoints,
                projectedPoints = projectedSpendingPoints,
                budgetLimit = totalCommitted + totalLikely + discretionaryBudget,
                modifier = Modifier.fillMaxWidth()
            )

            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Text(
                        text = if (expanded) "HIDE BREAKDOWN" else "SEE BREAKDOWN",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        details.forEach { section ->
                            DetailSection(section)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SemanticColors.GlassBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Management Section
            if (upcomingItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Text(
                    text = stringResource(R.string.financial_upcoming),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 0.5.sp
                )
                    
                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE ALL", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                upcomingItems.take(3).forEach { item ->
                    UpcomingRow(item = item, referenceNowMillis = referenceNowMillis)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalRecurringCount RECURRING ITEMS TRACKED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    
                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE RECURRING", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ForecastMetric(label: String, amount: Double, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        Text(
            text = CurrencyFormatter.format(amount, showCents = false),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun UpcomingRow(item: UpcomingItem, referenceNowMillis: Long) {
    val daysUntil = TimePeriodUtils.daysBetween(
        TimePeriodUtils.getStartOfDay(referenceNowMillis),
        TimePeriodUtils.getStartOfDay(item.date)
    )
    
    val dateLabel = when {
        daysUntil <= 0 -> "Today"
        daysUntil == 1 -> "Tomorrow"
        else -> DateFormatterUtils.formatTimestampJavaTime(item.date, "EEE, MMM d")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Distinction Icon
            val icon = if (item is UpcomingItem.Recurring) Icons.Default.Repeat else Icons.Default.EventNote
            val badgeText = if (item is UpcomingItem.Recurring) {
                item.pattern.frequency.name.lowercase().capitalize()
            } else {
                "Planned"
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(SemanticColors.GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, 
                    contentDescription = null, 
                    modifier = Modifier.size(16.dp),
                    tint = SemanticColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SemanticColors.TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysUntil <= 1) SemanticColors.WarningOrange else SemanticColors.TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "• $badgeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted
                    )
                }
            }
        }
        
        Text(
            text = CurrencyFormatter.format(item.amount, showCents = false),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextPrimary
        )
    }
}

@Composable
fun DetailSection(section: com.yourname.expensetracker.domain.model.NarrativeSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = section.icon, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = section.title.asString().uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 0.5.sp
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        section.items.forEach { item ->
            Row(
                modifier = Modifier
                    .padding(start = 36.dp, bottom = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = item.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextPrimary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
