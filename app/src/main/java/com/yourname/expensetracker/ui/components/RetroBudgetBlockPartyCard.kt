package com.yourname.expensetracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Retro 8-bit game style Budget Block Party card - V2 Enhanced Edition.
 * 
 * Features:
 * - Sharp pixel-perfect squares (no rounded corners)
 * - 3D beveled button effect with inner highlight and outer shadow
 * - LED matrix grid pattern inside blocks
 * - Two-digit digital number formatting
 * - Enhanced CRT animations (scanlines, flicker, bloom)
 * - Strong neon glow effects
 */
@Composable
fun RetroBudgetBlockPartyCard(
 days: List<DayBudgetStatus>,
 modifier: Modifier = Modifier,
 onNavigateToDay: ((Long) -> Unit)? = null,
 /** Placeholder default. Production callers should pass explicit currency. */
 currency: String = "EUR"
) {
    var selectedDay by remember { mutableStateOf<DayBudgetStatus?>(null) }

    selectedDay?.let { day ->
    RetroDayAtAGlanceDialog(
        day = day,
        onDismiss = { selectedDay = null },
        onViewTransactions = if (onNavigateToDay != null) {
            {
                selectedDay = null
                onNavigateToDay(day.date)
            }
        } else null,
        currency = currency
    )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Retro card with pixel border effect
        RetroGameCardV2(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { },
            borderColor = RetroColorsV2.NeonCyan,
            backgroundColor = RetroColorsV2.DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Retro header with icon and animated scanline
                RetroHeaderV2(days = days)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Animated scanline divider
                AnimatedScanlineDivider()
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Calendar grid with V2 enhanced day blocks
                CalendarGridV2(
                    days = days,
                    onDaySelected = { selectedDay = it }
                )

                Spacer(modifier = Modifier.height(16.dp))
                
                // Animated retro divider
                AnimatedRetroDivider()
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Enhanced legend with icons
                RetroLegendV2()
            }
        }
        
        // Enhanced scanline overlay with animation
        EnhancedScanlineOverlay()
    }
}

