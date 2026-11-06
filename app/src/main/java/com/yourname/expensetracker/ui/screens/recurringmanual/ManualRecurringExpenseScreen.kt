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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.screens.subscription.SubscriptionTotalStatus
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.util.UiTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRecurringExpenseScreen(
    onNavigateBack: () -> Unit,
    viewModel: ManualRecurringExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    val suggestionsState by viewModel.paymentLinkSuggestions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<ManualRecurringExpense?>(null) }
    var markPaidTarget by remember { mutableStateOf<ManualRecurringExpense?>(null) }

    // Action failures/info (add, toggle, delete, mark paid, already recorded) are transient:
    // snackbar while the list stays visible. Only load failures use the full-screen ErrorState.
    val snackbarHostState = remember { SnackbarHostState() }
    val actionMessageText = uiState.actionMessage?.asString()
    LaunchedEffect(uiState.actionMessage) {
        if (actionMessageText != null) {
            snackbarHostState.showSnackbar(actionMessageText)
            viewModel.consumeActionMessage()
        }
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.recurring_title),
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.recurring_add_title))
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
                uiState.error != null || uiState.errorText != null -> {
                    ErrorState(
                        message = uiState.errorText?.asString() ?: uiState.error.orEmpty(),
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
                            // INV-14: the label comes from the same code that decided inclusion,
                            // never from the separate homeCurrency flow above.
                            TotalMonthlyCard(
                                totalMonthly = uiState.totalMonthly,
                                totalCurrency = uiState.totalCurrency,
                                totalStatus = uiState.totalStatus,
                                excludedRuleCount = uiState.excludedRuleCount
                            )
                        }
                        
                        // FC-PAID-UNLINKED: an unreadable suggestion result is said once, never shown as
                        // "no suggestions". Loading, None and Content render nothing here.
                        if (suggestionsState is PaymentLinkSuggestionsUiState.Unavailable) {
                            item(key = "payment_link_suggestions_unavailable") {
                                PaymentLinkSuggestionsUnavailableLine()
                            }
                        }

                        val activeExpenses = uiState.recurringExpenses.filter { it.isActive }
                        if (activeExpenses.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.header_active_recurring, activeExpenses.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            items(activeExpenses, key = { it.id }) { expense ->
                                RecurringExpenseCard(
                                    expense = expense,
                                    homeCurrency = homeCurrency,
                                    referenceNowMillis = uiState.referenceNowMillis,
                                    onToggleStatus = { viewModel.toggleStatus(it.id, it.isActive) },
                                    onDelete = { showDeleteConfirm = it },
                                    // IRREGULAR rules have no scheduled occurrences: no mark-paid action.
                                    onMarkPaid = if (supportsMarkPaid(expense)) {
                                        { markPaidTarget = it }
                                    } else {
                                        null
                                    },
                                    isMarkPaidInFlight = expense.id in uiState.inFlightRuleIds,
                                    suggestions = if (supportsMarkPaid(expense)) {
                                        (suggestionsState as? PaymentLinkSuggestionsUiState.Content)
                                            ?.byRuleId?.get(expense.id).orEmpty()
                                    } else {
                                        emptyList()
                                    },
                                    onLinkSuggestion = { viewModel.linkSuggestedPayment(it) },
                                    onDeclineSuggestion = { viewModel.declineSuggestedPayment(it) }
                                )
                            }
                        }
                        
                        // Inactive Expenses
                        val inactiveExpenses = uiState.recurringExpenses.filter { !it.isActive }
                        if (inactiveExpenses.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.header_inactive_recurring, inactiveExpenses.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                            
                            items(inactiveExpenses, key = { it.id }) { expense ->
                                RecurringExpenseCard(
                                    expense = expense,
                                    homeCurrency = homeCurrency,
                                    referenceNowMillis = uiState.referenceNowMillis,
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
                referenceNowMillis = uiState.referenceNowMillis,
                onDismiss = { showAddDialog = false },
                onAdd = { merchant, amount, frequency, nextDate, note ->
                    viewModel.addRecurringExpense(merchant, amount, frequency, nextDate, note)
                    showAddDialog = false
                }
            )
        }
        
        // Mark paid choice (R21 S2): create the expense, or just record that it was already recorded.
        markPaidTarget?.let { expense ->
            // The expense is created in the RULE's currency, so the dialog shows that currency.
            val amountText = CurrencyFormatter.formatMoney(expense.amount, expense.currency)
            AlertDialog(
                onDismissRequest = { markPaidTarget = null },
                title = { Text(stringResource(R.string.recurring_mark_paid_dialog_title)) },
                text = {
                    Text(stringResource(R.string.recurring_mark_paid_dialog_body, expense.merchant, amountText))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.markAsPaid(expense)
                            markPaidTarget = null
                        }
                    ) {
                        Text(stringResource(R.string.recurring_mark_paid_creates_expense))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.markAlreadyRecorded(expense)
                            markPaidTarget = null
                        }
                    ) {
                        Text(stringResource(R.string.recurring_mark_already_recorded))
                    }
                }
            )
        }

        // Delete Confirmation
        showDeleteConfirm?.let { expense ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text(stringResource(R.string.recurring_delete_confirm_title)) },
                text = { Text(stringResource(R.string.recurring_delete_confirm_message, expense.merchant)) },
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
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text(stringResource(R.string.action_cancel))
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
            title = stringResource(R.string.label_active),
            value = uiState.activeCount.toString(),
            icon = Icons.Rounded.Repeat,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(1f)
        )
        
        SummaryCard(
            title = stringResource(R.string.label_due_soon),
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
private fun TotalMonthlyCard(
    totalMonthly: Double?,
    totalCurrency: String?,
    totalStatus: SubscriptionTotalStatus,
    excludedRuleCount: Int
) {

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
                text = stringResource(R.string.label_monthly_total),
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // INV-13: an unavailable total is never rendered as an amount (no 0).
            if (totalStatus == SubscriptionTotalStatus.UNAVAILABLE || totalMonthly == null || totalCurrency == null) {
                Text(
                    text = stringResource(R.string.recurring_total_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.TextSecondary
                )
            } else {
                Text(
                    text = CurrencyFormatter.formatMoney(totalMonthly, totalCurrency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )

                // INV-12: left-out rules are counted, not named; the notice carries only the count.
                if (totalStatus == SubscriptionTotalStatus.PARTIAL && excludedRuleCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.money_total_partial_notice,
                            excludedRuleCount,
                            excludedRuleCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurringExpenseCard(
    expense: ManualRecurringExpense,
    homeCurrency: String,
    referenceNowMillis: Long,
    onToggleStatus: (ManualRecurringExpense) -> Unit,
    onDelete: (ManualRecurringExpense) -> Unit,
    onMarkPaid: ((ManualRecurringExpense) -> Unit)?,
    isMarkPaidInFlight: Boolean = false,
    // FC-PAID-UNLINKED: ordered by due date; empty for inactive and IRREGULAR rules.
    suggestions: List<PaymentLinkSuggestionUi> = emptyList(),
    onLinkSuggestion: (PaymentLinkSuggestionUi) -> Unit = {},
    onDeclineSuggestion: (PaymentLinkSuggestionUi) -> Unit = {}
) {
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
    val isUpcoming = expense.nextDate <= referenceNowMillis + (7 * TimePeriodUtils.DAY_IN_MILLIS)
    
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
                    // The amount is stored in the RULE's currency (not necessarily the home currency).
                    text = "${CurrencyFormatter.formatMoney(expense.amount, expense.currency)} ${expense.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary
                )
                
                if (isUpcoming && expense.isActive) {
                    Text(
                        text = stringResource(R.string.label_due_format, dateFormat.format(Instant.ofEpochMilli(expense.nextDate).atZone(ZoneId.systemDefault()))),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.label_next_format, dateFormat.format(Instant.ofEpochMilli(expense.nextDate).atZone(ZoneId.systemDefault()))),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
            
            // FC-PAID-UNLINKED: one block per suggestion, between the amount/due line and Mark as Paid.
            // While a call for this rule is in flight the block buttons and Mark as Paid are disabled.
            if (expense.isActive && onMarkPaid != null) {
                suggestions.forEach { suggestion ->
                    Spacer(modifier = Modifier.height(8.dp))
                    PaymentLinkSuggestionBlock(
                        merchant = expense.merchant,
                        suggestion = suggestion,
                        enabled = !isMarkPaidInFlight,
                        onLink = { onLinkSuggestion(suggestion) },
                        onDecline = { onDeclineSuggestion(suggestion) }
                    )
                }
            }

            if (expense.isActive && onMarkPaid != null) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { onMarkPaid(expense) },
                    enabled = !isMarkPaidInFlight,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.recurring_mark_paid))
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
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

/**
 * FC-PAID-UNLINKED: one "Paid already?" suggestion. The amount is the expense's own amount with its own
 * currency via the money formatter (never converted, never zero or blank: the ViewModel drops such
 * items). Only the rule's merchant, the amount and dates are shown, never the expense note.
 *
 * Accessibility: the two text lines are one merged node; each button carries a description naming
 * merchant, amount and date; both buttons are at least 48dp high; at font scale 1.3 or more the
 * buttons stack full width; no fixed heights, no truncation.
 */
@Composable
internal fun PaymentLinkSuggestionBlock(
    merchant: String,
    suggestion: PaymentLinkSuggestionUi,
    enabled: Boolean,
    onLink: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val amountText = CurrencyFormatter.formatMoney(suggestion.paidAmount, suggestion.paidCurrency)
    val paidDateText = dateFormat.format(Instant.ofEpochMilli(suggestion.expenseDate).atZone(zone))
    val dueDateText = dateFormat.format(Instant.ofEpochMilli(suggestion.dueDate).atZone(zone))
    val summaryDescription = stringResource(
        R.string.recurring_link_suggestion_cd_format, merchant, amountText, paidDateText, dueDateText
    )
    val linkDescription = stringResource(
        R.string.recurring_link_action_cd_format, merchant, amountText, paidDateText
    )
    val declineDescription = stringResource(
        R.string.recurring_link_decline_cd_format, merchant, amountText, paidDateText
    )
    val stackButtons = LocalDensity.current.fontScale >= STACK_BUTTONS_FONT_SCALE

    val linkButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        OutlinedButton(
            onClick = onLink,
            enabled = enabled,
            modifier = buttonModifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = linkDescription },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SemanticColors.PrimaryIndigo)
        ) {
            Text(stringResource(R.string.recurring_link_action))
        }
    }
    val declineButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        TextButton(
            onClick = onDecline,
            enabled = enabled,
            modifier = buttonModifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = declineDescription },
            colors = ButtonDefaults.textButtonColors(contentColor = SemanticColors.TextSecondary)
        ) {
            Text(stringResource(R.string.recurring_link_decline))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.15f))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = summaryDescription
            }
        ) {
            Text(
                text = stringResource(R.string.recurring_link_suggestion_title_format, amountText, paidDateText),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
            // Names which bill it is: the occurrence due date, not the rule's nextDate.
            Text(
                text = stringResource(R.string.label_due_format, dueDateText),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (stackButtons) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                linkButton(Modifier.fillMaxWidth())
                declineButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                linkButton(Modifier.weight(1f))
                declineButton(Modifier.weight(1f))
            }
        }
    }
}

