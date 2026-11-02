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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import kotlinx.coroutines.launch
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateFeatureAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Warranty tracker screen: list of warranties with the shared add/edit form
 * ([WarrantyFormDialog]) and auto-detected warranty review.
 *
 * Edit (warranty-edit UF-02) is reached only from the card's Edit action; the screen holds the
 * open-form flags in saved state (the draft itself is saved by the dialog) and applies the
 * ViewModel's one-shot edit outcomes only to the dialog that is currently open.
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
    var showManualAddDialog by rememberSaveable { mutableStateOf(false) }
    // warranty-edit: id of the row whose edit form is open (null = none). The draft is saved by the dialog.
    var editingWarrantyId by rememberSaveable { mutableStateOf<Long?>(null) }
    // warranty-edit: id this screen submitted for saving; a WarrantyEditResult is applied only if its
    // warrantyId equals both this id and the id of the dialog that is open now.
    var awaitingEditId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editInvalidReason by remember { mutableStateOf<WarrantyEditError?>(null) }
    // G2-9: Reject is irreversible (no undo path), so it always goes through a confirmation dialog.
    var pendingRejectWarranty by remember { mutableStateOf<Warranty?>(null) }

    // Get contextual actions for empty state
    val emptyStateActions by remember(completedActionKeys) {
        derivedStateOf {
            actionRegistry.getActions(EmptyStateScreenKeys.WARRANTY)
        }
    }

    // R29: surface bounded mutation-failure messages (string resource only) as a snackbar.
    val snackbarHostState = remember { SnackbarHostState() }
    val mutationErrorText = state.mutationError?.asString()
    LaunchedEffect(mutationErrorText) {
        if (mutationErrorText != null) {
            snackbarHostState.showSnackbar(mutationErrorText)
            viewModel.clearMutationError()
        }
    }

    // warranty-edit: close the edit form, dropping every per-dialog marker (the draft is dropped with
    // the dialog's composition) and any in-dialog banner.
    val closeEditForm = {
        editingWarrantyId = null
        awaitingEditId = null
        editInvalidReason = null
        viewModel.clearEditFormError()
    }
    val snackbarScope = rememberCoroutineScope()
    val showSnack: (String) -> Unit = { message ->
        // Separate coroutine: showSnackbar suspends until dismissed and must not block outcome delivery.
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }
    val savedText = stringResource(R.string.warranty_edit_saved)
    val savedConfirmedText = stringResource(R.string.warranty_edit_saved_confirmed)
    val notFoundText = stringResource(R.string.warranty_error_not_found)
    val notEditableText = stringResource(R.string.warranty_edit_error_not_editable)

    // Applied to the dialog open right now only; a result tagged with any other id is dropped.
    val handleEditOutcome by rememberUpdatedState<(WarrantyEditResult) -> Unit> { result ->
        val openId = editingWarrantyId
        if (openId != null && result.warrantyId == openId && awaitingEditId == openId) {
            val outcome = result.outcome
            when (outcome) {
                is WarrantyEditOutcome.Saved -> {
                    closeEditForm()
                    showSnack(if (outcome.leftReview) savedConfirmedText else savedText)
                }
                WarrantyEditOutcome.NoChange -> closeEditForm()
                WarrantyEditOutcome.NotFound -> {
                    closeEditForm()
                    showSnack(notFoundText)
                }
                WarrantyEditOutcome.NotEditable -> {
                    closeEditForm()
                    showSnack(notEditableText)
                }
                is WarrantyEditOutcome.Invalid -> {
                    awaitingEditId = null
                    editInvalidReason = outcome.reason
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        viewModel.editOutcomes
            .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .collect { result -> handleEditOutcome(result) }
    }

    // warranty-edit: the open form's row (re-resolved by id from the live list, also after process
    // death). If it is gone once the list has loaded, close with the "not found" snackbar.
    val editTarget = editingWarrantyId?.let { id -> state.allWarranties.firstOrNull { it.id == id } }
    LaunchedEffect(editingWarrantyId, editTarget == null, state.isLoading) {
        if (editingWarrantyId != null && editTarget == null && !state.isLoading) {
            closeEditForm()
            showSnack(notFoundText)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // G2-8: rows past their end date that were never confirmed (shown only when N > 0).
            if (state.expiredUnconfirmedCount > 0) {
                TextButton(onClick = { viewModel.filterByStatus(WarrantyStatus.EXPIRED) }) {
                    Text(stringResource(R.string.warranty_expired_unconfirmed_count, state.expiredUnconfirmedCount))
                }
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
            } else if (state.warranties.isEmpty() && state.allWarranties.isNotEmpty()) {
                // Warranties exist but the active filter matches none (plain readable text).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.warranty_empty_filtered_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            if (state.showNeedsReviewOnly) {
                                R.string.warranty_empty_review_message
                            } else {
                                R.string.warranty_empty_filtered_message
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                when (actionType.feature) {
                                    EmptyStateFeatureAction.AddWarranty -> { showManualAddDialog = true } // same dialog as the FAB
                                    else -> { /* other features not handled here */ }
                                }
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
                            reviewState = state.reviewStateOf(warranty),
                            isMutating = warranty.id in state.mutatingWarrantyIds,
                            onMarkClaimed = { viewModel.markAsClaimed(warranty.id) },
                            onDelete = { viewModel.deleteWarranty(warranty) },
                            // F1: Review callbacks (INV-13: the ViewModel receives the id only)
                            onConfirm = { viewModel.confirmWarranty(warranty.id) },
                            onReject = { pendingRejectWarranty = warranty },
                            // warranty-edit: opens the shared form for this row (draft starts from the row).
                            onEdit = {
                                viewModel.clearEditFormError()
                                awaitingEditId = null
                                editInvalidReason = null
                                editingWarrantyId = warranty.id
                            }
                        )
                    }
                }
            }
        }
    }

    pendingRejectWarranty?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRejectWarranty = null },
            title = { Text(stringResource(R.string.warranty_reject_confirm_title)) },
            text = { Text(stringResource(R.string.warranty_reject_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRejectWarranty = null
                        viewModel.rejectAutoDetectedWarranty(target)
                    }
                ) {
                    Text(stringResource(R.string.warranty_reject_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRejectWarranty = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Add: the same shared form as edit, in add mode (top-bar +, FAB and the empty-state action
    // all set showManualAddDialog).
    if (showManualAddDialog) {
        WarrantyFormDialog(
            editTarget = null,
            reviewState = WarrantyReviewState.NOT_IN_REVIEW,
            referenceNowMillis = state.referenceNowMillis,
            isSaving = false,
            bannerMessage = null,
            invalidReason = null,
            onInvalidReasonConsumed = {},
            onDismiss = { showManualAddDialog = false },
            onSave = { input ->
                viewModel.addManualWarranty(
                    productName = input.productName,
                    merchantName = input.merchantName,
                    purchaseDate = input.purchaseDate,
                    warrantyDurationMonths = input.durationMonths,
                    supportPhone = input.supportPhone,
                    warrantyType = input.warrantyType
                )
                showManualAddDialog = false
            }
        )
    }

    // Edit: shown once the row is resolved from the live list (see the not-found effect above).
    if (editingWarrantyId != null && editTarget != null) {
        WarrantyFormDialog(
            editTarget = editTarget,
            reviewState = state.reviewStateOf(editTarget),
            referenceNowMillis = state.referenceNowMillis,
            isSaving = editTarget.id in state.mutatingWarrantyIds,
            bannerMessage = state.editFormError?.asString(),
            invalidReason = editInvalidReason,
            onInvalidReasonConsumed = { editInvalidReason = null },
            onDismiss = closeEditForm,
            onSave = { input ->
                awaitingEditId = editTarget.id
                viewModel.editWarranty(editTarget.id, input)
            }
        )
    }
}

/**
 * Card Edit action: pencil + "Edit" (decorative icon), min height 48dp. The product name is added to
 * the spoken description only (several Edit buttons share one list); it is UI-only and never logged.
 */
@Composable
private fun EditWarrantyButton(
    productName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    asText: Boolean = false
) {
    val description = stringResource(R.string.warranty_action_edit_cd_format, productName)
    val buttonModifier = modifier
        .heightIn(min = 48.dp)
        .semantics { contentDescription = description }
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        // The visible text is replaced by the description above in the accessibility tree.
        Text(
            text = stringResource(R.string.action_edit),
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
    if (asText) {
        TextButton(onClick = onClick, enabled = enabled, modifier = buttonModifier, content = content)
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = buttonModifier, content = content)
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WarrantyCard(
    warranty: Warranty,
    dateFormat: DateTimeFormatter,
    referenceNowMillis: Long,
    // INV-10: the single review-state derivation drives border, badge, date line and actions.
    reviewState: WarrantyReviewState,
    // INV-21: Edit, Confirm, Reject, Claim and Delete are all disabled while this card's id is
    // in mutatingWarrantyIds.
    isMutating: Boolean,
    onMarkClaimed: () -> Unit,
    onDelete: () -> Unit,
    // F1: Review callbacks
    onConfirm: () -> Unit = {},
    onReject: () -> Unit = {},
    // warranty-edit: opens the shared form for this row
    onEdit: () -> Unit = {}
) {
    val actions = WarrantyEditFormState.cardActionState(warranty.status, reviewState, isMutating)
    val daysRemaining = TimePeriodUtils.daysBetween(referenceNowMillis, warranty.warrantyEndDate)
    val isExpiringSoon = daysRemaining in 0..30
    val isExpired = daysRemaining < 0
    // F1: Check if auto-detected
    val isAutoDetected = warranty.autoDetected
    val awaitingReview = reviewState == WarrantyReviewState.AWAITING_REVIEW
    val expiredUnconfirmed = reviewState == WarrantyReviewState.EXPIRED_UNCONFIRMED

    Card(
        modifier = Modifier.fillMaxWidth(),
        // F1: error border only while a review is still actionable; expired-unconfirmed gets a
        // neutral outline (nothing is urgent any more).
        border = when {
            awaitingReview ->
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.error)
            expiredUnconfirmed ->
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            else -> null
        }
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
                
                // Badge precedence: expired-unconfirmed, awaiting review, expired, days left.
                if (expiredUnconfirmed) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            stringResource(R.string.warranty_badge_expired_unconfirmed),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (awaitingReview) {
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
                text = stringResource(
                    if (expiredUnconfirmed || warranty.status == WarrantyStatus.EXPIRED) R.string.warranty_expired_date else R.string.warranty_expires_date,
                    dateFormat.format(Instant.ofEpochMilli(warranty.warrantyEndDate - 1).atZone(ZoneId.systemDefault()))
                ),
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
            // Both review states offer confirm ("Keep in history" when expired) and reject; never Claim.
            if (actions.placement == WarrantyEditPlacement.REVIEW_STACK) {
                Spacer(modifier = Modifier.height(8.dp))
                // Vertical stack so long labels wrap instead of truncating at large font scales.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = actions.enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isMutating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    if (expiredUnconfirmed) {
                                        R.string.warranty_action_keep_expired
                                    } else {
                                        R.string.warranty_action_confirm
                                    }
                                )
                            )
                        }
                    }
                    // Order: Confirm, Edit, Reject (the destructive action stays last).
                    EditWarrantyButton(
                        productName = warranty.productName,
                        enabled = actions.enabled,
                        onClick = onEdit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = onReject,
                        enabled = actions.enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.warranty_action_reject))
                    }
                }
            } else if (actions.placement == WarrantyEditPlacement.ACTIVE_ROW) {
                Spacer(modifier = Modifier.height(8.dp))
                // FlowRow so Edit, Mark Claimed and Delete wrap at large font scales / long el-es labels.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EditWarrantyButton(
                        productName = warranty.productName,
                        enabled = actions.enabled,
                        onClick = onEdit
                    )
                    TextButton(onClick = onMarkClaimed, enabled = actions.enabled) {
                        Text(stringResource(R.string.warranty_action_mark_claimed))
                    }
                    IconButton(onClick = onDelete, enabled = actions.enabled) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.warranty_action_delete))
                    }
                }
            } else if (actions.placement == WarrantyEditPlacement.EXPIRED_TEXT) {
                // Confirmed EXPIRED card: Edit only (CLAIMED / TRANSFERRED cards have no actions).
                Spacer(modifier = Modifier.height(8.dp))
                EditWarrantyButton(
                    productName = warranty.productName,
                    enabled = actions.enabled,
                    onClick = onEdit,
                    asText = true
                )
            }
        }
    }
}