@Composable
private fun RetroHeaderV2(days: List<DayBudgetStatus>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Pulsing calendar icon
        val infiniteTransition = rememberInfiniteTransition(label = "icon_pulse")
        val iconGlow by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "icon_pulse"
        )
        
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(RetroColorsV2.NeonYellow.copy(alpha = 0.15f * iconGlow)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = RetroColorsV2.NeonYellow.copy(alpha = iconGlow),
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Retro title with pixel font styling and flicker effect
        val flickerAlpha by rememberInfiniteTransition(label = "flicker").animateFloat(
            initialValue = 0.95f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(50, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "flicker"
        )
        
        Text(
            text = stringResource(R.string.retro_budget_blocks),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.sp
            ),
            color = RetroColorsV2.NeonWhite.copy(alpha = flickerAlpha),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Enhanced day count badge with glow
        val daysUnderBudget = days.count { it.status == BlockStatus.UNDER_BUDGET }
        val totalDays = days.size
        val badgeColor = if (daysUnderBudget >= totalDays / 2) RetroColorsV2.NeonGreen else RetroColorsV2.NeonYellow
        
        Box(
            modifier = Modifier
                .background(RetroColorsV2.DarkSurface)
                .border(2.dp, badgeColor)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = String.format("%02d/%02d", daysUnderBudget, totalDays),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = badgeColor,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CalendarGridV2(
    days: List<DayBudgetStatus>,
    onDaySelected: (DayBudgetStatus) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val startOffset = if (days.isNotEmpty()) {
            // G-TIME-01: TimePeriodUtils.getDayOfWeek returns the Calendar-style
            // constants (SUNDAY=1..SATURDAY=7) derived from the given timestamp.
            (TimePeriodUtils.getDayOfWeek(days.first().date) + 5) % 7
        } else 0

        val paddedDays: List<DayBudgetStatus?> = List(startOffset) { null } + days
        paddedDays.chunked(7).forEach { week ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                week.forEach { dayOrNull ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (dayOrNull != null) {
                            RetroDayBlockV2(
                                day = dayOrNull,
                                onClick = { onDaySelected(dayOrNull) }
                            )
                        }
                    }
                }
                if (week.size < 7) {
                    repeat(7 - week.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Enhanced V2 Day Block with:
 * - Sharp pixel corners (0.dp radius)
 * - 3D beveled button effect
 * - LED matrix grid pattern
 * - Two-digit digital formatting
 * - Enhanced animations
 */
@Composable
private fun RetroDayBlockV2(
    day: DayBudgetStatus,
    onClick: () -> Unit
) {
    val isBillDay = day.status == BlockStatus.BILL_DAY
    
    // Color determination with enhanced glow
    val baseColor = when (day.status) {
        BlockStatus.UNDER_BUDGET -> RetroColorsV2.NeonGreen
        BlockStatus.OVER_BUDGET -> RetroColorsV2.NeonRed
        BlockStatus.TODAY -> RetroColorsV2.NeonCyan
        BlockStatus.FUTURE -> RetroColorsV2.DarkBorder.copy(alpha = 0.3f)
        BlockStatus.BILL_DAY -> Color.Transparent
        BlockStatus.NO_DATA -> RetroColorsV2.DarkBorder.copy(alpha = 0.2f)
    }
    
    // Enhanced pulsing animation for TODAY
    val infiniteTransition = rememberInfiniteTransition(label = "enhanced_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (day.status == BlockStatus.TODAY) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "enhanced_pulse"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (day.status == BlockStatus.TODAY) 1f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )
    
    // Flicker animation for critical days
    val flickerAlpha = if (day.status == BlockStatus.OVER_BUDGET) {
        infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(100),
                repeatMode = RepeatMode.Reverse
            ),
            label = "critical_flicker"
        ).value
    } else 1f
    
    val animatedColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(200),
        label = "color"
    )
    
    Box(
        modifier = Modifier
            .aspectRatio(1.1f)
            .let { modifier ->
                // Apply pulse scale only for TODAY
                if (day.status == BlockStatus.TODAY) {
                    modifier.graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                } else modifier
            }
            // 3D Beveled effect with drawBehind
            .drawBehind {
                val width = size.width
                val height = size.height
                
                // Background fill
                drawRect(
                    color = when {
                        day.status == BlockStatus.FUTURE -> animatedColor.copy(alpha = 0.15f)
                        isBillDay -> Color.Transparent
                        else -> animatedColor.copy(alpha = pulseAlpha * flickerAlpha)
                    },
                    size = Size(width, height)
                )
                
                if (!isBillDay && day.status != BlockStatus.FUTURE && day.status != BlockStatus.NO_DATA) {
                    // Top highlight (3D bevel effect)
                    drawRect(
                        color = Color.White.copy(alpha = 0.4f),
                        topLeft = Offset(0f, 0f),
                        size = Size(width, 2f)
                    )
                    drawRect(
                        color = Color.White.copy(alpha = 0.25f),
                        topLeft = Offset(0f, 0f),
                        size = Size(2f, height)
                    )
                    
                    // Bottom shadow (3D bevel effect)
                    drawRect(
                        color = Color.Black.copy(alpha = 0.4f),
                        topLeft = Offset(0f, height - 2f),
                        size = Size(width, 2f)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.25f),
                        topLeft = Offset(width - 2f, 0f),
                        size = Size(2f, height)
                    )
                }
                
                // LED Matrix grid pattern
                if (day.status != BlockStatus.FUTURE) {
                    val cellSize = 3f
                    val gap = 1f
                    val cols = (width / (cellSize + gap)).toInt()
                    val rows = (height / (cellSize + gap)).toInt()
                    
                    for (row in 0 until rows) {
                        for (col in 0 until cols) {
                            val x = col * (cellSize + gap) + 2f
                            val y = row * (cellSize + gap) + 2f
                            
                            // Only draw grid dots inside, not on edges
                            if (x + cellSize < width - 2f && y + cellSize < height - 2f) {
                                drawRect(
                                    color = when {
                                        day.status == BlockStatus.UNDER_BUDGET -> 
                                            RetroColorsV2.NeonGreen.copy(alpha = 0.15f)
                                        day.status == BlockStatus.OVER_BUDGET -> 
                                            RetroColorsV2.NeonRed.copy(alpha = 0.15f)
                                        day.status == BlockStatus.TODAY -> 
                                            RetroColorsV2.NeonCyan.copy(alpha = 0.15f)
                                        else -> RetroColorsV2.NeonWhite.copy(alpha = 0.08f)
                                    },
                                    topLeft = Offset(x, y),
                                    size = Size(cellSize, cellSize)
                                )
                            }
                        }
                    }
                }
            }
            // Border
            .then(
                if (isBillDay) {
                    Modifier.border(2.dp, RetroColorsV2.NeonYellow.copy(alpha = 0.8f))
                } else {
                    Modifier.border(
                        1.dp,
                        when {
                            day.status == BlockStatus.FUTURE || day.status == BlockStatus.NO_DATA -> 
                                RetroColorsV2.DarkBorder.copy(alpha = 0.5f)
                            day.status == BlockStatus.TODAY -> 
                                RetroColorsV2.NeonCyan.copy(alpha = 0.9f)
                            else -> RetroColorsV2.NeonWhite.copy(alpha = 0.25f)
                        }
                    )
                }
            )
            .clickable(enabled = day.status != BlockStatus.FUTURE, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (day.status != BlockStatus.FUTURE) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(2.dp)
            ) {
                // Two-digit digital-style number
                Text(
                    text = String.format("%02d", day.dayOfMonth),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp
                    ),
                    color = when {
                        isBillDay -> RetroColorsV2.NeonYellow
                        day.status == BlockStatus.OVER_BUDGET -> RetroColorsV2.NeonWhite
                        else -> RetroColorsV2.NeonWhite.copy(alpha = 0.95f)
                    },
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
                
                // Status indicator icon
                if (isBillDay) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = RetroColorsV2.NeonYellow.copy(alpha = 0.9f),
                        modifier = Modifier.size(10.dp)
                    )
                } else if (day.status == BlockStatus.UNDER_BUDGET && day.dayOfMonth <= 9) {
                    // Tiny pixel check for under budget single-digit days
                    Text(
                        text = "▲",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = RetroColorsV2.NeonGreen.copy(alpha = 0.7f),
                        fontSize = 6.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroLegendV2() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        RetroLegendItemV2(
            color = RetroColorsV2.NeonGreen,
            label = "OK",
            symbol = "▲"
        )
        RetroLegendItemV2(
            color = RetroColorsV2.NeonRed,
            label = "OVER",
            symbol = "▼"
        )
        RetroLegendItemV2(
            color = RetroColorsV2.NeonCyan,
            label = "TODAY",
            symbol = "◆"
        )
        RetroLegendItemV2(
            color = Color.Transparent,
            label = "BILL",
            symbol = "⚠",
            borderColor = RetroColorsV2.NeonYellow.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun RetroLegendItemV2(
    color: Color,
    label: String,
    symbol: String,
    borderColor: Color? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Pixel square with symbol
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(
                    if (color == Color.Transparent) Color.Transparent else color.copy(alpha = 0.9f)
                )
                .then(
                    if (borderColor != null) {
                        Modifier.border(1.5.dp, borderColor)
                    } else {
                        Modifier.border(1.dp, RetroColorsV2.NeonWhite.copy(alpha = 0.3f))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                fontSize = 8.sp,
                color = if (color == Color.Transparent) borderColor!! else RetroColorsV2.NeonWhite
            )
        }
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            fontSize = 9.sp,
            color = RetroColorsV2.NeonWhite.copy(alpha = 0.8f),
            letterSpacing = 1.sp
        )
    }
}

/**
 * V2 Enhanced Retro Game Card with sharper corners and better borders.
 */
@Composable
private fun RetroGameCardV2(
    modifier: Modifier = Modifier,
    borderColor: Color,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(3.dp, borderColor)
            .border(1.dp, RetroColorsV2.NeonWhite.copy(alpha = 0.2f))
            .padding(2.dp)
    ) {
        // Inner glow border
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, borderColor.copy(alpha = 0.3f))
        )
        
        // Corner decorations with larger brackets
        RetroCornerDecorationV2(alignment = Alignment.TopStart, color = borderColor)
        RetroCornerDecorationV2(alignment = Alignment.TopEnd, color = borderColor)
        RetroCornerDecorationV2(alignment = Alignment.BottomStart, color = borderColor)
        RetroCornerDecorationV2(alignment = Alignment.BottomEnd, color = borderColor)
        
        content()
    }
}

@Composable
private fun RetroCornerDecorationV2(
    alignment: Alignment,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(Color.Transparent)
            .then(
                when (alignment) {
                    Alignment.TopStart -> Modifier
                        .drawBehind {
                            // Outer bracket
                            drawLine(
                                color = color,
                                start = Offset(0f, 0f),
                                end = Offset(size.width * 0.6f, 0f),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = color,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height * 0.6f),
                                strokeWidth = 4f
                            )
                            // Inner accent
                            drawLine(
                                color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                                start = Offset(2f, 2f),
                                end = Offset(size.width * 0.4f, 2f),
                                strokeWidth = 1f
                            )
                        }
                    Alignment.TopEnd -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width * 0.4f, 0f),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = color,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height * 0.6f),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                                start = Offset(size.width - 2f, 2f),
                                end = Offset(size.width * 0.6f, 2f),
                                strokeWidth = 1f
                            )
                        }
                    Alignment.BottomStart -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height),
                                end = Offset(size.width * 0.6f, size.height),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = color,
                                start = Offset(0f, size.height),
                                end = Offset(0f, size.height * 0.4f),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                                start = Offset(2f, size.height - 2f),
                                end = Offset(size.width * 0.4f, size.height - 2f),
                                strokeWidth = 1f
                            )
                        }
                    else -> Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(size.width, size.height),
                                end = Offset(size.width * 0.4f, size.height),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = color,
                                start = Offset(size.width, size.height),
                                end = Offset(size.width, size.height * 0.4f),
                                strokeWidth = 4f
                            )
                            drawLine(
                                color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                                start = Offset(size.width - 2f, size.height - 2f),
                                end = Offset(size.width * 0.6f, size.height - 2f),
                                strokeWidth = 1f
                            )
                        }
                }
            )
    )
}

