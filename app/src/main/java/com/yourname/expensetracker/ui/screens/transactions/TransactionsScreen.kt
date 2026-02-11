package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import androidx.compose.material.icons.filled.Repeat
import java.util.Locale

@Composable
fun RecurrencePickerDialog(
    onDismiss: () -> Unit,
    onFrequencySelected: (RecurrenceFrequency) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Frequency") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(RecurrenceFrequency.values()) { frequency ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFrequencySelected(frequency) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = frequency.name.replace("_", " ").lowercase()
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    var expenseToRecurring by remember { mutableStateOf<Expense?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") }
            )
        }
    ) { padding ->
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No transactions found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Parsed expenses will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = transactions,
                    key = { it.expense.id },
                    contentType = { "transaction" }
                ) { item ->
                    TransactionItem(
                        transaction = item,
                        onDelete = { expenseToDelete = item.expense },
                        onEditCategory = { expenseToCategorize = item.expense },
                        onMarkRecurring = { expenseToRecurring = item.expense }
                    )
                }
            }
        }

        // ... Existing Dialogs ...
        if (expenseToDelete != null) {
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text("Delete Transaction") },
                text = { Text("Are you sure you want to delete this transaction from ${expenseToDelete?.merchant}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            expenseToDelete?.let { viewModel.deleteExpense(it) }
                            expenseToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { expenseToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Recurrence Picker Dialog
        if (expenseToRecurring != null) {
            RecurrencePickerDialog(
                onDismiss = { expenseToRecurring = null },
                onFrequencySelected = { frequency ->
                    expenseToRecurring?.let { viewModel.markAsRecurring(it, frequency) }
                    expenseToRecurring = null
                }
            )
        }

        // Category Selection Dialog
        if (expenseToCategorize != null) {
            CategoryPickerDialog(
                categories = categories,
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId) }
                    expenseToCategorize = null
                }
            )
        }
    }
}

@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onCategorySelected: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(category.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(category.icon, modifier = Modifier.padding(end = 12.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TransactionItem(
    transaction: ExpenseWithCategory,
    onDelete: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkRecurring: () -> Unit
) {
    val expense = transaction.expense
    val category = transaction.category

    val categoryColor = Color(transaction.categoryColor.toInt())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon (Clickable to change)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = categoryColor,
                        shape = CircleShape
                    )
                    .clickable { onEditCategory() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.icon ?: "❓",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.merchant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (expense.isManualEntry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("✏️", fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val methodIcon = when(expense.paymentMethod) {
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CASH -> "💵"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.BANK_TRANSFER -> "🏦"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD -> "💳"
                        else -> ""
                    }
                    if (methodIcon.isNotEmpty()) {
                        Text(methodIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = category?.name ?: "Uncategorized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onEditCategory() }
                    )
                }
                Text(
                    text = transaction.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Amount
            Text(
                text = transaction.formattedAmount,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )

            // Recurring Action
            IconButton(onClick = onMarkRecurring) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = "Mark as Recurring",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Delete Action
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
