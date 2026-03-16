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
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.routeDisplayText
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.util.Date

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
                SettingsSection(
                    title = "General",
                    description = "Control whether AI is active at all and which providers may be used."
                ) {
                    ToggleRow("Enable AI", settings.aiEnabled, viewModel::setAiEnabled)
                    ToggleRow("Allow cloud AI", settings.allowCloudAi, viewModel::setAllowCloudAi)
                    ToggleRow("Allow on-device AI", settings.allowOnDeviceAi, viewModel::setAllowOnDeviceAi)
                }
            }

            item {
                SettingsSection(
                    title = "Preferred Mode",
                    description = "Choose how the app should prefer local and cloud AI when both are available."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.preferredMode == mode,
                                onClick = { viewModel.setPreferredMode(mode) },
                                label = { Text(mode.displayLabel()) }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    title = "Capability Matrix",
                    description = "See feature enablement and on-device readiness together for each AI capability."
                ) {
                    CapabilityMatrixRow(
                        label = "Assistant",
                        enabled = settings.assistantEnabled,
                        onEnabledChange = viewModel::setAssistantEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.QUERY_INTERPRETATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Query interpretation",
                        enabled = settings.queryInterpretationEnabled,
                        onEnabledChange = viewModel::setQueryInterpretationEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.QUERY_INTERPRETATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Dashboard briefing",
                        enabled = settings.dashboardBriefingEnabled,
                        onEnabledChange = viewModel::setDashboardBriefingEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.DASHBOARD_BRIEFING },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Review explanation",
                        enabled = settings.reviewExplanationEnabled,
                        onEnabledChange = viewModel::setReviewExplanationEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.REVIEW_EXPLANATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Receipt assist",
                        enabled = settings.receiptAssistEnabled,
                        onEnabledChange = viewModel::setReceiptAssistEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.RECEIPT_EXTRACTION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Categorization fallback",
                        enabled = settings.categorizationFallbackEnabled,
                        onEnabledChange = viewModel::setCategorizationFallbackEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.CATEGORIZATION_FALLBACK },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = "Duplicate detection",
                        enabled = settings.dedupeJudgeEnabled,
                        onEnabledChange = viewModel::setDedupeJudgeEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.DEDUPE_JUDGE },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Privacy",
                    description = "Control what can leave the device and how cloud calls are constrained."
                ) {
                    ToggleRow("Redact before cloud", settings.redactBeforeCloud, viewModel::setRedactBeforeCloud)
                    ToggleRow("Wi-Fi only for cloud", settings.wifiOnlyForCloud, viewModel::setWifiOnlyForCloud)
                    ToggleRow("Store conversation history", settings.storeConversationHistory, viewModel::setStoreConversationHistory)
                }
            }

            item {
                SettingsSection(
                    title = "Runtime Status",
                    description = "See whether on-device AI is ready, downloading, or unavailable per capability."
                ) {
                    RuntimeGuidanceCard(
                        settings = settings,
                        highestPriorityMessage = uiState.runtimeSummary.highestPriorityMessage
                    )
                    RuntimeMetaCard(
                        networkAvailable = uiState.runtimeSummary.networkAvailable,
                        wifiConnected = uiState.runtimeSummary.wifiConnected,
                        lastRefreshedAt = uiState.runtimeSummary.lastRefreshedAt
                    )

                    uiState.runtimeSummary.highestPriorityMessage?.let {
                        ElevatedCard {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    uiState.runtimeSummary.capabilities.forEach { runtime ->
                        ListItem(
                            headlineContent = { Text(runtime.capability.label()) },
                            overlineContent = { RuntimeBadge(runtime.message == null) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(runtime.message ?: "Ready")
                                    runtime.routeDisplayText()?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    runtime.actionLabel?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeMetaCard(
    networkAvailable: Boolean,
    wifiConnected: Boolean,
    lastRefreshedAt: Long
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Runtime context", style = MaterialTheme.typography.titleSmall)
            Text(
                "Network: ${if (networkAvailable) "available" else "offline"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Wi-Fi: ${if (wifiConnected) "connected" else "not connected"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lastRefreshedAt > 0L) {
                Text(
                    "Last refreshed: ${DateFormatterUtils.timeWithSecondsAndDate().format(Date(lastRefreshedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@Composable
private fun CapabilityMatrixRow(
    label: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    runtime: AiCapabilityRuntimeStatus?,
    cloudFallbackAvailable: Boolean
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    runtime?.let {
                        RuntimeBadge(it.message == null)
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            val runtimeText = when {
                !enabled -> "Disabled in settings"
                runtime == null -> "Runtime status not loaded"
                runtime.message == null -> "Ready"
                else -> runtime.message
            }

            Text(
                text = runtimeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (enabled) {
                runtime?.routeDisplayText()?.let { routeText ->
                    Text(
                        text = routeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (enabled && runtime?.actionLabel != null) {
                Text(
                    text = runtime.actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            cloudFallbackHint(
                enabled = enabled,
                runtime = runtime,
                cloudFallbackAvailable = cloudFallbackAvailable
            )?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun RuntimeBadge(isReady: Boolean) {
    Surface(
        color = if (isReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = if (isReady) "Ready" else "Needs attention",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RuntimeGuidanceCard(settings: AiSettings, highestPriorityMessage: String?) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("What to do next", style = MaterialTheme.typography.titleSmall)
            Text(
                text = runtimeGuidanceText(settings, highestPriorityMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

internal fun runtimeGuidanceText(settings: AiSettings, highestPriorityMessage: String?): String = when {
    settings.aiEnabled && settings.allowCloudAi && highestPriorityMessage != null -> {
        "$highestPriorityMessage Cloud routing can still handle advisory features when your mode and privacy settings allow it."
    }
    settings.aiEnabled && settings.allowCloudAi -> {
        "Cloud AI is enabled, so advisory features can still run even if on-device AI is unavailable on this device."
    }
    highestPriorityMessage != null -> highestPriorityMessage
    else -> "If a capability is marked Ready, on-device AI can be used immediately when allowed by settings and routing."
}

internal fun cloudFallbackHint(
    enabled: Boolean,
    runtime: AiCapabilityRuntimeStatus?,
    cloudFallbackAvailable: Boolean
): String? {
    if (!enabled || !cloudFallbackAvailable || runtime?.message == null) {
        return null
    }
    if (runtime.route == com.yourname.expensetracker.domain.ai.model.AiRoute.CLOUD) {
        return null
    }
    return "Cloud fallback available"
}

private fun AiMode.displayLabel(): String = when (this) {
    AiMode.AUTO -> "Automatic"
    AiMode.ON_DEVICE -> "On-device"
    AiMode.CLOUD -> "Cloud"
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
