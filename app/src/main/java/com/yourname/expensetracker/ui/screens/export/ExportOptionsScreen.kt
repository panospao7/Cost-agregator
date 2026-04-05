package com.yourname.expensetracker.ui.screens.export

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.FileProvider
import com.yourname.expensetracker.ui.theme.SemanticColors
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExportOptionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var isPickingStartDate by remember { mutableStateOf(true) }
    var pendingExportFilePath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val sourcePath = pendingExportFilePath
        if (uri == null || sourcePath == null) {
            pendingExportFilePath = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    if (!source.exists()) error("Export file is missing")
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("Cannot open destination file")
                }
                snackbarHostState.showSnackbar(context.getString(R.string.export_save_success))
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.export_save_failed, t.message ?: "unknown error")
                )
            }
            pendingExportFilePath = null
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it, actionLabel = "Retry")
        }
    }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(
                action = {
                    TextButton(onClick = {
                        if (data.visuals.actionLabel == "Retry") {
                            viewModel.retry()
                            viewModel.clearError()
                        }
                    }) {
                        Text(data.visuals.actionLabel ?: "")
                    }
                }
            ) { Text(data.visuals.message) }
        } },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.export_title),
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Date Range Card
                item {
                    DateRangeCard(
                        startDate = uiState.startDate,
                        endDate = uiState.endDate,
                        expenseCount = uiState.expenseCount,
                        onStartDateClick = {
                            isPickingStartDate = true
                            showDatePicker = true
                        },
                        onEndDateClick = {
                            isPickingStartDate = false
                            showDatePicker = true
                        }
                    )
                }
                
                // Export Format Header
                item {
                    Text(
                        text = stringResource(R.string.header_export_format),
                        style = MaterialTheme.typography.titleMedium,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Export Format Options
                items(uiState.exportFormats) { format ->
                    ExportFormatCard(
                        format = format,
                        isSelected = uiState.selectedFormat == format.id,
                        onSelect = { viewModel.selectFormat(format.id) }
                    )
                }
                
                // Generate Button
                item {
                    Button(
                        onClick = { viewModel.generateExport() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SemanticColors.PrimaryIndigo
                        ),
                        enabled = !uiState.isLoading && uiState.expenseCount > 0
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Rounded.Download, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.export_generate_button))
                        }
                    }
                }
                
                // Export Result
                if (uiState.exportSuccess && uiState.exportFilePath != null) {
                    item {
                        ExportResultCard(
                            exportPreview = uiState.exportPreview.orEmpty(),
                            previewTruncated = uiState.exportPreviewTruncated,
                            onCopy = {
                                scope.launch {
                                    try {
                                        val fullText = withContext(Dispatchers.IO) {
                                            val path = uiState.exportFilePath ?: error("Export file path unavailable")
                                            File(path).also {
                                                if (!it.exists()) error("Export file is missing")
                                            }.readText()
                                        }
                                        clipboardManager.setText(AnnotatedString(fullText))
                                        snackbarHostState.showSnackbar(context.getString(R.string.export_copy_success))
                                    } catch (t: Throwable) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.export_save_failed, t.message ?: "unknown error")
                                        )
                                    }
                                }
                            },
                            onSave = {
                                pendingExportFilePath = uiState.exportFilePath
                                val ext = when (uiState.selectedFormat) {
                                    "quickbooks" -> "iif"
                                    "json" -> "json"
                                    else -> "csv"
                                }
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                saveLauncher.launch("expenses_$timestamp.$ext")
                            },
                            onShare = {
                                scope.launch {
                                    try {
                                        val ext = when (uiState.selectedFormat) {
                                            "quickbooks" -> "iif"
                                            "json" -> "json"
                                            else -> "csv"
                                        }
                                        val file = withContext(Dispatchers.IO) {
                                            val path = uiState.exportFilePath ?: error("Export file path unavailable")
                                            File(path).also {
                                                if (!it.exists()) error("Export file is missing")
                                            }
                                        }
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = when (ext) {
                                                "iif" -> "application/octet-stream"
                                                "json" -> "application/json"
                                                else -> "text/csv"
                                            }
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.action_share)))
                                        snackbarHostState.showSnackbar(context.getString(R.string.export_share_success))
                                    } catch (t: Throwable) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.export_save_failed, t.message ?: "unknown error")
                                        )
                                    }
                                }
                            },
                            onDismiss = { viewModel.clearExport() }
                        )
                    }
                }

                uiState.error?.let { errorText ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = errorText,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = {
                                    viewModel.retry()
                                    viewModel.clearError()
                                }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
                
                // Instructions
                item {
                    InstructionsCard()
                }
            }
        }
        
        // Date Picker Dialog
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = if (isPickingStartDate) uiState.startDate else uiState.endDate
            )
            
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { selectedDate ->
                                if (isPickingStartDate) {
                                    viewModel.setDateRange(selectedDate, uiState.endDate)
                                } else {
                                    viewModel.setDateRange(uiState.startDate, selectedDate)
                                }
                            }
                            showDatePicker = false
                        }
                    ) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

    }

    DisposableEffect(Unit) {
        onDispose { pendingExportFilePath = null }
    }
}

@Composable
private fun DateRangeCard(
    startDate: Long,
    endDate: Long,
    expenseCount: Int,
    onStartDateClick: () -> Unit,
    onEndDateClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.label_date_range),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start Date
                Column {
                    Text(
                        text = stringResource(R.string.label_from),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    TextButton(onClick = onStartDateClick) {
                        Text(dateFormat.format(Date(startDate)))
                    }
                }
                
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = SemanticColors.TextSecondary
                )
                
                // End Date
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.label_to),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary
                    )
                    TextButton(onClick = onEndDateClick) {
                        Text(dateFormat.format(Date(endDate)))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "$expenseCount expenses in selected range",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ExportFormatCard(
    format: ExportFormat,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
            } else {
                SemanticColors.SurfaceLight.copy(alpha = 0.5f)
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = SemanticColors.PrimaryIndigo
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            val icon = when (format.id) {
                "xero" -> Icons.Rounded.AccountBalance
                "quickbooks" -> Icons.Rounded.Calculate
                "freshbooks" -> Icons.Rounded.Receipt
                "json" -> Icons.Rounded.TableChart
                else -> Icons.Rounded.TableChart
            }
            
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) {
                            SemanticColors.PrimaryIndigo.copy(alpha = 0.3f)
                        } else {
                            SemanticColors.SurfaceLight
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) {
                        SemanticColors.PrimaryIndigo
                    } else {
                        SemanticColors.TextSecondary
                    },
                    modifier = Modifier.size(28.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = format.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = format.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = SemanticColors.PrimaryIndigo
                )
            }
        }
    }
}

@Composable
private fun ExportResultCard(
    exportPreview: String,
    previewTruncated: Boolean,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.export_success_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_dismiss),
                        tint = SemanticColors.TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Preview of data
            Text(
                text = exportPreview + if (previewTruncated) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.BaseNavy.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.export_copy_button))
                }
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.export_save_button))
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_share))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.export_tip_format, ".csv, .json or .iif"),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InstructionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.export_how_to_title),
                style = MaterialTheme.typography.titleSmall,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val instructions = listOf(
                stringResource(R.string.export_instructions_1),
                stringResource(R.string.export_instructions_2),
                stringResource(R.string.export_instructions_3),
                stringResource(R.string.export_instructions_4),
                stringResource(R.string.export_instructions_5)
            )
            
            instructions.forEach { instruction ->
                Text(
                    text = instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