@Composable
private fun AnimatedScanlineDivider() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline_divider")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline_offset"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColorsV2.NeonCyan.copy(alpha = 0.3f + (offset * 0.4f)),
                        RetroColorsV2.NeonCyan.copy(alpha = 0.6f + (offset * 0.4f)),
                        RetroColorsV2.NeonCyan.copy(alpha = 0.3f + (offset * 0.4f)),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = 1000f * offset
                )
            )
    )
}

@Composable
private fun AnimatedRetroDivider() {
    val infiniteTransition = rememberInfiniteTransition(label = "divider_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "divider_alpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColorsV2.NeonCyan.copy(alpha = alpha * 0.5f),
                        RetroColorsV2.NeonCyan.copy(alpha = alpha),
                        RetroColorsV2.NeonCyan.copy(alpha = alpha * 0.5f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun EnhancedScanlineOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline_overlay")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "overlay_scan"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .alpha(0.15f)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColorsV2.Scanline.copy(alpha = 0.1f),
                        RetroColorsV2.Scanline.copy(alpha = 0.3f),
                        RetroColorsV2.NeonCyan.copy(alpha = 0.1f),
                        RetroColorsV2.Scanline.copy(alpha = 0.3f),
                        RetroColorsV2.Scanline.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    startY = offset * 100,
                    endY = offset * 100 + 50
                )
            )
    )
}

