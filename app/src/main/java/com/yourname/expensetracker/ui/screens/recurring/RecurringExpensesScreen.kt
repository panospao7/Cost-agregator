package com.yourname.expensetracker.ui.screens.recurring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import timber.log.Timber

@HiltViewModel
class RecurringExpensesViewModel @Inject constructor(
    private val repository: FinancialWeatherRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val expenseRepository: ExpenseRepository,
    private val currencySettingsRepository: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    /** Placeholder initial value "EUR"; immediately replaced by [CurrencySettingsRepository.homeCurrency]. */
    private val _homeCurrency = currencySettingsRepository.homeCurrency()
        .stateIn(viewModelScope, SharingStarted.Lazily, "EUR")
    val homeCurrency: StateFlow<String> = _homeCurrency

    // Helper flow to trigger updates
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Combines manually-confirmed recurring entries (from DB) with auto-detected patterns
     * (from RecurringExpenseEngine). Manual entries take precedence when the same merchant
     * appears in both lists. Re-triggers whenever the manual_recurring_expenses table changes
     * or the refreshTrigger increments (e.g. after a confirm/delete action).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allPatterns: StateFlow<List<RecurringPattern>> = combine(
        recurringExpenseRepository.getAllFlow(),
        refreshTrigger
    ) { manualEntities, _ -> manualEntities }
        .flatMapLatest { manualEntities ->
            flow {
                // Map manual DB entries → domain model (confidence = 1.0, id set for delete)
                val manualPatterns = manualEntities.map { entity ->
                    RecurringPattern(
                        id = entity.id,
                        merchantName = entity.merchant,
                        averageAmount = entity.amount,
                        currency = entity.currency,
                        frequency = entity.frequency,
                        nextExpectedDate = entity.nextDate,
                        confidence = 1.0f,
                        periodVarianceDays = 0,
                        amountVariancePercent = 0.0,
                        previousDates = emptyList()
                    )
                }

                // Auto-detect patterns from transaction history (engine applies 6-month staleness filter)
                val now = timeProvider.now()
                val allExpenses = expenseRepository.getExpensesSince(
                    TimePeriodUtils.getYearRange(now).first
                )
                val detectedPatterns = recurringExpenseEngine.getPatterns(allExpenses)

                // Merge: manual takes precedence over auto-detected for the same merchant
                val merged = mutableMapOf<String, RecurringPattern>()
                // Add detected first (lower priority)
                detectedPatterns.forEach { merged[it.merchantName.lowercase().trim()] = it }
                // Overwrite with manual (higher priority)
                manualPatterns.forEach { merged[it.merchantName.lowercase().trim()] = it }

                emit(merged.values.sortedByDescending { it.confidence })
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val plannedExpenses = plannedExpenseRepository.getAllPlannedExpenses()
        .map { list ->
            list.map { entity ->
                com.yourname.expensetracker.domain.model.PlannedExpense(
                    id = entity.id,
                    description = entity.description,
                    amount = entity.amount,
                    date = entity.date,
                    categoryId = entity.categoryId,
                    isRecurring = entity.isRecurring,
                    priority = when(entity.priority) {
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.MUST
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.LIKELY
                        com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL -> com.yourname.expensetracker.domain.model.PlannedExpensePriority.OPTIONAL
                    },
                    sourceOccurrenceKey = entity.sourceOccurrenceKey
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList<com.yourname.expensetracker.domain.model.PlannedExpense>()
        )

    fun deleteManualRule(pattern: RecurringPattern) {
        viewModelScope.launch {
            try {
                if (pattern.id != null) {
                    recurringExpenseRepository.deleteById(pattern.id)
                    refreshTrigger.value += 1
                } else {
                    // Legacy fallback: Delete by merchant name if ID is missing (e.g. old local data)
                    val rules = recurringExpenseRepository.getAll()
                    val rule = rules.find { it.merchant == pattern.merchantName }
                    if (rule != null) {
                        recurringExpenseRepository.delete(rule)
                        refreshTrigger.value += 1
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete rule")
            }
        }
    }

    fun confirmPattern(pattern: RecurringPattern) {
        viewModelScope.launch {
            val manual = ManualRecurringExpense(
                merchant = pattern.merchantName,
                amount = pattern.averageAmount,
                currency = pattern.currency,
                frequency = pattern.frequency,
                nextDate = pattern.nextExpectedDate,
                note = "Detected and confirmed by user"
            )
            recurringExpenseRepository.insert(manual)
            refreshTrigger.value += 1
        }
    }


    fun deletePlannedExpense(planned: com.yourname.expensetracker.domain.model.PlannedExpense) {
        viewModelScope.launch {
            plannedExpenseRepository.deletePlannedExpenseById(planned.id)
            refreshTrigger.value += 1
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringExpensesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit = {}, // Default for preview/safety
    viewModel: RecurringExpensesViewModel = hiltViewModel()
) {
    val patterns by viewModel.allPatterns.collectAsState()
    val planned by viewModel.plannedExpenses.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState()
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val recurringTabTitle = stringResource(R.string.recurring_tab_recurring)
    val plannedTabTitle = stringResource(R.string.recurring_tab_planned)
    val tabs = listOf(recurringTabTitle, plannedTabTitle)

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.recurring_screen_title),
                        color = SemanticColors.TextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.recurring_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    val tabStatus = if (selectedTabIndex == index) 
                        stringResource(R.string.recurring_tab_selected) 
                    else 
                        stringResource(R.string.recurring_tab_not_selected)
                    val tabCd = stringResource(R.string.recurring_tab_cd_format, title, tabStatus)
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) },
                        modifier = Modifier.semantics {
                            contentDescription = tabCd
                        }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                // Recurring Tab
                if (patterns.isEmpty()) {
                    val emptyRecurringCd = stringResource(R.string.recurring_empty_recurring_cd)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = emptyRecurringCd },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.recurring_empty_recurring_title))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(patterns, key = { it.id ?: it.merchantName }) { pattern ->
                            RecurringExpenseItem(
                                pattern = pattern,
                                onDelete = { viewModel.deleteManualRule(pattern) },
                                onConfirm = { viewModel.confirmPattern(pattern) },
                                onMerchantClick = { 
                                    onNavigateToTransactions(
                                        TransactionFilter(merchantName = pattern.merchantName)
                                    ) 
                                }
                            )
                        }

                    }
                }
            } else {
                // Planned Tab
                if (planned.isEmpty()) {
                    val emptyPlannedCd = stringResource(R.string.recurring_empty_planned_cd)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = emptyPlannedCd },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.recurring_empty_planned_title))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                    items(planned, key = { it.id }) { item ->
                        PlannedExpenseItem(
                            expense = item,
                            homeCurrency = homeCurrency,
                            onDelete = { viewModel.deletePlannedExpense(item) }
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
/**
 * @param homeCurrency ISO-4217 currency code. Default "EUR" is a placeholder;
 *                     callers should pass the actual home currency from settings.
 */
fun PlannedExpenseItem(
    expense: com.yourname.expensetracker.domain.model.PlannedExpense,
    homeCurrency: String = "EUR",
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val priorityText = expense.priority.name.lowercase().replaceFirstChar { it.uppercase() }
    val cardDesc = stringResource(
        R.string.recurring_planned_item_cd_format,
        expense.description,
        expense.amount,
        priorityText
    )
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.semantics { contentDescription = cardDesc }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // Remember expensive string calculations
        val amountAndPriority = remember(expense.amount, expense.priority, homeCurrency) {
                "${CurrencyFormatter.format(expense.amount, homeCurrency)} • ${expense.priority.name.lowercase().replaceFirstChar { it.uppercase() }}"
                }
                Text(
                    text = amountAndPriority,
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
                val formattedDate = remember(expense.date) {
                    dateFormat.format(Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()))
                }
                Text(
                    text = stringResource(R.string.recurring_date_label, formattedDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            val deleteCd = stringResource(R.string.recurring_delete_planned_cd_format, expense.description)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteCd }
            ) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun RecurringExpenseItem(
    pattern: RecurringPattern,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onMerchantClick: () -> Unit = {}
) {
    val context = LocalContext.current

    val isManual = remember(pattern.id, pattern.confidence) { 
        pattern.id != null || pattern.confidence >= 0.99f 
    }
    
    val confirmedText = stringResource(R.string.recurring_item_confirmed_cd)
    val suggestedText = stringResource(R.string.recurring_item_suggested_cd)
    
    // Remember expensive string calculations
    val cardDescription = remember(pattern, isManual, confirmedText, suggestedText) {
        buildString {
            append("${pattern.merchantName}, ")
            append("${String.format("%.2f", pattern.averageAmount)} ${pattern.currency}, ")
            append("${pattern.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}")
            if (isManual) {
                append(", $confirmedText")
            } else {
                append(", $suggestedText")
            }
        }
    }
    
    val amountAndCurrency = remember(pattern.averageAmount, pattern.currency) {
        "${String.format("%.2f", pattern.averageAmount)} ${pattern.currency}"
    }
    
    val frequencyText = remember(pattern.frequency) {
        pattern.frequency.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.semantics { contentDescription = cardDescription }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pattern.merchantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onMerchantClick() }
                )
                Text(
                    text = "$amountAndCurrency • $frequencyText",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()) }
                val formattedDate = remember(pattern.nextExpectedDate) {
                    dateFormat.format(Instant.ofEpochMilli(pattern.nextExpectedDate).atZone(ZoneId.systemDefault()))
                }
                Text(
                    text = stringResource(R.string.recurring_next_label, formattedDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isManual) {
                val deleteCd = stringResource(R.string.recurring_delete_rule_cd_format, pattern.merchantName)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = deleteCd }
                ) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                val confirmCd = stringResource(R.string.recurring_confirm_cd_format, pattern.merchantName)
                SuggestionChip(
                    onClick = onConfirm,
                    label = { Text(stringResource(R.string.recurring_confirm_pattern)) },
                    modifier = Modifier.semantics { contentDescription = confirmCd }
                )
            }

        }
    }
}
