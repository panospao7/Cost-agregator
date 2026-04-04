package com.yourname.expensetracker.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.ai.AssistantResultCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(
    onDismiss: () -> Unit,
    onOpenTransactions: (com.yourname.expensetracker.ui.screens.transactions.TransactionFilter) -> Unit,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is AssistantNavigationEvent.OpenTransactions -> onOpenTransactions(event.filter)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge)
                }

                Row {
                    IconButton(onClick = { viewModel.clearSession() }) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.assistant_clear_session_cd))
                    }
                    if (uiState.canPersistHistory) {
                        IconButton(onClick = { viewModel.clearAllHistory() }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.assistant_clear_history_cd))
                        }
                    }
                }
            }

            if (uiState.isDisabled) {
                Text(
                    text = uiState.disabledReason ?: stringResource(R.string.assistant_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                uiState.runtimeStatusMessage?.let { runtimeMessage ->
                    Text(
                        text = runtimeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.runtimeDiagnostics?.let { diagnostics ->
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.messages.isEmpty()) {
                        item {
                            StarterPrompts(
                                onPromptSelected = viewModel::onSuggestionSelected
                            )
                        }
                    }

                    items(uiState.messages, key = {
                        when (it) {
                            is AssistantConversationItem.User -> it.id
                            is AssistantConversationItem.Result -> it.id
                            is AssistantConversationItem.Error -> it.id
                        }
                    }) { item ->
                        when (item) {
                            is AssistantConversationItem.User -> {
                                UserBubble(text = item.text)
                            }
                            is AssistantConversationItem.Result -> {
                                AssistantResultCard(
                                    result = item.result,
                                    canDrilldown = item.drilldownFilter != null,
                                    onOpenTransactions = {
                                        item.drilldownFilter?.let(viewModel::openDrilldown)
                                    },
                                    onClarificationSelected = viewModel::onClarificationSelected
                                )
                            }
                            is AssistantConversationItem.Error -> {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (uiState.isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text(stringResource(R.string.assistant_thinking), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                uiState.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { viewModel.retryLast() }) {
                        Text(stringResource(R.string.assistant_retry))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.input,
                        onValueChange = viewModel::updateInput,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.assistant_input_placeholder)) },
                        minLines = 1,
                        maxLines = 4
                    )
                    OutlinedButton(
                        onClick = { viewModel.submitQuery() },
                        enabled = uiState.input.isNotBlank() && !uiState.isLoading
                    ) {
                        Icon(Icons.Rounded.Send, contentDescription = stringResource(R.string.assistant_send_cd))
                    }
                }
            }
        }
    }
}

@Composable
private fun StarterPrompts(
    onPromptSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_try_asking), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { onPromptSelected("How much did I spend this month?") }, label = { Text(stringResource(R.string.assistant_suggestion_this_month)) })
            SuggestionChip(onClick = { onPromptSelected("Top merchants this month") }, label = { Text(stringResource(R.string.assistant_suggestion_top_merchants)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { onPromptSelected("Largest purchase this month") }, label = { Text(stringResource(R.string.assistant_suggestion_largest)) })
            SuggestionChip(onClick = { onPromptSelected("Show groceries this month") }, label = { Text(stringResource(R.string.assistant_suggestion_groceries)) })
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            modifier = Modifier
                .padding(start = 48.dp)
                .padding(10.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
