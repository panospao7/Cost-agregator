package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun PeriodBlock(
    period: PeriodTotal,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currency: String = "EUR"
) {
    val backgroundColor = when (period.status) {
        PeriodStatus.UNDER_AVERAGE -> SemanticColors.SuccessGreen
        PeriodStatus.OVER_AVERAGE -> SemanticColors.DangerRed
        PeriodStatus.CURRENT -> SemanticColors.PrimaryIndigo
        PeriodStatus.NO_DATA -> SemanticColors.GlassBorder.copy(alpha = 0.3f)
    }

    val selectedModifier = if (isSelected) {
        Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor.copy(alpha = if (period.status == PeriodStatus.NO_DATA) 0.5f else 0.9f))
            .then(selectedModifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = period.periodLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = CurrencyFormatter.format(period.totalAmount, currency, showCents = false),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp
            )
        }
    }
}
