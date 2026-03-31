package com.yourname.expensetracker.ui.screens.receiptmatching

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptMatchingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReceiptMatchingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt Matching") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.unmatchedReceipts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.runAutoMatching() },
                    icon = { Icon(Icons.Default.Search, null) },
                    text = { Text("Auto-Match") }
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
            // Status Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Unmatched",
                    value = state.unmatchedReceipts.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Suggestions",
                    value = state.pendingSuggestionCount.toString(),
                    modifier = Modifier.weight(1f),
                    isAlert = state.pendingSuggestionCount > 0
                )
                StatCard(
                    title = "Auto-Matched",
                    value = state.autoMatchedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Suggestions List
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.suggestedMatches.isEmpty()) {
                EmptyState()
            } else {
                Text(
                    text = "Match Suggestions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.suggestedMatches) { suggestion ->
                        MatchSuggestionCard(
                            suggestion = suggestion,
                            dateFormat = dateFormat,
                            onApprove = { viewModel.approveSuggestion(suggestion.receipt.id) },
                            onReject = { viewModel.rejectSuggestion(suggestion.receipt.id) }
                        )
                    }
                }
            }
        }
    }
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
    dateFormat: SimpleDateFormat,
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
                        text = suggestion.receipt.parsedMerchant ?: "Unknown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Receipt: €${String.format("%.2f", suggestion.receipt.parsedTotal ?: 0.0)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${dateFormat.format(Date(suggestion.receipt.parsedDate ?: suggestion.receipt.createdAt))}",
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
                    Text("${(suggestion.confidence * 100).toInt()}%")
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
                        text = "Suggested Match:",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "${suggestion.expenseMerchant ?: "Unknown"} - €${String.format("%.2f", suggestion.expenseAmount ?: 0.0)}",
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
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve")
                }
                
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject")
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
                text = "No match suggestions",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Scan receipts to see match suggestions here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