/**
 * V2 Enhanced Color Palette
 */
private object RetroColorsV2 {
    val NeonGreen = Color(0xFF39FF14)
    val NeonYellow = Color(0xFFFFFF00)
    val NeonOrange = Color(0xFFFF6600)
    val NeonRed = Color(0xFFFF0040)
    val NeonCyan = Color(0xFF00FFFF)
    val NeonWhite = Color(0xFFF0F0F0)
    val DarkBackground = Color(0xFF0D0D1A)
    val DarkSurface = Color(0xFF13132B)
    val DarkBorder = Color(0xFF1E1E3F)
    val DarkSegment = Color(0xFF2D2D2D)
    val Scanline = Color(0xFF000000)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroDayAtAGlanceDialog(
    day: DayBudgetStatus,
    onDismiss: () -> Unit,
    onViewTransactions: (() -> Unit)? = null,
    /** Placeholder default. Production callers should pass explicit currency. */
    currency: String = "EUR"
) {
    val dateStr = DateFormatterUtils.formatTimestampJavaTime(day.date, "MMM dd")
    
    val statusColor = when (day.status) {
        BlockStatus.UNDER_BUDGET -> RetroColorsV2.NeonGreen
        BlockStatus.OVER_BUDGET -> RetroColorsV2.NeonRed
        BlockStatus.TODAY -> if (day.actualSpent <= day.targetBudget) RetroColorsV2.NeonCyan else RetroColorsV2.NeonRed
        BlockStatus.BILL_DAY -> RetroColorsV2.NeonYellow
        BlockStatus.NO_DATA -> RetroColorsV2.NeonWhite.copy(alpha = 0.7f)
        BlockStatus.FUTURE -> RetroColorsV2.NeonWhite.copy(alpha = 0.7f)
    }
    
    val statusText = when (day.status) {
        BlockStatus.UNDER_BUDGET -> "UNDER BUDGET"
        BlockStatus.OVER_BUDGET -> "OVER BUDGET"
        BlockStatus.TODAY -> if (day.actualSpent <= day.targetBudget) "ON TRACK" else "OVER BUDGET"
        BlockStatus.BILL_DAY -> "BILL DAY"
        BlockStatus.NO_DATA -> "NO DATA"
        BlockStatus.FUTURE -> "UPCOMING"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = RetroColorsV2.DarkBackground,
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
                        .background(RetroColorsV2.NeonCyan.copy(alpha = 0.7f))
                        .border(1.dp, RetroColorsV2.NeonCyan)
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
            // Header - stacked layout for better space usage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RetroColorsV2.DarkSurface)
                    .border(2.dp, statusColor)
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Date and balance in one row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.retro_date_format, dateStr),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = RetroColorsV2.NeonCyan,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp
                        )
                        
