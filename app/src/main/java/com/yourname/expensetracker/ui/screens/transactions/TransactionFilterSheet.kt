package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.OwnershipFilter as VMOwnershipFilter
import com.yourname.expensetracker.ui.theme.SemanticColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var selectedType by remember { mutableStateOf(currentFilter?.transactionType) }
    var selectedOwnership by remember { mutableStateOf(currentOwnershipFilter) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    // Date range filtering can be complex to build visually from scratch quickly,
    // so for now we'll stick to defining category, type and ownership which covers the 90% use case.

    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = bottomSheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter Transactions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = {
                        selectedCategoryId = null
                        selectedType = null
                        selectedOwnership = VMOwnershipFilter.ALL
                        selectedYear = null
                        selectedMonth = null
                    }
                ) {
                    Text("Reset All", color = SemanticColors.DangerRed)
                }
            }

            // Transaction Type Section
            FilterSection(title = "Transaction Type") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val types = listOf(null to "All Types") + TransactionType.values()
                        .filter { it != TransactionType.UNKNOWN }
                        .map { it to it.name }
                    
                    types.forEach { (type, label) ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                selectedLabelColor = SemanticColors.PrimaryIndigo
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedType == type,
                                borderColor = SemanticColors.GlassBorder,
                                selectedBorderColor = SemanticColors.PrimaryIndigo
                            )
                        )
                    }
                }
            }

            // Ownership Section
            FilterSection(title = "Ownership") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VMOwnershipFilter.values().forEach { ownership ->
                        FilterChip(
                            selected = selectedOwnership == ownership,
                            onClick = { selectedOwnership = ownership },
                            label = { Text(ownership.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                selectedLabelColor = SemanticColors.PrimaryIndigo
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedOwnership == ownership,
                                borderColor = SemanticColors.GlassBorder,
                                selectedBorderColor = SemanticColors.PrimaryIndigo
                            )
                        )
                    }
                }
            }

            // Category Section
            FilterSection(title = "Category") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        CategorySelectCard(
                            name = "All Categories",
                            icon = "📁",
                            colorHex = "#808080", // Gray
                            isSelected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null }
                        )
                    }
                    items(categories, key = { it.id }) { category ->
                        CategorySelectCard(
                            name = category.name,
                            icon = category.icon,
                            colorHex = category.color,
                            isSelected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id }
                        )
                    }
                }
            }

            // Date Range - Year section
            FilterSection(title = "Year") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                    listOf(currentYear, currentYear - 1, currentYear - 2, currentYear - 3).forEach { year ->
                        FilterChip(
                            selected = selectedYear == year,
                            onClick = { selectedYear = year },
                            label = { Text(year.toString()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                selectedLabelColor = SemanticColors.PrimaryIndigo
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedYear == year,
                                borderColor = SemanticColors.GlassBorder,
                                selectedBorderColor = SemanticColors.PrimaryIndigo
                            )
                        )
                    }
                    FilterChip(
                        selected = selectedYear == null,
                        onClick = { selectedYear = null },
                        label = { Text("All Years") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                            selectedLabelColor = SemanticColors.PrimaryIndigo
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedYear == null,
                            borderColor = SemanticColors.GlassBorder,
                            selectedBorderColor = SemanticColors.PrimaryIndigo
                        )
                    )
                }
            }

            // Month selection (only if year is selected)
            if (selectedYear != null) {
                FilterSection(title = "Month") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
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
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                    selectedLabelColor = SemanticColors.PrimaryIndigo
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedMonth == month,
                                    borderColor = SemanticColors.GlassBorder,
                                    selectedBorderColor = SemanticColors.PrimaryIndigo
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                            calendar.set(java.util.Calendar.MINUTE, 0)
                            calendar.set(java.util.Calendar.SECOND, 0)
                            startDate = calendar.timeInMillis
                            
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
                            calendar.set(java.util.Calendar.MINUTE, 59)
                            calendar.set(java.util.Calendar.SECOND, 59)
                            endDate = calendar.timeInMillis
                        } else {
                            calendar.set(java.util.Calendar.MONTH, 0)
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
                            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                            calendar.set(java.util.Calendar.MINUTE, 0)
                            calendar.set(java.util.Calendar.SECOND, 0)
                            startDate = calendar.timeInMillis
                            
                            calendar.set(java.util.Calendar.MONTH, 11)
                            calendar.set(java.util.Calendar.DAY_OF_MONTH, 31)
                            calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
                            calendar.set(java.util.Calendar.MINUTE, 59)
                            calendar.set(java.util.Calendar.SECOND, 59)
                            endDate = calendar.timeInMillis
                        }
                    }
                    
                    val dateRangeToUse = if (startDate != null && endDate != null) {
                        Pair(startDate, endDate)
                    } else {
                        currentFilter?.dateRange
                    }

                    val newFilter = if (selectedCategoryId != null || selectedType != null || dateRangeToUse != null) {
                        TransactionFilter(
                            categoryId = selectedCategoryId,
                            transactionType = selectedType,
                            merchantName = currentFilter?.merchantName,
                            dateRange = dateRangeToUse
                        )
                    } else {
                        null
                    }
                    onApply(newFilter, selectedOwnership)
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
            ) {
                Text("Apply Filters", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun CategorySelectCard(
    name: String,
    icon: String,
    colorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val categoryColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: Exception) {
        SemanticColors.PrimaryIndigo
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) categoryColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) categoryColor else SemanticColors.GlassBorder
        ),
        modifier = Modifier.width(100.dp).height(100.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopEnd
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = categoryColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) categoryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
