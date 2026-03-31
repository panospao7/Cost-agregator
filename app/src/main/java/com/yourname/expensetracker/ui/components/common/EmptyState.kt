package com.yourname.expensetracker.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Predefined empty state configurations for common use cases.
 */
enum class EmptyStateType(
    val icon: ImageVector,
    val defaultTitle: String,
    val defaultMessage: String
) {
    TRANSACTIONS(
        Icons.Default.ReceiptLong,
        "No Transactions",
        "You haven't recorded any expenses yet. Start tracking your spending!"
    ),
    RECEIPTS(
        Icons.Default.ShoppingCart,
        "No Receipts",
        "No receipts have been scanned yet. Scan your first receipt to get started."
    ),
    ANALYTICS(
        Icons.Default.TrendingUp,
        "No Data Yet",
        "Add some transactions to see your spending analytics and insights."
    ),
    CATEGORIES(
        Icons.Default.PieChart,
        "No Categories",
        "No category data available. Transactions will be categorized automatically."
    ),
    GENERIC(
        Icons.Default.Inbox,
        "Nothing Here",
        "There's nothing to display at the moment."
    )
}

/**
 * A reusable empty state component that displays a friendly message when there's no data.
 * 
 * @param type The predefined empty state type (icon, title, message)
 * @param title Custom title (overrides type default)
 * @param message Custom message (overrides type default)
 * @param actionLabel Text for the primary action button (null = no button)
 * @param actionIcon Icon for the action button (optional)
 * @param secondaryLabel Text for the secondary action button (null = no button)
 * @param onActionClick Callback when primary action is clicked
 * @param onSecondaryClick Callback when secondary action is clicked
 * @param modifier Modifier for the component
 */
@Composable
fun EmptyState(
    type: EmptyStateType = EmptyStateType.GENERIC,
    title: String? = null,
    message: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    secondaryLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayTitle = title ?: type.defaultTitle
    val displayMessage = message ?: type.defaultMessage
    val emptyContentDescription = stringResource(R.string.a11y_empty_state_format, displayTitle, displayMessage)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.Space24)
            .semantics { this.contentDescription = emptyContentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with glassmorphism styling
        Icon(
            imageVector = type.icon,
            contentDescription = null,
            modifier = Modifier
                .size(Dimens.IconXLarge)
                .alpha(0.6f),
            tint = SemanticColors.TextSecondary
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space24))
        
        // Title
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(Dimens.Space12))
        
        // Message
        Text(
            text = displayMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.8f)
        )
        
        // Action buttons
        if (actionLabel != null || secondaryLabel != null) {
            Spacer(modifier = Modifier.height(Dimens.Space32))
            
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                actionLabel?.let { label ->
                    Button(
                        onClick = { onActionClick?.invoke() },
                        modifier = Modifier
                            .height(Dimens.ButtonHeightMedium)
                            .fillMaxWidth(0.6f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SemanticColors.PrimaryIndigo
                        )
                    ) {
                        if (actionIcon != null) {
                            Icon(
                                imageVector = actionIcon,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.IconSmall)
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                
                secondaryLabel?.let { label ->
                    OutlinedButton(
                        onClick = { onSecondaryClick?.invoke() },
                        modifier = Modifier
                            .height(Dimens.ButtonHeightMedium)
                            .fillMaxWidth(0.6f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SemanticColors.TextSecondary
                        )
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = SemanticColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated version of EmptyState that fades in.
 */
@Composable
fun AnimatedEmptyState(
    visible: Boolean,
    type: EmptyStateType = EmptyStateType.GENERIC,
    title: String? = null,
    message: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    secondaryLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        EmptyState(
            type = type,
            title = title,
            message = message,
            actionLabel = actionLabel,
            actionIcon = actionIcon,
            secondaryLabel = secondaryLabel,
            onActionClick = onActionClick,
            onSecondaryClick = onSecondaryClick
        )
    }
}

// Previews
@Preview(showBackground = true)
@Composable
private fun EmptyStateTransactionsPreview() {
    ExpenseTrackerTheme {
        EmptyState(
            type = EmptyStateType.TRANSACTIONS,
            actionLabel = "Add Expense",
            onActionClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStateReceiptsPreview() {
    ExpenseTrackerTheme {
        EmptyState(
            type = EmptyStateType.RECEIPTS,
            actionLabel = "Scan Receipt",
            secondaryLabel = "Enter Manually",
            onActionClick = {},
            onSecondaryClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStateGenericPreview() {
    ExpenseTrackerTheme {
        EmptyState(
            type = EmptyStateType.GENERIC,
            title = "No Results",
            message = "Try adjusting your search or filters"
        )
    }
}
