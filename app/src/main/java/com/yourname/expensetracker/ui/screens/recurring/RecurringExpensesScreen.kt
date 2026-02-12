package com.yourname.expensetracker.ui.screens.recurring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RecurringExpensesViewModel @Inject constructor(
    private val repository: FinancialWeatherRepository,
    private val recurringExpenseDao: RecurringExpenseDao,
    private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
) : ViewModel() {

    // Helper flow to trigger updates
    private val refreshTrigger = MutableStateFlow(0)

    val patterns: StateFlow<List<RecurringPattern>> = combine(
        repository.getFinancialWeather(), // This already emits on db changes if set up correctly, but let's see
        refreshTrigger
    ) { weather, _ ->
        // We actually need the full list, not just upcomingBills from weather.
        // But repository exposes upcomingBills via weather. 
        // Ideally we expose all patterns separately.
        // For now, let's assume we add a method to Repo or use Engine directly if needed.
        // To be simpler, let's expose patterns from Repo.
        emptyList<RecurringPattern>() // Placeholder until Repo updated
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // Better approach: Expose patterns flow from Repository
    val allPatterns = repository.getAllRecurringPatterns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plannedExpenses = repository.getAllPlannedExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteManualRule(pattern: RecurringPattern) {
        viewModelScope.launch {
            if (pattern.id != null) {
                recurringExpenseDao.deleteById(pattern.id)
                refreshTrigger.value += 1
            } else {
                // Legacy fallback: Delete by merchant name if ID is missing (e.g. old local data)
                val rules = recurringExpenseDao.getAll()
                val rule = rules.find { it.merchant == pattern.merchantName }
                if (rule != null) {
                    recurringExpenseDao.delete(rule)
                    refreshTrigger.value += 1
                }
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
            recurringExpenseDao.insert(manual)
            refreshTrigger.value += 1
        }
    }


    fun deletePlannedExpense(planned: com.yourname.expensetracker.domain.model.PlannedExpense) {
        viewModelScope.launch {
            plannedExpenseDao.deletePlannedExpenseById(planned.id)
            refreshTrigger.value += 1
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringExpensesScreen(
    onNavigateBack: () -> Unit,
    viewModel: RecurringExpensesViewModel = hiltViewModel()
) {
    val patterns by viewModel.allPatterns.collectAsState()
    val planned by viewModel.plannedExpenses.collectAsState()
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Recurring", "Planned")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Upcoming") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
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
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                // Recurring Tab
                if (patterns.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recurring expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(patterns) { pattern ->
                            RecurringExpenseItem(
                                pattern = pattern,
                                onDelete = { viewModel.deleteManualRule(pattern) },
                                onConfirm = { viewModel.confirmPattern(pattern) }
                            )
                        }

                    }
                }
            } else {
                // Planned Tab
                if (planned.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No planned expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(planned) { item ->
                            PlannedExpenseItem(
                                expense = item,
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
fun PlannedExpenseItem(
    expense: com.yourname.expensetracker.domain.model.PlannedExpense,
    onDelete: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                Text(
                    text = "€${String.format("%.2f", expense.amount)} • ${expense.priority.name.lowercase().capitalize()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
                Text(
                    text = "Date: ${dateFormat.format(Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = "Delete Planned",
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
    onConfirm: () -> Unit
) {

    val isManual = pattern.id != null || pattern.confidence >= 0.99f
    
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${String.format("%.2f", pattern.averageAmount)} ${pattern.currency} • ${pattern.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()) }
                Text(
                    text = "Next: ${dateFormat.format(Instant.ofEpochMilli(pattern.nextExpectedDate).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isManual) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                SuggestionChip(
                    onClick = onConfirm,
                    label = { Text("Confirm Pattern") }
                )
            }

        }
    }
}
