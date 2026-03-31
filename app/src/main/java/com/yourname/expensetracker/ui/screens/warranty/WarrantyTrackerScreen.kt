package com.yourname.expensetracker.ui.screens.warranty

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarrantyTrackerScreen(
    onNavigateBack: () -> Unit,
    viewModel: WarrantyTrackerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Warranty Tracker") },
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    title = "Active",
                    value = state.activeCount.toString(),
                    icon = Icons.Default.Shield,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    title = "Expiring Soon",
                    value = state.expiringSoonCount.toString(),
                    icon = Icons.Default.Shield,
                    modifier = Modifier.weight(1f),
                    isAlert = state.expiringSoonCount > 0
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Protected Value
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Protected Value",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "€${String.format("%.2f", state.totalProtectedValue)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedFilter == null,
                    onClick = { viewModel.filterByStatus(null) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = state.selectedFilter == WarrantyStatus.ACTIVE,
                    onClick = { viewModel.filterByStatus(WarrantyStatus.ACTIVE) },
                    label = { Text("Active") }
                )
                FilterChip(
                    selected = state.selectedFilter == WarrantyStatus.EXPIRED,
                    onClick = { viewModel.filterByStatus(WarrantyStatus.EXPIRED) },
                    label = { Text("Expired") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Warranty List
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.warranties.isEmpty()) {
                EmptyWarrantyState()
            } else {
                val filteredWarranties = state.selectedFilter?.let { filter ->
                    state.warranties.filter { it.status == filter }
                } ?: state.warranties

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredWarranties) { warranty ->
                        WarrantyCard(
                            warranty = warranty,
                            dateFormat = dateFormat,
                            onMarkClaimed = { viewModel.markAsClaimed(warranty.id) },
                            onDelete = { viewModel.deleteWarranty(warranty) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
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
private fun WarrantyCard(
    warranty: Warranty,
    dateFormat: SimpleDateFormat,
    onMarkClaimed: () -> Unit,
    onDelete: () -> Unit
) {
    val daysRemaining = (warranty.warrantyEndDate - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)
    val isExpiringSoon = daysRemaining in 0..30
    val isExpired = daysRemaining < 0

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
                        text = warranty.productName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = warranty.merchantName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (isExpiringSoon) {
                    Badge(
                        containerColor = if (isExpired) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            if (isExpired) "EXPIRED" else "${daysRemaining}d left",
                            color = if (isExpired) 
                                MaterialTheme.colorScheme.onError 
                            else 
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Expires: ${dateFormat.format(Date(warranty.warrantyEndDate))}",
                style = MaterialTheme.typography.bodyMedium
            )

            warranty.supportPhone?.let {
                Text(
                    text = "Support: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (warranty.status == WarrantyStatus.ACTIVE) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onMarkClaimed) {
                        Text("Mark Claimed")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWarrantyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No warranties tracked",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Scan receipts to automatically detect warranties",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
