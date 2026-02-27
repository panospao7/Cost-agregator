package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.OwnershipFilter as VMOwnershipFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFilterSheet(
    categories: List<Category>,
    currentFilter: TransactionFilter?,
    currentOwnershipFilter: VMOwnershipFilter,
    onDismiss: () -> Unit,
    onApply: (TransactionFilter?, VMOwnershipFilter) -> Unit,
    onClear: () -> Unit
) {
    var selectedCategoryId by remember { mutableStateOf(currentFilter?.categoryId) }
    var selectedType by remember { mutableStateOf<TransactionType?>(null) }
    var selectedOwnership by remember { mutableStateOf(currentOwnershipFilter) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = {
                    selectedCategoryId = null
                    selectedType = null
                    selectedOwnership = VMOwnershipFilter.ALL
                    selectedYear = null
                    selectedMonth = null
                }) {
                    Text("Clear All")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Ownership Filter
            Text(
                text = "Ownership",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedOwnership == VMOwnershipFilter.ALL,
                    onClick = { selectedOwnership = VMOwnershipFilter.ALL },
                    label = { Text("All") },
                    leadingIcon = if (selectedOwnership == VMOwnershipFilter.ALL) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedOwnership == VMOwnershipFilter.MINE,
                    onClick = { selectedOwnership = VMOwnershipFilter.MINE },
                    label = { Text("Mine") },
                    leadingIcon = if (selectedOwnership == VMOwnershipFilter.MINE) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedOwnership == VMOwnershipFilter.NOT_MINE,
                    onClick = { selectedOwnership = VMOwnershipFilter.NOT_MINE },
                    label = { Text("Not Mine") },
                    leadingIcon = if (selectedOwnership == VMOwnershipFilter.NOT_MINE) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedOwnership == VMOwnershipFilter.SHARED,
                    onClick = { selectedOwnership = VMOwnershipFilter.SHARED },
                    label = { Text("Shared") },
                    leadingIcon = if (selectedOwnership == VMOwnershipFilter.SHARED) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedOwnership == VMOwnershipFilter.TRANSFER,
                    onClick = { selectedOwnership = VMOwnershipFilter.TRANSFER },
                    label = { Text("Transfers") },
                    leadingIcon = if (selectedOwnership == VMOwnershipFilter.TRANSFER) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Transaction Type
            Text(
                text = "Transaction Type",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text("All Types") },
                    leadingIcon = if (selectedType == null) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedType == TransactionType.PURCHASE,
                    onClick = { selectedType = TransactionType.PURCHASE },
                    label = { Text("Purchases") },
                    leadingIcon = if (selectedType == TransactionType.PURCHASE) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedType == TransactionType.DEPOSIT,
                    onClick = { selectedType = TransactionType.DEPOSIT },
                    label = { Text("Deposits") },
                    leadingIcon = if (selectedType == TransactionType.DEPOSIT) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = selectedType == TransactionType.WITHDRAWAL,
                    onClick = { selectedType = TransactionType.WITHDRAWAL },
                    label = { Text("Withdrawals") },
                    leadingIcon = if (selectedType == TransactionType.WITHDRAWAL) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Categories
            Text(
                text = "Category",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // All categories chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null },
                    label = { Text("All Categories") },
                    leadingIcon = if (selectedCategoryId == null) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategoryId == category.id,
                        onClick = { selectedCategoryId = category.id },
                        label = { Text("${category.icon} ${category.name}") },
                        leadingIcon = if (selectedCategoryId == category.id) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Date Range - Year selection
            Text(
                text = "Year",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                listOf(currentYear, currentYear - 1, currentYear - 2, currentYear - 3).forEach { year ->
                    FilterChip(
                        selected = selectedYear == year,
                        onClick = { selectedYear = year },
                        label = { Text(year.toString()) },
                        leadingIcon = if (selectedYear == year) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
                FilterChip(
                    selected = selectedYear == null,
                    onClick = { selectedYear = null },
                    label = { Text("All Years") },
                    leadingIcon = if (selectedYear == null) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            // Month selection (only if year is selected)
            if (selectedYear != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Month",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        1 to "Jan", 2 to "Feb", 3 to "Mar", 4 to "Apr",
                        5 to "May", 6 to "Jun", 7 to "Jul", 8 to "Aug",
                        9 to "Sep", 10 to "Oct", 11 to "Nov", 12 to "Dec"
                    ).forEach { (month, name) ->
                        FilterChip(
                            selected = selectedMonth == month,
                            onClick = { selectedMonth = if (selectedMonth == month) null else month },
                            label = { Text(name) },
                            leadingIcon = if (selectedMonth == month) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Apply Button
            Button(
                onClick = {
                    val calendar = java.util.Calendar.getInstance()
                    var startDate: Long? = null
                    var endDate: Long? = null

                    if (selectedYear != null) {
                        calendar.timeInMillis = System.currentTimeMillis()
                        calendar.set(java.util.Calendar.YEAR, selectedYear!!)
                        if (selectedMonth != null) {
                            calendar.set(java.util.Calendar.MONTH, selectedMonth!! - 1)
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
                            startDate = calendar.timeInMillis
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                            endDate = calendar.timeInMillis
                        } else {
                            calendar.set(java.util.Calendar.MONTH, 0)
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
                            startDate = calendar.timeInMillis
                            calendar.set(java.util.Calendar.MONTH, 11)
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, 31)
                            endDate = calendar.timeInMillis
                        }
                    }

                    val filter = TransactionFilter(
                        categoryId = selectedCategoryId,
                        dateRange = if (startDate != null && endDate != null) Pair(startDate, endDate) else null
                    )
                    onApply(filter, selectedOwnership)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Apply Filters", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
