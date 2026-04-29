package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors

data class CategorySpending(
    val name: String,
    val icon: String,
    val color: Color,
    val amount: Double,
    val percentage: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBreakdownSheet(
    periodLabel: String,
    categories: List<CategoryBreakdown>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    currency: String = "EUR"
) {
    val displayCategories = categories.take(5)
    val hasMore = categories.size > 5

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SemanticColors.BaseNavy,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.category_breakdown_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary
                )
                Text(
                    text = periodLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            displayCategories.forEach { breakdown ->
                val color = try {
                    Color(android.graphics.Color.parseColor(breakdown.category.color))
                } catch (_: Exception) {
                    Color.Gray
                }
            CategoryRow(
                category = CategorySpending(
                    name = breakdown.category.name,
                    icon = breakdown.category.icon,
                    color = color,
                    amount = breakdown.totalAmount,
                    percentage = (breakdown.percentageOfTotal / 100.0).toFloat()
                ),
                currency = currency
            )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (hasMore) {
                OutlinedButton(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.TextSecondary
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        SemanticColors.GlassBorder
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.category_breakdown_show_all_format, categories.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.GlassSurface,
                    contentColor = SemanticColors.TextPrimary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SemanticColors.GlassBorder)
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun CategoryRow(category: CategorySpending, currency: String = "EUR") {
    Card(
        colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(category.color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.icon,
                            fontSize = 16.sp
                        )
                    }
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = SemanticColors.TextPrimary
                    )
                }

                Text(
                    text = CurrencyFormatter.format(category.amount, currency),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = SemanticColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { category.percentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = category.color,
                trackColor = SemanticColors.GlassBorder,
            )
        }
    }
}
