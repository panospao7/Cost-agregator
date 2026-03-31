package com.yourname.expensetracker.ui.screens.reminder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.reminder.BillReminder
import com.yourname.expensetracker.domain.reminder.ReminderUrgency
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillRemindersScreen(
    onNavigateBack: () -> Unit,
    viewModel: BillRemindersViewModel = hiltViewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    val monthlyTotal by viewModel.monthlyTotal.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bill Reminders") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MonthlyBillsCard(monthlyTotal)
            }
            
            item {
                Text(
                    text = "Upcoming Bills",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            items(reminders) { reminder ->
                BillReminderCard(
                    reminder = reminder,
                    onMarkPaid = { viewModel.markBillPaid(reminder.recurringExpenseId) }
                )
            }
        }
    }
}

@Composable
private fun MonthlyBillsCard(total: Double) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Expected Monthly Bills",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = currencyFormat.format(total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun BillReminderCard(
    reminder: BillReminder,
    onMarkPaid: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (reminder.urgency) {
                ReminderUrgency.CRITICAL -> MaterialTheme.colorScheme.errorContainer
                ReminderUrgency.URGENT -> MaterialTheme.colorScheme.tertiaryContainer
                ReminderUrgency.WARNING -> MaterialTheme.colorScheme.secondaryContainer
                ReminderUrgency.INFO -> MaterialTheme.colorScheme.surfaceContainer
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
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Payment,
                        contentDescription = null,
                        tint = when (reminder.urgency) {
                            ReminderUrgency.CRITICAL -> MaterialTheme.colorScheme.error
                            ReminderUrgency.URGENT -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = reminder.merchant,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (reminder.isOverdue) 
                                "⚠️ Overdue!" 
                            else 
                                "Due in ${reminder.daysUntilDue} days",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reminder.isOverdue || reminder.urgency == ReminderUrgency.CRITICAL)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Text(
                    text = currencyFormat.format(reminder.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Due: ${dateFormat.format(java.util.Date(reminder.dueDate))}",
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (!reminder.isOverdue) {
                    Button(
                        onClick = onMarkPaid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Mark Paid")
                    }
                } else {
                    Button(
                        onClick = onMarkPaid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Warning, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pay Now")
                    }
                }
            }
        }
    }
}
