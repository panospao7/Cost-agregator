package com.yourname.expensetracker.ui.screens.receiptscan

import android.Manifest
import android.content.Intent
import android.net.Uri as AndroidUri
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.components.ai.CategoryAssistCard
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import com.yourname.expensetracker.ui.components.ai.ReceiptAssistCard
import com.yourname.expensetracker.ui.components.ai.ReceiptItemBreakdownCard
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import android.provider.Settings

/** Returns the currency symbol for [currencyCode], or "?" if not yet loaded. */
private fun getCurrencySymbol(currencyCode: String?): String {
    if (currencyCode.isNullOrBlank()) return "?"
    return CurrencyFormatter.getCurrencySymbol(currencyCode)
}

/** S7-027: Route entry point — owns Hilt injection. ReceiptScanScreen keeps ViewModel for now. */
@Composable
fun ReceiptScanRoute(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    ReceiptScanScreen(onDismiss = onDismiss, viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showDebugViewer by remember { mutableStateOf(false) }
    var showCameraDeniedCard by remember { mutableStateOf(false) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.processGalleryImage(it) }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraDeniedCard = false
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
        } else {
            showCameraDeniedCard = true
        }
    }

    // Handle done step - auto-dismiss
    LaunchedEffect(state.step) {
        if (state.step == ScanStep.DONE) {
            delay(1500)
            onDismiss()
        }
    }
    
    // Debug viewer dialog
    if (showDebugViewer && state.debugData != null) {
        com.yourname.expensetracker.ui.screens.debug.DebugViewerScreen(
            debugData = state.debugData!!,
            onClose = { showDebugViewer = false }
        )
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> stringResource(R.string.receipt_scan_title)
                            ScanStep.PROCESSING -> stringResource(R.string.receipt_processing_title)
                            ScanStep.REVIEW -> stringResource(R.string.receipt_review_title)
                            ScanStep.DONE -> stringResource(R.string.receipt_saved_title)
                            ScanStep.ERROR -> stringResource(R.string.receipt_error_title)
                        },
                        color = SemanticColors.TextPrimary
                    )
                },
                navigationIcon = {
                    val closeScannerCd = stringResource(R.string.receipt_close_scanner_cd)
                    IconButton(
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        modifier = Modifier.semantics { contentDescription = closeScannerCd }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                },
                actions = {
                    // S7-018: Debug button only in debug builds
                    if (com.yourname.expensetracker.BuildConfig.DEBUG &&
                        (state.step == ScanStep.REVIEW || state.step == ScanStep.ERROR) &&
                        state.debugData != null) {
                        val viewDebugCd = stringResource(R.string.receipt_view_debug_cd)
                        IconButton(
                            onClick = { showDebugViewer = true },
                            modifier = Modifier.semantics { contentDescription = viewDebugCd }
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> CaptureStep(
                    imageUri = state.imageUri,
                    showCameraDenied = showCameraDeniedCard,
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            AndroidUri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    },
                    onCameraClick = {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasCameraPermission) {
                            val uri = viewModel.createTempPhotoUri()
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryClick = {
                        galleryLauncher.launch(arrayOf("image/*", "application/pdf"))
                    }
                )

                ScanStep.PROCESSING -> ProcessingStep()

                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    categories = categories,
                    viewModel = viewModel
                )

                ScanStep.DONE -> DoneStep()

                ScanStep.ERROR -> ErrorStep(
                    errorMessage = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    imageUri: Uri?,
    showCameraDenied: Boolean,
    onOpenSettings: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    // Image preview area
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = stringResource(R.string.receipt_preview_cd),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                val noImageCd = stringResource(R.string.receipt_no_image_selected_cd)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.semantics { contentDescription = noImageCd }
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.receipt_capture_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    val takePhotoCd = stringResource(R.string.receipt_take_photo_cd)
    val selectGalleryCd = stringResource(R.string.receipt_select_gallery_cd)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = takePhotoCd },
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.receipt_camera_button))
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = selectGalleryCd },
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.receipt_gallery_button))
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (showCameraDenied) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.receipt_camera_permission_denied_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.receipt_camera_permission_denied_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.receipt_open_settings_button))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Tips
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.receipt_tips_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.receipt_tip_flat_surface), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.receipt_tip_lighting), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.receipt_tip_frame), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.receipt_tip_steady), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProcessingStep() {
    val processingCd = stringResource(R.string.receipt_processing_cd)
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = processingCd },
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        stringResource(R.string.receipt_scanning_text),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        stringResource(R.string.receipt_reading_text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    state: ReceiptScanState,
    categories: List<Category>,
    viewModel: ReceiptScanViewModel
) {
    val parsed = state.parsedReceipt

    state.quickSavePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReceiptQuickSaveConfirmation,
            title = { Text(stringResource(R.string.receipt_quick_save_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.receipt_quick_save_description),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        stringResource(R.string.receipt_ai_fill_format, preview.autoAppliedFields.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    HorizontalDivider()
                    preview.fieldSummaries.forEach { field ->
                        val renderedValue = when (field.label) {
                            // S7-011: Only format with currency when it is loaded
                            "Amount" -> {
                                val cur = state.editCurrency
                                if (cur.isNullOrBlank()) String.format(java.util.Locale.US, "%.2f", preview.amount)
                                else CurrencyFormatter.formatMoney(preview.amount, cur)
                            }
                            "Date" -> DateFormatterUtils.formatTimestampJavaTime(preview.date, "dd/MM/yyyy")
                            else -> field.value
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${field.label}: $renderedValue",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = field.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    preview.diagnostics.forEach { diagnostics ->
                        Text(
                            text = diagnostics,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReceiptQuickSave) {
                    Text(stringResource(R.string.receipt_save_now_button))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReceiptQuickSaveConfirmation) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    // Image preview (small)
    if (state.imageUri != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            AsyncImage(
                model = state.imageUri,
                contentDescription = stringResource(R.string.receipt_image_cd),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Confidence indicator
    ConfidenceIndicator(confidence = state.ocrConfidence)

    Spacer(modifier = Modifier.height(12.dp))

    if (viewModel.shouldOfferReceiptAssist()) {
        when (val assistState = state.receiptAssistState) {
            AiLoadState.Idle,
            AiLoadState.Disabled -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.receipt_assist_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.receiptAssistMessage ?: stringResource(R.string.receipt_assist_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestReceiptAssist(force = true) }) {
                            Text(stringResource(R.string.receipt_try_ai_button))
                        }
                    }
                }
            }
            AiLoadState.Loading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.receipt_ai_reviewing))
                    }
                }
            }
            is AiLoadState.Ready -> {
                ReceiptAssistCard(
                    suggestion = assistState.value,
                    diagnostics = state.receiptAssistDiagnostics,
                    onApplyMerchant = viewModel::applyReceiptAssistMerchant,
                    onApplyTotal = viewModel::applyReceiptAssistTotal,
                    onApplyDate = viewModel::applyReceiptAssistDate,
                    onApplyAll = viewModel::applyAllReceiptAssist,
                    onDismiss = viewModel::dismissReceiptAssist
                )
            }
            is AiLoadState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.receipt_ai_failed),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            assistState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        state.receiptAssistDiagnostics?.let { diagnostics ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                diagnostics,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestReceiptAssist(force = true) }) {
                            Text(stringResource(R.string.receipt_retry_ai_button))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (viewModel.shouldOfferCategoryAssist()) {
        when (val categoryState = state.categoryAssistState) {
            AiLoadState.Idle,
            AiLoadState.Disabled -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.receipt_category_assist_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.categoryAssistMessage ?: stringResource(R.string.receipt_category_assist_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestCategoryAssist() }) {
                            Text(stringResource(R.string.receipt_suggest_category_button))
                        }
                    }
                }
            }
            AiLoadState.Loading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.receipt_ai_checking_category))
                    }
                }
            }
            is AiLoadState.Ready -> {
                CategoryAssistCard(
                    suggestion = categoryState.value,
                    diagnostics = state.categoryAssistDiagnostics,
                    onApply = viewModel::applyCategoryAssist,
                    onDismiss = viewModel::dismissCategoryAssist
                )
            }
            is AiLoadState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.receipt_category_ai_failed),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            categoryState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        state.categoryAssistDiagnostics?.let { diagnostics ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                diagnostics,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestCategoryAssist(force = true) }) {
                            Text(stringResource(R.string.receipt_retry_category_button))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    state.receiptAssistMessage?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = viewModel::clearReceiptAssistMessage) {
                    Text(stringResource(R.string.receipt_ok_button))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    state.categoryAssistMessage?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = viewModel::clearCategoryAssistMessage) {
                    Text(stringResource(R.string.receipt_ok_button))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Merchant
    val merchantInputCd = stringResource(R.string.receipt_merchant_input_cd)
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text(stringResource(R.string.receipt_merchant_label)) },
        placeholder = { Text(stringResource(R.string.receipt_merchant_placeholder)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = merchantInputCd }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Amount
    val amountInputCd = stringResource(R.string.receipt_amount_input_cd)
    val totalLabelRes = if (parsed?.taxInclusive == true)
        R.string.receipt_total_amount_incl_tax_label
    else
        R.string.receipt_total_amount_label
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text(stringResource(totalLabelRes)) },
        leadingIcon = {
            // S7-010: Use editCurrency (user-selected), not parsed OCR currency
            Text(getCurrencySymbol(state.editCurrency), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = amountInputCd }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Currency (RCP-10/N2)
    CurrencyPicker(
        selectedCurrency = state.editCurrency ?: "",
        onCurrencySelected = { viewModel.updateCurrency(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Payment Method
    val paymentSelected = stringResource(R.string.receipt_payment_selected)
    val paymentNotSelected = stringResource(R.string.receipt_payment_not_selected)
    val cardPaymentCd = stringResource(R.string.receipt_payment_card_cd, paymentSelected)
    val cardPaymentNotCd = stringResource(R.string.receipt_payment_card_cd, paymentNotSelected)
    val cashPaymentCd = stringResource(R.string.receipt_payment_cash_cd, paymentSelected)
    val cashPaymentNotCd = stringResource(R.string.receipt_payment_cash_cd, paymentNotSelected)
    Text(
        stringResource(R.string.receipt_payment_method_label),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = stringResource(R.string.transactions_payment_method_card),
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = if (state.paymentMethod == PaymentMethod.CARD) cardPaymentCd else cardPaymentNotCd }
        )
        PaymentMethodChip(
            label = stringResource(R.string.transactions_payment_method_cash),
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = if (state.paymentMethod == PaymentMethod.CASH) cashPaymentCd else cashPaymentNotCd }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Category
    Text(
        stringResource(R.string.receipt_category_label),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )

    // S7-024: Rationale dialog
    state.selectedItemRationale?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::dismissItemRationale,
            title = { Text(item.itemDescription, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.receipt_category_label) + ": ${item.userCorrectedCategoryName ?: item.suggestedCategoryName ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.receipt_confidence_indicator_format, "", (item.confidence * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    item.aiRationale?.let { rationale ->
                        HorizontalDivider()
                        Text(rationale, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissItemRationale) { Text(stringResource(R.string.review_close_button)) }
            }
        )
    }

    // S7-025: Item correction error
    state.itemCorrectionError?.let { err ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearItemCorrectionError) { Text(stringResource(R.string.review_close_button)) }
            }
        }
    }

    // Line items breakdown with AI categorization
    val itemCategorizations = state.itemCategorizations
    if (itemCategorizations.isNotEmpty() && state.showItemBreakdown) {
        Spacer(modifier = Modifier.height(16.dp))
        // S7-023: Clarify item categories are metadata only
        Text(
            text = stringResource(R.string.receipt_item_categories_metadata_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        ReceiptItemBreakdownCard(
            items = itemCategorizations,
            categories = categories,
            isLoading = state.isAnalyzingItems,
            currency = state.editCurrency ?: state.parsedReceipt?.currency ?: "",
            updatingItemIds = state.itemCorrectionUpdatingIds,
            onItemCategoryChanged = { item, category ->
                viewModel.updateItemCategory(item, category)
            },
            onShowRationale = { item ->
                viewModel.showItemRationale(item)
            }
        )
    } else if (parsed?.lineItems?.isNotEmpty() == true) {
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.receipt_detected_items_format, parsed.lineItems.size),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    when {
                        state.isAnalyzingItems -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        // S7-027: AI disabled — show info instead of Analyze button
                        !state.itemAnalysisAvailable -> {
                            Text(
                                stringResource(R.string.receipt_item_analysis_ai_disabled),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            val analyzeItemsCd = stringResource(R.string.receipt_analyze_items_cd)
                            TextButton(
                                onClick = { viewModel.analyzeReceiptItems() },
                                modifier = Modifier.semantics { contentDescription = analyzeItemsCd }
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.receipt_analyze_button))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.description, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(CurrencyFormatter.formatMoney(item.totalPrice, parsed.currency), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }

                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.receipt_tax_vat_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(CurrencyFormatter.formatMoney(tax, parsed.currency), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    state.itemAnalysisError?.let { analysisError ->
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = analysisError,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    viewModel.clearItemAnalysisError()
                    viewModel.analyzeReceiptItems()
                }) {
                    Text(stringResource(R.string.receipt_item_analysis_retry_button))
                }
            }
        }
    }

    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    val notesInputCd = stringResource(R.string.receipt_notes_input_cd)
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text(stringResource(R.string.receipt_notes_label)) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = notesInputCd },
        minLines = 1,
        maxLines = 3
    )

    // S7-034: Typed raw OCR display — hide when blank (privacy policy may have purged it)
    val rawOcrUiState = when {
        state.rawOcrText.isBlank() -> null // Not available or purged by privacy policy
        else -> state.rawOcrText
    }
    if (rawOcrUiState != null) {
        Spacer(modifier = Modifier.height(8.dp))
        val toggleCd = stringResource(R.string.receipt_toggle_cd)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.toggleRawText() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.receipt_raw_ocr_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                if (state.showRawText) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = toggleCd,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = state.showRawText) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Text(
                    text = rawOcrUiState,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }

    // Error messages
    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    when (state.saveResult) {
        is SaveReceiptResult.DuplicateTransaction -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.receipt_duplicate_transaction), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        is SaveReceiptResult.DuplicateReceipt -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.receipt_duplicate_receipt), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        is SaveReceiptResult.PartialLinkFailure -> {
            // S7-005: Expense was created but receipt link failed
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.receipt_partial_link_failure),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        is SaveReceiptResult.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text((state.saveResult as SaveReceiptResult.Error).message, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        else -> {}
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (viewModel.canOfferReceiptQuickSave()) {
        val quickSaveCd = stringResource(R.string.receipt_quick_save_cd)
        FilledTonalButton(
            onClick = viewModel::requestReceiptQuickSaveConfirmation,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { contentDescription = quickSaveCd },
            enabled = !state.isSaving,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.receipt_quick_save_button))
        }

        Spacer(modifier = Modifier.height(12.dp))
    } else {
        viewModel.quickSaveUnavailableReason()?.let { reason ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.receipt_quick_save_standby),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // Save button
    val saveCd = stringResource(R.string.receipt_save_cd)
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = saveCd },
        enabled = !state.isSaving,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.receipt_save_button), fontSize = 16.sp)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.7f -> Color(0xFF4CAF50)
        confidence >= 0.4f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val label = when {
        confidence >= 0.7f -> stringResource(R.string.receipt_confidence_high)
        confidence >= 0.4f -> stringResource(R.string.receipt_confidence_medium)
        else -> stringResource(R.string.receipt_confidence_low)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(R.string.receipt_confidence_indicator_format, label, percentage),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * RCP-10/N2: Currency picker for receipt review.
 * Allows the user to change the currency before saving.
 * Defaults to OCR-detected currency or home currency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPicker(
    selectedCurrency: String,
    onCurrencySelected: (String) -> Unit
) {
    val currencies = listOf("EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD")
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.receipt_currency_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = CurrencyFormatter.getCurrencySymbol(selectedCurrency).let { symbol ->
                    "$symbol  $selectedCurrency"
                },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                currencies.forEach { currencyCode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                CurrencyFormatter.getCurrencySymbol(currencyCode).let { symbol ->
                                    "$symbol  $currencyCode"
                                }
                            )
                        },
                        onClick = {
                            onCurrencySelected(currencyCode)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = stringResource(R.string.receipt_success_cd),
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        stringResource(R.string.receipt_saved_message),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        stringResource(R.string.receipt_saved_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    val retryCd = stringResource(R.string.receipt_retry_cd)
    Spacer(modifier = Modifier.height(80.dp))
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = stringResource(R.string.receipt_error_cd),
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        stringResource(R.string.receipt_error_message),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        errorMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.semantics { contentDescription = retryCd }
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.receipt_try_again_button))
    }
}
