package com.yourname.expensetracker.ui.components.health

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.health.HealthBreakdown
import com.yourname.expensetracker.domain.health.HealthScoreResult
import com.yourname.expensetracker.domain.health.HealthStatus
import com.yourname.expensetracker.domain.health.PeriodHealthScore
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

/**
 * Retro 8-bit game style health score widget.
 * Features pixel aesthetics, scanlines, segmented health bars, and retro gaming colors.
 */
@Composable
fun HealthScoreWidget(
    healthScore: HealthScoreResult,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val compositeStatus = healthScore.getCompositeStatus()
    val compositeColor = getRetroHealthColor(compositeStatus)
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Retro card with pixel border effect
        RetroGameCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            borderColor = compositeColor,
            backgroundColor = RetroColors.DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Retro header with pixel heart
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RetroPixelHeart(
                        healthStatus = compositeStatus,
                        compositeScore = healthScore.composite,
                        modifier = Modifier.size(36.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    // Retro title with pixel font styling
                    Text(
                        text = stringResource(R.string.health_financial_hp),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        ),
                        color = RetroColors.NeonWhite,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Composite score badge
                    RetroBadge(
                        text = "${healthScore.composite}%",
                        color = compositeColor
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Segmented health bars (like Zelda/Metroid heart containers)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RetroHealthContainer(
                        label = stringResource(R.string.health_day),
                        score = healthScore.today.score,
                        maxSegments = 10,
                        color = getRetroHealthColor(getStatusFromScore(healthScore.today.score)),
                        modifier = Modifier.weight(1f)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    RetroHealthContainer(
                        label = stringResource(R.string.health_week), 
                        score = healthScore.week.score,
                        maxSegments = 10,
                        color = getRetroHealthColor(getStatusFromScore(healthScore.week.score)),
                        modifier = Modifier.weight(1f)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    RetroHealthContainer(
                        label = stringResource(R.string.health_month),
                        score = healthScore.month.score,
                        maxSegments = 10,
                        color = getRetroHealthColor(getStatusFromScore(healthScore.month.score)),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status text in retro style
                RetroStatusText(
                    status = compositeStatus,
                    score = healthScore.composite
                )
                
                // Expandable breakdown with retro styling
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(16.dp))
                    RetroDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    RetroBreakdownDetails(healthScore = healthScore)
                }
                
                // Retro expand/collapse hint
                Text(
                    text = if (isExpanded) stringResource(R.string.health_collapse_hint) else stringResource(R.string.health_expand_hint),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = RetroColors.NeonCyan.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        }
        
        // Scanline overlay effect
        ScanlineOverlay()
    }
}

/**
 * Retro game card with pixel border effect
 */
@Composable
private fun RetroGameCard(
    modifier: Modifier = Modifier,
    borderColor: Color,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(3.dp, borderColor) // Thick pixel border
            .border(1.dp, RetroColors.NeonWhite.copy(alpha = 0.3f)) // Inner glow
            .padding(2.dp) // Padding for pixel effect
    ) {
        // Corner decorations (like old school RPG UI)
        RetroCornerDecoration(alignment = Alignment.TopStart, color = borderColor)
        RetroCornerDecoration(alignment = Alignment.TopEnd, color = borderColor)
        RetroCornerDecoration(alignment = Alignment.BottomStart, color = borderColor)
        RetroCornerDecoration(alignment = Alignment.BottomEnd, color = borderColor)
        
        content()
    }
}

/**
 * Corner bracket decorations for retro feel
 */
@Composable
private fun RetroCornerDecoration(
    alignment: Alignment,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(Color.Transparent)
            .then(
                when (alignment) {
                    Alignment.TopStart -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(0f, 0f),
                                end = Offset(size.width * 0.7f, 0f),
                                strokeWidth = 3f
                            )
                            drawLine(
                                color = color,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height * 0.7f),
                                strokeWidth = 3f
                            )
                        }
                    Alignment.TopEnd -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width * 0.3f, 0f),
                                strokeWidth = 3f
                            )
                            drawLine(
                                color = color,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height * 0.7f),
                                strokeWidth = 3f
                            )
                        }
                    Alignment.BottomStart -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height),
                                end = Offset(size.width * 0.7f, size.height),
                                strokeWidth = 3f
                            )
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height),
                                end = Offset(0f, size.height * 0.3f),
                                strokeWidth = 3f
                            )
                        }
                    else -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(size.width, size.height),
                                end = Offset(size.width * 0.3f, size.height),
                                strokeWidth = 3f
                            )
                            drawLine(
                                color = color,
                                start = Offset(size.width, size.height),
                                end = Offset(size.width, size.height * 0.3f),
                                strokeWidth = 3f
                            )
                        }
                }
            )
    )
}

