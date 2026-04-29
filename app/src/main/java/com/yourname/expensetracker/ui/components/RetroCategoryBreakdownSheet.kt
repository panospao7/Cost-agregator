package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * Retro Arcade style Category Breakdown Sheet.
 * Matches the aesthetic of RetroTotalsDashboardCard with stage select styling.
 * 
 * Features:
 * - Stage Analysis header with neon glow
 * - RPG block-style progress bars
 * - Pixel bracket category names [CATEGORY]
 * - 3D beveled cards with sharp corners
 * - Medal indicators for top categories
 * - CRT scanline effects
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetroCategoryBreakdownSheet(
    periodLabel: String,
    categories: List<CategoryBreakdown>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    currency: String = "EUR"
) {
    var showAll by remember { mutableStateOf(false) }
    val displayCount = if (showAll) categories.size else minOf(5, categories.size)
    val displayCategories = categories.take(displayCount)
    
    // Calculate max amount for percentage scaling
    val maxAmount = categories.maxOfOrNull { it.totalAmount } ?: 1.0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = RetroColorsBreakdown.DarkBackground,
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
                        .background(RetroColorsBreakdown.NeonOrange.copy(alpha = 0.7f))
                        .border(1.dp, RetroColorsBreakdown.NeonOrange)
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title with star
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    
                    Text(
                        text = stringResource(R.string.retro_star_icon),
                        color = RetroColorsBreakdown.NeonYellow.copy(alpha = iconGlow),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
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
                        text = stringResource(R.string.retro_stage_analysis_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        ),
                        color = RetroColorsBreakdown.NeonWhite.copy(alpha = flickerAlpha),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 15.sp
                    )
                }
                
                // Period label badge
                Box(
                    modifier = Modifier
                        .background(RetroColorsBreakdown.DarkSurface)
                        .border(1.5.dp, RetroColorsBreakdown.NeonOrange)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.retro_period_label_format, periodLabel),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = RetroColorsBreakdown.NeonOrange,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Animated scanline divider
            RetroScanlineDividerBreakdown()
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Category scores header
            Text(
                text = stringResource(R.string.retro_category_scores),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                ),
                color = RetroColorsBreakdown.NeonYellow,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Category list
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayCategories.forEachIndexed { index, breakdown ->
                    val percentage = if (maxAmount > 0) (breakdown.totalAmount / maxAmount).toFloat() else 0f
                    val medal = when (index) {
                        0 -> "🥇"
                        1 -> "🥈"
                        2 -> "🥉"
                        else -> null
                    }
                    
        RetroCategoryBreakdownRow(
                breakdown = breakdown,
                percentage = percentage,
                medal = medal,
                rank = index + 1,
                currency = currency
            )
                }
            }
            
            // Show all / Show less button
            if (categories.size > 5) {
                Spacer(modifier = Modifier.height(12.dp))
                
                if (!showAll) {
                    Button(
                        onClick = { showAll = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RetroColorsBreakdown.NeonCyan.copy(alpha = 0.12f),
                            contentColor = RetroColorsBreakdown.NeonCyan
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, RetroColorsBreakdown.NeonCyan),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.retro_view_all_categories_format, categories.size),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { showAll = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RetroColorsBreakdown.DarkSurface,
                            contentColor = RetroColorsBreakdown.NeonWhite.copy(alpha = 0.7f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RetroColorsBreakdown.DarkBorder),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.retro_show_top_5),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Close button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsBreakdown.DarkSurface,
                    contentColor = RetroColorsBreakdown.NeonWhite
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, RetroColorsBreakdown.DarkBorder),
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
private fun RetroCategoryBreakdownRow(
    breakdown: CategoryBreakdown,
    percentage: Float,
    medal: String?,
    rank: Int,
    currency: String = "EUR"
) {
    val categoryColor = try {
        Color(android.graphics.Color.parseColor(breakdown.category.color))
    } catch (e: Exception) {
        RetroColorsBreakdown.NeonWhite
    }
    
    val totalBlocks = 20
    val filledBlocks = (percentage * totalBlocks).toInt()
    
    // Rank-based border color
    val borderColor = when (rank) {
        1 -> RetroColorsBreakdown.Gold
        2 -> RetroColorsBreakdown.Silver
        3 -> RetroColorsBreakdown.Bronze
        else -> RetroColorsBreakdown.DarkBorder
    }
    
    val backgroundAlpha = if (rank <= 3) 0.1f else 0.05f
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when (rank) {
                    1 -> RetroColorsBreakdown.Gold.copy(alpha = backgroundAlpha)
                    2 -> RetroColorsBreakdown.Silver.copy(alpha = backgroundAlpha)
                    3 -> RetroColorsBreakdown.Bronze.copy(alpha = backgroundAlpha)
                    else -> RetroColorsBreakdown.DarkSurface.copy(alpha = backgroundAlpha)
                }
            )
            .border(
                width = if (rank <= 3) 1.5.dp else 1.dp,
                color = borderColor
            )
            .drawBehind {
                val width = size.width
                val height = size.height
                
                // 3D bevel effect
                // Top highlight
                drawRect(
                    color = Color.White.copy(alpha = 0.15f),
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(width, 1.5f)
                )
                // Bottom shadow
                drawRect(
                    color = Color.Black.copy(alpha = 0.2f),
                    topLeft = Offset(0f, height - 1.5f),
                    size = androidx.compose.ui.geometry.Size(width, 1.5f)
                )
            }
            .padding(10.dp)
    ) {
        Column {
            // Header row: Icon + Name + Amount + Medal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Category icon in pixel frame
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(categoryColor.copy(alpha = 0.2f))
                            .border(1.5.dp, categoryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = breakdown.category.icon,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Category name with brackets
                    Text(
                        text = stringResource(R.string.retro_category_name_format, breakdown.category.name.uppercase()),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = RetroColorsBreakdown.NeonWhite,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Amount
                    Text(
                        text = CurrencyFormatter.format(breakdown.totalAmount, currency, showCents = false),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = RetroColorsBreakdown.NeonWhite,
                        fontSize = 13.sp
                    )
                    
                    // Percentage
                    Text(
                        text = "${String.format("%.0f", breakdown.percentageOfTotal)}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = categoryColor,
                        fontSize = 9.sp
                    )
                    
                    // Medal for top 3
                    if (medal != null) {
                        Text(
                            text = medal,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // RPG block progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                repeat(totalBlocks) { index ->
                    val isFilled = index < filledBlocks
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                if (isFilled) categoryColor.copy(alpha = 0.8f)
                                else RetroColorsBreakdown.DarkBorder.copy(alpha = 0.3f)
                            )
                            .border(
                                width = 0.5.dp,
                                color = if (isFilled) categoryColor else RetroColorsBreakdown.DarkBorder
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroScanlineDividerBreakdown() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanline_alpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        RetroColorsBreakdown.NeonOrange.copy(alpha = alpha * 0.5f),
                        RetroColorsBreakdown.NeonOrange.copy(alpha = alpha),
                        RetroColorsBreakdown.NeonOrange.copy(alpha = alpha * 0.5f),
                        Color.Transparent
                    )
                )
            )
    )
}

// Color palette for breakdown sheet - matches RetroTotalsDashboardCard
private object RetroColorsBreakdown {
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
