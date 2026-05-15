package com.yourname.expensetracker.ui.screens.warranty

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Warranty tracker screen — currently read-only display of warranties with
 * manual-add dialog and auto-detected warranty review.
 *
 * ## WRN-19: Warranty edit form UI (planned)
 * Currently warranties are displayed read-only. There is no edit form to update
 * an existing warranty's fields. The plan is to add a dedicated edit dialog or
 * navigation target with the following fields:
 *
 * - **Product name** (pre-filled, editable)
 * - **Merchant name** (pre-filled, editable)
 * - **Purchase date** (date picker)
 * - **Warranty duration** (months spinner)
 * - **Support phone** (editable text, optional)
 * - **Warranty type** (dropdown: manufacturer, extended, store, third-party)
 *
 * The edit entry point would be from the [WarrantyCard] (e.g. a long-press or
 * edit icon button). On save, [WarrantyTrackerViewModel] should update the
 * warranty via [WarrantyTrackerRepository.update].
 *
 * TODO: Add edit button to WarrantyCard that opens WarrantyEditDialog.
 *       The dialog should reuse ManualWarrantyDialog's field layout but
 *       pre-populate from the existing [Warranty] entity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarrantyTrackerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScanReceipt: () -> Unit = {},
    viewModel: WarrantyTrackerViewModel = hiltViewModel(),
    actionRegistry: ContextualActionRegistry
) {
    val state by viewModel.state.collectAsState()
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
    var showManualAddDialog by remember { mutableStateOf(false) }

    // Get contextual actions for empty state
    val emptyStateActions by remember(completedActionKeys) {
        derivedStateOf {
            actionRegistry.getActions(EmptyStateScreenKeys.WARRANTY)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_warranty_tracker)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showManualAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showManualAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    title = stringResource(R.string.warranty_summary_active),
                    value = state.activeCount.toString(),
                    icon = Icons.Default.Shield,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    title = stringResource(R.string.warranty_summary_expiring),
                    value = state.expiringSoonCount.toString(),
                    icon = Icons.Default.Shield,
                    modifier = Modifier.weight(1f),
                    isAlert = state.expiringSoonCount > 0
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Protected Value
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.warranty_summary_protected_value),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = stringResource(R.string.warranty_protected_value, String.format("%.2f", state.totalProtectedValue)),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // F1: Auto-detected summary card
            if (state.autoDetectedWarranties.isNotEmpty()) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.warranty_auto_detected_count, state.autoDetectedWarranties.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                if (state.needsReviewCount > 0) {
                                    Text(
                                        text = stringResource(R.string.warranty_needs_review_count, state.needsReviewCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        if (state.needsReviewCount > 0) {
                            Button(
                                onClick = { viewModel.showNeedsReview() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(stringResource(R.string.warranty_review_button))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedFilter == null,
                    onClick = { viewModel.filterByStatus(null) },
                    label = { Text(stringResource(R.string.warranty_filter_all)) }
                )
                FilterChip(
                    selected = state.selectedFilter == WarrantyStatus.ACTIVE,
                    onClick = { viewModel.filterByStatus(WarrantyStatus.ACTIVE) },
                    label = { Text(stringResource(R.string.warranty_filter_active)) }
                )
                FilterChip(
                    selected = state.selectedFilter == WarrantyStatus.EXPIRED,
                    onClick = { viewModel.filterByStatus(WarrantyStatus.EXPIRED) },
                    label = { Text(stringResource(R.string.warranty_filter_expired)) }
                )
                // F1: Auto-detected filter chip
                if (state.autoDetectedWarranties.isNotEmpty()) {
                    FilterChip(
                        selected = state.showAutoDetectedOnly,
                        onClick = { viewModel.filterByAutoDetected() },
                        label = { Text(stringResource(R.string.warranty_filter_auto)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Warranty List
            if (state.loadableState is com.yourname.expensetracker.ui.model.LoadableUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.warranties.isEmpty()) {
                // Enhanced empty state with contextual actions
                EnhancedEmptyState(
                    type = EmptyStateType.GENERIC,
                    title = stringResource(R.string.warranty_empty_title),
                    message = stringResource(R.string.warranty_empty_message),
                    actions = emptyStateActions,
                    onActionClick = { action ->
                        when (val actionType = action.action) {
                            is EmptyStateActionType.NavigateToDestination -> {
                                if (actionType.destination == NavigationDestination.ScanReceipt) {
                                    onNavigateToScanReceipt()
                                }
                            }
                            is EmptyStateActionType.ExecuteAction -> actionType.action.invoke()
                            is EmptyStateActionType.OpenFeature -> {
                                // Handle opening feature
                            }
                        }
                    },
                    onDismissAction = { actionId ->
                        actionRegistry.markCompleted(EmptyStateScreenKeys.WARRANTY, actionId)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.warranties) { warranty ->
                        WarrantyCard(
                            warranty = warranty,
                            dateFormat = dateFormat,
                            referenceNowMillis = state.referenceNowMillis,
                            onMarkClaimed = { viewModel.markAsClaimed(warranty.id) },
                            onDelete = { viewModel.deleteWarranty(warranty) },
                            // F1: Review callbacks
                            onConfirm = { viewModel.confirmWarranty(warranty) },
                            onReject = { viewModel.rejectAutoDetectedWarranty(warranty) }
                        )
                    }
                }
            }
        }
    }

    if (showManualAddDialog) {
        ManualWarrantyDialog(
            onDismiss = { showManualAddDialog = false },
            referenceNowMillis = state.referenceNowMillis,
            onSave = { productName, merchantName, purchaseDate, durationMonths, supportPhone ->
                viewModel.addManualWarranty(
                    productName = productName,
                    merchantName = merchantName,
                    purchaseDate = purchaseDate,
                    warrantyDurationMonths = durationMonths,
                    supportPhone = supportPhone
                )
                showManualAddDialog = false
            }
        )
    }
}

@Composable
private fun ManualWarrantyDialog(
    onDismiss: () -> Unit,
    onSave: (productName: String, merchantName: String, purchaseDate: Long, durationMonths: Int, supportPhone: String?) -> Unit,
    /** S12-008: Use ViewModel reference time instead of LocalDate.now() */
    referenceNowMillis: Long = System.currentTimeMillis()
) {
    var productName by remember { mutableStateOf("") }
    var merchantName by remember { mutableStateOf("") }
    // S12-008: Use referenceNowMillis from ViewModel (TimeProvider) not wall clock
    var purchaseDateText by remember { mutableStateOf(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).format(
        java.time.Instant.ofEpochMilli(referenceNowMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    )) }
    var durationMonthsText by remember { mutableStateOf("24") }
    var supportPhone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.warranty_manual_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text(stringResource(R.string.warranty_manual_product_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = merchantName,
                    onValueChange = { merchantName = it },
                    label = { Text(stringResource(R.string.warranty_manual_merchant_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = purchaseDateText,
                    onValueChange = { purchaseDateText = it },
                    label = { Text(stringResource(R.string.warranty_manual_purchase_date)) },
                    placeholder = { Text("yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = durationMonthsText,
                    onValueChange = { durationMonthsText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.warranty_manual_duration_months)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = supportPhone,
                    onValueChange = { supportPhone = it },
                    label = { Text(stringResource(R.string.warranty_manual_support_phone_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val parsedDate = runCatching {
                LocalDate.parse(purchaseDateText, DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
            val parsedDuration = durationMonthsText.toIntOrNull()
            val canSave = productName.isNotBlank() && merchantName.isNotBlank() && parsedDate != null && (parsedDuration ?: 0) > 0
            Button(
                onClick = {
                    if (parsedDate != null && parsedDuration != null) {
                        onSave(
                            productName.trim(),
                            merchantName.trim(),
                            TimePeriodUtils.getStartOfDay(parsedDate),
                            parsedDuration,
                            supportPhone.trim().ifBlank { null }
                        )
                    }
                },
                enabled = canSave
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isAlert: Boolean = false
) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun WarrantyCard(
    warranty: Warranty,
    dateFormat: DateTimeFormatter,
    referenceNowMillis: Long,
    onMarkClaimed: () -> Unit,
    onDelete: () -> Unit,
    // F1: Review callbacks
    onConfirm: () -> Unit = {},
    onReject: () -> Unit = {}
) {
    val daysRemaining = TimePeriodUtils.daysBetween(referenceNowMillis, warranty.warrantyEndDate)
    val isExpiringSoon = daysRemaining in 0..30
    val isExpired = daysRemaining < 0
    // F1: Check if auto-detected
    val isAutoDetected = warranty.autoDetected
    val needsReview = warranty.needsReview

    Card(
        modifier = Modifier.fillMaxWidth(),
        // F1: Different border for items needing review
        border = if (needsReview) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = warranty.productName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // F1: Auto-detected badge
                        if (isAutoDetected) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.warranty_badge_auto_detected),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = warranty.merchantName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // F1: Needs review badge
                if (needsReview) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            stringResource(R.string.warranty_badge_needs_review),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else if (isExpired || isExpiringSoon) {
                    Badge(
                        containerColor = if (isExpired) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            if (isExpired) stringResource(R.string.warranty_badge_expired) else stringResource(R.string.warranty_days_left_format, daysRemaining),
                            color = if (isExpired) 
                                MaterialTheme.colorScheme.onError 
                            else 
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                // S12-007: warrantyEndDate is exclusive — display the day before
                text = stringResource(R.string.warranty_expires_date, dateFormat.format(Instant.ofEpochMilli(warranty.warrantyEndDate - 1).atZone(ZoneId.systemDefault()))),
                style = MaterialTheme.typography.bodyMedium
            )

            // F1: Show extraction confidence for auto-detected warranties
            if (isAutoDetected) {
                Text(
                    text = stringResource(R.string.warranty_confidence_format, warranty.extractionConfidence.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            warranty.supportPhone?.let {
                Text(
                    text = stringResource(R.string.warranty_support_phone, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // F1: Review actions for warranties needing review
            if (needsReview) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.warranty_action_confirm))
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.warranty_action_reject))
                    }
                }
            } else if (warranty.status == WarrantyStatus.ACTIVE) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onMarkClaimed) {
                        Text(stringResource(R.string.warranty_action_mark_claimed))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.warranty_action_delete))
                    }
                }
            }
        }
    }
}
