package com.yourname.expensetracker.ui.screens.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.model.ReviewCaptureAssistState
import com.yourname.expensetracker.domain.ai.model.ReviewReceiptPrefill
import com.yourname.expensetracker.ui.components.TransferDirectionBadge
import com.yourname.expensetracker.ui.components.AmountText
import com.yourname.expensetracker.ui.components.ai.CategoryAssistCard
import com.yourname.expensetracker.ui.components.ai.DedupeAssistCard
import com.yourname.expensetracker.ui.components.ai.ReceiptAssistCard
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import coil.compose.AsyncImage
import java.io.File
import com.yourname.expensetracker.ui.screens.debug.DebugViewerScreen
import com.yourname.expensetracker.domain.util.AmountUtils
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val aiExplanationStates by viewModel.aiExplanationStates.collectAsState()
    val reviewCaptureAssistStates by viewModel.reviewCaptureAssistStates.collectAsState()
    val quickApprovePreview by viewModel.quickApprovePreview.collectAsState()
    val reviewQuickApproveEnabled by viewModel.reviewQuickApproveEnabled.collectAsState()
    val operationState by viewModel.operationState.collectAsState()
    val inFlightMutationKinds by viewModel.inFlightMutationKinds.collectAsState()
    val locationEditState by viewModel.locationEditState.collectAsState()

    // S6-014: editingReview driven by uiEvents, not by prefill maps
    var editingReview by remember { mutableStateOf<PendingReviewWithReceipt?>(null) }
    var editPrefillCategoryId by remember { mutableStateOf<Long?>(null) }
    var editPrefillReceipt by remember { mutableStateOf<com.yourname.expensetracker.domain.ai.model.ReviewReceiptPrefill?>(null) }

    var debugReview by remember { mutableStateOf<PendingReview?>(null) }
    val processingIds = remember { mutableStateListOf<Long>() }
    val haptic = rememberHapticFeedback()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isBatchProcessing = operationState != null
    
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showDebugMenu by remember { mutableStateOf(false) }
    var debugInfoDialogText by remember { mutableStateOf<String?>(null) }
    var showDebugViewer by remember { mutableStateOf(false) }
    var showApproveAllConfirm by remember { mutableStateOf(false) }
    var showClearDebugConfirm by remember { mutableStateOf(false) }
    var showClearScannedConfirm by remember { mutableStateOf(false) }
    var showClearQueueConfirm by remember { mutableStateOf(false) }
    val debugData by viewModel.debugData.collectAsState()
    val debugActionsEnabled = BuildConfig.DEBUG

    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.processBatch(uris)
        }
    }

    val statementLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.processStatement(it) }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // S6-005: Close edit dialog only after successful approval
    LaunchedEffect(Unit) {
        viewModel.editApproveSuccess.collect { _ ->
            editingReview = null
            editPrefillCategoryId = null
            editPrefillReceipt = null
        }
    }

    // S6-014: Collect one-shot OpenEditWithPrefill events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is ReviewUiEvent.OpenEditWithPrefill -> {
                    val item = pendingReviews.firstOrNull { it.review.id == event.reviewId }
                    if (item != null) {
                        editPrefillCategoryId = event.categoryId
                        editPrefillReceipt = event.receiptPrefill
                        // S6-018: Init location state from review before opening sheet
                        viewModel.initLocationForReview(
                            lat = item.review.suggestedLatitude,
                            lon = item.review.suggestedLongitude,
                            address = null
                        )
                        editingReview = item
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.review_queue_title_format, pendingCount), 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                ),
                actions = {
                    val approveAllCd = stringResource(R.string.review_approve_all_cd)
                    IconButton(
                        onClick = { showApproveAllConfirm = true },
                        enabled = pendingCount > 0 && !isBatchProcessing,
                        modifier = Modifier.semantics { contentDescription = approveAllCd }
                    ) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = null)
                    }

                    if (debugActionsEnabled) {
                        // Debug viewer button (show when debug data is available)
                        if (debugData != null) {
                            val viewDebugCd = stringResource(R.string.review_view_debug_cd)
                            IconButton(
                                onClick = { showDebugViewer = true },
                                modifier = Modifier.semantics { contentDescription = viewDebugCd }
                            ) {
                                Icon(Icons.Rounded.BugReport, contentDescription = null)
                            }
                        }

                        val debugMenuCd = stringResource(R.string.review_debug_menu_cd)
                        Box {
                            IconButton(
                                onClick = { showDebugMenu = !showDebugMenu },
                                modifier = Modifier.semantics { contentDescription = debugMenuCd }
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showDebugMenu,
                                onDismissRequest = { showDebugMenu = false }
                            ) {
                                val massInsertCd = stringResource(R.string.review_mass_insert_cd)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_mass_insert)) },
                                    onClick = {
                                        showDebugMenu = false
                                        batchLauncher.launch(arrayOf("image/*", "application/pdf"))
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Layers, contentDescription = null) },
                                    modifier = Modifier.semantics { contentDescription = massInsertCd }
                                )
                                val importStatementCd = stringResource(R.string.review_import_statement_cd)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_import_bank_statement)) },
                                    onClick = {
                                        showDebugMenu = false
                                        statementLauncher.launch(arrayOf("image/*", "application/pdf"))
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.ReceiptLong, contentDescription = null) },
                                    modifier = Modifier.semantics { contentDescription = importStatementCd }
                                )
                                HorizontalDivider()
                                val exportParserCd = stringResource(R.string.review_export_parser_cd)
                                val copiedToast = stringResource(R.string.review_copied_toast)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_export_parser_data)) },
                                    onClick = {
                                        showDebugMenu = false
                                        coroutineScope.launch {
                                            val data = viewModel.getDebugExportData()
                                            clipboardManager.setText(AnnotatedString(data))
                                            snackbarHostState.showSnackbar(copiedToast)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                    modifier = Modifier.semantics { contentDescription = exportParserCd }
                                )
                                HorizontalDivider()
                                val clearDebugCd = stringResource(R.string.review_clear_debug_cd)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_clear_debug_data)) },
                                    onClick = {
                                        showDebugMenu = false
                                        showClearDebugConfirm = true
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.semantics { contentDescription = clearDebugCd }
                                )
                                val clearScannedCd = stringResource(R.string.review_clear_scanned_cd)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_clear_scanned_data)) },
                                    onClick = {
                                        showDebugMenu = false
                                        showClearScannedConfirm = true
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.semantics { contentDescription = clearScannedCd }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.review_clear_queue)) },
                                    onClick = {
                                        showDebugMenu = false
                                        showClearQueueConfirm = true
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.RemoveCircle, null) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            val emptyCd = stringResource(R.string.review_empty_cd)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .semantics { contentDescription = emptyCd },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = SemanticColors.SuccessGreen
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.review_empty_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.review_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.review_swipe_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                items(pendingReviews, key = { it.review.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (processingIds.contains(item.review.id)) return@rememberSwipeToDismissBoxState false
                            
                            when (dismissValue) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Success)
                                    viewModel.approveReview(item.review.id)
                                    true
                                }
                                SwipeToDismissBoxValue.EndToStart -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Error)
                                    viewModel.rejectReview(item.review.id)
                                    true
                                }
                                else -> false
                            }
                        }
                    )

                    val noReceiptDebugMsg = stringResource(R.string.review_no_receipt_debug)
                    val loadingText = stringResource(R.string.label_loading)

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> SemanticColors.SuccessGreen
                                SwipeToDismissBoxValue.EndToStart -> SemanticColors.DangerRed
                                else -> Color.Transparent
                            }
                            val alignment = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                            val icon = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Icons.Rounded.CheckCircle
                                SwipeToDismissBoxValue.EndToStart -> Icons.Rounded.Delete
                                else -> Icons.AutoMirrored.Rounded.ArrowForward
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 24.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(icon, null, tint = Color.White)
                            }
                        },
                        content = {
                            ReviewCard(
                                item = item,
                                categories = categories,
                                mutationKind = inFlightMutationKinds[item.review.id],
                                onApprove = { viewModel.approveReview(item.review.id) },
                                onReject = { viewModel.rejectReview(item.review.id) },
                                onEdit = {
                                    viewModel.initLocationForReview(
                                        lat = item.review.suggestedLatitude,
                                        lon = item.review.suggestedLongitude,
                                        address = null
                                    )
                                    editPrefillCategoryId = null
                                    editPrefillReceipt = null
                                    editingReview = item
                                },
                                onDebug = {
                                    if (debugActionsEnabled) {
                                        item.receipt?.let { receipt ->
                                            coroutineScope.launch {
                                                debugInfoDialogText = loadingText
                                                debugInfoDialogText = viewModel.getReceiptDebugInfo(receipt.id)
                                            }
                                        } ?: run {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(noReceiptDebugMsg)
                                            }
                                        }
                                        debugReview = item.review
                                    }
                                },
                                debugEnabled = debugActionsEnabled,
                                aiExplanationState = aiExplanationStates[item.review.id]
                                    ?: AiLoadState.Idle,
                                captureAssistState = reviewCaptureAssistStates[item.review.id]
                                    ?: ReviewCaptureAssistState(),
                                onLoadAiExplanation = {
                                    viewModel.loadAiExplanation(item.review.id)
                                },
                                onLoadCategoryAssist = {
                                    viewModel.requestCategoryAssist(item.review.id)
                                },
                                // S6-014: applyCategorySuggestion emits OpenEditWithPrefill event
                                onApplyCategoryAssist = {
                                    viewModel.applyCategorySuggestion(item.review.id)
                                },
                                onLoadReceiptAssist = {
                                    viewModel.requestReceiptAssist(item.review.id, force = true)
                                },
                                // S6-014: applyReceiptSuggestion emits OpenEditWithPrefill event
                                onApplyReceiptAssist = {
                                    viewModel.applyReceiptSuggestion(item.review.id, ReviewViewModel.ReceiptApplyField.ALL)
                                },
                                onApplyReceiptAssistField = { field ->
                                    viewModel.applyReceiptSuggestion(item.review.id, field)
                                },
                                reviewQuickApproveEnabled = reviewQuickApproveEnabled,
                                canQuickApprove = viewModel.canOfferQuickApprove(item.review.id),
                                onRequestQuickApprove = {
                                    viewModel.requestQuickApprovePreview(item.review.id)
                                },
                                onDismissCategoryAssist = {
                                    viewModel.dismissCategoryAssist(item.review.id)
                                },
                                onDismissReceiptAssist = {
                                    viewModel.dismissReceiptAssist(item.review.id)
                                },
                                onLoadDedupeAssist = {
                                    viewModel.requestDedupeAssist(item.review.id)
                                },
                                onDismissDedupeAssist = {
                                    viewModel.dismissDedupeAssist(item.review.id)
                                }
                            )
                        }
                    )
                }
            }
        }

        editingReview?.let { item ->
            // S6-003: Key by review ID so Compose resets remembered state when switching reviews
            key(item.review.id, editPrefillCategoryId, editPrefillReceipt) {
                EditReviewDialog(
                    review = item.review,
                    receipt = item.receipt,
                    categories = categories,
                    onDismiss = {
                        editingReview = null
                        editPrefillCategoryId = null
                        editPrefillReceipt = null
                    },
                    onSave = { amount, merchant, categoryId, date, type, transferDir, transferAcct, applyToAll, approveAllPending, lat, lon, address, osmId, currency ->
                        viewModel.approveReviewWithEdits(
                            reviewId = item.review.id,
                            finalAmount = amount,
                            finalMerchant = merchant,
                            finalCategoryId = categoryId,
                            finalDate = date,
                            finalType = type,
                            finalTransferDirection = transferDir,
                            finalTransferAccountName = transferAcct,
                            applyToAll = applyToAll,
                            approveAllPending = approveAllPending,
                            locationCleared = viewModel.locationEditState.value.locationCleared,
                            finalLatitude = lat,
                            finalLongitude = lon,
                            finalAddress = address,
                            finalPlaceId = osmId,
                            finalCurrency = currency
                        )
                        // S6-005: Dialog closes via editApproveSuccess LaunchedEffect, not here
                    },
                    initialCategoryIdOverride = editPrefillCategoryId,
                    initialReceiptPrefill = editPrefillReceipt,
                    locationEditState = locationEditState,
                    onLocationQueryChanged = viewModel::onLocationQueryChanged,
                    onLocationSelected = viewModel::onLocationSelected,
                    onLocationCleared = viewModel::onLocationCleared
                )
            }
        }

        quickApprovePreview?.let { preview ->
            AlertDialog(
                onDismissRequest = viewModel::dismissQuickApprovePreview,
                title = { Text(stringResource(R.string.review_approve_suggested_category_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.review_approve_suggested_category_description),
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.review_merchant_label, preview.merchant), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.review_amount_label, AmountUtils.formatAmount(preview.amount)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.review_category_label, preview.categoryName), style = MaterialTheme.typography.bodySmall)
                        preview.diagnostics.forEach { diagnostics ->
                            Text(
                                diagnostics,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmQuickApprove) {
                        Text(stringResource(R.string.review_approve_review_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissQuickApprovePreview) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }

        if (debugActionsEnabled) {
            debugReview?.let { review ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { debugReview = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.yourname.expensetracker.ui.screens.debug.CategorizationDebugScreen(
                        initialMerchant = review.suggestedMerchant,
                        initialAmount = review.suggestedAmount ?: 0.0,
                        initialTimestamp = review.createdAt,
                        onNavigateBack = { debugReview = null }
                    )
                }
            }
        }

            debugInfoDialogText?.let { info ->
            val copiedToast = stringResource(R.string.review_copied_toast)
            AlertDialog(
                onDismissRequest = { debugInfoDialogText = null },
                title = { Text(stringResource(R.string.review_receipt_debug_info_title)) },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(info))
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(copiedToast)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.review_copy_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { debugInfoDialogText = null }) {
                        Text(stringResource(R.string.review_close_button))
                    }
                }
            )
        }
        }

        if (showApproveAllConfirm) {
            AlertDialog(
                onDismissRequest = { showApproveAllConfirm = false },
                title = { Text(stringResource(R.string.review_approve_all_title)) },
                text = { Text(stringResource(R.string.review_approve_all_message, pendingCount)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showApproveAllConfirm = false
                            viewModel.approveAll()
                        },
                        enabled = !isBatchProcessing
                    ) {
                        Text(stringResource(R.string.review_approve_all_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showApproveAllConfirm = false }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }

        if (showClearDebugConfirm) {
            AlertDialog(
                onDismissRequest = { showClearDebugConfirm = false },
                title = { Text(stringResource(R.string.review_clear_debug_confirm_title)) },
                text = { Text(stringResource(R.string.review_clear_debug_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDebugConfirm = false
                            viewModel.clearDebugData()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDebugConfirm = false }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }

        if (showClearScannedConfirm) {
            AlertDialog(
                onDismissRequest = { showClearScannedConfirm = false },
                title = { Text(stringResource(R.string.review_clear_scanned_confirm_title)) },
                text = { Text(stringResource(R.string.review_clear_scanned_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearScannedConfirm = false
                            viewModel.clearScannedData()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearScannedConfirm = false }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }

        if (showClearQueueConfirm) {
            AlertDialog(
                onDismissRequest = { showClearQueueConfirm = false },
                title = { Text(stringResource(R.string.review_clear_queue_confirm_title)) },
                text = { Text(stringResource(R.string.review_clear_queue_confirm_message, pendingCount)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearQueueConfirm = false
                            viewModel.rejectAll()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearQueueConfirm = false }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }

        // Batch processing overlay
        if (isBatchProcessing) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = SemanticColors.PrimaryLight)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.review_processing_batch),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    // S6-008: Only show progress bar when we have real determinate progress
                    operationState?.let { op ->
                        if (op.current != null && op.total != null && op.total > 0) {
                            Text(
                                "${op.current} / ${op.total}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { op.current.toFloat() / op.total },
                                modifier = Modifier.width(200.dp),
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                        // S6-009: Only show cancel for cancellable operations
                        if (op.canCancel) {
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(onClick = viewModel::cancelBatchProcessing) {
                                Text(stringResource(R.string.cancel_button), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        
        // Debug Viewer Dialog
        if (debugActionsEnabled && showDebugViewer) {
            debugData?.let { data ->
                DebugViewerScreen(
                    debugData = data,
                    onClose = { showDebugViewer = false }
                )
            }
        }
    }
}

@Composable
fun ReviewCard(
    item: PendingReviewWithReceipt,
    categories: List<Category>,
    mutationKind: String? = null,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onDebug: () -> Unit,
    debugEnabled: Boolean = false,
    aiExplanationState: AiLoadState<ReviewExplanationUi> = AiLoadState.Idle,
    captureAssistState: ReviewCaptureAssistState = ReviewCaptureAssistState(),
    onLoadAiExplanation: () -> Unit = {},
    onLoadCategoryAssist: () -> Unit = {},
    onApplyCategoryAssist: () -> Unit = {},
    onLoadReceiptAssist: () -> Unit = {},
    onApplyReceiptAssist: () -> Unit = {},
    onApplyReceiptAssistField: (ReviewViewModel.ReceiptApplyField) -> Unit = { onApplyReceiptAssist() },
    reviewQuickApproveEnabled: Boolean = false,
    canQuickApprove: Boolean = false,
    onRequestQuickApprove: () -> Unit = {},
    onDismissCategoryAssist: () -> Unit = {},
    onDismissReceiptAssist: () -> Unit = {},
    onLoadDedupeAssist: () -> Unit = {},
    onDismissDedupeAssist: () -> Unit = {}
) {
    val review = item.review
    val quickApproveBlockedByDuplicate =
        ((captureAssistState.dedupeSuggestion as? AiLoadState.Ready)?.value?.verdict == DuplicateVerdict.LIKELY_DUPLICATE)
    // S6-025: Derive per-button states from mutationKind
    val isAnyMutationInFlight = mutationKind != null
    val isApproving = mutationKind == "approve" || mutationKind == "edit"
    val isRejecting = mutationKind == "reject"
    
    // Find the suggested category
    val suggestedCategory = categories.find { it.id == review.suggestedCategoryId }
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.getDefault()) }
    var showTrustSignal by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()

    val confidenceColor = when {
        review.confidence >= 0.85f -> SemanticColors.SuccessGreen
        review.confidence >= 0.65f -> SemanticColors.WarningOrange
        else -> SemanticColors.DangerRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.packageName.split(".").lastOrNull()?.uppercase() ?: stringResource(R.string.review_system),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                Surface(
                    color = confidenceColor.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.review_confidence_format, (review.confidence * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = confidenceColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                // Receipt Thumbnail if available
                if (item.receipt?.imagePath != null) {
                    Card(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
                    ) {
                        AsyncImage(
                            model = item.receipt.imagePath?.let { File(it) },
                            contentDescription = stringResource(R.string.review_receipt_thumbnail_cd),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.suggestedMerchant,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = dateFormat.format(Instant.ofEpochMilli(review.createdAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // S6-006: Show unavailable label instead of misleading 0.0
                        if (review.suggestedAmount != null) {
                            AmountText(
                                amount = review.suggestedAmount,
                                style = MaterialTheme.typography.headlineSmall,
                                color = SemanticColors.TextPrimary
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.review_amount_required),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        
                        // Category icon if available
                        if (suggestedCategory != null) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = try {
                                    android.graphics.Color.parseColor(suggestedCategory.color).toLong().let { 
                                        androidx.compose.ui.graphics.Color(it).copy(alpha = 0.2f) 
                                    }
                                } catch (e: Exception) {
                                    SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                }
                            ) {
                                Text(
                                    text = suggestedCategory.icon,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        }
                    }
                    
                    // Transfer Direction Badge (for transfers and deposits)
                    if (review.suggestedType == TransactionType.TRANSFER.name || 
                        review.suggestedType == TransactionType.DEPOSIT.name) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TransferDirectionBadge(
                            direction = parseTransferDirectionOrNull(review.suggestedDirection),
                            accountName = review.suggestedAccountName,
                            compact = true
                        )
                    }

                    // Location chip (if GPS was captured at review time)
                    if (review.suggestedLatitude != null && review.suggestedLongitude != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = SemanticColors.PrimaryIndigo
                                )
                                Text(
                                    text = "%.4f, %.4f".format(review.suggestedLatitude, review.suggestedLongitude),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SemanticColors.PrimaryIndigo
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Trust Signal / Detailed Evidence
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.BaseNavy.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, SemanticColors.GlassBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .then(
                        if (debugEnabled) {
                            Modifier.clickable {
                                haptic(HapticType.Standard)
                                onDebug()
                            }
                        } else {
                            Modifier
                        }
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.review_raw_evidence_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (showTrustSignal) {
                            TextButton(onClick = { showTrustSignal = false }) {
                                Text(stringResource(R.string.review_hide_button))
                            }
                        } else {
                            TextButton(onClick = { showTrustSignal = true }) {
                                Text(stringResource(R.string.a11y_expand))
                            }
                        }
                        Icon(
                            Icons.Rounded.BugReport,
                            null,
                            tint = SemanticColors.TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                AnimatedVisibility(visible = showTrustSignal) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        // S6-020: Only show raw text if it exists (null = privacy policy purged it)
                        val displayText = when {
                            review.notificationText.isNullOrBlank() ->
                                stringResource(R.string.review_raw_evidence_privacy_hidden)
                            else -> review.notificationText
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (review.notificationText.isNullOrBlank())
                                SemanticColors.TextSecondary
                            else
                                SemanticColors.TextPrimary,
                            fontFamily = if (review.notificationText.isNullOrBlank())
                                androidx.compose.ui.text.font.FontFamily.Default
                            else
                                androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── AI Explanation Section ────────────────────────────────────────
            // Separate from the raw evidence panel above; never touches approve/reject flow.
            AiExplanationSection(
                state = aiExplanationState,
                onRequest = onLoadAiExplanation
            )

            Spacer(modifier = Modifier.height(12.dp))

            ReviewCaptureAssistSection(
                item = item,
                state = captureAssistState,
                onRequestReceiptAssist = onLoadReceiptAssist,
                onApplyReceiptAssist = onApplyReceiptAssist,
                onApplyReceiptAssistField = onApplyReceiptAssistField,
                onRequestCategoryAssist = onLoadCategoryAssist,
                onApplyCategoryAssist = onApplyCategoryAssist,
                reviewQuickApproveEnabled = reviewQuickApproveEnabled,
                canQuickApprove = canQuickApprove,
                onRequestQuickApprove = onRequestQuickApprove,
                quickApproveBlockedByDuplicate = quickApproveBlockedByDuplicate,
                onDismissCategoryAssist = onDismissCategoryAssist,
                onDismissReceiptAssist = onDismissReceiptAssist,
                onRequestDedupeAssist = onLoadDedupeAssist,
                onDismissDedupeAssist = onDismissDedupeAssist
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val editCd = stringResource(R.string.review_edit_cd)
                OutlinedIconButton(
                    onClick = {
                        haptic(HapticType.Heavy)
                        onEdit()
                    },
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isAnyMutationInFlight
                ) {
                    Icon(Icons.Rounded.Edit, editCd, modifier = Modifier.size(20.dp))
                }

                Button(
                    onClick = {
                        haptic(HapticType.Error)
                        onReject()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isAnyMutationInFlight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (isRejecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onErrorContainer)
                    } else {
                        Text(stringResource(R.string.review_reject_button), fontWeight = FontWeight.Bold)
                    }
                }

                // S6-D5-004: Missing required fields — disable approve, show edit CTA
                val review = item.review
                val canDirectApprove = review.suggestedAmount != null &&
                    review.suggestedAmount > 0.0 &&
                    review.suggestedMerchant.isNotBlank() &&
                    review.suggestedMerchant != "Unknown"

                Button(
                    onClick = {
                        if (canDirectApprove) {
                            haptic(HapticType.Success)
                            onApprove()
                        } else {
                            onEdit()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isAnyMutationInFlight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.SuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    if (isApproving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else if (!canDirectApprove) {
                        Text(stringResource(R.string.review_edit_required_button), fontWeight = FontWeight.Bold)
                    } else {
                        Text(stringResource(R.string.review_approve_button), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * AI explanation surface for a single review card.
 *
 * States:
 * - [AiLoadState.Disabled] / [AiLoadState.Idle] — shows a subtle "Explain with AI" button.
 * - [AiLoadState.Loading]  — shows an inline progress indicator.
 * - [AiLoadState.Ready]    — shows headline + body text.
 * - [AiLoadState.Error]    — shows error message with a retry affordance.
 *
 * This section is completely non-destructive: it never touches approve/reject/edit logic.
 */
@Composable
private fun AiExplanationSection(
    state: AiLoadState<ReviewExplanationUi>,
    onRequest: () -> Unit
) {
    when (state) {
        is AiLoadState.Disabled, is AiLoadState.Idle -> {
            // Show a minimal "Explain with AI" prompt — only visible when AI is on
            // For Disabled we hide it entirely; for Idle we show the tap affordance.
            if (state is AiLoadState.Idle) {
                OutlinedButton(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.4f))
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = SemanticColors.PrimaryIndigo
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.review_explain_ai_button),
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.PrimaryIndigo
                    )
                }
            }
        }

        is AiLoadState.Loading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.PrimaryIndigo.copy(alpha = 0.06f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = SemanticColors.PrimaryIndigo
                )
                Text(
                    stringResource(R.string.review_generating_explanation),
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.PrimaryIndigo
                )
            }
        }

        is AiLoadState.Ready -> {
            val ui = state.value
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.PrimaryIndigo.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        SemanticColors.PrimaryIndigo.copy(alpha = 0.25f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SemanticColors.PrimaryIndigo
                    )
                    Text(
                        stringResource(R.string.review_ai_explanation_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.PrimaryIndigo,
                        letterSpacing = 1.sp
                    )
                }
                if (ui.headline.isNotBlank()) {
                    Text(
                        ui.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticColors.TextPrimary
                    )
                }
                if (ui.body.isNotBlank()) {
                    Text(
                        ui.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary
                    )
                }
                ui.diagnostics?.let { diagnostics ->
                    Text(
                        diagnostics,
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary.copy(alpha = 0.8f)
                    )
                }
                ui.caution?.let { caution ->
                    Text(
                        caution,
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.WarningOrange
                    )
                }
            }
        }

        is AiLoadState.Error -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.review_ai_explanation_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRequest) {
                    Text(
                        stringResource(R.string.review_retry_button),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.PrimaryIndigo
                    )
                }
            }
        }
    }
}

internal fun parseTransferDirectionOrNull(raw: String?): TransferDirection? {
    val normalized = raw?.trim().takeUnless { it.isNullOrBlank() }
        ?: return null
    return TransferDirection.entries.firstOrNull { it.name == normalized }
}

internal fun parseTransactionTypeOrNull(raw: String?): TransactionType? {
    val normalized = raw?.trim().takeUnless { it.isNullOrBlank() }
        ?: return null
    return TransactionType.entries.firstOrNull { it.name == normalized }
}

@Composable
private fun ReviewCaptureAssistSection(
    item: PendingReviewWithReceipt,
    state: ReviewCaptureAssistState,
    onRequestReceiptAssist: () -> Unit,
    onApplyReceiptAssist: () -> Unit,
    onApplyReceiptAssistField: (ReviewViewModel.ReceiptApplyField) -> Unit = { onApplyReceiptAssist() },
    onRequestCategoryAssist: () -> Unit,
    onApplyCategoryAssist: () -> Unit,
    reviewQuickApproveEnabled: Boolean,
    canQuickApprove: Boolean,
    onRequestQuickApprove: () -> Unit,
    quickApproveBlockedByDuplicate: Boolean,
    onDismissCategoryAssist: () -> Unit,
    onDismissReceiptAssist: () -> Unit,
    onRequestDedupeAssist: () -> Unit,
    onDismissDedupeAssist: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (item.receipt != null) {
            when (val receiptState = state.receiptSuggestion) {
                is AiLoadState.Idle, is AiLoadState.Disabled -> {
                    OutlinedButton(onClick = onRequestReceiptAssist, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.review_try_receipt_assist))
                    }
                    state.receiptMessage?.let { message ->
                        AssistInfoRow(message = message)
                    }
                }
                is AiLoadState.Loading -> {
                    AssistLoadingRow(stringResource(R.string.review_reviewing_receipt))
                }
                is AiLoadState.Ready -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReceiptAssistCard(
                            suggestion = receiptState.value,
                            diagnostics = state.receiptDiagnostics,
                            onApplyMerchant = { onApplyReceiptAssistField(ReviewViewModel.ReceiptApplyField.MERCHANT) },
                            onApplyTotal = { onApplyReceiptAssistField(ReviewViewModel.ReceiptApplyField.AMOUNT) },
                            onApplyDate = { onApplyReceiptAssistField(ReviewViewModel.ReceiptApplyField.DATE) },
                            onApplyAll = onApplyReceiptAssist,
                            onDismiss = onDismissReceiptAssist
                        )
                        state.receiptMessage?.let { message ->
                            AssistInfoRow(message = message)
                        }
                    }
                }
                is AiLoadState.Error -> {
                    AssistErrorRow(
                        message = receiptState.message,
                        diagnostics = state.receiptDiagnostics,
                        onRetry = onRequestReceiptAssist
                    )
                }
            }
        }

        when (val categoryState = state.categorySuggestion) {
            is AiLoadState.Idle -> {
                OutlinedButton(onClick = onRequestCategoryAssist, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_suggest_category))
                }
            }
            is AiLoadState.Loading -> {
                AssistLoadingRow(stringResource(R.string.review_checking_category))
            }
            is AiLoadState.Ready -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryAssistCard(
                        suggestion = categoryState.value,
                        diagnostics = state.categoryDiagnostics,
                        onApply = onApplyCategoryAssist,
                        onDismiss = onDismissCategoryAssist
                    )
                    if (canQuickApprove) {
                        FilledTonalButton(
                            onClick = onRequestQuickApprove,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.review_quick_approve_category))
                        }
                    } else if (reviewQuickApproveEnabled && quickApproveBlockedByDuplicate) {
                        AssistInfoRow(
                            message = stringResource(R.string.review_quick_approve_blocked)
                        )
                    }
                }
            }
            is AiLoadState.Error -> {
                AssistErrorRow(
                    message = categoryState.message,
                    diagnostics = state.categoryDiagnostics,
                    onRetry = onRequestCategoryAssist
                )
            }
            is AiLoadState.Disabled -> Unit
        }

        when (val dedupeState = state.dedupeSuggestion) {
            is AiLoadState.Idle -> {
                OutlinedButton(onClick = onRequestDedupeAssist, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_check_duplicates))
                }
            }
            is AiLoadState.Loading -> {
                AssistLoadingRow(stringResource(R.string.review_checking_duplicates))
            }
            is AiLoadState.Ready -> {
                DedupeAssistCard(
                    suggestion = dedupeState.value,
                    diagnostics = state.dedupeDiagnostics,
                    onDismiss = onDismissDedupeAssist
                )
            }
            is AiLoadState.Error -> {
                AssistErrorRow(
                    message = dedupeState.message,
                    diagnostics = state.dedupeDiagnostics,
                    onRetry = onRequestDedupeAssist
                )
            }
            is AiLoadState.Disabled -> Unit
        }
    }
}

@Composable
private fun AssistInfoRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SemanticColors.WarningOrange.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                SemanticColors.WarningOrange.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = SemanticColors.WarningOrange
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.WarningOrange
        )
    }
}

@Composable
private fun AssistLoadingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SemanticColors.PrimaryIndigo.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = SemanticColors.PrimaryIndigo
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryIndigo)
    }
}

@Composable
private fun AssistErrorRow(
    message: String,
    diagnostics: String? = null,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.review_retry_button), color = SemanticColors.PrimaryIndigo)
                }
        }
        diagnostics?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * EditReviewDialog — converted from AlertDialog to ModalBottomSheet (B9 fix).
 *
 * AlertDialog constrains its content height, which made the embedded
 * LocationSearchPicker map (260dp) effectively unusable — it was either
 * clipped or unreachable. ModalBottomSheet with skipPartiallyExpanded = true
 * gives the content full screen height, consistent with EditLocationDialog,
 * LocationCorrectionSheet, and PinExpenseSheet.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditReviewDialog(
    review: PendingReview,
    receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?, Long?, TransactionType?, TransferDirection?, String?, Boolean, Boolean, Double?, Double?, String?, String?, String?) -> Unit,
    initialCategoryIdOverride: Long? = null,
    initialReceiptPrefill: ReviewReceiptPrefill? = null,
    locationEditState: ReviewLocationEditState = ReviewLocationEditState(),
    onLocationQueryChanged: (String) -> Unit = {},
    onLocationSelected: (com.yourname.expensetracker.domain.location.GeocodingResult) -> Unit = {},
    onLocationCleared: () -> Unit = {}
) {
    var amount by remember {
        mutableStateOf(String.format("%.2f", initialReceiptPrefill?.amount ?: review.suggestedAmount ?: 0.0))
    }
    var merchant by remember { mutableStateOf(initialReceiptPrefill?.merchant ?: review.suggestedMerchant) }
    // S6-D5-003: Currency is editable — null means user must select before approval
    var selectedCurrency by remember { mutableStateOf(review.suggestedCurrency ?: "") }
    var selectedDateMs by remember { mutableStateOf(initialReceiptPrefill?.date ?: review.suggestedDate ?: review.createdAt) }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryIdOverride ?: review.suggestedCategoryId) }
    var selectedType by remember { 
        mutableStateOf(
            parseTransactionTypeOrNull(review.suggestedType) ?: TransactionType.PURCHASE
        )
    }
    var applyToAll by remember { mutableStateOf(false) }
    var approveAllPending by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()

    // S6-017: Transfer metadata fields
    var transferDirection by remember {
        mutableStateOf(parseTransferDirectionOrNull(review.suggestedDirection))
    }
    var transferAccount by remember { mutableStateOf(review.suggestedAccountName ?: "") }

    // S6-018: Location state comes from ViewModel — no local lat/lon/address vars
    var showLocationPicker by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title
            Text(
                stringResource(R.string.review_fix_extraction_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Transaction Type Selector
            Text(
                stringResource(R.string.review_transaction_type_label),
                style = MaterialTheme.typography.labelMedium
            )
            
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TransactionType.entries.filter { it != TransactionType.UNKNOWN }.forEach { type ->
                    val isSelected = selectedType == type
                    val typeColor = when (type) {
                        TransactionType.PURCHASE -> SemanticColors.DangerRed
                        TransactionType.DEPOSIT -> SemanticColors.SuccessGreen
                        TransactionType.WITHDRAWAL -> SemanticColors.WarningOrange
                        TransactionType.TRANSFER -> SemanticColors.PrimaryIndigo
                        else -> SemanticColors.TextSecondary
                    }
                    val typeIcon = when (type) {
                        TransactionType.PURCHASE -> "💸"
                        TransactionType.DEPOSIT -> "💰"
                        TransactionType.WITHDRAWAL -> "🏧"
                        TransactionType.TRANSFER -> "🔄"
                        else -> "❓"
                    }
                    Surface(
                        onClick = { 
                            haptic(HapticType.Standard)
                            selectedType = type
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) typeColor.copy(alpha = 0.2f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (isSelected) typeColor else SemanticColors.GlassBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(typeIcon)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                type.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) typeColor else SemanticColors.TextSecondary
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(stringResource(R.string.review_merchant_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // S6-017: Transfer metadata — only shown when type is TRANSFER
            if (selectedType == TransactionType.TRANSFER) {
                Text(
                    stringResource(R.string.review_transfer_metadata_label),
                    style = MaterialTheme.typography.labelMedium
                )
                val directions = TransferDirection.entries
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    directions.forEach { dir ->
                        val isSelected = transferDirection == dir
                        Surface(
                            onClick = { transferDirection = dir },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) SemanticColors.PrimaryIndigo.copy(alpha = 0.2f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) SemanticColors.PrimaryIndigo else SemanticColors.GlassBorder)
                        ) {
                            Text(
                                dir.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) SemanticColors.PrimaryIndigo else SemanticColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = transferAccount,
                    onValueChange = { transferAccount = it },
                    label = { Text(stringResource(R.string.review_transfer_account_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text(
                    // S6-016: Show actual currency from review, not hardcoded EUR
                    if (selectedCurrency.isNotBlank()) "Amount ($selectedCurrency)"
                    else stringResource(R.string.review_amount_label_generic)
                ) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // S6-D5-003: Currency picker — required for synthetic placeholders
            OutlinedTextField(
                value = selectedCurrency,
                onValueChange = { selectedCurrency = it.uppercase().take(3) },
                label = { Text(stringResource(R.string.review_currency_label)) },
                singleLine = true,
                placeholder = { Text("USD") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                isError = selectedCurrency.isBlank()
            )

            DateSelector(
                dateMs = selectedDateMs,
                onDateSelected = { selectedDateMs = it }
            )

            if (initialReceiptPrefill != null || receipt != null) {
                val aiHints = buildList {
                    if (initialReceiptPrefill?.merchant != null) add(stringResource(R.string.receipt_merchant_label).lowercase())
                    if (initialReceiptPrefill?.amount != null) add(stringResource(R.string.receipt_total_amount_label).lowercase())
                    if (initialReceiptPrefill?.date != null) add(stringResource(R.string.date_label))
                }
                AssistInfoRow(
                    message = if (aiHints.isNotEmpty()) {
                        stringResource(R.string.review_ai_prefilled_format, aiHints.joinToString(", "))
                    } else {
                        stringResource(R.string.review_receipt_assist_hint)
                    }
                )
            }

            Text(
                stringResource(R.string.review_assign_category),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp)
            )

            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { category ->
                    Surface(
                        onClick = { 
                            haptic(HapticType.Standard)
                            selectedCategoryId = category.id 
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedCategoryId == category.id)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedCategoryId == category.id) MaterialTheme.colorScheme.primary else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(category.icon, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(category.name, style = MaterialTheme.typography.bodyMedium)
                            if (selectedCategoryId == category.id) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { applyToAll = !applyToAll }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = applyToAll,
                    onCheckedChange = { applyToAll = it }
                )
                Text(
                    text = stringResource(R.string.review_apply_all_format, merchant),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { approveAllPending = !approveAllPending }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = approveAllPending,
                    onCheckedChange = { approveAllPending = it },
                    colors = CheckboxDefaults.colors(checkedColor = SemanticColors.SuccessGreen)
                )
                Text(
                    text = stringResource(R.string.review_approve_all_identical),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                    color = SemanticColors.SuccessGreen,
                    fontWeight = FontWeight.Bold
                )
            }

            // Location section — S6-018: driven by VM locationEditState
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.review_location_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row {
                    if (locationEditState.selectedLat != null) {
                        TextButton(onClick = onLocationCleared) {
                            Text(stringResource(R.string.review_clear_button), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { showLocationPicker = !showLocationPicker }) {
                        Text(
                            if (showLocationPicker) stringResource(R.string.review_hide_button)
                            else if (locationEditState.selectedLat != null) stringResource(R.string.review_edit_button)
                            else stringResource(R.string.review_add_button)
                        )
                    }
                }
            }
            if (locationEditState.selectedLat != null && !showLocationPicker) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SemanticColors.PrimaryIndigo
                    )
                    Text(
                        text = locationEditState.selectedAddress
                            ?: "%.4f, %.4f".format(locationEditState.selectedLat, locationEditState.selectedLon),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.PrimaryIndigo
                    )
                }
            }
            if (showLocationPicker) {
                OutlinedTextField(
                    value = locationEditState.query,
                    onValueChange = onLocationQueryChanged,
                    label = { Text(stringResource(R.string.review_location_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (locationEditState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                )
                locationEditState.results.forEach { result ->
                    Surface(
                        onClick = { onLocationSelected(result) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = result.displayAddress ?: result.name ?: "Unknown",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                locationEditState.error?.let { err ->
                    Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    haptic(HapticType.Standard)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cancel_button))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        haptic(HapticType.Success)
                        // S6-015: Validate before save
                        val parsedAmount = AmountUtils.parseAmount(amount)
                        if (amount.isNotBlank() && parsedAmount == null) {
                            return@Button
                        }
                        if (merchant.isBlank()) {
                            return@Button
                        }
                        // S6-017: Transfer requires direction
                        if (selectedType == TransactionType.TRANSFER && transferDirection == null) {
                            return@Button
                        }
                        val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - (review.suggestedAmount ?: 0.0)) > 0.001) parsedAmount else null
                        val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                        val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                        val originalType = parseTransactionTypeOrNull(review.suggestedType)
                        val editedType = selectedType.takeIf { originalType != it }
                        val editedDate = selectedDateMs.takeIf { it != (review.suggestedDate ?: review.createdAt) }
                        // S6-018: Use VM location state
                        val locLat = locationEditState.selectedLat
                        val locLon = locationEditState.selectedLon
                        val locAddress = locationEditState.selectedAddress
                        val locPlaceId = locationEditState.selectedPlaceId
                        onSave(
                            editedAmount, editedMerchant, editedCategory, editedDate, editedType,
                            // S6-004: Pass transfer direction/account
                            transferDirection.takeIf { editedType == TransactionType.TRANSFER || editedType == TransactionType.DEPOSIT },
                            transferAccount.takeIf { (editedType == TransactionType.TRANSFER || editedType == TransactionType.DEPOSIT) && transferAccount.isNotBlank() },
                            applyToAll, approveAllPending,
                            locLat.takeIf { it != review.suggestedLatitude },
                            locLon.takeIf { it != review.suggestedLongitude },
                            locAddress.takeIf { locLat != review.suggestedLatitude || locLon != review.suggestedLongitude },
                            locPlaceId,
                            // S6-D5-003: Pass selected currency
                            selectedCurrency.takeIf { it.isNotBlank() }
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.review_confirm_fix_button))
                }
            }
        }
    }
}
