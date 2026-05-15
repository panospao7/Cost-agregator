package com.yourname.expensetracker.ui.components.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * A shimmer/skeleton loading effect for indicating content is loading.
 *
 * S2-009: Quiet by default — individual boxes do not announce to accessibility.
 * Parent containers should announce loading once via their own semantics.
 * S2-011: Uses MaterialTheme colors for dark-mode compatibility.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    // S2-011: Theme-aware defaults instead of SemanticColors
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    shimmerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    // S2-009: false by default — parent announces loading once
    announceLoading: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    
    val brush = Brush.linearGradient(
        colors = listOf(
            color,
            shimmerColor,
            color
        ),
        start = Offset(x = shimmerAnimation * 1000f, y = 0f),
        end = Offset(x = shimmerAnimation * 1000f + 200f, y = 0f)
    )
    
    val loadingContentDescription = stringResource(R.string.a11y_loading_content)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.SkeletonCornerRadius))
            .background(brush)
            .then(
                if (announceLoading) Modifier.semantics { contentDescription = loadingContentDescription }
                else Modifier.clearAndSetSemantics { }  // S2-009: decorative — no accessibility noise
            )
    )
}

/**
 * Skeleton for a transaction list item.
 */
@Composable
fun TransactionItemSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.SkeletonItemHeight),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(Dimens.CardCornerRadiusSmall)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.Space16),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon placeholder
            SkeletonBox(
                modifier = Modifier.size(40.dp),
                color = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f)
            )
            
            Spacer(modifier = Modifier.width(Dimens.Space16))
            
            // Title and subtitle
            Column(
                modifier = Modifier.weight(1f)
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .width(120.dp)
                        .height(16.dp)
                )
                Spacer(modifier = Modifier.height(Dimens.Space8))
                SkeletonBox(
                    modifier = Modifier
                        .width(80.dp)
                        .height(12.dp)
                )
            }
            
            // Amount placeholder
            SkeletonBox(
                modifier = Modifier
                    .width(60.dp)
                    .height(20.dp)
            )
        }
    }
}

/**
 * Skeleton for a dashboard card.
 */
@Composable
fun DashboardCardSkeleton(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(Dimens.CardCornerRadius)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.Space16)
        ) {
            // Header with icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    modifier = Modifier.size(Dimens.IconMedium),
                    color = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.width(Dimens.Space12))
                SkeletonBox(
                    modifier = Modifier
                        .width(100.dp)
                        .height(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(Dimens.Space16))
            
            // Main value
            SkeletonBox(
                modifier = Modifier
                    .width(120.dp)
                    .height(32.dp)
            )
            
            Spacer(modifier = Modifier.height(Dimens.Space8))
            
            // Subtitle
            SkeletonBox(
                modifier = Modifier
                    .width(80.dp)
                    .height(14.dp)
            )
        }
    }
}

/**
 * Skeleton for a chart/ graph placeholder.
 */
@Composable
fun ChartSkeleton(
    modifier: Modifier = Modifier,
    bars: Int = 7
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(Dimens.CardCornerRadius)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.Space16)
        ) {
            // Title
            SkeletonBox(
                modifier = Modifier
                    .width(150.dp)
                    .height(20.dp)
            )
            
            Spacer(modifier = Modifier.height(Dimens.Space24))
            
            // Chart bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(bars) { index ->
                    val heightFraction = (index + 1) / bars.toFloat()
                    SkeletonBox(
                        modifier = Modifier
                            .width(24.dp)
                            .fillMaxHeight(heightFraction)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

/**
 * Skeleton for receipt scanning screen.
 */
@Composable
fun ReceiptScanSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Receipt image placeholder
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
            color = SemanticColors.SurfaceLight.copy(alpha = 0.4f)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space24))
        
        // AI processing indicator
        SkeletonBox(
            modifier = Modifier
                .width(200.dp)
                .height(20.dp)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space16))
        
        // Merchant name
        SkeletonBox(
            modifier = Modifier
                .width(150.dp)
                .height(24.dp)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space8))
        
        // Total amount
        SkeletonBox(
            modifier = Modifier
                .width(100.dp)
                .height(32.dp)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space24))
        
        // Item list
        repeat(4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.Space8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp)
                )
                Spacer(modifier = Modifier.width(Dimens.Space16))
                SkeletonBox(
                    modifier = Modifier
                        .width(60.dp)
                        .height(16.dp)
                )
            }
        }
    }
}

/**
 * Full screen list skeleton with multiple items.
 */
@Composable
fun ListSkeleton(
    itemCount: Int = 5,
    modifier: Modifier = Modifier
) {
    val loadingDescription = stringResource(R.string.a11y_loading_content)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.Space16)
            .semantics { contentDescription = loadingDescription },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.Space12)
    ) {
        repeat(itemCount) {
            TransactionItemSkeleton()
        }
    }
}

/**
 * Skeleton for AI thinking/processing state.
 */
@Composable
fun AIProcessingSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Pulsing circle
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(50))
                .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            SkeletonBox(
                modifier = Modifier.size(48.dp),
                color = SemanticColors.PrimaryIndigo.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.height(Dimens.Space24))
        
        // Processing text placeholder
        SkeletonBox(
            modifier = Modifier
                .width(180.dp)
                .height(20.dp)
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space12))
        
        // Progress bar
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(4.dp)
        )
    }
}

// Previews
@Preview(showBackground = true)
@Composable
private fun TransactionItemSkeletonPreview() {
    ExpenseTrackerTheme {
        TransactionItemSkeleton(
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardCardSkeletonPreview() {
    ExpenseTrackerTheme {
        DashboardCardSkeleton(
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChartSkeletonPreview() {
    ExpenseTrackerTheme {
        ChartSkeleton(
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReceiptScanSkeletonPreview() {
    ExpenseTrackerTheme {
        ReceiptScanSkeleton(
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ListSkeletonPreview() {
    ExpenseTrackerTheme {
        ListSkeleton(itemCount = 3)
    }
}

@Preview(showBackground = true)
@Composable
private fun AIProcessingSkeletonPreview() {
    ExpenseTrackerTheme {
        AIProcessingSkeleton(
            modifier = Modifier.padding(16.dp)
        )
    }
}