                        val balance = day.targetBudget - day.actualSpent
                        val balanceColor = if (balance >= 0) RetroColorsV2.NeonGreen else RetroColorsV2.NeonRed
                        Text(
                            text = CurrencyFormatter.formatWithSign(balance, currency),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = balanceColor,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    // Status centered below
                    Text(
                        text = "◄ $statusText ►",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = statusColor,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontSize = 16.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Target Breakdown - compact layout
            RetroDialogSection(
                title = stringResource(R.string.retro_target_breakdown),
                titleColor = RetroColorsV2.NeonYellow
            ) {
                Column {
                    // Base target
                    RetroStatRowV3(
                        label = "BASE",
                        value = CurrencyFormatter.formatMoney(day.baseTarget, currency),
                        color = RetroColorsV2.NeonWhite.copy(alpha = 0.9f)
                    )
                    
                    // Recurring items - show count, not full names
                    if (day.recurringImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val itemCount = day.recurringItems.size
                        val labelText = if (itemCount == 1) "RECURRING" else "RECURRING ($itemCount)"
                        RetroStatRowV3(
                            label = labelText,
                            value = "+${CurrencyFormatter.formatMoney(day.recurringImpact, currency)}",
                            color = RetroColorsV2.NeonWhite.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Planned items
                    if (day.plannedImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val itemCount = day.plannedItems.size
                        val labelText = if (itemCount == 1) "PLANNED" else "PLANNED ($itemCount)"
                        RetroStatRowV3(
                            label = labelText,
                            value = "+${CurrencyFormatter.formatMoney(day.plannedImpact, currency)}",
                            color = RetroColorsV2.NeonWhite.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Divider
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        RetroColorsV2.NeonCyan.copy(alpha = 0.7f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Total
                    RetroStatRowV3(
                        label = "TOTAL TARGET",
                        value = CurrencyFormatter.formatMoney(day.targetBudget, currency),
                        color = RetroColorsV2.NeonCyan,
                        isBold = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            
            // Actual Spending - compact layout
            RetroDialogSection(
                title = stringResource(R.string.retro_actual_spending),
                titleColor = RetroColorsV2.NeonYellow
            ) {
                Column {
                    val isOverBudget = day.actualSpent > day.targetBudget
                    
                    RetroStatRowV3(
                        label = "SPENT",
                        value = CurrencyFormatter.formatMoney(day.actualSpent, currency),
                        color = if (isOverBudget) RetroColorsV2.NeonRed else RetroColorsV2.NeonGreen,
                        isBold = true
                    )
                    
                    // Show difference
                    if (day.actualSpent > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val diff = day.targetBudget - day.actualSpent
                        val diffText = if (diff >= 0) "UNDER BY" else "OVER BY"
                        val diffColor = if (diff >= 0) RetroColorsV2.NeonGreen else RetroColorsV2.NeonRed
                        RetroStatRowV3(
                            label = diffText,
                            value = CurrencyFormatter.formatMoney(kotlin.math.abs(diff), currency),
                            color = diffColor
                        )
                    }
                    
                    // Transactions list
                    if (day.topTransactions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(RetroColorsV2.NeonWhite.copy(alpha = 0.15f))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        day.topTransactions.take(3).forEach { exp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.retro_merchant_format, exp.merchant.take(18)),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = RetroColorsV2.NeonWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = CurrencyFormatter.formatMoney(exp.amount, currency),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = RetroColorsV2.NeonWhite,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        
                        if (day.topTransactions.size > 3) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.retro_more_format, day.topTransactions.size - 3),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                                fontSize = 9.sp
                            )
                        }
                    } else if (day.actualSpent == 0.0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.retro_no_transactions),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = RetroColorsV2.NeonWhite.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Action buttons - more compact
            if (onViewTransactions != null && day.status != BlockStatus.FUTURE) {
                Button(
                    onClick = onViewTransactions,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RetroColorsV2.NeonCyan.copy(alpha = 0.12f),
                        contentColor = RetroColorsV2.NeonCyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, RetroColorsV2.NeonCyan),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Text(
                        "[VIEW ALL TRANSACTIONS]",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsV2.DarkSurface,
                    contentColor = RetroColorsV2.NeonWhite
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, RetroColorsV2.DarkBorder),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text(
                    "[CLOSE]",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RetroDialogSection(
    title: String,
    titleColor: Color,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            ),
            color = titleColor,
            modifier = Modifier.padding(bottom = 8.dp),
            fontSize = 11.sp
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(RetroColorsV2.DarkSurface)
                .border(1.dp, RetroColorsV2.DarkBorder)
                .padding(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun RetroStatRowV3(
    label: String,
    value: String,
    color: Color,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = color.copy(alpha = if (isBold) 1f else 0.8f),
            fontSize = 12.sp,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
            ),
            color = color,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}
