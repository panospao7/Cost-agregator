package com.yourname.expensetracker.ui.screens.addexpense

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    initialAmount: String? = null,
    initialMerchant: String? = null,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Handle save result
    LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is SaveResult.Success -> {
                viewModel.reset()
                onDismiss()
            }
            else -> { /* handled in UI */ }
        }
    }

    // Set initial values once
    LaunchedEffect(Unit) {
        if (initialAmount != null || initialMerchant != null) {
            viewModel.setInitialValues(initialAmount, initialMerchant)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopAppBar(
                title = { Text(stringResource(com.yourname.expensetracker.R.string.add_expense_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(com.yourname.expensetracker.R.string.close_content_description))
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(com.yourname.expensetracker.R.string.save_button))
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // === Merchant Field with Autocomplete ===
                MerchantFieldWithSuggestions(
                    merchant = state.merchant,
                    onMerchantChange = { viewModel.updateMerchant(it) },
                    suggestions = state.suggestions,
                    showSuggestions = state.showSuggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    onDismissSuggestions = { viewModel.dismissSuggestions() },
                    error = state.merchantError,
                    categories = categories,
                    onNextFocus = { focusManager.moveFocus(FocusDirection.Down) }
                )

                // === Amount Field ===
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text(stringResource(com.yourname.expensetracker.R.string.amount_label)) },
                    placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.amount_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    isError = state.amountError != null,
                    supportingText = state.amountError?.let { { Text(it) } },
                    leadingIcon = { Text(Currency.getInstance("EUR").symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.fillMaxWidth()
                )

                // === Payment Method ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.payment_method_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_card),
                        selected = state.paymentMethod == PaymentMethod.CARD,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_cash),
                        selected = state.paymentMethod == PaymentMethod.CASH,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_transfer),
                        selected = state.paymentMethod == PaymentMethod.BANK_TRANSFER,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.BANK_TRANSFER) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // === Category Selector ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.category_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                CategoryGrid(
                    categories = categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) }
                )

                // === Date Picker ===
                DateSelector(
                    dateMs = state.date,
                    onDateSelected = { viewModel.updateDate(it) }
                )

                // === Transaction Type (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTransactionType() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.transaction_type_prefix, state.transactionType.name.lowercase()
                            .replaceFirstChar { it.uppercase() }),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showTransactionType) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }

                AnimatedVisibility(visible = state.showTransactionType) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                            FilterChip(
                                selected = state.transactionType == type,
                                onClick = { viewModel.selectTransactionType(type) },
                                label = {
                                    Text(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }
                }

                // === Notes (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNotes() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showNotes) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }

                AnimatedVisibility(visible = state.showNotes) {
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { viewModel.updateNotes(it) },
                        label = { Text(stringResource(com.yourname.expensetracker.R.string.notes_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }

                // === Recurring Options ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.repeat_transaction_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isRecurring,
                        onCheckedChange = { viewModel.toggleRecurring() }
                    )
                }
                
                AnimatedVisibility(visible = state.isRecurring) {
                    Column {
                        Text(
                            stringResource(com.yourname.expensetracker.R.string.frequency_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Simple horizontal scroll for frequencies
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.yourname.expensetracker.domain.model.RecurrenceFrequency.values()
                                .filter { it != com.yourname.expensetracker.domain.model.RecurrenceFrequency.IRREGULAR }
                                .forEach { freq ->
                                    FilterChip(
                                        selected = state.recurrenceFrequency == freq,
                                        onClick = { viewModel.setRecurrenceFrequency(freq) },
                                        label = { 
                                            Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) 
                                        }
                                    )
                                }
                        }
                    }
                }

                // === Not Mine Expense ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Not mine (belongs to someone else)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isNotMine,
                        onCheckedChange = { viewModel.setIsNotMine(it) }
                    )
                }

                AnimatedVisibility(visible = state.isNotMine) {
                    OutlinedTextField(
                        value = state.ownerName,
                        onValueChange = { viewModel.updateOwnerName(it) },
                        label = { Text("Owner name (e.g., Partner, Roommate)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === Shared Expense ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Shared expense (split with someone)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isSharedExpense,
                        onCheckedChange = { viewModel.setIsSharedExpense(it) }
                    )
                }

                AnimatedVisibility(visible = state.isSharedExpense) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.sharedWithName,
                            onValueChange = { viewModel.updateSharedWithName(it) },
                            label = { Text("Shared with (name)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.mySharePercentage,
                                onValueChange = { viewModel.updateMySharePercentage(it) },
                                label = { Text("My share %") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("e.g., 50") }
                            )
                            OutlinedTextField(
                                value = state.myShareAmount,
                                onValueChange = { viewModel.updateMyShareAmount(it) },
                                label = { Text("Or amount") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("e.g., 25.00") }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === Transfer Direction (show when TRANSFER selected) ===
                AnimatedVisibility(visible = state.transactionType == TransactionType.TRANSFER) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Transfer direction",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.transferDirection == TransferDirection.INCOMING,
                                onClick = { viewModel.setTransferDirection(TransferDirection.INCOMING) },
                                label = { Text("Incoming (to me)") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = state.transferDirection == TransferDirection.OUTGOING,
                                onClick = { viewModel.setTransferDirection(TransferDirection.OUTGOING) },
                                label = { Text("Outgoing (from me)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = state.transferAccountName,
                            onValueChange = { viewModel.updateTransferAccountName(it) },
                            label = { Text("Account/Person name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("e.g., Savings account, John, Bank transfer") }
                        )
                    }
                }

                // === Error Messages ===
                when (val result = state.saveResult) {
                    is SaveResult.Duplicate -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(com.yourname.expensetracker.R.string.error_duplicate_transaction),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is SaveResult.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "❌ ${result.message}",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun MerchantFieldWithSuggestions(
    merchant: String,
    onMerchantChange: (String) -> Unit,
    suggestions: List<MerchantSuggestion>,
    showSuggestions: Boolean,
    onSuggestionSelected: (MerchantSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    error: String?,
    categories: List<Category>,
    onNextFocus: () -> Unit
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    Column {
        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            label = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_label)) },
            placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNextFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        // Suggestions dropdown
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        val category = suggestion.categoryId?.let { categoryMap[it] }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Category icon
                                if (category != null) {
                                    val catColor = remember(category.color) {
                                        try {
                                            Color(android.graphics.Color.parseColor(category.color))
                                        } catch (e: Exception) {
                                            Color.Gray
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(catColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(category.icon, fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.merchant,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            if (category != null) append(category.name)
                                            if (suggestion.txCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append(stringResource(com.yourname.expensetracker.R.string.visits_suffix_format, suggestion.txCount))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    stringResource(com.yourname.expensetracker.R.string.avg_amount_format, suggestion.avgAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (suggestion != suggestions.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    // Wrapping flow layout using multiple rows
    val chunked = remember(categories) { categories.chunked(4) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { category ->
                    val isSelected = selectedId == category.id
                    val catColor = remember(category.color) {
                        try {
                            Color(android.graphics.Color.parseColor(category.color))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                    }
                    Surface(
                        onClick = { onSelect(category.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(
                            2.dp, catColor
                        ) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(category.icon, fontSize = 20.sp)
                            Text(
                                category.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Fill remaining space in last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy, HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = stringResource(com.yourname.expensetracker.R.string.date_label),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                stringResource(com.yourname.expensetracker.R.string.date_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                dateFormat.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day, just change the date
                            val calOld = Calendar.getInstance().apply { timeInMillis = dateMs }
                            val calNew = Calendar.getInstance().apply { timeInMillis = selectedDate }
                            calNew.set(Calendar.HOUR_OF_DAY, calOld.get(Calendar.HOUR_OF_DAY))
                            calNew.set(Calendar.MINUTE, calOld.get(Calendar.MINUTE))
                            calNew.set(Calendar.SECOND, calOld.get(Calendar.SECOND))
                            onDateSelected(calNew.timeInMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(com.yourname.expensetracker.R.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
