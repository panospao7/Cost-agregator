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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.ui.components.AmountText
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var editingReview by remember { mutableStateOf<PendingReview?>(null) }
    // Guard against double-swipes/rapid-fire actions
    val processingIds = remember { mutableStateListOf<Long>() }
    val haptic = rememberHapticFeedback()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isBatchProcessing by viewModel.isBatchProcessing.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showDebugMenu by remember { mutableStateOf(false) }

    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.processBatch(uris)
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "REVIEW QUEUE ($pendingCount)", 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showDebugMenu = !showDebugMenu }) {
                            Icon(Icons.Rounded.MoreVert, "Debug Options")
                        }
                        DropdownMenu(
                            expanded = showDebugMenu,
                            onDismissRequest = { showDebugMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mass Insert (Batch)") },
                                onClick = {
                                    showDebugMenu = false
                                    batchLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Rounded.Layers, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export Parser Data") },
                                onClick = {
                                    showDebugMenu = false
                                    coroutineScope.launch {
                                        val data = viewModel.getDebugExportData()
                                        clipboardManager.setText(AnnotatedString(data))
                                        snackbarHostState.showSnackbar("Parser info copied to clipboard")
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear Scanned Data") },
                                onClick = {
                                    showDebugMenu = false
                                    viewModel.clearScannedData()
                                },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All caught up!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "No transactions need your review",
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
                        "Swipe right to approve, left to reject",
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
                                onApprove = { viewModel.approveReview(item.review.id) },
                                onReject = { viewModel.rejectReview(item.review.id) },
                                onEdit = { editingReview = item.review }
                            )
                        }
                    )
                }
            }
        }

        editingReview?.let { review ->
            EditReviewDialog(
                review = review,
                categories = categories,
                onDismiss = { editingReview = null },
                onSave = { amount, merchant, categoryId ->
                    viewModel.approveReviewWithEdits(
                        reviewId = review.id,
                        finalAmount = amount,
                        finalMerchant = merchant,
                        finalCategoryId = categoryId
                    )
                    editingReview = null
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
                        "PROCESSING BATCH...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    batchProgress?.let { (current, total) ->
                        Text(
                            "$current / $total",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total },
                            modifier = Modifier.width(200.dp),
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewCard(
    item: PendingReviewWithReceipt,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit
) {
    val review = item.review
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
                    text = review.packageName.split(".").lastOrNull()?.uppercase() ?: "SYSTEM",
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
                            text = "${(review.confidence * 100).toInt()}% CONFIDENCE",
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
                if (item.receipt != null) {
                    Card(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
                    ) {
                        AsyncImage(
                            model = File(item.receipt.imagePath),
                            contentDescription = "Receipt Thumbnail",
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

                    AmountText(
                        amount = review.suggestedAmount,
                        style = MaterialTheme.typography.headlineSmall,
                        color = SemanticColors.TextPrimary
                    )
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
                    .clickable { 
                        haptic(HapticType.Standard)
                        showTrustSignal = !showTrustSignal 
                    }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RAW SOURCE EVIDENCE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Icon(
                        if (showTrustSignal) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        null,
                        tint = SemanticColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                AnimatedVisibility(visible = showTrustSignal) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = review.notificationText ?: "No raw data captured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextPrimary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedIconButton(
                    onClick = {
                        haptic(HapticType.Heavy)
                        onEdit()
                    },
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Edit, "Edit", modifier = Modifier.size(20.dp))
                }

                Button(
                    onClick = {
                        haptic(HapticType.Error)
                        onReject()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Reject", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        haptic(HapticType.Success)
                        onApprove()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.SuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    Text("Approve", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EditReviewDialog(
    review: PendingReview,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?) -> Unit
) {
    var amount by remember { mutableStateOf(String.format("%.2f", review.suggestedAmount)) }
    var merchant by remember { mutableStateOf(review.suggestedMerchant) }
    var selectedCategoryId by remember { mutableStateOf(review.suggestedCategoryId) }
    val haptic = rememberHapticFeedback()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix Extraction Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    "Assign Category",
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic(HapticType.Success)
                    val parsedAmount = amount.replace(",", ".").toDoubleOrNull()
                    val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - review.suggestedAmount) > 0.001) parsedAmount else null
                    val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                    val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                    onSave(editedAmount, editedMerchant, editedCategory)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirm Fix")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptic(HapticType.Standard)
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}
