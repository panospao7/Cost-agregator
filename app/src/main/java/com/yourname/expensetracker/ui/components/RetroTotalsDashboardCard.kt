package com.yourname.expensetracker.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * Retro Arcade Stage Select style Totals Dashboard.
 * 
 * Features:
 * - Stage cards grid (like arcade level select)
 * - Medal system: 🥇 Gold, 🥈 Silver, 🥉 Bronze, 👾 Invader
 * - 3D beveled stage cards with neon glow
 * - CRT flicker and scanline effects
 * - Drill-down navigation with "ENTER STAGE" flow
 */
@Composable
fun RetroTotalsDashboardCard(
    periods: List<PeriodTotal>,
    currentLevel: PeriodLevel,
    selectedPeriod: PeriodTotal?,
    isLoading: Boolean,
    averageAmount: Double,
    onPeriodSelected: (PeriodTotal) -> Unit,
    onLevelChanged: (PeriodLevel) -> Unit,
    onEnterStage: (PeriodTotal) -> Unit,
    onViewAnalysis: (PeriodTotal) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStageDialog by remember { mutableStateOf<PeriodTotal?>(null) }
    
    showStageDialog?.let { period ->
        RetroStageDialog(
            period = period,
            currentLevel = currentLevel,
            averageAmount = averageAmount,
            onDismiss = { showStageDialog = null },
            onEnterStage = {
                showStageDialog = null
                onEnterStage(period)
            },
            onViewAnalysis = {
                showStageDialog = null
                onViewAnalysis(period)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        RetroGameCardV2Totals(
            modifier = Modifier.fillMaxWidth(),
            borderColor = RetroColorsTotals.NeonOrange,
            backgroundColor = RetroColorsTotals.DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Arcade header
                RetroTotalsHeader(
                    currentLevel = currentLevel,
                    yearLabel = periods.firstOrNull()?.let { 
                        it.periodLabel.split(" ").lastOrNull() ?: "2024"
                    } ?: "2024"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Navigation bar (hub level indicator)
                RetroLevelNavigationBar(
                    currentLevel = currentLevel,
                    onBack = if (currentLevel != PeriodLevel.YEAR) {
                        { onLevelChanged(PeriodLevel.entries[currentLevel.ordinal - 1]) }
                    } else null
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Loading or Stage Grid
                if (isLoading) {
                    RetroLoadingIndicator()
                } else {
                    // Stage Select Grid - tapping drills down immediately (except for days which are leaf nodes)
                    RetroStageGrid(
                        periods = periods,
                        selectedPeriod = selectedPeriod,
                        averageAmount = averageAmount,
                        currentLevel = currentLevel,
                        onStageSelected = { period ->
                            if (currentLevel != PeriodLevel.DAY) {
                                // Drill down for year/month/week levels
                                onEnterStage(period)
                            }
                            // Days don't drill - they're leaf nodes
                        },
                        onStageLongPress = { period ->
                            // Show dialog on long press for all levels including days
                            showStageDialog = period
                        }
                    )
                }
                
                // Selected stage indicator
                selectedPeriod?.let { period ->
                    Spacer(modifier = Modifier.height(12.dp))
                    RetroSelectedStageIndicator(
                        period = period,
                        averageAmount = averageAmount
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Legend
                RetroTotalsLegend()
            }
        }
        
        EnhancedScanlineOverlayTotals()
    }
}

@Composable
private fun RetroTotalsHeader(currentLevel: PeriodLevel, yearLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Pulsing star icon
        val infiniteTransition = rememberInfiniteTransition(label = "star_pulse")
        val iconGlow by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "star_pulse"
        )
        
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(RetroColorsTotals.NeonYellow.copy(alpha = 0.2f * iconGlow)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.retro_star_icon),
                color = RetroColorsTotals.NeonYellow.copy(alpha = iconGlow),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.width(10.dp))
        
        // Title with CRT flicker
        val flickerAlpha by rememberInfiniteTransition(label = "flicker").animateFloat(
            initialValue = 0.95f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(80, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "flicker"
        )
        
        Text(
            text = stringResource(R.string.retro_score_attack_title),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            ),
            color = RetroColorsTotals.NeonWhite.copy(alpha = flickerAlpha),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 15.sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Year badge
        Box(
            modifier = Modifier
                .background(RetroColorsTotals.DarkSurface)
                .border(1.5.dp, RetroColorsTotals.NeonOrange)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.retro_year_label_format, yearLabel),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = RetroColorsTotals.NeonOrange,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun RetroLevelNavigationBar(
    currentLevel: PeriodLevel,
    onBack: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Back button
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .background(RetroColorsTotals.DarkSurface)
                    .border(1.dp, RetroColorsTotals.NeonCyan)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.retro_return_button),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = RetroColorsTotals.NeonCyan,
                    fontSize = 9.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        
        // Level path indicator
        val levels = PeriodLevel.entries
        val currentIndex = levels.indexOf(currentLevel)
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            levels.forEachIndexed { index, level ->
                val isActive = index == currentIndex
                val isPast = index < currentIndex
                
                Box(
                    modifier = Modifier
                        .background(
                            when {
                                isActive -> RetroColorsTotals.NeonOrange.copy(alpha = 0.3f)
                                isPast -> RetroColorsTotals.NeonGreen.copy(alpha = 0.15f)
                                else -> RetroColorsTotals.DarkBorder
                            }
                        )
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = when {
                                isActive -> RetroColorsTotals.NeonOrange
                                isPast -> RetroColorsTotals.NeonGreen.copy(alpha = 0.5f)
                                else -> RetroColorsTotals.DarkBorder
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = level.name.take(1),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = when {
                            isActive -> RetroColorsTotals.NeonOrange
                            isPast -> RetroColorsTotals.NeonGreen.copy(alpha = 0.7f)
                            else -> RetroColorsTotals.NeonWhite.copy(alpha = 0.4f)
                        },
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroStageGrid(
    periods: List<PeriodTotal>,
    selectedPeriod: PeriodTotal?,
    averageAmount: Double,
    currentLevel: PeriodLevel,
    onStageSelected: (PeriodTotal) -> Unit,
    onStageLongPress: (PeriodTotal) -> Unit
) {
    val columns = when {
        periods.size <= 4 -> 2
        periods.size <= 6 -> 3
        else -> 4
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        periods.chunked(columns).forEach { rowPeriods ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowPeriods.forEach { period ->
                    val isSelected = period.periodKey == selectedPeriod?.periodKey
                    RetroStageCard(
                        period = period,
                        isSelected = isSelected,
                        averageAmount = averageAmount,
                        onClick = { onStageSelected(period) },
                        onLongClick = { onStageLongPress(period) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill remaining space in last row
                if (rowPeriods.size < columns) {
                    repeat(columns - rowPeriods.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RetroStageCard(
    period: PeriodTotal,
    isSelected: Boolean,
    averageAmount: Double,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Calculate medal based on spending vs average
    val percentageOfAvg = if (averageAmount > 0) (period.totalAmount / averageAmount) * 100 else 100.0
    val medal = when {
        percentageOfAvg < 80 -> "🥇"  // Gold - under budget
        percentageOfAvg < 120 -> "🥈" // Silver - on target
        percentageOfAvg < 150 -> "🥉" // Bronze - over budget
        else -> "👾"                   // Invader - danger!
    }
    
    val medalColor = when {
        percentageOfAvg < 80 -> RetroColorsTotals.Gold
        percentageOfAvg < 120 -> RetroColorsTotals.Silver
        percentageOfAvg < 150 -> RetroColorsTotals.Bronze
        else -> RetroColorsTotals.NeonRed
    }
    
    // Stage number (1-12 for months, etc.)
    val stageNumber = period.periodLabel.split(" ").firstOrNull()?.take(3)?.uppercase() ?: "???"
    
    // Selection glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "select_pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isSelected) 1f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )
    
    val borderColor = if (isSelected) RetroColorsTotals.NeonOrange.copy(alpha = glowAlpha) else RetroColorsTotals.DarkBorder
    val backgroundAlpha = if (isSelected) 0.15f else 0.05f
    
    Box(
        modifier = modifier
            .aspectRatio(0.85f)
            .background(RetroColorsTotals.DarkSurface.copy(alpha = backgroundAlpha))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .drawBehind {
                val width = size.width
                val height = size.height
                
                // 3D bevel effect
                if (!isSelected) {
                    // Top highlight
                    drawRect(
                        color = Color.White.copy(alpha = 0.2f),
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(width, 2f)
                    )
                    // Bottom shadow
                    drawRect(
                        color = Color.Black.copy(alpha = 0.3f),
                        topLeft = Offset(0f, height - 2f),
                        size = androidx.compose.ui.geometry.Size(width, 2f)
                    )
                }
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxSize()
        ) {
            // Stage label
            Text(
                text = stageNumber,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isSelected) RetroColorsTotals.NeonOrange else RetroColorsTotals.NeonWhite.copy(alpha = 0.8f),
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
            
            // Amount (score)
            Text(
                text = CurrencyFormatter.format(period.totalAmount, showCents = false),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = RetroColorsTotals.NeonWhite,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            
            // Medal
            Text(
                text = medal,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun RetroSelectedStageIndicator(
    period: PeriodTotal,
    averageAmount: Double
) {
    val percentageOfAvg = if (averageAmount > 0) (period.totalAmount / averageAmount) * 100 else 100.0
    val rankText = when {
        percentageOfAvg < 80 -> "GOLD RANK"
        percentageOfAvg < 120 -> "SILVER RANK"
        percentageOfAvg < 150 -> "BRONZE RANK"
        else -> "BOSS LEVEL"
    }
    val rankColor = when {
        percentageOfAvg < 80 -> RetroColorsTotals.Gold
        percentageOfAvg < 120 -> RetroColorsTotals.Silver
        percentageOfAvg < 150 -> RetroColorsTotals.Bronze
        else -> RetroColorsTotals.NeonRed
    }
    
    val infiniteTransition = rememberInfiniteTransition(label = "indicator_pulse")
    val indicatorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicator_pulse"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(RetroColorsTotals.DarkSurface)
            .border(1.5.dp, RetroColorsTotals.NeonOrange.copy(alpha = indicatorAlpha))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.retro_stage_label_format, period.periodLabel.uppercase()),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = RetroColorsTotals.NeonOrange,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = stringResource(R.string.retro_score_label_format, period.totalAmount),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = RetroColorsTotals.NeonWhite,
                    fontSize = 13.sp
                )
            }
            
            Box(
                modifier = Modifier
                    .background(RetroColorsTotals.DarkBackground)
                    .border(1.5.dp, rankColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = rankText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = rankColor,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun RetroTotalsLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        RetroLegendItemTotals("🥇", "<80%", RetroColorsTotals.Gold)
        RetroLegendItemTotals("🥈", "80-120%", RetroColorsTotals.Silver)
        RetroLegendItemTotals("🥉", "120-150%", RetroColorsTotals.Bronze)
        RetroLegendItemTotals("👾", ">150%", RetroColorsTotals.NeonRed)
    }
}

@Composable
private fun RetroLegendItemTotals(icon: String, label: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 12.sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = color,
            fontSize = 8.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroStageDialog(
    period: PeriodTotal,
    currentLevel: PeriodLevel,
    averageAmount: Double,
    onDismiss: () -> Unit,
    onEnterStage: () -> Unit,
    onViewAnalysis: () -> Unit
) {
    val percentageOfAvg = if (averageAmount > 0) (period.totalAmount / averageAmount) * 100 else 100.0
    val percentage = period.totalAmount / if (averageAmount > 0) averageAmount else 1.0
    val filledBlocks = (percentage.coerceIn(0.0, 2.0) * 10).toInt()
    
    val rankText = when {
        percentageOfAvg < 80 -> "GOLD"
        percentageOfAvg < 120 -> "SILVER"
        percentageOfAvg < 150 -> "BRONZE"
        else -> "BOSS"
    }
    val rankColor = when {
        percentageOfAvg < 80 -> RetroColorsTotals.Gold
        percentageOfAvg < 120 -> RetroColorsTotals.Silver
        percentageOfAvg < 150 -> RetroColorsTotals.Bronze
        else -> RetroColorsTotals.NeonRed
    }
    
    val medal = when {
        percentageOfAvg < 80 -> "🥇"
        percentageOfAvg < 120 -> "🥈"
        percentageOfAvg < 150 -> "🥉"
        else -> "👾"
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = RetroColorsTotals.DarkBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .background(RetroColorsTotals.NeonOrange.copy(alpha = 0.7f))
                        .border(1.dp, RetroColorsTotals.NeonOrange)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header with medal
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RetroColorsTotals.DarkSurface)
                    .border(2.dp, rankColor)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.retro_stage_label_format, period.periodLabel.uppercase()),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = rankColor,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.retro_score_label_format, period.totalAmount),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = RetroColorsTotals.NeonWhite,
                            fontSize = 16.sp
                        )
                    }
                    
                    // Big medal
                    Text(
                        text = medal,
                        fontSize = 36.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Stats
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RetroColorsTotals.DarkSurface)
                    .border(1.dp, RetroColorsTotals.DarkBorder)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.retro_stage_completion),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = RetroColorsTotals.NeonYellow,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // RPG block progress bar
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        repeat(20) { index ->
                            val isFilled = index < filledBlocks.coerceAtMost(20)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .background(
                                        if (isFilled) rankColor.copy(alpha = 0.8f)
                                        else RetroColorsTotals.DarkBorder.copy(alpha = 0.3f)
                                    )
                                    .border(
                                        width = 0.5.dp,
                                        color = if (isFilled) rankColor else RetroColorsTotals.DarkBorder
                                    )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.retro_percentage_format, percentageOfAvg),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = RetroColorsTotals.NeonWhite.copy(alpha = 0.7f),
                            fontSize = 9.sp
                        )
                        Text(
                            text = rankText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = rankColor,
                            fontSize = 9.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Comparison stats
                    RetroStatRowTotals("STAGE BUDGET", CurrencyFormatter.format(averageAmount), RetroColorsTotals.NeonCyan)
                    Spacer(modifier = Modifier.height(4.dp))
                    val diff = period.totalAmount - averageAmount
                    val diffText = CurrencyFormatter.formatWithSign(diff)
                    val diffColor = if (diff > 0) RetroColorsTotals.NeonRed else if (diff < 0) RetroColorsTotals.NeonGreen else RetroColorsTotals.NeonWhite
                    RetroStatRowTotals("DIFFERENCE", diffText, diffColor)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons
            Button(
                onClick = onEnterStage,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsTotals.NeonOrange.copy(alpha = 0.15f),
                    contentColor = RetroColorsTotals.NeonOrange
                ),
                border = androidx.compose.foundation.BorderStroke(2.dp, RetroColorsTotals.NeonOrange),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.retro_enter_stage_button),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 13.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onViewAnalysis,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsTotals.NeonCyan.copy(alpha = 0.12f),
                    contentColor = RetroColorsTotals.NeonCyan
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, RetroColorsTotals.NeonCyan),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.retro_view_analysis_button),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 12.sp
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsTotals.DarkSurface,
                    contentColor = RetroColorsTotals.NeonWhite
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, RetroColorsTotals.DarkBorder),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.retro_close_button),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RetroLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loading_alpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.retro_loading_stages),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = RetroColorsTotals.NeonOrange.copy(alpha = alpha),
                fontSize = 14.sp,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.retro_insert_coin),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = RetroColorsTotals.NeonYellow.copy(alpha = alpha),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RetroStatRowTotals(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.retro_stat_label_format, label),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = RetroColorsTotals.NeonWhite.copy(alpha = 0.8f),
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = color,
            fontSize = 11.sp
        )
    }
}

// Color palette for totals card
private object RetroColorsTotals {
    val NeonGreen = Color(0xFF39FF14)
    val NeonYellow = Color(0xFFFFFF00)
    val NeonOrange = Color(0xFFFF6600)
    val NeonCyan = Color(0xFF00FFFF)
    val NeonRed = Color(0xFFFF0040)
    val NeonWhite = Color(0xFFF0F0F0)
    val Gold = Color(0xFFFFD700)
    val Silver = Color(0xFFC0C0C0)
    val Bronze = Color(0xFFCD7F32)
    val DarkBackground = Color(0xFF0D0D1A)
    val DarkSurface = Color(0xFF13132B)
    val DarkBorder = Color(0xFF1E1E3F)
}

@Composable
private fun RetroGameCardV2Totals(
    modifier: Modifier = Modifier,
    borderColor: Color,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(3.dp, borderColor)
            .border(1.dp, RetroColorsTotals.NeonWhite.copy(alpha = 0.2f))
            .padding(2.dp)
    ) {
        content()
    }
}

@Composable
private fun EnhancedScanlineOverlayTotals() {
    val infiniteTransition = rememberInfiniteTransition(label = "overlay")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "overlay_scan"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .alpha(0.12f)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.1f),
                        RetroColorsTotals.NeonOrange.copy(alpha = 0.05f),
                        Color.Black.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    startY = offset * 80,
                    endY = offset * 80 + 30
                )
            )
    )
}
