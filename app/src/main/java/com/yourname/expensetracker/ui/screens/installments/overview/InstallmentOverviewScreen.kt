package com.yourname.expensetracker.ui.screens.installments.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.yourname.expensetracker.ui.screens.installments.create.InstallmentCreateScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.EmptyState
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.InlineErrorBanner
import com.yourname.expensetracker.ui.model.LoadableUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Installments overview (loan-installments S10a-1): remaining scheduled payments per currency,
 * the next due payment, payments awaiting confirmation, and the ACTIVE plan cards. Read-only.
 * Actions (record, link, skip, add plan) arrive with S10b. A plan card opens the read-only plan
 * detail and the top-bar History action opens the plan history (S10a-2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentOverviewScreen(
    onNavigateBack: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: InstallmentOverviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    // S10b-2: the create form is a full-screen overlay hosted here (no new navigation destination).
    var creating by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.installments_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.installments_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { creating = true }) {
                        Text(stringResource(R.string.installments_create_add_plan))
                    }
                    // History is reachable from the empty variants too, so finished plans are never hidden.
                    TextButton(onClick = onOpenHistory) {
                        Text(stringResource(R.string.installments_history_action))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val loadable = state.loadable) {
                is LoadableUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is LoadableUiState.Empty -> {
                    val finished = state.emptyKind == InstallmentOverviewEmptyKind.NO_ACTIVE_PLANS
                    EmptyState(
                        title = stringResource(
                            if (finished) R.string.installments_empty_finished_title else R.string.installments_empty_title
                        ),
                        message = loadable.reason.asString()
                    )
                }

                is LoadableUiState.Error -> ErrorState(
                    message = loadable.message.asString(),
                    onRetry = viewModel::retry
                )

                is LoadableUiState.Data -> OverviewContent(
                    content = loadable.value,
                    refreshFailed = state.refreshFailed,
                    onRetry = viewModel::retry,
                    onOpenPlan = onOpenPlan
                )
            }
        }
    }
    if (creating) {
        InstallmentCreateScreen(
            onClose = { creating = false },
            onCreated = { planId ->
                creating = false
                onOpenPlan(planId)
            }
        )
    }
}

@Composable
private fun OverviewContent(
    content: InstallmentOverviewContent,
    refreshFailed: Boolean,
    onRetry: () -> Unit,
    onOpenPlan: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (refreshFailed) {
            item { InlineErrorBanner(message = stringResource(R.string.installments_error_refresh), onRetry = onRetry) }
        }
        if (content.unreadablePlanCount > 0) {
            item { UnreadableNotice(content.unreadablePlanCount) }
        }
        if (content.remainingLines.isNotEmpty()) {
            item { RemainingCard(content.remainingLines) }
        }
        content.nextDueCard?.let { card ->
            item { NextDueCard(card) }
        }
        if (content.awaitingLines.isNotEmpty()) {
            item { AwaitingCard(content.awaitingLines) }
        }
        if (content.plans.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.installments_active_plans),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(content.plans, key = { it.planId }) { plan -> PlanCard(plan, onClick = { onOpenPlan(plan.planId) }) }
        }
    }
}

@Composable
private fun UnreadableNotice(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.installments_unreadable_plans, count, count),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
private fun RemainingCard(lines: List<InstallmentRemainingLineUi>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.installments_remaining_label),
                style = MaterialTheme.typography.titleMedium
            )
            // One line per currency present; never a combined total (INV-16).
            lines.forEach { line ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    // formatted already carries the currency symbol (CurrencyFormatter.formatMoney).
                    Text(line.remaining.formatted, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        pluralStringResource(R.plurals.installments_plan_count, line.planCount, line.planCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                text = stringResource(R.string.installments_remaining_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NextDueCard(card: InstallmentNextDueCardUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.installments_next_due), style = MaterialTheme.typography.titleMedium)
            Text(card.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.installments_next_line,
                    dueLabel(card.nextDue.dueState, card.nextDue.dueEpochDay),
                    card.nextDue.amount.formatted
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AwaitingCard(lines: List<InstallmentAwaitingLineUi>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.installments_needs_attention), style = MaterialTheme.typography.titleMedium)
            // AE-10b: due or overdue payments of confirm-mode plans are not counted until confirmed.
            lines.forEach { line ->
                Text(
                    // The amount string already carries the currency symbol; no code prefix.
                    text = pluralStringResource(
                        R.plurals.installments_awaiting_header,
                        line.count,
                        line.count,
                        line.amount.formatted
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlanCard(plan: InstallmentPlanCardUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(plan.displayName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.installments_status_active), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = stringResource(
                    when (plan.fulfilmentMode) {
                        InstallmentFulfilmentMode.AUTO_RECORD -> R.string.installments_mode_auto_label
                        InstallmentFulfilmentMode.CONFIRM -> R.string.installments_mode_confirm_label
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.installments_progress, plan.paidCount, plan.totalCount),
                style = MaterialTheme.typography.bodyMedium
            )
            plan.nextDue?.let { due ->
                Text(
                    text = stringResource(
                        R.string.installments_next_line,
                        dueLabel(due.dueState, due.dueEpochDay),
                        due.amount.formatted
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (plan.awaitingConfirmation) {
                Text(
                    text = stringResource(R.string.installments_awaiting_plan),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            plan.endEpochDay?.let { end ->
                Text(
                    text = stringResource(
                        R.string.installments_two_parts,
                        stringResource(R.string.installments_ends, formatDate(end)),
                        pluralStringResource(
                            R.plurals.installments_payments_left,
                            plan.scheduledCount,
                            plan.scheduledCount
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = stringResource(
                    R.string.installments_label_value,
                    stringResource(R.string.installments_remaining_label),
                    plan.remaining.formatted
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            // Unavailable is shown as the word, never as a zero amount (INV-17).
            val paidSoFar = when (val paid = plan.paidSoFar) {
                is InstallmentAmountUi.Money -> paid.display.formatted
                is InstallmentAmountUi.Unavailable -> stringResource(R.string.installments_unavailable)
            }
            Text(
                text = stringResource(R.string.installments_paid_so_far, paidSoFar),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun dueLabel(state: InstallmentDueState, dueEpochDay: Long): String = when (state) {
    InstallmentDueState.UPCOMING -> formatDate(dueEpochDay)
    InstallmentDueState.DUE -> stringResource(R.string.installments_due_today)
    InstallmentDueState.OVERDUE -> stringResource(R.string.installments_overdue_on, formatDate(dueEpochDay))
}

private fun formatDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