/**
 * Pixel-style animated heart
 */
@Composable
private fun RetroPixelHeart(
    healthStatus: HealthStatus,
    compositeScore: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "retro_pulse")
    
    val pulseIntensity = when (healthStatus) {
        HealthStatus.EXCELLENT -> 0f
        HealthStatus.GOOD -> 0.08f
        HealthStatus.FAIR -> 0.15f
        HealthStatus.WARNING -> 0.25f
        HealthStatus.CRITICAL -> 0.35f
    }
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f + pulseIntensity,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutLinearInEasing), // Faster retro pulse
            repeatMode = RepeatMode.Reverse
        ),
        label = "retro_pulse"
    )
    
    val color = getRetroHealthColor(healthStatus)
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "retro_color"
    )
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Glow effect
        Box(
            modifier = Modifier
                .size(28.dp * scale)
                .background(animatedColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
        )
        
        // Pixel heart icon
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = stringResource(R.string.a11y_health_score_format, healthStatus.name, compositeScore),
            tint = animatedColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Segmented health container like classic games (Zelda hearts, Metroid energy tanks)
 */
@Composable
private fun RetroHealthContainer(
    label: String,
    score: Int,
    maxSegments: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val filledSegments = (score / 100.0 * maxSegments).toInt().coerceIn(0, maxSegments)
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Retro label
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = RetroColors.NeonCyan
        )
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Segmented health bar (like classic RPG)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(5) { index ->
                    RetroSegment(
                        filled = index < filledSegments,
                        color = color
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(5) { index ->
                    RetroSegment(
                        filled = (index + 5) < filledSegments,
                        color = color
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Score in retro style
        Text(
            text = "$score",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
            ),
            color = color
        )
    }
}

/**
 * Individual pixel segment for health bar
 */
@Composable
private fun RetroSegment(
    filled: Boolean,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(width = 8.dp, height = 10.dp)
            .background(
                if (filled) color else RetroColors.DarkSegment,
                RoundedCornerShape(1.dp)
            )
            .border(1.dp, if (filled) RetroColors.NeonWhite.copy(alpha = 0.5f) else RetroColors.DarkBorder)
    )
}

/**
 * Retro badge for score display
 */
@Composable
private fun RetroBadge(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .background(RetroColors.DarkBackground, RoundedCornerShape(2.dp))
            .border(2.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
    }
}

/**
 * Status text in retro gaming style
 */
@Composable
private fun RetroStatusText(
    status: HealthStatus,
    score: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val statusText = when (status) {
        HealthStatus.EXCELLENT -> stringResource(R.string.health_status_excellent)
        HealthStatus.GOOD -> stringResource(R.string.health_status_good)
        HealthStatus.FAIR -> stringResource(R.string.health_status_fair)
        HealthStatus.WARNING -> stringResource(R.string.health_status_warning)
        HealthStatus.CRITICAL -> stringResource(R.string.health_status_critical)
    }
    
    val color = getRetroHealthColor(status)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Blinking cursor effect
        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
        val cursorAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(500),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursor"
        )
        
        Text(
            text = "> ",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = RetroColors.NeonCyan.copy(alpha = cursorAlpha)
        )
        
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = color
        )
        
        Text(
            text = " <",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = RetroColors.NeonCyan.copy(alpha = cursorAlpha)
        )
    }
}

/**
 * Retro divider with pixel styling
 */
@Composable
private fun RetroDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColors.NeonCyan.copy(alpha = 0.5f),
                        RetroColors.NeonCyan,
                        RetroColors.NeonCyan.copy(alpha = 0.5f),
                        Color.Transparent
                    )
                )
            )
    )
}

/**
 * Retro-styled breakdown details
 */
