package com.yourname.expensetracker.ui.screens.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.util.CsvImportFailureCode
import com.yourname.expensetracker.util.CsvImportPreview

private val CSV_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "*/*")
private val MIN_TOUCH_TARGET = 48.dp

/** Bounded failure code to its user-facing reason (noun phrase) string resource. */
internal fun CsvImportFailureCode.reasonResId(): Int = when (this) {
    CsvImportFailureCode.INVALID_DATE -> R.string.csv_import_failure_invalid_date
    CsvImportFailureCode.INVALID_AMOUNT -> R.string.csv_import_failure_invalid_amount
    CsvImportFailureCode.MISSING_COLUMN -> R.string.csv_import_failure_missing_column
    CsvImportFailureCode.INVALID_HEADER -> R.string.csv_import_failure_invalid_header
    CsvImportFailureCode.MALFORMED_ROW -> R.string.csv_import_failure_malformed_row
    CsvImportFailureCode.UNKNOWN_TRANSACTION_TYPE -> R.string.csv_import_failure_unknown_transaction_type
    CsvImportFailureCode.ROW_REJECTED -> R.string.csv_import_failure_row_rejected
    CsvImportFailureCode.WRITE_BLOCKED -> R.string.csv_import_failure_write_blocked
    CsvImportFailureCode.INTERNAL -> R.string.csv_import_failure_internal
}

/**
 * J-12-2: "Import CSV" card for the Export screen. Pick, read, preview,
 * import with progress, then a result. All user text comes from string
 * resources; the picked URI is only captured by the stream-opening lambda.
 */
@Composable
fun CsvImportCard(viewModel: CsvImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onFileSelected { context.contentResolver.openInputStream(uri) }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.csv_import_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.csv_import_card_description),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (val s = state) {
                is CsvImportUiState.Idle,
                is CsvImportUiState.Preview -> ChooseFileButton(enabled = s is CsvImportUiState.Idle) {
                    picker.launch(CSV_MIME_TYPES)
                }

                is CsvImportUiState.Reading -> {
                    val reading = stringResource(R.string.csv_import_reading)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MIN_TOUCH_TARGET)
                            .semantics { contentDescription = reading; liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = reading, color = SemanticColors.TextSecondary)
                    }
                }

                is CsvImportUiState.Importing -> ImportingContent(s, onCancel = viewModel::cancelImport)

                is CsvImportUiState.Result -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val resources = LocalContext.current.resources
                        Text(
                            text = stringResource(R.string.csv_import_result_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = SemanticColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = resources.getQuantityString(R.plurals.csv_import_result_imported, s.imported, s.imported),
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = resources.getQuantityString(R.plurals.csv_import_result_duplicates, s.duplicates, s.duplicates),
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = resources.getQuantityString(R.plurals.csv_import_result_failed, s.failed, s.failed),
                            color = SemanticColors.TextSecondary
                        )
                    }
                    StatusDismissButton(onDismiss = viewModel::dismiss)
                }

                is CsvImportUiState.Cancelled -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = LocalContext.current.resources.getQuantityString(
                                R.plurals.csv_import_cancelled, s.processedRows, s.processedRows
                            ),
                            color = SemanticColors.TextSecondary
                        )
                    }
                    StatusDismissButton(onDismiss = viewModel::dismiss)
                }

                is CsvImportUiState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = errorText(s.error), color = MaterialTheme.colorScheme.error)
                    }
                    StatusDismissButton(onDismiss = viewModel::dismiss)
                }

                is CsvImportUiState.WriteBlocked -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ui_write_blocked_maintenance),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    StatusDismissButton(onDismiss = viewModel::dismiss)
                }
            }
        }
    }

    (state as? CsvImportUiState.Preview)?.let { preview ->
        CsvImportPreviewDialog(
            preview = preview.preview,
            onConfirm = viewModel::confirmImport,
            onCancel = viewModel::cancelPreview
        )
    }
}

@Composable
private fun ChooseFileButton(enabled: Boolean, onClick: () -> Unit) {
    val cd = stringResource(R.string.csv_import_choose_file_cd)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .semantics { contentDescription = cd }
    ) {
        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.csv_import_choose_file))
    }
}

@Composable
private fun ImportingContent(state: CsvImportUiState.Importing, onCancel: () -> Unit) {
    val progressCd = stringResource(R.string.csv_import_progress_cd_format, state.current, state.total)
    val fraction = if (state.total > 0) state.current.toFloat() / state.total else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = progressCd; liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            text = stringResource(R.string.csv_import_progress_format, state.current, state.total),
            color = SemanticColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET)
    ) {
        Text(stringResource(R.string.csv_import_cancel_import))
    }
}

@Composable
private fun StatusDismissButton(onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET),
        colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
    ) {
        Text(stringResource(R.string.action_ok))
    }
}

@Composable
private fun errorText(error: CsvImportError): String = when (error) {
    is CsvImportError.SizeLimitExceeded -> stringResource(R.string.csv_import_error_size_limit)
    is CsvImportError.ReadFailed -> stringResource(R.string.csv_import_error_read_failed)
    is CsvImportError.Failure ->
        stringResource(R.string.csv_import_error_format, stringResource(error.code.reasonResId()))
}

@Composable
private fun CsvImportPreviewDialog(
    preview: CsvImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.csv_import_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (preview.validRows <= 0) {
                    Text(stringResource(R.string.csv_import_preview_nothing))
                } else {
                    Text(
                        resources.getQuantityString(
                            R.plurals.csv_import_preview_valid, preview.validRows, preview.validRows
                        )
                    )
                }
                preview.invalidRowsByCode.entries
                    .filter { it.value > 0 }
                    .sortedBy { it.key.ordinal }
                    .forEach { (code, count) ->
                        Text(
                            resources.getQuantityString(
                                R.plurals.csv_import_preview_invalid,
                                count,
                                count,
                                stringResource(code.reasonResId())
                            )
                        )
                    }
                if (preview.unknownCategoryRows > 0) {
                    Text(
                        resources.getQuantityString(
                            R.plurals.csv_import_preview_unknown_category,
                            preview.unknownCategoryRows,
                            preview.unknownCategoryRows
                        )
                    )
                }
                if (preview.skippedInstallmentRows > 0) {
                    Text(
                        resources.getQuantityString(
                            R.plurals.csv_import_preview_skipped_installments,
                            preview.skippedInstallmentRows,
                            preview.skippedInstallmentRows
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = preview.validRows > 0,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)
            ) {
                Text(stringResource(R.string.csv_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
