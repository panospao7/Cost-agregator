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
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.OwnershipFilter as VMOwnershipFilter
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionFilterSheet(
    categories: List<Category>,
    currentFilter: TransactionFilter?,
    currentOwnershipFilter: VMOwnershipFilter,
    referenceNowMs: Long,
    onDismiss: () -> Unit,
    onApply: (TransactionFilter?, VMOwnershipFilter) -> Unit,
    onClear: () -> Unit
) {
    var selectedCategoryId by remember { mutableStateOf(currentFilter?.categoryId) }
    var selectedType by remember { mutableStateOf(currentFilter?.transactionType) }
    var selectedOwnership by remember { mutableStateOf(currentOwnershipFilter) }
    // Initialize year/month chips from the existing filter's dateRange so an
    // existing explicit range is visible/editable when re-opening the sheet.
    var selectedYear by remember {
        mutableStateOf(currentFilter?.dateRange?.let { (start, _) ->
            TimePeriodUtils.getYear(start)
        })
    }
    var selectedMonth by remember {
        mutableStateOf(currentFilter?.dateRange?.let { (start, _) ->
            TimePeriodUtils.getMonth(start) + 1  // getMonth() is 0-based; chips are 1-based
        })
    }

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
                    text = stringResource(R.string.filter_transactions_title),
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
                    Text(stringResource(R.string.filter_reset_all), color = SemanticColors.DangerRed)
                }
            }

            // Transaction Type Section
            FilterSection(title = stringResource(R.string.filter_transaction_type_title)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val types = listOf(null to stringResource(R.string.filter_all_types)) + TransactionType.values()
                        .filter { it != TransactionType.UNKNOWN }
                        .map { it to getTransactionTypeLabel(it) }
                    
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
            FilterSection(title = stringResource(R.string.filter_ownership_title)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VMOwnershipFilter.values().forEach { ownership ->
                        FilterChip(
                            selected = selectedOwnership == ownership,
                            onClick = { selectedOwnership = ownership },
                            label = { Text(stringResource(getOwnershipLabelRes(ownership))) },
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
            FilterSection(title = stringResource(R.string.filter_category_title)) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        CategorySelectCard(
                            name = stringResource(R.string.filter_all_categories),
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
            FilterSection(title = stringResource(R.string.filter_year_title)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentYear = TimePeriodUtils.getYear(referenceNowMs)
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
                        label = { Text(stringResource(R.string.filter_all_years)) },
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
                FilterSection(title = stringResource(R.string.filter_month_title)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            1 to stringResource(R.string.month_jan_short), 2 to stringResource(R.string.month_feb_short), 3 to stringResource(R.string.month_mar_short), 4 to stringResource(R.string.month_apr_short),
                            5 to stringResource(R.string.month_may_short), 6 to stringResource(R.string.month_jun_short), 7 to stringResource(R.string.month_jul_short), 8 to stringResource(R.string.month_aug_short),
                            9 to stringResource(R.string.month_sep_short), 10 to stringResource(R.string.month_oct_short), 11 to stringResource(R.string.month_nov_short), 12 to stringResource(R.string.month_dec_short)
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
                    // Build the date range using TimePeriodUtils half-open convention
                    // [startInclusive, endExclusive) — no 23:59:59 clamping.
                    val dateRangeToUse: Pair<Long, Long>? = if (selectedYear != null) {
                        if (selectedMonth != null) {
                            // Specific year + month: e.g., 2024-Mar-01 00:00 → 2024-Apr-01 00:00
                            TimePeriodUtils.getMonthRange(selectedYear!!, selectedMonth!!)
                        } else {
                            // Year only: e.g., 2024-Jan-01 00:00 → 2025-Jan-01 00:00
                            TimePeriodUtils.getYearRange(selectedYear!!)
                        }
                    } else {
                        // No year selected → date filter is explicitly cleared (null),
                        // NOT a fallback to the previous filter's dateRange.
                        null
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
                Text(stringResource(R.string.filter_apply), style = MaterialTheme.typography.titleMedium)
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
private fun getTransactionTypeLabel(type: TransactionType): String {
    return when (type) {
        TransactionType.PURCHASE -> stringResource(R.string.transaction_type_purchase)
        TransactionType.DEPOSIT -> stringResource(R.string.transaction_type_deposit)
        TransactionType.WITHDRAWAL -> stringResource(R.string.transaction_type_withdrawal)
        TransactionType.TRANSFER -> stringResource(R.string.transaction_type_transfer)
        TransactionType.UNKNOWN -> stringResource(R.string.transaction_type_unknown)
    }
}

@Composable
private fun getOwnershipLabelRes(ownership: VMOwnershipFilter): Int {
    return when (ownership) {
        VMOwnershipFilter.ALL -> R.string.transactions_ownership_all
        VMOwnershipFilter.MINE -> R.string.transactions_ownership_mine
        VMOwnershipFilter.NOT_MINE -> R.string.transactions_ownership_not_mine
        VMOwnershipFilter.SHARED -> R.string.transactions_ownership_shared
        VMOwnershipFilter.TRANSFER -> R.string.transactions_ownership_transfers
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
                    contentDescription = stringResource(R.string.filter_selected_cd),
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
