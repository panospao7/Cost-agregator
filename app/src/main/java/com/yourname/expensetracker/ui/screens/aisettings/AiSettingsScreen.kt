package com.yourname.expensetracker.ui.screens.aisettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onDismiss: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isRefreshingRuntime) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    } else {
                        TextButton(onClick = viewModel::refreshRuntimeStatus) {
                            Text("Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection("General") {
                    ToggleRow("Enable AI", settings.aiEnabled, viewModel::setAiEnabled)
                    ToggleRow("Allow cloud AI", settings.allowCloudAi, viewModel::setAllowCloudAi)
                    ToggleRow("Allow on-device AI", settings.allowOnDeviceAi, viewModel::setAllowOnDeviceAi)
                }
            }

            item {
                SettingsSection("Preferred Mode") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.preferredMode == mode,
                                onClick = { viewModel.setPreferredMode(mode) },
                                label = { Text(mode.name.replace('_', ' ')) }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection("Capabilities") {
                    ToggleRow("Assistant", settings.assistantEnabled, viewModel::setAssistantEnabled)
                    ToggleRow("Query interpretation", settings.queryInterpretationEnabled, viewModel::setQueryInterpretationEnabled)
                    ToggleRow("Dashboard briefing", settings.dashboardBriefingEnabled, viewModel::setDashboardBriefingEnabled)
                    ToggleRow("Review explanation", settings.reviewExplanationEnabled, viewModel::setReviewExplanationEnabled)
                    ToggleRow("Receipt assist", settings.receiptAssistEnabled, viewModel::setReceiptAssistEnabled)
                    ToggleRow("Categorization fallback", settings.categorizationFallbackEnabled, viewModel::setCategorizationFallbackEnabled)
                    ToggleRow("Duplicate detection", settings.dedupeJudgeEnabled, viewModel::setDedupeJudgeEnabled)
                }
            }

            item {
                SettingsSection("Privacy") {
                    ToggleRow("Redact before cloud", settings.redactBeforeCloud, viewModel::setRedactBeforeCloud)
                    ToggleRow("Wi-Fi only for cloud", settings.wifiOnlyForCloud, viewModel::setWifiOnlyForCloud)
                    ToggleRow("Store conversation history", settings.storeConversationHistory, viewModel::setStoreConversationHistory)
                }
            }

            item {
                SettingsSection("Runtime Status") {
                    uiState.runtimeSummary.highestPriorityMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    uiState.runtimeSummary.capabilities.forEach { runtime ->
                        ListItem(
                            headlineContent = { Text(runtime.capability.label()) },
                            supportingContent = { Text(runtime.message ?: "Ready") }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun AiCapability.label(): String = when (this) {
    AiCapability.DASHBOARD_BRIEFING -> "Dashboard briefing"
    AiCapability.REVIEW_EXPLANATION -> "Review explanation"
    AiCapability.QUERY_INTERPRETATION -> "Query interpretation"
    AiCapability.RECEIPT_EXTRACTION -> "Receipt assist"
    AiCapability.CATEGORIZATION_FALLBACK -> "Categorization"
    AiCapability.DEDUPE_JUDGE -> "Duplicate detection"
    AiCapability.LOCATION_SUMMARY -> "Location summary"
}
