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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.annotation.StringRes
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

/**
 * Predefined empty state configurations for common use cases.
 */
enum class EmptyStateType(
    val icon: ImageVector,
    @StringRes val titleResId: Int,
    @StringRes val messageResId: Int
) {
    TRANSACTIONS(
        Icons.Default.ReceiptLong,
        R.string.empty_title_transactions,
        R.string.empty_message_transactions
    ),
    RECEIPTS(
        Icons.Default.ShoppingCart,
        R.string.empty_title_receipts,
        R.string.empty_message_receipts
    ),
    ANALYTICS(
        Icons.Default.TrendingUp,
        R.string.empty_title_analytics,
        R.string.empty_message_analytics
    ),
    CATEGORIES(
        Icons.Default.PieChart,
        R.string.empty_title_categories,
        R.string.empty_message_categories
    ),
    GENERIC(
        Icons.Default.Inbox,
        R.string.empty_title_generic,
        R.string.empty_message_generic
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
    EnhancedEmptyState(
        type = type,
        title = title,
        message = message,
        actions = emptyList(),
        onActionClick = null,
        onDismissAction = null,
        actionLabel = actionLabel,
        actionIcon = actionIcon,
        secondaryLabel = secondaryLabel,
        onPrimaryClick = onActionClick,
        onSecondaryClick = onSecondaryClick,
        modifier = modifier
    )
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
