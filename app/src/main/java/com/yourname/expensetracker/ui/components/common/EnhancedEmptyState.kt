package com.yourname.expensetracker.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.theme.Dimens
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Enhanced empty state component that displays contextual action chips.
 *
 * This component extends the base EmptyState with the ability to display
 * multiple contextual actions as dismissible chips, providing users with
 * clear next steps when encountering empty states.
 *
 * @param type The predefined empty state type (icon, title, message)
 * @param title Custom title (overrides type default)
 * @param message Custom message (overrides type default)
 * @param actions List of contextual actions to display as chips
 * @param onActionClick Callback when an action chip is clicked (returns the action)
 * @param onDismissAction Callback when an action chip is dismissed (returns the action id)
 * @param actionLabel Text for the primary action button (null = no button)
 * @param actionIcon Icon for the action button (optional)
 * @param secondaryLabel Text for the secondary action button (null = no button)
 * @param onPrimaryClick Callback when primary action is clicked
 * @param onSecondaryClick Callback when secondary action is clicked
 * @param modifier Modifier for the component
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnhancedEmptyState(
    type: EmptyStateType = EmptyStateType.GENERIC,
    title: String? = null,
    message: String? = null,
    actions: List<EmptyStateAction> = emptyList(),
    onActionClick: ((EmptyStateAction) -> Unit)? = null,
    onDismissAction: ((String) -> Unit)? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    secondaryLabel: String? = null,
    onPrimaryClick: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayTitle = title ?: stringResource(type.titleResId)
    val displayMessage = message ?: stringResource(type.messageResId)
    val emptyContentDescription = stringResource(
        R.string.a11y_empty_state_format,
        displayTitle,
        displayMessage
    )

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

        // Contextual Action Chips
        if (actions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Dimens.Space32))

            Text(
                text = stringResource(R.string.empty_state_suggested_actions),
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TextSecondary,
                modifier = Modifier.padding(bottom = Dimens.Space12)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(0.9f),
                horizontalArrangement = Arrangement.spacedBy(
                    Dimens.Space8,
                    Alignment.CenterHorizontally
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
                maxItemsInEachRow = 3
            ) {
                actions.forEach { action ->
                    ActionChip(
                        action = action,
                        onClick = { onActionClick?.invoke(action) },
                        onDismiss = { onDismissAction?.invoke(action.id) }
                    )
                }
            }
        }

        // Legacy action buttons (for backward compatibility)
        if (actionLabel != null || secondaryLabel != null) {
            Spacer(modifier = Modifier.height(Dimens.Space32))

            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                actionLabel?.let { label ->
                    Button(
                        onClick = { onPrimaryClick?.invoke() },
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
 * A single action chip that displays an action with icon, title, and optional dismiss.
 */
@Composable
private fun ActionChip(
    action: EmptyStateAction,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    ElevatedAssistChip(
        onClick = onClick,
        label = {
            Text(
                text = action.title,
                style = MaterialTheme.typography.labelMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            // Dismiss button (small x icon)
            Surface(
                onClick = onDismiss,
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_action),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = AssistChipDefaults.elevatedAssistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = SemanticColors.PrimaryIndigo
        ),
        elevation = AssistChipDefaults.elevatedAssistChipElevation(
            elevation = 2.dp
        )
    )
}

/**
 * Animated version of EnhancedEmptyState that fades in.
 */
@Composable
fun AnimatedEnhancedEmptyState(
    visible: Boolean,
    type: EmptyStateType = EmptyStateType.GENERIC,
    title: String? = null,
    message: String? = null,
    actions: List<EmptyStateAction> = emptyList(),
    onActionClick: ((EmptyStateAction) -> Unit)? = null,
    onDismissAction: ((String) -> Unit)? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    secondaryLabel: String? = null,
    onPrimaryClick: (() -> Unit)? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        EnhancedEmptyState(
            type = type,
            title = title,
            message = message,
            actions = actions,
            onActionClick = onActionClick,
            onDismissAction = onDismissAction,
            actionLabel = actionLabel,
            actionIcon = actionIcon,
            secondaryLabel = secondaryLabel,
            onPrimaryClick = onPrimaryClick,
            onSecondaryClick = onSecondaryClick
        )
    }
}

// Previews
@Preview(showBackground = true)
@Composable
private fun EnhancedEmptyStateWithActionsPreview() {
    ExpenseTrackerTheme {
        EnhancedEmptyState(
            type = EmptyStateType.TRANSACTIONS,
            actions = listOf(
                EmptyStateAction(
                    id = "scan_receipt",
                    title = "Scan Receipt",
                    description = "Scan a receipt to add expenses",
                    icon = Icons.Default.Check,
                    action = EmptyStateActionType.OpenFeature("scan_receipt"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "add_manual",
                    title = "Add Manually",
                    description = "Add an expense manually",
                    icon = Icons.Default.Check,
                    action = EmptyStateActionType.OpenFeature("add_expense"),
                    priority = 5
                )
            ),
            onActionClick = {},
            onDismissAction = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EnhancedEmptyStateGenericPreview() {
    ExpenseTrackerTheme {
        EnhancedEmptyState(
            type = EmptyStateType.GENERIC,
            title = "No Results",
            message = "Try adjusting your search or filters",
            actionLabel = "Clear Filters",
            onPrimaryClick = {}
        )
    }
}