/** FC-PAID-UNLINKED: the single muted line shown when the suggestion read was blocked or failed. */
@Composable
internal fun PaymentLinkSuggestionsUnavailableLine(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.recurring_link_suggestions_unavailable),
        style = MaterialTheme.typography.bodySmall,
        color = SemanticColors.TextSecondary,
        modifier = modifier.fillMaxWidth()
    )
}

private const val STACK_BUTTONS_FONT_SCALE = 1.3f

@Composable
private fun AddRecurringExpenseDialog(
    // G-TIME-01: required — the caller passes uiState.referenceNowMillis
    // (ViewModel TimeProvider-backed); no wall-clock default.
    referenceNowMillis: Long,
    onDismiss: () -> Unit,
    onAdd: (String, Double, RecurrenceFrequency, Long, String?) -> Unit
) {
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var note by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var nextDate by remember { mutableStateOf(referenceNowMillis) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_recurring_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(stringResource(R.string.recurring_merchant_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.recurring_amount_label)) },
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
                        label = { Text(stringResource(R.string.label_frequency)) },
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
                val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
                OutlinedTextField(
                    value = dateFormat.format(Instant.ofEpochMilli(nextDate).atZone(ZoneId.systemDefault())),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.recurring_next_date_label)) },
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
                    label = { Text(stringResource(R.string.recurring_note_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amount)?.let { amt ->
                        onAdd(merchant, amt, frequency, nextDate, note.takeIf { it.isNotBlank() })
                    }
                },
                enabled = merchant.isNotBlank() &&
                    com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amount) != null
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
    
    // Date Picker
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = UiTimeUtils.instantToPickerMillis(nextDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            nextDate = UiTimeUtils.pickerMillisToLocalStartOfDay(it)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
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
            text = stringResource(R.string.empty_recurring_title),
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.empty_recurring_message),
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
            Text(stringResource(R.string.action_retry))
        }
    }
}