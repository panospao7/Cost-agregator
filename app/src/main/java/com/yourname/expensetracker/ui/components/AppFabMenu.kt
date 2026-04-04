package com.yourname.expensetracker.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.Dimens

@Composable
fun AppFabMenu(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onAddExpense: () -> Unit,
    onScanReceipt: () -> Unit,
    onRecurringExpenses: () -> Unit,
    onDismissRequest: (() -> Unit)? = null,
    showScrimWhenExpanded: Boolean = true,
    scrimColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
    modifier: Modifier = Modifier
) {
    val dismissMenu = {
        if (expanded) {
            onDismissRequest?.invoke() ?: onExpandToggle()
        }
    }
    val scrimInteractionSource = remember { MutableInteractionSource() }

    BackHandler(enabled = expanded) {
        dismissMenu()
    }

    Box(modifier = modifier) {
        if (expanded && showScrimWhenExpanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(scrimColor)
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null
                    ) { dismissMenu() }
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.add_expense_scan_receipt),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        FloatingActionButton(
                            onClick = onScanReceipt,
                            modifier = Modifier.size(Dimens.TouchTargetMin),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.Receipt, contentDescription = stringResource(R.string.add_expense_scan_receipt))
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.recurring_title),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        FloatingActionButton(
                            onClick = onRecurringExpenses,
                            modifier = Modifier.size(Dimens.TouchTargetMin),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Icon(Icons.Default.Repeat, contentDescription = stringResource(R.string.a11y_recurring_expenses))
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.add_expense_title),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        FloatingActionButton(
                            onClick = onAddExpense,
                            modifier = Modifier.size(Dimens.TouchTargetMin),
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.a11y_add_expense))
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = onExpandToggle,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (expanded) stringResource(R.string.a11y_close) else stringResource(R.string.action_add)
                )
            }
        }
    }
}
