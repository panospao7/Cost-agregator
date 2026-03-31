package com.yourname.expensetracker.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.analytics.CategoryTrendDirection
import com.yourname.expensetracker.domain.usecase.dashboard.CategorySpending
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.util.Date

/**
 * Retro Arcade RPG Hybrid Top Categories Card.
 * 
 * Features:
 * - Arcade cabinet aesthetic with neon glow
 * - RPG block-style progress bars (▓▓▓▓▓░░░░░)
 * - Gold/Silver/Bronze rank badges (1st, 2nd, 3rd)
 * - Trend indicators (↑/↓) with percentages
 * - Pixel bracket styling [CATEGORY_NAME]
 * - Pulsing glow on #1 category (the champion)
 */
@Composable
fun RetroTopCategoriesCard(
    categories: List<CategorySpending>,
    categoryTrends: Map<Long, CategoryTrendInfo> = emptyMap(),
    transactions: List<Expense> = emptyList(),
    modifier: Modifier = Modifier,
    onCategoryClick: ((CategorySpending) -> Unit)? = null,
    onViewAllTransactions: (() -> Unit)? = null
) {
    var selectedCategory by remember { mutableStateOf<CategorySpending?>(null) }
    
    selectedCategory?.let { category ->
        val trendInfo = categoryTrends[category.category.id]
        // Filter transactions for this category
        val categoryTransactions = transactions.filter { it.categoryId == category.category.id }
        RetroCategoryDetailDialog(
            category = category,
            trendInfo = trendInfo,
            transactions = categoryTransactions,
            onDismiss = { selectedCategory = null },
            onViewAll = onViewAllTransactions
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        RetroGameCardV2Categories(
            modifier = Modifier.fillMaxWidth(),
            borderColor = RetroColorsCategories.NeonPink,
            backgroundColor = RetroColorsCategories.DarkBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Arcade header
                RetroCategoriesHeader(categories.size)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Animated scanline separator
                AnimatedScanlineDividerCategories()
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Category rows with RPG bars
                categories.take(5).forEachIndexed { index, category ->
                    val trendInfo = categoryTrends[category.category.id]
                    RetroCategoryRow(
                        rank = index + 1,
                        category = category,
                        trendInfo = trendInfo,
                        isTopRank = index == 0,
                        onClick = { onCategoryClick?.invoke(category) ?: run { selectedCategory = category } }
                    )
                    if (index < categories.size - 1 && index < 4) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
        
        EnhancedScanlineOverlayCategories()
    }
}

@Composable
private fun RetroCategoriesHeader(totalCategories: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Pulsing trophy icon
        val infiniteTransition = rememberInfiniteTransition(label = "trophy_pulse")
        val iconGlow by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutLinearInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "trophy_pulse"
        )
        
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(RetroColorsCategories.NeonYellow.copy(alpha = 0.2f * iconGlow)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "★",
                color = RetroColorsCategories.NeonYellow.copy(alpha = iconGlow),
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
            text = "HIGH SCORES",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            ),
            color = RetroColorsCategories.NeonWhite.copy(alpha = flickerAlpha),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 15.sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Count badge
        Box(
            modifier = Modifier
                .background(RetroColorsCategories.DarkSurface)
                .border(1.5.dp, RetroColorsCategories.NeonPink)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "[${minOf(totalCategories, 5)}/$totalCategories]",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = RetroColorsCategories.NeonPink,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun RetroCategoryRow(
    rank: Int,
    category: CategorySpending,
    trendInfo: CategoryTrendInfo?,
    isTopRank: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "champion_pulse")
    
    // Champion glow animation for #1
    val championGlow by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "champion_glow"
    )
    
    val rowBorderColor = when {
        isTopRank -> RetroColorsCategories.NeonYellow.copy(alpha = championGlow)
        else -> RetroColorsCategories.DarkBorder
    }
    
    val rankColor = when (rank) {
        1 -> RetroColorsCategories.Gold
        2 -> RetroColorsCategories.Silver
        3 -> RetroColorsCategories.Bronze
        else -> RetroColorsCategories.NeonWhite.copy(alpha = 0.5f)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isTopRank) RetroColorsCategories.NeonYellow.copy(alpha = 0.05f)
                else Color.Transparent
            )
            .border(
                width = if (isTopRank) 1.5.dp else 1.dp,
                color = rowBorderColor
            )
            .padding(10.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Rank + Icon + Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Rank badge
                    Box(
                        modifier = Modifier.width(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (rank) {
                                1 -> "1ST"
                                2 -> "2ND"
                                3 -> "3RD"
                                else -> "${rank}TH"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = rankColor,
                            fontSize = if (rank <= 3) 11.sp else 9.sp,
                            letterSpacing = if (rank <= 3) 0.5.sp else 0.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Category icon in pixel frame
                    val categoryColor = try {
                        Color(android.graphics.Color.parseColor(category.category.color))
                    } catch (e: Exception) { RetroColorsCategories.NeonWhite }
                    
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(categoryColor.copy(alpha = 0.2f))
                            .border(1.5.dp, categoryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.category.icon,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Category name in brackets
                    Column {
                        Text(
                            text = "[${category.category.name.uppercase()}]",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = RetroColorsCategories.NeonWhite,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
                
                // Right: Amount + Trend
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Amount
                        Text(
                            text = "€${String.format("%.0f", category.total)}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = RetroColorsCategories.NeonWhite,
                            fontSize = 13.sp
                        )
                        
                        // Trend indicator
                        if (trendInfo != null && trendInfo.changePercent != null) {
                            val trendColor = when (trendInfo.direction) {
                                CategoryTrendDirection.UP_FAST, CategoryTrendDirection.UP ->
                                    RetroColorsCategories.NeonRed  // More spending = bad (red)
                                CategoryTrendDirection.DOWN_FAST, CategoryTrendDirection.DOWN ->
                                    RetroColorsCategories.NeonGreen  // Less spending = good (green)
                                else -> RetroColorsCategories.NeonWhite.copy(alpha = 0.5f)
                            }
                            
                            val trendSymbol = when (trendInfo.direction) {
                                CategoryTrendDirection.UP_FAST, CategoryTrendDirection.UP -> "▲"
                                CategoryTrendDirection.DOWN_FAST, CategoryTrendDirection.DOWN -> "▼"
                                else -> "◆"
                            }
                            
                            Text(
                                text = "$trendSymbol${String.format("%.0f", kotlin.math.abs(trendInfo.changePercent))}%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = trendColor,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // RPG Block Progress Bar
            RetroBlockProgressBar(
                percentage = category.percentage,
                color = try {
                    Color(android.graphics.Color.parseColor(category.category.color))
                } catch (e: Exception) { RetroColorsCategories.NeonWhite }
            )
        }
    }
}

@Composable
private fun RetroBlockProgressBar(
    percentage: Float,
    color: Color
) {
    val totalBlocks = 20
    val filledBlocks = (percentage / 100f * totalBlocks).toInt()
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        repeat(totalBlocks) { index ->
            val isFilled = index < filledBlocks
            val blockAlpha = if (isFilled) 0.9f else 0.15f
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(
                        if (isFilled) color.copy(alpha = blockAlpha)
                        else RetroColorsCategories.DarkBorder.copy(alpha = 0.3f)
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (isFilled) color.copy(alpha = 0.5f)
                        else RetroColorsCategories.DarkBorder
                    )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroCategoryDetailDialog(
    category: CategorySpending,
    trendInfo: CategoryTrendInfo?,
    transactions: List<Expense> = emptyList(),
    onDismiss: () -> Unit,
    onViewAll: (() -> Unit)? = null
) {
    var showTransactions by remember { mutableStateOf(true) }
    var visibleTransactionCount by remember { mutableStateOf(5) }
    
    val categoryColor = try {
        Color(android.graphics.Color.parseColor(category.category.color))
    } catch (e: Exception) { RetroColorsCategories.NeonWhite }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = RetroColorsCategories.DarkBackground,
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
                        .background(RetroColorsCategories.NeonPink.copy(alpha = 0.7f))
                        .border(1.dp, RetroColorsCategories.NeonPink)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RetroColorsCategories.DarkSurface)
                    .border(2.dp, categoryColor)
                    .padding(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[${category.category.name.uppercase()}]",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = categoryColor,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp
                        )
                        
                        Text(
                            text = "€${String.format("%.2f", category.total)}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = RetroColorsCategories.NeonWhite,
                            fontSize = 16.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Stats row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RetroStatBadge("PWR: ${String.format("%.0f", category.percentage)}%", categoryColor)
                        if (trendInfo?.changePercent != null) {
                            val trendSymbol = when (trendInfo.direction) {
                                CategoryTrendDirection.UP_FAST, CategoryTrendDirection.UP -> "▲"
                                CategoryTrendDirection.DOWN_FAST, CategoryTrendDirection.DOWN -> "▼"
                                else -> "◆"
                            }
                            val trendColor = when (trendInfo.direction) {
                                CategoryTrendDirection.UP_FAST, CategoryTrendDirection.UP ->
                                    RetroColorsCategories.NeonRed
                                CategoryTrendDirection.DOWN_FAST, CategoryTrendDirection.DOWN ->
                                    RetroColorsCategories.NeonGreen
                                else -> RetroColorsCategories.NeonWhite.copy(alpha = 0.5f)
                            }
                            RetroStatBadge(
                                "$trendSymbol ${String.format("%.1f", kotlin.math.abs(trendInfo.changePercent))}%",
                                trendColor
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Toggle buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RetroToggleButton(
                    text = "◄ TRANSACTIONS ►",
                    isSelected = showTransactions,
                    onClick = { showTransactions = true },
                    modifier = Modifier.weight(1f)
                )
                RetroToggleButton(
                    text = "◄ ANALYTICS ►",
                    isSelected = !showTransactions,
                    onClick = { showTransactions = false },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Content area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RetroColorsCategories.DarkSurface)
                    .border(1.dp, RetroColorsCategories.DarkBorder)
                    .padding(12.dp)
            ) {
                if (showTransactions) {
                    RetroTransactionsSection(
                        transactions = transactions,
                        visibleCount = visibleTransactionCount,
                        onShowMore = { visibleTransactionCount += 5 }
                    )
                } else {
                    RetroAnalyticsSection(
                        category = category,
                        trendInfo = trendInfo
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Close button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroColorsCategories.DarkSurface,
                    contentColor = RetroColorsCategories.NeonWhite
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, RetroColorsCategories.DarkBorder),
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
private fun RetroStatBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(RetroColorsCategories.DarkBackground)
            .border(1.dp, color)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = color,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RetroToggleButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) 
                RetroColorsCategories.NeonPink.copy(alpha = 0.2f)
            else 
                RetroColorsCategories.DarkSurface,
            contentColor = if (isSelected) 
                RetroColorsCategories.NeonPink
            else 
                RetroColorsCategories.NeonWhite.copy(alpha = 0.6f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) 
                RetroColorsCategories.NeonPink
            else 
                RetroColorsCategories.DarkBorder
        ),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun RetroTransactionsSection(
    transactions: List<Expense>,
    visibleCount: Int,
    onShowMore: () -> Unit
) {
    Column {
        Text(
            text = "◄ LAST HITS ►",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = RetroColorsCategories.NeonYellow,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        if (transactions.isEmpty()) {
            Text(
                text = "[NO TRANSACTIONS]",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                color = RetroColorsCategories.NeonWhite.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        } else {
            // Sort by date descending and take visible count
            val sortedTransactions = transactions.sortedByDescending { it.date }.take(visibleCount)
            
            sortedTransactions.forEach { expense ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> ${expense.merchant.take(20)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = RetroColorsCategories.NeonWhite.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "€${String.format("%.2f", expense.amount)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = RetroColorsCategories.NeonWhite,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "[${DateFormatterUtils.monthDayShort().format(Date(expense.date))}]",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = RetroColorsCategories.NeonWhite.copy(alpha = 0.5f),
                        fontSize = 9.sp
                    )
                }
            }
            
            if (transactions.size > visibleCount) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onShowMore,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = RetroColorsCategories.NeonCyan
                    )
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
                            "[SHOW NEXT 5]",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroAnalyticsSection(
    category: CategorySpending,
    trendInfo: CategoryTrendInfo?
) {
    Column {
        Text(
            text = "◄ PERFORMANCE ►",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = RetroColorsCategories.NeonYellow,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Current stats
        RetroStatRowCategories("THIS MONTH", "€${String.format("%.2f", category.total)}", RetroColorsCategories.NeonWhite)
        
        if (trendInfo?.previousTotal != null) {
            Spacer(modifier = Modifier.height(4.dp))
            val change = category.total - trendInfo.previousTotal
            val changeText = if (change >= 0) "+€${String.format("%.2f", change)}" else "-€${String.format("%.2f", kotlin.math.abs(change))}"
            val changeColor = if (change > 0) RetroColorsCategories.NeonRed else if (change < 0) RetroColorsCategories.NeonGreen else RetroColorsCategories.NeonWhite
            RetroStatRowCategories("LAST MONTH", "€${String.format("%.2f", trendInfo.previousTotal)}", RetroColorsCategories.NeonWhite.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(4.dp))
            RetroStatRowCategories("CHANGE", changeText, changeColor)
        }
        
        if (trendInfo?.averageOverMonths != null) {
            Spacer(modifier = Modifier.height(4.dp))
            RetroStatRowCategories("3-MO AVG", "€${String.format("%.2f", trendInfo.averageOverMonths)}", RetroColorsCategories.NeonCyan)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            RetroColorsCategories.NeonPink.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        // Trend direction
        val trendText = when (trendInfo?.direction) {
            CategoryTrendDirection.UP_FAST -> "▲▲ SPIKING"
            CategoryTrendDirection.UP -> "▲ RISING"
            CategoryTrendDirection.DOWN_FAST -> "▼▼ DROPPING"
            CategoryTrendDirection.DOWN -> "▼ FALLING"
            else -> "◆ STABLE"
        }
        val trendColor = when (trendInfo?.direction) {
            CategoryTrendDirection.UP_FAST, CategoryTrendDirection.UP -> RetroColorsCategories.NeonRed
            CategoryTrendDirection.DOWN_FAST, CategoryTrendDirection.DOWN -> RetroColorsCategories.NeonGreen
            else -> RetroColorsCategories.NeonWhite.copy(alpha = 0.7f)
        }
        
        RetroStatRowCategories("TREND", trendText, trendColor, isBold = true)
    }
}

@Composable
private fun RetroStatRowCategories(
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
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = color.copy(alpha = 0.8f),
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
            ),
            color = color,
            fontSize = 11.sp
        )
    }
}

/**
 * Data class for category trend information
 */
data class CategoryTrendInfo(
    val previousTotal: Double?,
    val changePercent: Float?,
    val direction: CategoryTrendDirection,
    val averageOverMonths: Double?,
    val monthsOfData: Int
)

// Color palette for categories card
private object RetroColorsCategories {
    val NeonGreen = Color(0xFF39FF14)
    val NeonYellow = Color(0xFFFFFF00)
    val NeonPink = Color(0xFFFF00FF)
    val NeonCyan = Color(0xFF00FFFF)
    val NeonRed = Color(0xFFFF0040)
    val NeonWhite = Color(0xFFF0F0F0)
    val Gold = Color(0xFFFFD700)
    val Silver = Color(0xFFC0C0C0)
    val Bronze = Color(0xFFCD7F32)
    val DarkBackground = Color(0xFF0D0D1A)
    val DarkSurface = Color(0xFF13132B)
    val DarkBorder = Color(0xFF1E1E3F)
    val Scanline = Color(0xFF000000)
}

@Composable
private fun RetroGameCardV2Categories(
    modifier: Modifier = Modifier,
    borderColor: Color,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(3.dp, borderColor)
            .border(1.dp, RetroColorsCategories.NeonWhite.copy(alpha = 0.2f))
            .padding(2.dp)
    ) {
        content()
    }
}

@Composable
private fun AnimatedScanlineDividerCategories() {
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
                        RetroColorsCategories.NeonPink.copy(alpha = alpha * 0.5f),
                        RetroColorsCategories.NeonPink.copy(alpha = alpha),
                        RetroColorsCategories.NeonPink.copy(alpha = alpha * 0.5f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun EnhancedScanlineOverlayCategories() {
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
                        RetroColorsCategories.Scanline.copy(alpha = 0.1f),
                        RetroColorsCategories.NeonPink.copy(alpha = 0.05f),
                        RetroColorsCategories.Scanline.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    startY = offset * 80,
                    endY = offset * 80 + 30
                )
            )
    )
}
