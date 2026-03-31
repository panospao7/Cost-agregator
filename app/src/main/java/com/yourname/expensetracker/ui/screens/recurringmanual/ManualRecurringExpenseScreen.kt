@file:OptIn(ExperimentalMaterial3Api::class)

package com.yourname.expensetracker.ui.screens.recurringmanual

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRecurringExpenseScreen(
    onNavigateBack: () -> Unit,
    viewModel: ManualRecurringExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<ManualRecurringExpense?>(null) }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Recurring Expenses",
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = SemanticColors.PrimaryIndigo
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Recurring Expense")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
                    }
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.refresh() }
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Summary Cards
                        item {
                            SummaryCards(uiState)
                        }
                        
                        // Total Monthly Card
                        item {
                            TotalMonthlyCard(totalMonthly = uiState.totalMonthly)
                        }
                        
                        // Active Expenses Header
                        val activeExpenses = uiState.recurringExpenses.filter { it.isActive }
                        if (activeExpenses.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Active Recurring Expenses (${activeExpenses.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            items(activeExpenses, key = { it.id }) { expense ->
                                RecurringExpenseCard(
                                    expense = expense,
                                    onToggleStatus = { viewModel.toggleStatus(it.id, it.isActive) },
                                    onDelete = { showDeleteConfirm = it },
                                    onMarkPaid = { viewModel.markAsPaid(it) }
                                )
                            }
                        }
                        
                        // Inactive Expenses
                        val inactiveExpenses = uiState.recurringExpenses.filter { !it.isActive }
                        if (inactiveExpenses.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Inactive (${inactiveExpenses.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                            
                            items(inactiveExpenses, key = { it.id }) { expense ->
                                RecurringExpenseCard(
                                    expense = expense,
                                    onToggleStatus = { viewModel.toggleStatus(it.id, it.isActive) },
                                    onDelete = { showDeleteConfirm = it },
                                    onMarkPaid = null
                                )
                            }
                        }
                        
                        if (uiState.recurringExpenses.isEmpty()) {
                            item {
                                EmptyState()
                            }
                        }
                        
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
        
        // Add Dialog
        if (showAddDialog) {
            AddRecurringExpenseDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { merchant, amount, frequency, nextDate, note ->
                    viewModel.addRecurringExpense(merchant, amount, frequency, nextDate, note)
                    showAddDialog = false
                }
            )
        }
        
        // Delete Confirmation
        showDeleteConfirm?.let { expense ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text("Delete Recurring Expense?") },
                text = { Text("Are you sure you want to delete ${expense.merchant}?") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteExpense(expense.id)
                            showDeleteConfirm = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SummaryCards(uiState: ManualRecurringExpenseUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = "Active",
            value = uiState.activeCount.toString(),
            icon = Icons.Rounded.Repeat,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(1f)
        )
        
        SummaryCard(
            title = "Due Soon",
            value = uiState.upcomingCount.toString(),
            icon = Icons.Rounded.Schedule,
            color = Color(0xFFFF9800),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary
            )
        }
    }
}

@Composable
private fun TotalMonthlyCard(totalMonthly: Double) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Monthly Total",
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = currencyFormat.format(totalMonthly),
                style = MaterialTheme.typography.headlineSmall,
                color = SemanticColors.PrimaryIndigo,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RecurringExpenseCard(
    expense: ManualRecurringExpense,
    onToggleStatus: (ManualRecurringExpense) -> Unit,
    onDelete: (ManualRecurringExpense) -> Unit,
    onMarkPaid: ((ManualRecurringExpense) -> Unit)?
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    val isUpcoming = expense.nextDate <= System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expense.isActive) {
                if (isUpcoming) {
                    Color(0xFFFF9800).copy(alpha = 0.15f)
                } else {
                    SemanticColors.SurfaceLight.copy(alpha = 0.5f)
                }
            } else {
                SemanticColors.SurfaceLight.copy(alpha = 0.3f)
            }
        )
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = expense.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (expense.isActive) {
                            SemanticColors.TextPrimary
                        } else {
                            SemanticColors.TextSecondary
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    expense.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextSecondary
                        )
                    }
                }
                
                Switch(
                    checked = expense.isActive,
                    onCheckedChange = { onToggleStatus(expense) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currencyFormat.format(expense.amount)} ${expense.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary
                )
                
                if (isUpcoming && expense.isActive) {
                    Text(
                        text = "Due ${dateFormat.format(Date(expense.nextDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "Next: ${dateFormat.format(Date(expense.nextDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
            
            if (expense.isActive && onMarkPaid != null) {
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = { onMarkPaid(expense) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mark as Paid")
                }
            }
            
            if (!expense.isActive) {
                TextButton(
                    onClick = { onDelete(expense) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Rounded.Delete, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun AddRecurringExpenseDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double, RecurrenceFrequency, Long, String?) -> Unit
) {
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var note by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var nextDate by remember { mutableStateOf(System.currentTimeMillis()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Recurring Expense") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant/Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Frequency
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = frequency.name.lowercase().replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Frequency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        RecurrenceFrequency.values().forEach { freq ->
                            DropdownMenuItem(
                                text = { Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    frequency = freq
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                // Next Date
                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                OutlinedTextField(
                    value = dateFormat.format(Date(nextDate)),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Next Due Date") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Rounded.CalendarToday, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (Optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amount.toDoubleOrNull()?.let { amt ->
                        onAdd(merchant, amt, frequency, nextDate, note.takeIf { it.isNotBlank() })
                    }
                },
                enabled = merchant.isNotBlank() && amount.toDoubleOrNull() != null
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
    
    // Date Picker
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = nextDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { nextDate = it }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Repeat,
            contentDescription = null,
            tint = SemanticColors.TextSecondary,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "No recurring expenses",
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Tap + to add rent, utilities, or other recurring payments",
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = SemanticColors.PrimaryIndigo
            )
        ) {
            Text("Try Again")
        }
    }
}