@Composable
private fun RetroBreakdownDetails(
    healthScore: HealthScoreResult
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.health_score_breakdown),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = RetroColors.NeonYellow,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        RetroBreakdownSection(
            period = stringResource(R.string.health_period_today),
            breakdown = healthScore.today.breakdown
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        RetroBreakdownSection(
            period = stringResource(R.string.health_period_week),
            breakdown = healthScore.week.breakdown
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        RetroBreakdownSection(
            period = stringResource(R.string.health_period_month),
            breakdown = healthScore.month.breakdown
        )
    }
}

/**
 * Individual period breakdown in retro style
 */
@Composable
private fun RetroBreakdownSection(
    period: String,
    breakdown: HealthBreakdown
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RetroColors.DarkSurface, RoundedCornerShape(2.dp))
            .border(1.dp, RetroColors.DarkBorder, RoundedCornerShape(2.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "[$period]",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = RetroColors.NeonCyan
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        RetroStatRow(label = stringResource(R.string.health_stat_budget), value = breakdown.budgetHealth, max = 25)
        RetroStatRow(label = stringResource(R.string.health_stat_control), value = breakdown.spendingControl, max = 25)
        RetroStatRow(label = stringResource(R.string.health_stat_clean), value = breakdown.cleanliness, max = 10)
        
        if (breakdown.bonusPoints > 0) {
            RetroStatRow(label = stringResource(R.string.health_stat_bonus), value = breakdown.bonusPoints, max = 15, isBonus = true)
        }
    }
}

/**
 * Retro stat row with pixel styling
 */
@Composable
private fun RetroStatRow(
    label: String,
    value: Int,
    max: Int,
    isBonus: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = RetroColors.NeonWhite.copy(alpha = 0.7f)
        )
        
        val color = when {
            isBonus -> RetroColors.NeonYellow
            value >= max * 0.8 -> RetroColors.NeonGreen
            value >= max * 0.5 -> RetroColors.NeonOrange
            else -> RetroColors.NeonRed
        }
        
        Text(
            text = String.format("%02d/%02d", value, max),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
    }
}

/**
 * Scanline overlay effect for retro CRT look
 */
@Composable
private fun ScanlineOverlay() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColors.Scanline.copy(alpha = 0.1f),
                        RetroColors.Scanline.copy(alpha = 0.2f),
                        RetroColors.Scanline.copy(alpha = 0.1f),
                        Color.Transparent
                    )
                )
            )
    )
}

/**
 * Retro color palette
 */
private object RetroColors {
    val NeonGreen = Color(0xFF39FF14)      // Classic arcade green
    val NeonYellow = Color(0xFFFFFF00)     // Coin-up yellow
    val NeonOrange = Color(0xFFFF6600)    // Warning orange
    val NeonRed = Color(0xFFFF0040)       // Critical red
    val NeonCyan = Color(0xFF00FFFF)      // CRT cyan
    val NeonWhite = Color(0xFFF0F0F0)     // Pixel white
    val DarkBackground = Color(0xFF1A1A2E) // Deep retro blue-black
    val DarkSurface = Color(0xFF16213E)    // Slightly lighter
    val DarkBorder = Color(0xFF0F3460)     // Border color
    val DarkSegment = Color(0xFF2D2D2D)    // Empty segment
    val Scanline = Color(0xFF000000)       // Scanline black
}

/**
 * Get retro color for health status
 */
private fun getRetroHealthColor(status: HealthStatus): Color {
    return when (status) {
        HealthStatus.EXCELLENT -> RetroColors.NeonGreen
        HealthStatus.GOOD -> Color(0xFF7FFF00)      // Chartreuse
        HealthStatus.FAIR -> RetroColors.NeonYellow
        HealthStatus.WARNING -> RetroColors.NeonOrange
        HealthStatus.CRITICAL -> RetroColors.NeonRed
    }
}

private fun getStatusFromScore(score: Int): HealthStatus {
    return when (score) {
        in 85..100 -> HealthStatus.EXCELLENT
        in 70..84 -> HealthStatus.GOOD
        in 50..69 -> HealthStatus.FAIR
        in 30..49 -> HealthStatus.WARNING
        else -> HealthStatus.CRITICAL
    }
}
