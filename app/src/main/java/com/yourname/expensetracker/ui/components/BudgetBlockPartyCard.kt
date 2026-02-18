package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.util.Date

enum class BlockStatus {
    UNDER_BUDGET, // Time to Party (Green)
    OVER_BUDGET,  // Party Pooper (Red)
    FUTURE,       // TBD (Gray)
    TODAY,        // Active (Blue)
    BILL_DAY,     // Bills (White Outline)
    NO_DATA       // No spending recorded yet (Gray)
}

data class DayBudgetStatus(
    val dayOfMonth: Int,
    val date: Long,
    val actualSpent: Double,
    val targetBudget: Double,
    val isToday: Boolean,
    val status: BlockStatus,
    // Drill-Down Data
    val baseTarget: Double = 0.0,
    val recurringImpact: Double = 0.0,
    val plannedImpact: Double = 0.0,
    val recurringItems: List<String> = emptyList(),
    val plannedItems: List<String> = emptyList(),
    val topTransactions: List<com.yourname.expensetracker.data.database.entity.Expense> = emptyList()
)

@Composable
fun BudgetBlockPartyCard(
    days: List<DayBudgetStatus>,
    modifier: Modifier = Modifier
) {
    var selectedDay by remember { mutableStateOf<DayBudgetStatus?>(null) }

    if (selectedDay != null) {
        DayAtAGlanceDialog(
            day = selectedDay!!,
            onDismiss = { selectedDay = null }
        )
    }

    BentoCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        Text(
            "BUDGET BLOCK PARTY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            days.chunked(7).forEach { week ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    week.forEach { day ->
                         Box(modifier = Modifier.weight(1f)) {
                             DayBlock(day, onClick = { selectedDay = day })
                         }
                    }
                    // Fill remaining space if last week is short
                    if (week.size < 7) {
                        repeat(7 - week.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayBlock(day: DayBudgetStatus, onClick: () -> Unit) {
    val isBillDay = day.status == BlockStatus.BILL_DAY
    val color = when (day.status) {
        BlockStatus.UNDER_BUDGET -> SemanticColors.SuccessGreen
        BlockStatus.OVER_BUDGET -> SemanticColors.DangerRed
        BlockStatus.TODAY -> SemanticColors.PrimaryIndigo
        BlockStatus.FUTURE -> SemanticColors.GlassBorder.copy(alpha = 0.5f)
        BlockStatus.BILL_DAY -> Color.Transparent
        BlockStatus.NO_DATA -> SemanticColors.GlassBorder.copy(alpha = 0.3f)
    }

    val borderModifier = if (isBillDay) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
    } else Modifier

    Box(
        modifier = Modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (day.status == BlockStatus.FUTURE) 0.2f else if (isBillDay) 0f else 0.9f))
            .then(borderModifier)
            .clickable(enabled = day.status != BlockStatus.FUTURE, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (day.status != BlockStatus.FUTURE) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                if (isBillDay) {
                     Text(
                        text = "💸",
                        fontSize = 8.sp,
                        modifier = Modifier.alpha(0.8f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayAtAGlanceDialog(
    day: DayBudgetStatus,
    onDismiss: () -> Unit
) {
    val dateStr = DateFormatterUtils.monthDay().format(Date(day.date))

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateStr.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (day.status == BlockStatus.UNDER_BUDGET) "Under Budget" else "Over Budget",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (day.status == BlockStatus.UNDER_BUDGET) SemanticColors.SuccessGreen else SemanticColors.DangerRed,
                        fontWeight = FontWeight.Black
                    )
                }
                
                // Balance badge
                val balance = day.targetBudget - day.actualSpent
                val balanceColor = if (balance >= 0) SemanticColors.SuccessGreen else SemanticColors.DangerRed
                Surface(
                    color = balanceColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = (if (balance >= 0) "+" else "") + "€${String.format("%.2f", balance)}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = balanceColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 🎯 Target Breakdown
            Text("TARGET BREAKDOWN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Base Allowance", color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.baseTarget)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                    }
                    if (day.recurringImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recurring (${day.recurringItems.joinToString(", ")})", color = SemanticColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("+€${String.format("%.2f", day.recurringImpact)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        }
                    }
                    if (day.plannedImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Planned (${day.plannedItems.joinToString(", ")})", color = SemanticColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("+€${String.format("%.2f", day.plannedImpact)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SemanticColors.GlassBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Target", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.targetBudget)}", fontWeight = FontWeight.Bold, color = SemanticColors.PrimaryIndigo)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // 💸 Actual Spending
            Text("WHAT HAPPENED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Spent", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.actualSpent)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (day.topTransactions.isNotEmpty()) {
                        day.topTransactions.forEach { exp ->
                             Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(exp.merchant, color = SemanticColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("€${String.format("%.2f", exp.amount)}", color = SemanticColors.TextPrimary, fontSize = 13.sp)
                            }
                        }
                    } else if (day.actualSpent > 0) {
                        Text("No specific transactions found.", style = MaterialTheme.typography.bodySmall, color = SemanticColors.TextSecondary)
                    } else {
                        Text("No spending recorded.", style = MaterialTheme.typography.bodySmall, color = SemanticColors.TextSecondary)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onDismiss, // Ideally navigate to transactions filtered by day, but that requires hoisting nav logic. Keep simple for now.
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.GlassSurface, contentColor = SemanticColors.TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, SemanticColors.GlassBorder)
            ) {
                Text("Close")
            }
        }
    }
}
