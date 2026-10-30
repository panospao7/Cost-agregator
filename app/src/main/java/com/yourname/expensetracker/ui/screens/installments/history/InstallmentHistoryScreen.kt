package com.yourname.expensetracker.ui.screens.installments.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.EmptyState
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.InlineErrorBanner
import com.yourname.expensetracker.ui.model.LoadableUiState
import com.yourname.expensetracker.ui.screens.installments.formatInstallmentDate
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi

/**
 * Plan history (loan-installments S10a-2): COMPLETED, CANCELLED, REFUNDED and PAID_OFF plans with
 * dates, counts, total counted and financing cost. Read-only; a card opens the read-only plan detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentHistoryScreen(
    onNavigateBack: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    viewModel: InstallmentHistoryViewModel = hiltViewModel()
) {
    // The ViewModel is activity-scoped (no NavHost): collect only while this screen is composed.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.installments_history_title)) },
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

                is LoadableUiState.Empty -> EmptyState(
                    title = stringResource(R.string.installments_history_empty_title),
                    message = loadable.reason.asString()
                )

                is LoadableUiState.Error -> ErrorState(
                    message = loadable.message.asString(),
                    onRetry = viewModel::retry
                )

                is LoadableUiState.Data -> HistoryContent(
                    content = loadable.value,
                    selected = state.filter,
                    refreshFailed = state.refreshFailed,
                    onSelectFilter = viewModel::selectFilter,
                    onRetry = viewModel::retry,
                    onOpenPlan = onOpenPlan
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    content: InstallmentHistoryContent,
    selected: InstallmentHistoryFilter,
    refreshFailed: Boolean,
    onSelectFilter: (InstallmentHistoryFilter) -> Unit,
    onRetry: () -> Unit,
    onOpenPlan: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (refreshFailed) {
            item { InlineErrorBanner(message = stringResource(R.string.installments_error_refresh_history), onRetry = onRetry) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InstallmentHistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == selected,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(stringResource(filterLabel(filter))) }
                    )
                }
            }
        }
        if (content.cards.isEmpty()) {
            item {
                Text(
                    text = stringResource(filteredEmptyText(selected)),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            items(content.cards, key = { it.planId }) { card -> HistoryCard(card, onOpenPlan) }
        }
    }
}

private fun filterLabel(filter: InstallmentHistoryFilter): Int = when (filter) {
    InstallmentHistoryFilter.ALL -> R.string.installments_filter_all
    InstallmentHistoryFilter.COMPLETED -> R.string.installments_status_completed
    InstallmentHistoryFilter.CANCELLED -> R.string.installments_status_cancelled
    InstallmentHistoryFilter.REFUNDED -> R.string.installments_status_refunded
    InstallmentHistoryFilter.PAID_OFF -> R.string.installments_status_paid_off
}

private fun filteredEmptyText(filter: InstallmentHistoryFilter): Int = when (filter) {
    // ALL with no cards cannot happen (that is the Empty state); the text is a safe fallback.
    InstallmentHistoryFilter.ALL -> R.string.installments_history_empty_body
    InstallmentHistoryFilter.COMPLETED -> R.string.installments_history_empty_completed
    InstallmentHistoryFilter.CANCELLED -> R.string.installments_history_empty_cancelled
    InstallmentHistoryFilter.REFUNDED -> R.string.installments_history_empty_refunded
    InstallmentHistoryFilter.PAID_OFF -> R.string.installments_history_empty_paid_off
}

private fun statusLabel(status: InstallmentPlanStatus): Int = when (status) {
    // ACTIVE plans are never in History; the label is a defensive fallback.
    InstallmentPlanStatus.ACTIVE -> R.string.installments_status_active
    InstallmentPlanStatus.COMPLETED -> R.string.installments_status_completed
    InstallmentPlanStatus.CANCELLED -> R.string.installments_status_cancelled
    InstallmentPlanStatus.REFUNDED -> R.string.installments_status_refunded
    InstallmentPlanStatus.PAID_OFF -> R.string.installments_status_paid_off
}

@Composable
private fun HistoryCard(card: InstallmentHistoryCardUi, onOpenPlan: (Long) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenPlan(card.planId) }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // A redacted restore can leave the plan name blank (INV-34): show a fixed generic label.
                Text(
                    text = card.displayName.takeIf { it.isNotBlank() } ?: stringResource(R.string.installments_plan_unnamed),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(statusLabel(card.finalStatus)), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = stringResource(
                    R.string.installments_two_parts,
                    stringResource(
                        R.string.installments_history_range,
                        dateOrUnavailable(card.startEpochDay),
                        dateOrUnavailable(card.endEpochDay)
                    ),
                    card.currencyCode
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            countsText(card)?.let { counts ->
                Text(text = counts, style = MaterialTheme.typography.bodyMedium)
            }
            if (card.paidBeforeTrackingCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.installments_history_before_tracking,
                        card.paidBeforeTrackingCount,
                        card.paidBeforeTrackingCount
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // Unavailable is shown as the word, never as a zero amount (INV-17). A refunded plan's
            // total is a real zero and is shown with its reason.
            Text(
                text = stringResource(R.string.installments_history_total, amountText(card.totalCounted)),
                style = MaterialTheme.typography.bodyMedium
            )
            if (card.finalStatus == InstallmentPlanStatus.REFUNDED) {
                Text(
                    text = stringResource(R.string.installments_history_refunded_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.installments_financing_cost, amountText(card.financingCost)),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Counts line, e.g. "3 paid · 9 cancelled" (AE-7). Each count is its own plural, zero counts are
 * hidden, and the parts are joined with the [R.string.installments_two_parts] format string (no
 * concatenation, so the order stays RTL-safe). Null when every count is zero.
 */
@Composable
private fun countsText(card: InstallmentHistoryCardUi): String? {
    val parts = buildList {
        if (card.paidCount > 0) {
            add(pluralStringResource(R.plurals.installments_history_count_paid, card.paidCount, card.paidCount))
        }
        if (card.skippedCount > 0) {
            add(pluralStringResource(R.plurals.installments_history_count_skipped, card.skippedCount, card.skippedCount))
        }
        if (card.cancelledCount > 0) {
            add(pluralStringResource(R.plurals.installments_history_count_cancelled, card.cancelledCount, card.cancelledCount))
        }
        if (card.reversedCount > 0) {
            add(pluralStringResource(R.plurals.installments_history_count_refunded, card.reversedCount, card.reversedCount))
        }
    }
    if (parts.isEmpty()) return null
    var line = parts.first()
    for (next in parts.drop(1)) {
        line = stringResource(R.string.installments_two_parts, line, next)
    }
    return line
}

@Composable
private fun dateOrUnavailable(epochDay: Long?): String =
    epochDay?.let(::formatInstallmentDate) ?: stringResource(R.string.installments_unavailable)

@Composable
private fun amountText(amount: InstallmentAmountUi): String = when (amount) {
    is InstallmentAmountUi.Money -> amount.display.formatted
    is InstallmentAmountUi.Unavailable -> stringResource(R.string.installments_unavailable)
}
