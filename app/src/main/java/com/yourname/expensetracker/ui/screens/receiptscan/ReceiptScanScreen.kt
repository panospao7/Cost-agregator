package com.yourname.expensetracker.ui.screens.receiptscan

import android.Manifest
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
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.components.ai.CategoryAssistCard
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import com.yourname.expensetracker.ui.components.ai.ReceiptAssistCard
import com.yourname.expensetracker.ui.components.ai.ReceiptItemBreakdownCard
import kotlinx.coroutines.delay
import java.util.Currency
import java.util.Date

private fun getCurrencySymbol(currencyCode: String?): String {
    return try { Currency.getInstance(currencyCode ?: "EUR").symbol } catch(e: Exception) { "€" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showDebugViewer by remember { mutableStateOf(false) }

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
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> "Scan Receipt"
                            ScanStep.PROCESSING -> "Processing..."
                            ScanStep.REVIEW -> "Review & Save"
                            ScanStep.DONE -> "Saved!"
                            ScanStep.ERROR -> "Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        modifier = Modifier.semantics { contentDescription = "Close receipt scanner" }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                },
                actions = {
                    // Debug button (only show in review/error steps)
                    if ((state.step == ScanStep.REVIEW || state.step == ScanStep.ERROR) && state.debugData != null) {
                        IconButton(
                            onClick = { showDebugViewer = true },
                            modifier = Modifier.semantics { contentDescription = "View debug information" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null
                            )
                        }
                    }
                }
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
                    errorMessage = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    imageUri: Uri?,
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
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.semantics { contentDescription = "No receipt image selected. Take a photo or select from gallery to begin." }
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Take a photo or select from gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Take photo with camera" },
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Camera")
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Select image from gallery" },
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Gallery")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

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
                    "Tips for best results:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Place receipt on a flat, dark surface", style = MaterialTheme.typography.bodySmall)
            Text("• Ensure good lighting with no shadows", style = MaterialTheme.typography.bodySmall)
            Text("• Capture the entire receipt in frame", style = MaterialTheme.typography.bodySmall)
            Text("• Keep the camera steady", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProcessingStep() {
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = "Processing receipt, please wait" },
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Scanning receipt...",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Reading text and extracting details",
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
            title = { Text("Quick save with AI?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This fills missing draft fields from AI suggestions and then uses the normal save path.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "AI will fill: ${preview.autoAppliedFields.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    HorizontalDivider()
                    preview.fieldSummaries.forEach { field ->
                        val renderedValue = when (field.label) {
                            "Amount" -> "${getCurrencySymbol(parsed?.currency)}${field.value}"
                            "Date" -> DateFormatterUtils.shortDate().format(Date(preview.date))
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
                    Text("Save now")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReceiptQuickSaveConfirmation) {
                    Text("Cancel")
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
                contentDescription = "Receipt",
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
                            "Need help filling missing fields?",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.receiptAssistMessage ?: "AI can suggest merchant, total, and date from the OCR text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestReceiptAssist(force = true) }) {
                            Text("Try AI assist")
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
                        Text("AI is reviewing the OCR text for missing receipt fields.")
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
                            "AI receipt assist failed",
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
                            Text("Retry AI assist")
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
                            "Need help choosing a category?",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.categoryAssistMessage ?: "AI can suggest a category from the receipt text and parsed fields.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.requestCategoryAssist() }) {
                            Text("Suggest category with AI")
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
                        Text("AI is checking the best category for this receipt.")
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
                            "AI category assist failed",
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
                            Text("Retry category assist")
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
                    Text("OK")
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
                    Text("OK")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Merchant
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text("Merchant") },
        placeholder = { Text("Store name") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Merchant name input field" }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Amount
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text("Total Amount") },
        leadingIcon = { 
            Text(getCurrencySymbol(parsed?.currency), fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Total amount input field" }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Payment Method
    Text(
        "Payment Method",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = "Card",
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Card payment method, ${if (state.paymentMethod == PaymentMethod.CARD) "selected" else "not selected"}" }
        )
        PaymentMethodChip(
            label = "Cash",
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Cash payment method, ${if (state.paymentMethod == PaymentMethod.CASH) "selected" else "not selected"}" }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Category
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )

    // Line items breakdown with AI categorization
    val itemCategorizations = state.itemCategorizations
    if (itemCategorizations.isNotEmpty() && state.showItemBreakdown) {
        Spacer(modifier = Modifier.height(16.dp))
        
        ReceiptItemBreakdownCard(
            items = itemCategorizations,
            categories = categories,
            isLoading = state.isAnalyzingItems,
            onItemCategoryChanged = { item, category ->
                viewModel.updateItemCategory(item, category)
            },
            onShowRationale = { item ->
                viewModel.showItemRationale(item)
            }
        )
    } else if (parsed?.lineItems?.isNotEmpty() == true) {
        // Show simple preview with analyze button if AI not yet run
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Detected Items (${parsed.lineItems.size})",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    
                    // Analyze button
                    if (state.isAnalyzingItems) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = { viewModel.analyzeReceiptItems() },
                            modifier = Modifier.semantics { contentDescription = "Analyze receipt items with AI" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Analyze")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", item.totalPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Tax if detected
                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tax/VAT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", tax)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text("Notes (optional)") },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Optional notes input field" },
        minLines = 1,
        maxLines = 3
    )

    // Raw OCR toggle
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleRawText() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Raw OCR Text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            if (state.showRawText) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = state.showRawText) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = state.rawOcrText.ifBlank { "No text detected" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
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
        is SaveReceiptResult.Duplicate -> {
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
                        "A similar transaction already exists",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        is SaveReceiptResult.Error -> {
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
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        (state.saveResult as SaveReceiptResult.Error).message,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        else -> {}
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (viewModel.canOfferReceiptQuickSave()) {
        FilledTonalButton(
            onClick = viewModel::requestReceiptQuickSaveConfirmation,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .semantics { contentDescription = "Quick save expense using AI to fill missing fields" },
            enabled = !state.isSaving,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Quick Save With AI")
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
                        text = "Quick save is standing by",
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
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = "Save expense to database" },
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
            Text("Save Expense", fontSize = 16.sp)
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
        confidence >= 0.7f -> "High confidence"
        confidence >= 0.4f -> "Medium confidence"
        else -> "Low confidence - please verify"
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
            "$label ($percentage%)",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = "Success",
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense saved!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Your receipt has been processed and saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(80.dp))
    Icon(
        imageVector = Icons.Default.Error,
        contentDescription = "Error",
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Something went wrong",
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
        modifier = Modifier.semantics { contentDescription = "Retry receipt processing" }
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Try Again")
    }
}
