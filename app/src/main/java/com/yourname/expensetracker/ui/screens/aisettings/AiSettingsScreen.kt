package com.yourname.expensetracker.ui.screens.aisettings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.routeDisplayText
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.theme.SemanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onDismiss: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.ai_settings_title),
                        color = SemanticColors.TextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ai_back_cd))
                    }
                },
                actions = {
                    if (uiState.isRefreshingRuntime) {
                        val refreshingCd = stringResource(R.string.ai_refreshing_cd)
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .semantics { contentDescription = refreshingCd }
                        )
                    } else {
                        val refreshButtonCd = stringResource(R.string.ai_refresh_button_cd)
                        TextButton(
                            onClick = viewModel::refreshRuntimeStatus,
                            modifier = Modifier.semantics { contentDescription = refreshButtonCd }
                        ) {
                            Text(stringResource(R.string.ai_refresh))
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_general),
                    description = stringResource(R.string.ai_section_general_desc)
                ) {
                    ToggleRow(stringResource(R.string.ai_toggle_enable), settings.aiEnabled, viewModel::setAiEnabled)
                    CloudAiToggleWithDisclosure(settings.allowCloudAi, viewModel::setAllowCloudAi)
                    // S11-002: Show effective cloud policy status
                    uiState.effectiveCloudBlocked?.let { reason ->
                        Text(
                            text = "⚠ " + stringResource(R.string.ai_cloud_blocked_format, reason.asString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = androidx.compose.ui.Modifier.padding(start = 16.dp, bottom = 4.dp)
                        )
                    }
                    if (uiState.effectiveCloudBlocked == null && settings.allowCloudAi && settings.aiEnabled) {
                        Text(
                            text = "✓ " + stringResource(R.string.ai_cloud_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = androidx.compose.ui.Modifier.padding(start = 16.dp, bottom = 4.dp)
                        )
                    }
                    // S11-007: Show settings write error
                    uiState.settingsWriteError?.let { err ->
                        Text(
                            text = err.asString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = androidx.compose.ui.Modifier
                                .padding(start = 16.dp, bottom = 4.dp)
                                .clickable { viewModel.clearSettingsWriteError() }
                        )
                    }
                    ToggleRow(stringResource(R.string.ai_toggle_on_device), settings.allowOnDeviceAi, viewModel::setAllowOnDeviceAi)
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_preferred_mode),
                    description = stringResource(R.string.ai_section_preferred_mode_desc)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiMode.entries.forEach { mode ->
                            val isSelected = settings.preferredMode == mode
                            val modeStatus = if (isSelected) 
                                stringResource(R.string.recurring_tab_selected) 
                            else 
                                stringResource(R.string.recurring_tab_not_selected)
                            val modeCd = stringResource(R.string.ai_mode_cd_format, mode.displayLabel(), modeStatus)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setPreferredMode(mode) },
                                label = { Text(mode.displayLabel()) },
                                modifier = Modifier.semantics {
                                    contentDescription = modeCd
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_provider),
                    description = stringResource(R.string.ai_section_provider_desc)
                ) {
                    ProviderAndApiKeySection(
                        providerName = uiState.providerName,
                        apiKeyInput = uiState.apiKeyInput,
                        hasStoredApiKey = uiState.hasStoredApiKey,
                        apiKeyValidationMessage = uiState.apiKeyValidationMessage,
                        isTestingConnection = uiState.isTestingConnection,
                        connectionTestMessage = uiState.connectionTestMessage,
                        isConnectionTestSuccess = uiState.isConnectionTestSuccess,
                        onApiKeyChange = viewModel::updateApiKeyInput,
                        onSaveApiKey = viewModel::saveApiKey,
                        onTestConnection = viewModel::testConnection
                    )
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_capability),
                    description = stringResource(R.string.ai_section_capability_desc)
                ) {
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_assistant),
                        enabled = settings.assistantEnabled,
                        onEnabledChange = viewModel::setAssistantEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.QUERY_INTERPRETATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_query),
                        enabled = settings.queryInterpretationEnabled,
                        onEnabledChange = viewModel::setQueryInterpretationEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.QUERY_INTERPRETATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_dashboard),
                        enabled = settings.dashboardBriefingEnabled,
                        onEnabledChange = viewModel::setDashboardBriefingEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.DASHBOARD_BRIEFING },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    // B2e (UX 2.1/2.2, INV-15/16): on-device only; independent of the briefing switch.
                    TransactionInsightRow(
                        state = transactionInsightRowState(
                            settings = settings,
                            runtime = uiState.runtimeSummary.capabilities
                                .find { it.capability == AiCapability.TRANSACTION_INSIGHT }
                        ),
                        checked = settings.transactionInsightEnabled,
                        onCheckedChange = viewModel::setTransactionInsightEnabled
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_review),
                        enabled = settings.reviewExplanationEnabled,
                        onEnabledChange = viewModel::setReviewExplanationEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.REVIEW_EXPLANATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_receipt),
                        enabled = settings.receiptAssistEnabled,
                        onEnabledChange = viewModel::setReceiptAssistEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.RECEIPT_EXTRACTION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    ToggleRow(
                        stringResource(R.string.ai_toggle_receipt_image),
                        settings.receiptImageCloudEnabled,
                        viewModel::setReceiptImageCloudEnabled
                    )
                    Text(
                        text = stringResource(R.string.ai_receipt_image_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_categorization),
                        enabled = settings.categorizationFallbackEnabled,
                        onEnabledChange = viewModel::setCategorizationFallbackEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.CATEGORIZATION_FALLBACK },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_duplicate),
                        enabled = settings.dedupeJudgeEnabled,
                        onEnabledChange = viewModel::setDedupeJudgeEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.DEDUPE_JUDGE },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    // S11-008: Receipt item categorization toggle
                    CapabilityMatrixRow(
                        label = stringResource(R.string.ai_capability_receipt_item_categorization),
                        enabled = settings.receiptItemCategorizationEnabled,
                        onEnabledChange = viewModel::setReceiptItemCategorizationEnabled,
                        runtime = uiState.runtimeSummary.capabilities.find { it.capability == AiCapability.RECEIPT_ITEM_CATEGORIZATION },
                        cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
                    )
                    // INV-7: warranty detection is local-only; the former toggle gated no data flow, so the
                    // row is hidden and replaced by this static caption (UX 2.3). The stored flag is untouched.
                    Text(
                        text = stringResource(R.string.ai_warranty_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_privacy),
                    description = stringResource(R.string.ai_section_privacy_desc)
                ) {
                    ToggleRow(stringResource(R.string.ai_toggle_redact), settings.redactBeforeCloud, viewModel::setRedactBeforeCloud)
                    ToggleRow(stringResource(R.string.ai_toggle_wifi_only), settings.wifiOnlyForCloud, viewModel::setWifiOnlyForCloud)
                    ToggleRow(stringResource(R.string.ai_toggle_store_history), settings.storeConversationHistory, viewModel::setStoreConversationHistory)
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_experimental),
                    description = stringResource(R.string.ai_section_experimental_desc)
                ) {
                    ToggleRow(
                        stringResource(R.string.ai_toggle_proactive),
                        settings.proactiveBriefingsEnabled,
                        viewModel::setProactiveBriefingsEnabled
                    )
                    // B3b (UX 2.4, INV-24/25): the toggle controls only the notification; the briefing
                    // itself is generated in the background whenever the arming predicate holds.
                    Text(
                        text = stringResource(R.string.ai_proactive_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // G2-10 / UX-Q-3: caption only. No permission request, no settings deep link, no button.
                    if (uiState.notificationsBlocked) {
                        Text(
                            text = stringResource(R.string.ai_proactive_notifications_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ToggleRow(
                        stringResource(R.string.ai_toggle_quick_save),
                        settings.receiptQuickSaveEnabled,
                        viewModel::setReceiptQuickSaveEnabled
                    )
                    ToggleRow(
                        stringResource(R.string.ai_toggle_quick_approve),
                        settings.reviewQuickApproveEnabled,
                        viewModel::setReviewQuickApproveEnabled
                    )
                    Text(
                        text = stringResource(R.string.ai_experimental_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.ai_section_runtime),
                    description = stringResource(R.string.ai_section_runtime_desc)
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
                        val runtimeLabel = runtime.capability.label()
                        val isReady = runtime.message == null
                        val runtimeStatusText = runtime.message ?: stringResource(R.string.ai_status_ready)
                        ListItem(
                            headlineContent = { Text(runtimeLabel) },
                            overlineContent = { RuntimeBadge(isReady) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(runtimeStatusText)
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
            Text(stringResource(R.string.ai_runtime_context_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.ai_runtime_network_format,
                    if (networkAvailable) stringResource(R.string.ai_network_available) else stringResource(R.string.ai_network_offline)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.ai_runtime_wifi_format,
                    if (wifiConnected) stringResource(R.string.ai_wifi_connected) else stringResource(R.string.ai_wifi_not_connected)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lastRefreshedAt > 0L) {
                Text(
                    text = stringResource(
                        R.string.ai_runtime_last_refreshed_format,
                        DateFormatterUtils.formatTimestampJavaTime(lastRefreshedAt, "HH:mm:ss dd/MM")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ProviderAndApiKeySection(
    providerName: String,
    apiKeyInput: String,
    hasStoredApiKey: Boolean,
    apiKeyValidationMessage: UiText?,
    isTestingConnection: Boolean,
    connectionTestMessage: UiText?,
    isConnectionTestSuccess: Boolean?,
    onApiKeyChange: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onTestConnection: () -> Unit
) {
    var revealApiKey by rememberSaveable { mutableStateOf(false) }

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_provider_label, providerName),
                style = MaterialTheme.typography.bodyLarge
            )

            if (hasStoredApiKey && apiKeyInput.isBlank()) {
                Text(
                    text = stringResource(R.string.ai_api_key_saved_masked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.ai_api_key_label)) },
                placeholder = { Text(stringResource(R.string.ai_api_key_placeholder)) },
                singleLine = true,
                isError = apiKeyValidationMessage != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false
                ),
                visualTransformation = if (revealApiKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { revealApiKey = !revealApiKey }) {
                        Icon(
                            imageVector = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (revealApiKey) {
                                stringResource(R.string.ai_hide_api_key_cd)
                            } else {
                                stringResource(R.string.ai_show_api_key_cd)
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.ai_api_key_secure_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            apiKeyValidationMessage?.let {
                Text(
                    text = it.asString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSaveApiKey) {
                    Text(stringResource(R.string.ai_save_api_key))
                }
                Button(onClick = onTestConnection, enabled = !isTestingConnection) {
                    Text(
                        if (isTestingConnection) {
                            stringResource(R.string.ai_testing_connection)
                        } else {
                            stringResource(R.string.ai_test_connection)
                        }
                    )
                }
            }

            connectionTestMessage?.let { message ->
                val feedbackColor = when (isConnectionTestSuccess) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = message.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = feedbackColor
                )
            }
        }
    }
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

internal const val CLOUD_AI_DISCLOSURE_TAG = "ai_cloud_briefing_disclosure"

/**
 * ai-fixes INV-25 (Z2): the "Allow cloud AI" toggle with the background-briefing disclosure
 * shown directly under it, so the consequence is visible where the user grants consent.
 */
@Composable
internal fun CloudAiToggleWithDisclosure(
    allowCloudAi: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ToggleRow(stringResource(R.string.ai_toggle_cloud), allowCloudAi, onCheckedChange)
    Text(
        text = stringResource(R.string.ai_cloud_briefing_disclosure),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(CLOUD_AI_DISCLOSURE_TAG)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val statusText = if (checked) stringResource(R.string.ai_toggle_enabled) else stringResource(R.string.ai_toggle_disabled)
    val toggleCd = stringResource(R.string.ai_toggle_cd_format, label, statusText)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = toggleCd
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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
    val enabledText = stringResource(if (enabled) R.string.ai_toggle_enabled else R.string.ai_toggle_disabled)
    val runtimeText = when {
        !enabled -> stringResource(R.string.ai_status_disabled)
        runtime == null -> stringResource(R.string.ai_status_not_loaded)
        runtime.message == null -> stringResource(R.string.ai_status_ready)
        else -> runtime.message
    }
    
    val accessibilityDescription = stringResource(
        R.string.ai_capability_cd_format,
        label,
        enabledText,
        runtimeText
    )

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .semantics {
                    contentDescription = accessibilityDescription
                },
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

internal enum class TransactionInsightRowStatus {
    READY,
    NEEDS_AI,
    NEEDS_ON_DEVICE,
    DISABLED,
    NOT_LOADED,
    NEEDS_ATTENTION
}

internal data class TransactionInsightRowState(
    val status: TransactionInsightRowStatus,
    /** False only for a permanent platform limit (UX 2.2); the stored value is never rewritten. */
    val switchEnabled: Boolean,
    /** Bounded on-device status text; only set for [TransactionInsightRowStatus.NEEDS_ATTENTION]. */
    val attentionMessage: String?
)

/**
 * UX 2.2 state table for the insight row. Deliberately ignores the router's route reason (which
 * may mention cloud) and uses only the on-device status, because the insight never uses the cloud.
 */
internal fun transactionInsightRowState(
    settings: AiSettings,
    runtime: AiCapabilityRuntimeStatus?
): TransactionInsightRowState {
    val permanentLimit = runtime != null && (
        runtime.status == OnDeviceModelStatus.UNSUPPORTED_DEVICE ||
            runtime.status == OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION
        )
    return when {
        permanentLimit -> TransactionInsightRowState(
            TransactionInsightRowStatus.NEEDS_ATTENTION,
            switchEnabled = false,
            attentionMessage = runtime?.onDeviceMessage
        )
        !settings.aiEnabled -> TransactionInsightRowState(TransactionInsightRowStatus.NEEDS_AI, true, null)
        !settings.allowOnDeviceAi -> TransactionInsightRowState(TransactionInsightRowStatus.NEEDS_ON_DEVICE, true, null)
        !settings.transactionInsightEnabled -> TransactionInsightRowState(TransactionInsightRowStatus.DISABLED, true, null)
        runtime == null -> TransactionInsightRowState(TransactionInsightRowStatus.NOT_LOADED, true, null)
        runtime.status == OnDeviceModelStatus.AVAILABLE -> TransactionInsightRowState(TransactionInsightRowStatus.READY, true, null)
        else -> TransactionInsightRowState(
            TransactionInsightRowStatus.NEEDS_ATTENTION,
            switchEnabled = true,
            attentionMessage = runtime.onDeviceMessage
        )
    }
}

@Composable
private fun TransactionInsightRow(
    state: TransactionInsightRowState,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val label = stringResource(R.string.ai_insight_label)
    val enabledText = stringResource(if (checked) R.string.ai_toggle_enabled else R.string.ai_toggle_disabled)
    val statusText = when (state.status) {
        TransactionInsightRowStatus.READY -> stringResource(R.string.ai_status_ready)
        TransactionInsightRowStatus.NEEDS_AI -> stringResource(R.string.ai_insight_needs_ai)
        TransactionInsightRowStatus.NEEDS_ON_DEVICE -> stringResource(R.string.ai_insight_needs_on_device)
        TransactionInsightRowStatus.DISABLED -> stringResource(R.string.ai_status_disabled)
        TransactionInsightRowStatus.NOT_LOADED -> stringResource(R.string.ai_status_not_loaded)
        TransactionInsightRowStatus.NEEDS_ATTENTION ->
            state.attentionMessage ?: stringResource(R.string.ai_status_needs_attention)
    }
    val accessibilityDescription = stringResource(
        R.string.ai_capability_cd_format,
        label,
        enabledText,
        statusText
    )

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .semantics { contentDescription = accessibilityDescription },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    when (state.status) {
                        TransactionInsightRowStatus.READY -> RuntimeBadge(isReady = true)
                        TransactionInsightRowStatus.NEEDS_ATTENTION -> RuntimeBadge(isReady = false)
                        else -> Unit
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = state.switchEnabled
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // UX 8: permanent description as its own text node. Honest on-device copy; no route
            // line and no cloud-fallback hint is ever rendered for this row (INV-16).
            Text(
                text = stringResource(R.string.ai_insight_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            text = if (isReady) stringResource(R.string.ai_status_ready) else stringResource(R.string.ai_status_needs_attention),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RuntimeGuidanceCard(settings: AiSettings, highestPriorityMessage: String?) {
    val guidanceText = runtimeGuidanceText(settings, highestPriorityMessage)
    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.ai_guidance_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = guidanceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
internal fun runtimeGuidanceText(settings: AiSettings, highestPriorityMessage: String?): String = when {
    settings.aiEnabled && settings.allowCloudAi && highestPriorityMessage != null -> {
        stringResource(R.string.ai_guidance_cloud_with_message_format, highestPriorityMessage)
    }
    settings.aiEnabled && settings.allowCloudAi -> {
        stringResource(R.string.ai_guidance_cloud_enabled)
    }
    highestPriorityMessage != null -> highestPriorityMessage
    else -> stringResource(R.string.ai_guidance_default)
}

@Composable
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
    return stringResource(R.string.ai_cloud_fallback_hint)
}

@Composable
private fun AiMode.displayLabel(): String = when (this) {
    AiMode.AUTO -> stringResource(R.string.ai_mode_auto)
    AiMode.ON_DEVICE -> stringResource(R.string.ai_mode_on_device)
    AiMode.CLOUD -> stringResource(R.string.ai_mode_cloud)
}

@Composable
private fun AiCapability.label(): String = when (this) {
    AiCapability.DASHBOARD_BRIEFING -> stringResource(R.string.ai_capability_dashboard)
    AiCapability.REVIEW_EXPLANATION -> stringResource(R.string.ai_capability_review)
    AiCapability.QUERY_INTERPRETATION -> stringResource(R.string.ai_capability_query)
    AiCapability.RECEIPT_EXTRACTION -> stringResource(R.string.ai_capability_receipt)
    AiCapability.WARRANTY_EXTRACTION -> stringResource(R.string.ai_capability_warranty_extraction)
    AiCapability.CATEGORIZATION_FALLBACK -> stringResource(R.string.ai_capability_categorization)
    AiCapability.DEDUPE_JUDGE -> stringResource(R.string.ai_capability_duplicate)
    AiCapability.LOCATION_SUMMARY -> stringResource(R.string.ai_capability_location_summary)
    AiCapability.NOTIFICATION_PARSE -> stringResource(R.string.ai_capability_notification_parse)
    AiCapability.REVIEW_PRIORITIZATION -> stringResource(R.string.ai_capability_review_prioritization)
    AiCapability.SEMANTIC_DEDUPE -> stringResource(R.string.ai_capability_semantic_dedupe)
    AiCapability.RECEIPT_ITEM_CATEGORIZATION -> stringResource(R.string.ai_capability_receipt_item_cat)
    AiCapability.TRANSACTION_INSIGHT -> stringResource(R.string.ai_capability_transaction_insight)
}
