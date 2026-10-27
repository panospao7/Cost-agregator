package com.yourname.expensetracker.ui.screens.installments.detail

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.InlineErrorBanner
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi

/**
 * Plan detail (loan-installments S10a-2): header, progress, remaining scheduled payments, the
 * per-entry schedule with status labels, and payments awaiting confirmation. Read-only: row
 * actions (Record, Link, Skip), edit and end flows arrive with S10b.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentPlanDetailScreen(
    planId: Long,
    onNavigateBack: () -> Unit,
    viewModel: InstallmentPlanDetailViewModel = hiltViewModel(key = "installment_plan_$planId")
) {
    LaunchedEffect(planId) { viewModel.start(planId) }
    // The ViewModel is activity-scoped (no NavHost): stop its collection when the screen leaves.
    DisposableEffect(planId) { onDispose { viewModel.stop() } }
    val state by viewModel.state.collectAsState()
    val loaded = (state.loadable as? LoadableUiState.Data)?.value
    // A redacted restore can leave the plan name blank (INV-34): show a fixed generic label.
    val loadedName = loaded?.displayName?.takeIf { it.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loadedName ?: stringResource(R.string.installments_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.installments_back)
                        )
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

                // Detail has no Empty state: a plan that cannot be read is NOT_FOUND.
                is LoadableUiState.Empty -> Unit

                is LoadableUiState.Error -> ErrorState(
                    message = loadable.message.asString(),
                    // NOT_FOUND offers Back (top bar) only; a retry cannot make a deleted plan appear.
                    onRetry = if (state.errorCode == InstallmentDetailErrorCode.NOT_FOUND) null else viewModel::retry
                )

                is LoadableUiState.Data -> DetailContent(
                    content = loadable.value,
                    refreshFailed = state.refreshFailed,
                    onRetry = viewModel::retry
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    content: InstallmentPlanDetailContent,
    refreshFailed: Boolean,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (refreshFailed) {
            item { InlineErrorBanner(message = stringResource(R.string.installments_error_refresh_detail), onRetry = onRetry) }
        }
        item { HeaderCard(content) }
        statusBanner(content.status)?.let { banner ->
            item { Text(stringResource(banner), style = MaterialTheme.typography.bodyMedium) }
        }
        if (content.awaitingCount > 0 && content.awaitingAmount != null) {
            item { AwaitingCard(content) }
        }
        item {
            Text(
                text = stringResource(R.string.installments_schedule),
                style = MaterialTheme.typography.titleMedium
            )
        }
        items(content.entries, key = { it.entryId }) { entry -> EntryRow(entry) }
    }
}

private fun statusBanner(status: InstallmentPlanStatus): Int? = when (status) {
    InstallmentPlanStatus.ACTIVE -> null
    InstallmentPlanStatus.COMPLETED -> R.string.installments_banner_completed
    InstallmentPlanStatus.CANCELLED,
    InstallmentPlanStatus.REFUNDED,
    InstallmentPlanStatus.PAID_OFF -> R.string.installments_banner_ended
}

private fun statusLabel(status: InstallmentPlanStatus): Int = when (status) {
    InstallmentPlanStatus.ACTIVE -> R.string.installments_status_active
    InstallmentPlanStatus.COMPLETED -> R.string.installments_status_completed
    InstallmentPlanStatus.CANCELLED -> R.string.installments_status_cancelled
    InstallmentPlanStatus.REFUNDED -> R.string.installments_status_refunded
    InstallmentPlanStatus.PAID_OFF -> R.string.installments_status_paid_off
}

@Composable
private fun HeaderCard(content: InstallmentPlanDetailContent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = content.displayName.takeIf { it.isNotBlank() } ?: stringResource(R.string.installments_plan_unnamed),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(statusLabel(content.status)), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = stringResource(
                    R.string.installments_two_parts,
                    stringResource(
                        when (content.kind) {
                            InstallmentPlanKind.PURCHASE_ATTACHED -> R.string.installments_kind_purchase
                            InstallmentPlanKind.STANDALONE -> R.string.installments_kind_standalone
                        }
                    ),
                    content.currencyCode
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content.lenderLabel?.let { lender ->
                Text(
                    text = stringResource(R.string.installments_lender, lender),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(
                    when (content.fulfilmentMode) {
                        InstallmentFulfilmentMode.AUTO_RECORD -> R.string.installments_mode_auto_label
                        InstallmentFulfilmentMode.CONFIRM -> R.string.installments_mode_confirm_label
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.installments_progress, content.paidCount, content.totalCount),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = pluralStringResource(
                    R.plurals.installments_payments_left,
                    content.scheduledCount,
                    content.scheduledCount
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    R.string.installments_label_value,
                    stringResource(R.string.installments_remaining_label),
                    content.remaining.formatted
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = content.endEpochDay?.let { stringResource(R.string.installments_ends, formatInstallmentDate(it)) }
                    ?: stringResource(R.string.installments_end_date_unavailable),
                style = MaterialTheme.typography.bodyMedium
            )
            // Unavailable is shown as the word, never as a zero amount (INV-17).
            Text(
                text = stringResource(R.string.installments_paid_so_far, amountText(content.paidSoFar)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.installments_financing_cost, amountText(content.financingCost)),
                style = MaterialTheme.typography.bodyMedium
            )
            content.financingNote?.let { note ->
                Text(
                    text = stringResource(
                        when (note) {
                            InstallmentFinancingNoteUi.NEEDS_SAME_CURRENCY_PURCHASE ->
                                R.string.installments_financing_needs_purchase
                            InstallmentFinancingNoteUi.NOT_AVAILABLE_FOR_STATUS ->
                                R.string.installments_financing_not_for_status
                            InstallmentFinancingNoteUi.PAYMENT_UNREADABLE ->
                                R.string.installments_financing_payment_unreadable
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AwaitingCard(content: InstallmentPlanDetailContent) {
    val amount = content.awaitingAmount ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.installments_needs_attention), style = MaterialTheme.typography.titleMedium)
            // AE-10b: due or overdue payments of a confirm-mode plan are not counted until confirmed.
            Text(
                text = pluralStringResource(
                    R.plurals.installments_awaiting_header,
                    content.awaitingCount,
                    content.awaitingCount,
                    amount.formatted
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun EntryRow(entry: InstallmentEntryRowUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(
                        R.string.installments_two_parts,
                        stringResource(R.string.installments_entry_number, entry.seq),
                        formatInstallmentDate(entry.dueEpochDay)
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(entry.amount.formatted, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = entryLabel(entry),
                style = MaterialTheme.typography.bodyMedium
            )
            entry.reason?.let { reason ->
                Text(
                    text = stringResource(reasonText(reason)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.awaitingConfirmation) {
                Text(
                    text = stringResource(R.string.installments_awaiting_plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            entry.originalAmount?.let { original ->
                Text(
                    text = stringResource(R.string.installments_entry_was, original.formatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (val variance = entry.variance) {
                null -> Unit
                is InstallmentAmountUi.Unavailable -> Text(
                    text = stringResource(R.string.installments_entry_paid_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                // A zero variance is already mapped to null by the mapper.
                is InstallmentAmountUi.Money -> Text(
                    text = stringResource(R.string.installments_entry_differs, variance.display.formatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun entryLabel(entry: InstallmentEntryRowUi): String = stringResource(
    when (entry.label) {
        InstallmentEntryLabelUi.UPCOMING -> R.string.installments_entry_upcoming
        InstallmentEntryLabelUi.DUE_TODAY -> R.string.installments_due_today
        InstallmentEntryLabelUi.OVERDUE -> R.string.installments_entry_overdue
        InstallmentEntryLabelUi.PAID -> R.string.installments_entry_paid
        InstallmentEntryLabelUi.SKIPPED -> R.string.installments_entry_skipped
        InstallmentEntryLabelUi.CANCELLED -> R.string.installments_entry_cancelled
        InstallmentEntryLabelUi.REFUNDED -> R.string.installments_entry_refunded
        InstallmentEntryLabelUi.PAID_BEFORE_TRACKING -> R.string.installments_entry_paid_before_tracking
        InstallmentEntryLabelUi.PAYOFF -> R.string.installments_entry_payoff
    }
)

private fun reasonText(reason: InstallmentEntryReasonUi): Int = when (reason) {
    InstallmentEntryReasonUi.SKIP_LENDER_HOLIDAY -> R.string.installments_reason_skip_lender_holiday
    InstallmentEntryReasonUi.SKIP_WAIVED -> R.string.installments_reason_skip_waived
    InstallmentEntryReasonUi.SKIP_OTHER -> R.string.installments_reason_skip_other
    InstallmentEntryReasonUi.PLAN_STOPPED -> R.string.installments_reason_stopped
    InstallmentEntryReasonUi.PLAN_REFUNDED -> R.string.installments_reason_refunded
    InstallmentEntryReasonUi.PAID_OFF_EARLY -> R.string.installments_reason_paid_off_early
    InstallmentEntryReasonUi.SHORTENED -> R.string.installments_reason_shortened
    InstallmentEntryReasonUi.EXPENSE_REMOVED -> R.string.installments_reason_expense_removed
}

@Composable
private fun amountText(amount: InstallmentAmountUi): String = when (amount) {
    is InstallmentAmountUi.Money -> amount.display.formatted
    is InstallmentAmountUi.Unavailable -> stringResource(R.string.installments_unavailable)
}
