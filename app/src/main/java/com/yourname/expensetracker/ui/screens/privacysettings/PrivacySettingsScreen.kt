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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // -- Notification section --
            item {
                SectionHeader("Notification Capture")
            }
            item {
                ToggleRow(
                    label = "Capture notifications",
                    checked = settings.notificationCaptureEnabled,
                    onCheckedChange = viewModel::setNotificationCaptureEnabled
                )
            }

            item { HorizontalDivider() }

            // -- Cloud AI section --
            item {
                SectionHeader("Cloud AI")
            }
            item {
                ToggleRow(
                    label = "Enable cloud AI features",
                    checked = settings.cloudAiEnabled,
                    onCheckedChange = viewModel::setCloudAiEnabled
                )
            }
            item {
                ToggleRow(
                    label = "Redact data before cloud upload",
                    checked = settings.redactBeforeCloud,
                    onCheckedChange = viewModel::setRedactBeforeCloud
                )
            }
            item {
                ToggleRow(
                    label = "Upload receipt images to cloud",
                    checked = settings.receiptImageCloudEnabled,
                    onCheckedChange = viewModel::setReceiptImageCloudEnabled
                )
            }
            item {
                ToggleRow(
                    label = "Bank statement AI validation",
                    checked = settings.bankStatementAiEnabled,
                    onCheckedChange = viewModel::setBankStatementAiEnabled
                )
            }

            item { HorizontalDivider() }

            // -- Location section --
            item {
                SectionHeader("Location")
            }
            item {
                ToggleRow(
                    label = "External geocoding (Nominatim/OSM)",
                    checked = settings.externalGeocodingEnabled,
                    onCheckedChange = viewModel::setExternalGeocodingEnabled
                )
            }
            item {
                ToggleRow(
                    label = "Background location backfill",
                    checked = settings.backgroundLocationBackfillEnabled,
                    onCheckedChange = viewModel::setBackgroundLocationBackfillEnabled
                )
            }
            item {
                ToggleRow(
                    label = "Device GPS location",
                    checked = settings.deviceGpsLocationEnabled,
                    onCheckedChange = viewModel::setDeviceGpsLocationEnabled
                )
            }

            item { HorizontalDivider() }

            // -- Backup section --
            item {
                SectionHeader("Backup")
            }
            item {
                ToggleRow(
                    label = "Encrypted backups",
                    checked = settings.encryptedBackupEnabled,
                    onCheckedChange = viewModel::setEncryptedBackupEnabled
                )
            }

            item { HorizontalDivider() }

            // -- Data Retention section --
            item {
                SectionHeader("Data Retention")
            }
            item {
                RetentionSlider(
                    label = "Raw notification retention (days)",
                    value = settings.rawNotificationRetentionDays.toFloat(),
                    onValueChange = { viewModel.setRawNotificationRetentionDays(it.toInt()) }
                )
            }
            item {
                RetentionSlider(
                    label = "Raw OCR retention (days)",
                    value = settings.rawOcrRetentionDays.toFloat(),
                    onValueChange = { viewModel.setRawOcrRetentionDays(it.toInt()) }
                )
            }

            item { HorizontalDivider() }

            // -- Debug section --
            item {
                SectionHeader("Debug")
            }
            item {
                ToggleRow(
                    label = "Persist debug data",
                    checked = settings.debugDataPersistenceEnabled,
                    onCheckedChange = viewModel::setDebugDataPersistenceEnabled
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
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
    onCheckedChange: (Boolean) -> Unit
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
            color = SemanticColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
