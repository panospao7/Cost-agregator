package com.yourname.expensetracker.ui.screens.bank

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankConnectionsScreen(
    onNavigateBack: () -> Unit,
    onAddConnection: () -> Unit,
    viewModel: BankConnectionsViewModel = hiltViewModel()
) {
    val connections by viewModel.connections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank Connections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onAddConnection) {
                        Icon(Icons.Default.Add, "Connect Bank")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (connections.isEmpty()) {
                EmptyBankConnectionsView(onAddConnection)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(connections) { connection ->
                        BankConnectionCard(
                            connection = connection,
                            onSync = { viewModel.syncConnection(connection.id) },
                            onDisconnect = { viewModel.disconnect(connection.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBankConnectionsView(onAddConnection: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No Bank Connections",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Connect your bank accounts to automatically import transactions",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onAddConnection) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connect Bank")
        }
    }
}

@Composable
private fun BankConnectionCard(
    connection: BankConnection,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = connection.bankName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${connection.countryCode} • ${connection.syncFrequency.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                ConnectionStatusIcon(connection.isConnected, connection.lastSyncStatus)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            connection.lastSync?.let { lastSync ->
                Text(
                    text = "Last synced: ${dateFormat.format(Date(lastSync))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            if (!connection.lastError.isNullOrEmpty()) {
                Text(
                    text = "Error: ${connection.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (connection.isConnected) {
                    OutlinedButton(onClick = onSync) {
                        Icon(Icons.Default.Sync, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Now")
                    }
                }
                
                TextButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (connection.isConnected) "Disconnect" else "Remove")
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusIcon(isConnected: Boolean, syncStatus: SyncStatus) {
    when {
        isConnected && syncStatus == SyncStatus.SUCCESS -> {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Connected",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
        isConnected && syncStatus == SyncStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Sync Failed",
                tint = MaterialTheme.colorScheme.error
            )
        }
        isConnected -> {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = "Syncing",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Disconnected",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
