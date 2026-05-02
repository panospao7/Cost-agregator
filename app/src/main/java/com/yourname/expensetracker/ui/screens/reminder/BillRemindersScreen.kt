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
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bill reminders screen — displays upcoming bill reminders from [BillReminderManager].
 *
 * ⚠️ LEGACY PATH: This screen currently uses the older [BillReminderManager] which
 *    queries due reminders directly. It should be migrated to use
 *    [RecurringLifecycleCoordinator.getDueReminders] for consistent lifecycle-aware
 *    reminder generation across the app.
 *
 * TODO: Migrate from BillReminderManager to RecurringLifecycleCoordinator as the
 *       reminder source-of-truth. See [BillReminderManager] KDoc for migration notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillRemindersScreen(
    onNavigateBack: () -> Unit,
    viewModel: BillRemindersViewModel = hiltViewModel()
) {
    val reminders by viewModel.reminders.collectAsState()
    val monthlyTotal by viewModel.monthlyTotal.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bill_reminders_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
                MonthlyBillsCard(monthlyTotal, homeCurrency)
            }
            
            item {
                Text(
                    text = stringResource(R.string.bill_reminders_upcoming),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            items(reminders) { reminder ->
                BillReminderCard(
                    reminder = reminder,
                    homeCurrency = homeCurrency,
                    onMarkPaid = { viewModel.markBillPaid(reminder.recurringExpenseId) }
                )
            }
        }
    }
}

@Composable
private fun MonthlyBillsCard(total: Double, homeCurrency: String) {
    
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
                text = stringResource(R.string.bill_reminders_expected_monthly),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = CurrencyFormatter.format(total, homeCurrency),
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
    homeCurrency: String,
    onMarkPaid: () -> Unit
) {
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
                        val statusText = if (reminder.isOverdue) 
                            stringResource(R.string.bill_reminders_overdue)
                        else 
                            stringResource(R.string.bill_reminders_due_in_days_format, reminder.daysUntilDue)
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reminder.isOverdue || reminder.urgency == ReminderUrgency.CRITICAL)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Text(
                    text = CurrencyFormatter.format(reminder.amount, homeCurrency),
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
                    text = stringResource(R.string.bill_reminders_due_format, dateFormat.format(java.util.Date(reminder.dueDate))),
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (!reminder.isOverdue) {
                    Button(
                        onClick = onMarkPaid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text(stringResource(R.string.bill_reminders_mark_paid))
                    }
                } else {
                    Button(
                        onClick = onMarkPaid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.bill_reminders_pay_now))
                    }
                }
            }
        }
    }
}
