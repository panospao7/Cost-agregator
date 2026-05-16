package com.yourname.expensetracker.ui.screens.receiptmatching

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/** S7-028: Route entry point — owns Hilt injection. */
@Composable
fun ReceiptMatchingRoute(
    onNavigateBack: () -> Unit,
    viewModel: ReceiptMatchingViewModel = hiltViewModel()
) {
    ReceiptMatchingScreen(onNavigateBack = onNavigateBack, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptMatchingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReceiptMatchingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_receipt_matching)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.unmatchedReceipts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.isAutoMatching) viewModel.runAutoMatching() },
                    icon = {
                        if (state.isAutoMatching)
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        else Icon(Icons.Default.Search, null)
                    },
                    text = {
                        Text(
                            if (state.isAutoMatching) stringResource(R.string.receipt_auto_matching_running)
                            else stringResource(R.string.receipt_action_auto_match)
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // S7-005: Render error state
            state.error?.let { error ->
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(onClick = viewModel::clearError) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Status Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = stringResource(R.string.receipt_stat_unmatched),
                    value = state.unmatchedReceipts.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = stringResource(R.string.receipt_stat_suggestions),
                    value = state.pendingSuggestionCount.toString(),
                    modifier = Modifier.weight(1f),
                    isAlert = state.pendingSuggestionCount > 0
                )
                StatCard(
                    title = stringResource(R.string.receipt_stat_auto_matched),
                    value = state.autoMatchedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Suggestions List
            if (state.contentState is ReceiptMatchingContentState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.suggestedMatches.isEmpty() && state.unmatchedReceipts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.suggestedMatches.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.receipt_section_suggestions),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(state.suggestedMatches) { suggestion ->
                            MatchSuggestionCard(
                                suggestion = suggestion,
                                dateFormat = dateFormat,
                                isMutating = suggestion.receipt.id in state.mutatingReceiptIds,
                                onApprove = { viewModel.approveSuggestion(suggestion.receipt.id) },
                                onReject = { viewModel.rejectSuggestion(suggestion.receipt.id) }
                            )
                        }
                    }

                    if (state.unmatchedReceipts.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.receipt_section_unmatched_queue),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(state.unmatchedReceipts) { receipt ->
                            UnmatchedReceiptCard(
                                receipt = receipt,
                                dateFormat = dateFormat,
                                isMutating = receipt.id in state.mutatingReceiptIds,
                                onManualMatch = { viewModel.openManualMatch(receipt) },
                                onSkip = { viewModel.skipReceipt(receipt.id) },
                                onRerun = { viewModel.rerunForReceipt(receipt) }
                            )
                        }
                    }
                }
            }
        }
    }

    state.selectedReceiptForManualMatch?.let { receipt ->
        ManualMatchDialog(
            receipt = receipt,
            candidates = state.manualCandidates,
            dateFormat = dateFormat,
            onDismiss = { viewModel.closeManualMatch() },
            onSelectExpense = { expenseId -> viewModel.manualMatch(receipt.id, expenseId) }
        )
    }
}

@Composable
private fun UnmatchedReceiptCard(
    receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt,
    dateFormat: DateTimeFormatter,
    isMutating: Boolean = false,
    onManualMatch: () -> Unit,
    onSkip: () -> Unit,
    onRerun: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = receipt.parsedMerchant ?: stringResource(R.string.receipt_label_unknown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.receipt_receipt_total, receipt.parsedTotal?.let { com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(it, receipt.currency) } ?: stringResource(R.string.receipt_amount_unavailable)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = dateFormat.format(Instant.ofEpochMilli(receipt.parsedDate ?: receipt.createdAt).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onManualMatch, enabled = !isMutating, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Link, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.receipt_action_manual_match))
                }
                OutlinedButton(onClick = onSkip, enabled = !isMutating, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.SkipNext, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.receipt_action_skip))
                }
                OutlinedButton(onClick = onRerun, enabled = !isMutating, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.receipt_action_rerun))
                }
            }
        }
    }
}

@Composable
private fun ManualMatchDialog(
    receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt,
    candidates: List<com.yourname.expensetracker.data.database.entity.Expense>,
    dateFormat: DateTimeFormatter,
    onDismiss: () -> Unit,
    onSelectExpense: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.receipt_manual_match_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.receipt_manual_match_target,
                        receipt.parsedMerchant ?: stringResource(R.string.receipt_label_unknown),
                        receipt.parsedTotal?.let { com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(it, receipt.currency) } ?: stringResource(R.string.receipt_amount_unavailable)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.receipt_manual_match_no_candidates),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(candidates) { expense ->
                            OutlinedButton(
                                onClick = { onSelectExpense(expense.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = expense.merchant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        // S12-030: Use expense.currency for display
                                        text = "${CurrencyFormatter.formatMoney(expense.amount, expense.currency)} • ${dateFormat.format(Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    isAlert: Boolean = false
) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MatchSuggestionCard(
    suggestion: MatchSuggestion,
    dateFormat: DateTimeFormatter,
    isMutating: Boolean = false,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = suggestion.receipt.parsedMerchant ?: stringResource(R.string.receipt_label_unknown),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.receipt_receipt_total, suggestion.receipt.parsedTotal?.let { com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(it, suggestion.receipt.currency) } ?: stringResource(R.string.receipt_amount_unavailable)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${dateFormat.format(Instant.ofEpochMilli(suggestion.receipt.parsedDate ?: suggestion.receipt.createdAt).atZone(ZoneId.systemDefault()))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Badge(
                    containerColor = if (suggestion.confidence >= 0.90) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(stringResource(R.string.receipt_confidence_format, (suggestion.confidence * 100).toInt()))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Divider()
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.receipt_label_suggested_match),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        // S12-030: Use suggestion.expenseCurrency for display
                        text = stringResource(R.string.receipt_suggested_match_details,
                            suggestion.expenseMerchant ?: stringResource(R.string.receipt_label_unknown),
                            if (suggestion.expenseAmount != null && suggestion.expenseCurrency != null)
                                CurrencyFormatter.formatMoney(suggestion.expenseAmount, suggestion.expenseCurrency)
                            else suggestion.expenseAmount?.let { "%.2f".format(it) } ?: "—"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    enabled = !isMutating,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isMutating) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Default.Check, null); Spacer(modifier = Modifier.width(4.dp)); Text(stringResource(R.string.receipt_action_approve)) }
                }
                
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.receipt_action_reject))
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.receipt_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.receipt_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
