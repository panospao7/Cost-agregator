package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.ui.components.PrivacyBlockedCard
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Privacy settings screen.
 *
 * Note: Changing certain settings may require an app restart to take full effect:
 * - "Capture notifications" — requires restart of NotificationListenerService
 * - "Background location backfill" — requires restart of background location workers
 *
 * TODO: Add per-setting "restart required" badges or snackbar hints once the
 *       underlying services support dynamic reconfiguration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onDismiss: () -> Unit,
    viewModel: PrivacySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val isSaving = uiState.isSaving

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { Text("Privacy Settings", color = SemanticColors.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
        if (uiState.isLoading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // -- Blocked features summary --
            if (uiState.blocked.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.privacy_section_disabled_features))
                }
                items(uiState.blocked.size) { index ->
                    PrivacyBlockedCard(
                        blocked = uiState.blocked[index],
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                item { HorizontalDivider() }
            }

            // -- Error message --
            if (uiState.errorMessage != null) {
                item {
                    Text(
                        text = uiState.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // -- Notification section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_notification))
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_notification_capture),
                    checked = settings.notificationCaptureEnabled,
                    onCheckedChange = viewModel::setNotificationCaptureEnabled,
                    enabled = !isSaving
                )
            }

            item { HorizontalDivider() }

            // -- Cloud AI section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_cloud_ai))
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_cloud_ai),
                    checked = settings.cloudAiEnabled,
                    onCheckedChange = viewModel::requestSetCloudAiEnabled,
                    enabled = !isSaving
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_redact_before_cloud),
                    checked = settings.redactBeforeCloud,
                    onCheckedChange = viewModel::requestSetRedactBeforeCloud,
                    enabled = !isSaving
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_receipt_image_cloud),
                    checked = settings.receiptImageCloudEnabled,
                    onCheckedChange = viewModel::setReceiptImageCloudEnabled,
                    enabled = !isSaving
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_bank_statement_ai),
                    checked = settings.bankStatementAiEnabled,
                    onCheckedChange = viewModel::setBankStatementAiEnabled,
                    enabled = !isSaving
                )
            }

            item { HorizontalDivider() }

            // -- Location section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_location))
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_external_geocoding),
                    checked = settings.externalGeocodingEnabled,
                    onCheckedChange = viewModel::setExternalGeocodingEnabled,
                    enabled = !isSaving
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_background_location),
                    checked = settings.backgroundLocationBackfillEnabled,
                    onCheckedChange = viewModel::setBackgroundLocationBackfillEnabled,
                    enabled = !isSaving
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_device_gps),
                    checked = settings.deviceGpsLocationEnabled,
                    onCheckedChange = viewModel::setDeviceGpsLocationEnabled,
                    enabled = !isSaving
                )
            }

            item { HorizontalDivider() }

            // -- Backup section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_backup))
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_encrypted_backup),
                    checked = settings.encryptedBackupEnabled,
                    onCheckedChange = viewModel::setEncryptedBackupEnabled,
                    enabled = !isSaving
                )
            }

            item { HorizontalDivider() }

            // -- Data Retention section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_data_retention))
            }
            item {
                RetentionSlider(
                    label = stringResource(R.string.privacy_slider_notification_retention),
                    value = settings.rawNotificationRetentionDays.toFloat(),
                    onValueChange = { viewModel.setRawNotificationRetentionDays(it.toInt()) }
                )
            }
            item {
                RetentionSlider(
                    label = stringResource(R.string.privacy_slider_ocr_retention),
                    value = settings.rawOcrRetentionDays.toFloat(),
                    onValueChange = { viewModel.setRawOcrRetentionDays(it.toInt()) }
                )
            }

            item { HorizontalDivider() }

            // -- Raw Storage Modes section (S3-009) --
            item {
                SectionHeader(stringResource(R.string.privacy_section_raw_storage))
            }
            item {
                RawStorageModeRow(
                    label = stringResource(R.string.privacy_raw_storage_notification_label),
                    selected = settings.rawNotificationStorageMode,
                    onSelect = viewModel::setRawNotificationStorageMode,
                    enabled = !isSaving
                )
            }
            item {
                RawStorageModeRow(
                    label = stringResource(R.string.privacy_raw_storage_ocr_label),
                    selected = settings.rawOcrStorageMode,
                    onSelect = viewModel::setRawOcrStorageMode,
                    enabled = !isSaving
                )
            }
            item {
                RawStorageModeRow(
                    label = stringResource(R.string.privacy_raw_storage_email_label),
                    selected = settings.emailReceiptStorageMode,
                    onSelect = viewModel::setEmailReceiptStorageMode,
                    enabled = !isSaving
                )
            }
            item {
                RawStorageModeRow(
                    label = stringResource(R.string.privacy_raw_storage_bank_statement_label),
                    selected = settings.rawBankStatementStorageMode,
                    onSelect = viewModel::setRawBankStatementStorageMode,
                    enabled = !isSaving
                )
            }

            item { HorizontalDivider() }

            // -- Debug section --
            item {
                SectionHeader(stringResource(R.string.privacy_section_debug))
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.privacy_toggle_debug_persist),
                    checked = settings.debugDataPersistenceEnabled,
                    onCheckedChange = viewModel::setDebugDataPersistenceEnabled,
                    enabled = !isSaving
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        // S3-010: Confirmation dialogs for risky privacy toggles
        uiState.pendingRiskyConfirm?.let { confirm ->
            val (title, message) = when (confirm) {
                RiskyToggleConfirm.ENABLE_CLOUD_AI ->
                    stringResource(R.string.privacy_confirm_enable_cloud_ai_title) to
                    stringResource(R.string.privacy_confirm_enable_cloud_ai_message)
                RiskyToggleConfirm.DISABLE_REDACTION ->
                    stringResource(R.string.privacy_confirm_disable_redaction_title) to
                    stringResource(R.string.privacy_confirm_disable_redaction_message)
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = viewModel::dismissRiskyConfirm,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = viewModel::confirmRiskyToggle) {
                        Text(stringResource(R.string.privacy_confirm_proceed), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = viewModel::dismissRiskyConfirm) {
                        Text(stringResource(R.string.privacy_confirm_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = SemanticColors.PrimaryIndigo,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) SemanticColors.TextPrimary else SemanticColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun RetentionSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = "$label: ${value.toInt()}",
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextPrimary
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..365f,
            steps = 51 // (365 / 7) approx — every week
        )
    }
}


@Composable
private fun RawStorageModeRow(
    label: String,
    selected: com.yourname.expensetracker.domain.privacy.RawStorageMode,
    onSelect: (com.yourname.expensetracker.domain.privacy.RawStorageMode) -> Unit,
    enabled: Boolean = true
) {
    val options = listOf(
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_RAW to stringResource(R.string.privacy_raw_storage_store_raw),
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_REDACTED to stringResource(R.string.privacy_raw_storage_store_redacted),
        com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_METADATA_ONLY to stringResource(R.string.privacy_raw_storage_metadata_only),
        com.yourname.expensetracker.domain.privacy.RawStorageMode.DO_NOT_STORE to stringResource(R.string.privacy_raw_storage_do_not_store),
    )
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) SemanticColors.TextPrimary else SemanticColors.TextSecondary)
        when (selected) {
            com.yourname.expensetracker.domain.privacy.RawStorageMode.STORE_RAW ->
                Text(stringResource(R.string.privacy_raw_storage_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        options.forEach { (mode, label2) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.RadioButton(
                    selected = selected == mode,
                    onClick = { if (enabled) onSelect(mode) },
                    enabled = enabled
                )
                Text(label2, style = MaterialTheme.typography.bodySmall, color = if (enabled) SemanticColors.TextPrimary else SemanticColors.TextSecondary)
            }
        }
    }
}
