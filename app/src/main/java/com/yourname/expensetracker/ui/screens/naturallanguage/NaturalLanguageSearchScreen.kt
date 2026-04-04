@file:OptIn(ExperimentalLayoutApi::class)

package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NaturalLanguageSearchScreen(
    onNavigateBack: () -> Unit,
    onViewTransaction: (Long) -> Unit,
    viewModel: NaturalLanguageSearchViewModel = hiltViewModel()
) {
    val searchState by viewModel.searchState.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val interpretation by viewModel.interpretation.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nlp_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Input
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.updateQuery(it) },
                        label = { Text(stringResource(R.string.nlp_search_label)) },
                        placeholder = { 
                            Text(stringResource(R.string.nlp_search_placeholder)) 
                        },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearQuery() }) {
                                    Icon(Icons.Default.Clear, stringResource(R.string.cd_clear))
                                }
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Example queries
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.nlp_try_asking),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ExampleChip(stringResource(R.string.nlp_example_total_week)) { viewModel.updateQuery(it) }
                            ExampleChip(stringResource(R.string.nlp_example_restaurants)) { viewModel.updateQuery(it) }
                            ExampleChip(stringResource(R.string.nlp_example_groceries)) { viewModel.updateQuery(it) }
                            ExampleChip(stringResource(R.string.nlp_example_gas)) { viewModel.updateQuery(it) }
                        }
                    }
                }
            }
            
            // Search Results
            when (searchState) {
                is SearchState.Interpreting -> {
                    InterpretingState()
                }
                is SearchState.Results -> {
                    SearchResultsContent(
                        interpretation = interpretation,
                        results = results,
                        onViewTransaction = onViewTransaction
                    )
                }
                is SearchState.Empty -> {
                    EmptySearchState()
                }
                else -> {
                    InitialSearchState()
                }
            }
        }
    }
}

@Composable
fun ExampleChip(text: String, onClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { onClick(text) }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun InterpretingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.nlp_understanding),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchResultsContent(
    interpretation: NaturalLanguageSearchEngine.QueryInterpretation?,
    results: List<NaturalLanguageExpense>,
    onViewTransaction: (Long) -> Unit
) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Interpretation summary
        interpretation?.let { interp ->
            item {
                InterpretationCard(interpretation = interp)
            }
            
            // Summary for totals
            if (interp.queryType == NaturalLanguageSearchEngine.QueryType.TOTAL_AMOUNT) {
                val total = results.sumOf { it.effectiveAmount }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.nlp_total_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            
                            Text(
                                text = numberFormat.format(total),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            
                            interp.dateRange?.let { range ->
                                Text(
                                    text = "${range.start.format(DateTimeFormatter.ofPattern("MMM d"))} - ${range.end.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = stringResource(R.string.nlp_transactions_count_format, results.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        
        // Transaction list
        items(results) { expense ->
            TransactionResultCard(
                expense = expense,
                onClick = { onViewTransaction(expense.id) }
            )
        }
    }
}

@Composable
fun InterpretationCard(interpretation: NaturalLanguageSearchEngine.QueryInterpretation) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = stringResource(R.string.nlp_understood_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Text(
                    text = stringResource(R.string.nlp_confidence_format, interpretation.confidence.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show what was extracted
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                interpretation.extractedAmounts?.let { amounts ->
                    amounts.forEach { amount ->
                        ExtractedChip(
                            icon = Icons.Rounded.AttachMoney,
                            label = when (amount.comparison) {
                                NaturalLanguageSearchEngine.AmountComparison.OVER -> stringResource(R.string.nlp_amount_over_format, amount.value)
                                NaturalLanguageSearchEngine.AmountComparison.UNDER -> stringResource(R.string.nlp_amount_under_format, amount.value)
                                NaturalLanguageSearchEngine.AmountComparison.EXACTLY -> stringResource(R.string.nlp_amount_exactly_format, amount.value)
                                NaturalLanguageSearchEngine.AmountComparison.BETWEEN -> stringResource(R.string.nlp_amount_between_format, amount.value)
                            }
                        )
                    }
                }
                
                interpretation.dateRange?.let { range ->
                    ExtractedChip(
                        icon = Icons.Rounded.CalendarToday,
                        label = "${range.start.format(DateTimeFormatter.ofPattern("MMM d"))} - ${range.end.format(DateTimeFormatter.ofPattern("MMM d"))}"
                    )
                }
                
                interpretation.categories?.let { categories ->
                    categories.forEach { category ->
                        ExtractedChip(
                            icon = Icons.Rounded.Category,
                            label = category.capitalize()
                        )
                    }
                }
                
                interpretation.merchants?.let { merchants ->
                    merchants.forEach { merchant ->
                        ExtractedChip(
                            icon = Icons.Rounded.Store,
                            label = merchant
                        )
                    }
                }
                
                interpretation.locations?.let { locations ->
                    locations.forEach { location ->
                        ExtractedChip(
                            icon = Icons.Rounded.LocationOn,
                            label = location
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExtractedChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun TransactionResultCard(
    expense: NaturalLanguageExpense,
    onClick: () -> Unit
) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val date = java.time.Instant.ofEpochMilli(expense.date)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Merchant icon placeholder
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = expense.merchant.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.merchant,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = date.format(dateFormatter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = numberFormat.format(expense.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EmptySearchState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.nlp_no_results),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun InitialSearchState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.nlp_initial_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.nlp_initial_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
