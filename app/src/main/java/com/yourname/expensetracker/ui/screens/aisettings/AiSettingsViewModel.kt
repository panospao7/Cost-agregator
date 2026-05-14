package com.yourname.expensetracker.ui.screens.aisettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class AiSettingsUiState(
    val settings: AiSettings = AiSettings(),
    val runtimeSummary: AiRuntimeStatusSummary = AiRuntimeStatusSummary(emptyList(), null),
    val isRefreshingRuntime: Boolean = false,
    val providerName: String = AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_PROVIDER,
    val apiKeyInput: String = "",
    val hasStoredApiKey: Boolean = false,
    val apiKeyValidationMessage: String? = null,
    val isTestingConnection: Boolean = false,
    val connectionTestMessage: String? = null,
    val isConnectionTestSuccess: Boolean? = null
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase,
    private val secureKeyStorage: SecureKeyStorage,
    private val privacyGate: PrivacyGate
) : ViewModel() {

    private val providerConnectionTestClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    val settings = aiSettingsRepository.settings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiSettings()
    )

    init {
        viewModelScope.launch {
            aiSettingsRepository.settings().collect { settings ->
                _uiState.value = _uiState.value.copy(
                    settings = settings,
                    hasStoredApiKey = secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI)
                )
                refreshRuntimeStatus()
            }
        }
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRefreshingRuntime = true)
                val summary = getAiRuntimeStatusUseCase(
                    capabilities = listOf(
                        AiCapability.QUERY_INTERPRETATION,
                        AiCapability.DASHBOARD_BRIEFING,
                        AiCapability.REVIEW_EXPLANATION,
                        AiCapability.RECEIPT_EXTRACTION,
                        AiCapability.CATEGORIZATION_FALLBACK,
                        AiCapability.DEDUPE_JUDGE
                    )
                )
                _uiState.value = _uiState.value.copy(
                    runtimeSummary = summary,
                    hasStoredApiKey = secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI)
                )
                aiRuntimeDiagnostics.recordRuntimeRefresh(
                    message = "AI settings refresh: network=${summary.networkAvailable}, wifi=${summary.wifiConnected}, highest='${summary.highestPriorityMessage ?: "none"}'",
                    now = summary.lastRefreshedAt
                )
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshingRuntime = false)
            }
        }
    }

    fun updateApiKeyInput(value: String) {
        _uiState.value = _uiState.value.copy(
            apiKeyInput = value,
            apiKeyValidationMessage = validateApiKey(value),
            connectionTestMessage = null,
            isConnectionTestSuccess = null
        )
    }

    fun saveApiKey() {
        val key = _uiState.value.apiKeyInput.trim()
        val validation = validateApiKey(key)
        if (validation != null) {
            _uiState.value = _uiState.value.copy(
                apiKeyValidationMessage = validation,
                connectionTestMessage = "Enter a valid API key before saving.",
                isConnectionTestSuccess = false
            )
            return
        }

        // S3-007: Blank input with stored key should NOT delete — require explicit removeApiKey()
        if (key.isBlank()) {
            if (_uiState.value.hasStoredApiKey) {
                _uiState.value = _uiState.value.copy(
                    apiKeyValidationMessage = "Enter a new key or use Remove Key to delete the stored key.",
                    connectionTestMessage = null
                )
            }
            return
        }

        if (_uiState.value.isConnectionTestSuccess != true) {
            _uiState.value = _uiState.value.copy(
                apiKeyValidationMessage = null,
                connectionTestMessage = "Run a successful connection test before saving this API key.",
                isConnectionTestSuccess = false
            )
            return
        }

        secureKeyStorage.storeKey(SecureKeyStorage.KEY_GEMINI, key)
        _uiState.value = _uiState.value.copy(
            apiKeyInput = "",
            hasStoredApiKey = true,
            apiKeyValidationMessage = null,
            connectionTestMessage = "API key saved securely.",
            isConnectionTestSuccess = true
        )
    }

    /**
     * S3-007: Explicit API key removal. Requires user to call this directly
     * (not triggered by blank save input) to prevent accidental key deletion.
     */
    fun removeApiKey() {
        secureKeyStorage.deleteKey(SecureKeyStorage.KEY_GEMINI)
        _uiState.value = _uiState.value.copy(
            hasStoredApiKey = false,
            apiKeyInput = "",
            apiKeyValidationMessage = null,
            connectionTestMessage = "API key removed.",
            isConnectionTestSuccess = null
        )
    }

    fun testConnection() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = true,
                    connectionTestMessage = null,
                    isConnectionTestSuccess = null
                )

                val state = _uiState.value
                val typedKey = state.apiKeyInput.trim()
                val persistedKey = secureKeyStorage.getGeminiKey().orEmpty()
                val keyToUse = when {
                    typedKey.isNotBlank() -> typedKey
                    persistedKey.isNotBlank() -> persistedKey
                    else -> ""
                }

                if (keyToUse.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        apiKeyValidationMessage = "API key is required for cloud provider.",
                        connectionTestMessage = "Enter an API key before testing connection.",
                        isConnectionTestSuccess = false
                    )
                    return@launch
                }

                val keyValidation = validateApiKey(keyToUse)
                if (keyValidation != null) {
                    _uiState.value = _uiState.value.copy(
                        apiKeyValidationMessage = keyValidation,
                        connectionTestMessage = "Enter a valid API key before testing connection.",
                        isConnectionTestSuccess = false
                    )
                    return@launch
                }

                val settings = _uiState.value.settings
                if (!settings.aiEnabled) {
                    _uiState.value = _uiState.value.copy(
                        connectionTestMessage = "Enable AI first, then run connection test again.",
                        isConnectionTestSuccess = false
                    )
                    return@launch
                }

                if (!settings.allowCloudAi) {
                    _uiState.value = _uiState.value.copy(
                        connectionTestMessage = "Enable cloud AI to test provider connectivity.",
                        isConnectionTestSuccess = false
                    )
                    return@launch
                }

                val summary = getAiRuntimeStatusUseCase(listOf(AiCapability.QUERY_INTERPRETATION))
                val capability = summary.capabilities.firstOrNull { it.capability == AiCapability.QUERY_INTERPRETATION }
                val runtimeFailure = when {
                    !summary.networkAvailable -> "No network connection. Check internet and retry."
                    settings.wifiOnlyForCloud && !summary.wifiConnected -> "Cloud AI is limited to Wi-Fi by settings."
                    capability == null -> "Couldn’t load runtime status. Please retry."
                    capability.route == AiRoute.DISABLED || capability.route == AiRoute.DETERMINISTIC_FALLBACK ->
                        capability.message ?: "Cloud AI is currently unavailable."
                    else -> null
                }

                val providerFailure = runtimeFailure ?: probeCloudProviderConnection(keyToUse)

                _uiState.value = _uiState.value.copy(
                    runtimeSummary = summary,
                    apiKeyValidationMessage = null,
                    connectionTestMessage = providerFailure
                        ?: "Connection test passed. Cloud provider is configured and reachable.",
                    isConnectionTestSuccess = providerFailure == null
                )
            } catch (e: Exception) {
                Timber.e(e, "AI settings connection test failed")
                _uiState.value = _uiState.value.copy(
                    connectionTestMessage = "Connection test failed. Please retry.",
                    isConnectionTestSuccess = false
                )
            } finally {
                _uiState.value = _uiState.value.copy(isTestingConnection = false)
            }
        }
    }

    private suspend fun probeCloudProviderConnection(apiKey: String): String? = withContext(Dispatchers.IO) {
        // PRIVACY GATE: Check privacy gate before probing cloud provider
        val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
        if (gateCheck.blocksExecution()) {
            Timber.w("AiSettingsViewModel: provider probe blocked by privacy gate: ${gateCheck.reason()}")
            return@withContext "Cloud AI is blocked by privacy settings: ${gateCheck.reason()}"
        }

        val request = Request.Builder()
            .url("${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()

        try {
            providerConnectionTestClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> null
                    response.code in setOf(400, 401, 403) -> "Provider rejected the API key. Check the key and retry."
                    response.code == 429 -> "Provider rate limit reached. Wait a moment and retry."
                    response.code in 500..599 -> "Provider is temporarily unavailable. Please retry."
                    else -> "Provider connectivity test failed (HTTP ${response.code})."
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "AI settings provider probe failed")
            "Could not reach the cloud provider. Check internet and retry."
        }
    }

    fun setAiEnabled(enabled: Boolean) = update(
        transform = { it.copy(aiEnabled = enabled) },
        syncProactiveBriefings = true
    )
    fun setAllowCloudAi(enabled: Boolean) = update(transform = { it.copy(allowCloudAi = enabled) })
    fun setAllowOnDeviceAi(enabled: Boolean) = update(transform = { it.copy(allowOnDeviceAi = enabled) })
    fun setAssistantEnabled(enabled: Boolean) = update(transform = { it.copy(assistantEnabled = enabled) })
    fun setQueryInterpretationEnabled(enabled: Boolean) = update(transform = { it.copy(queryInterpretationEnabled = enabled) })
    fun setDashboardBriefingEnabled(enabled: Boolean) = update(
        transform = { it.copy(dashboardBriefingEnabled = enabled) },
        syncProactiveBriefings = true
    )
    fun setReviewExplanationEnabled(enabled: Boolean) = update(transform = { it.copy(reviewExplanationEnabled = enabled) })
    fun setReceiptAssistEnabled(enabled: Boolean) = update(transform = { it.copy(receiptAssistEnabled = enabled) })
    fun setWarrantyExtractionEnabled(enabled: Boolean) = update(transform = { it.copy(warrantyExtractionEnabled = enabled) })
    fun setReceiptImageCloudEnabled(enabled: Boolean) = update(transform = { it.copy(receiptImageCloudEnabled = enabled) })
    fun setReceiptItemCategorizationEnabled(enabled: Boolean) = update(transform = { it.copy(receiptItemCategorizationEnabled = enabled) })
    fun setCategorizationFallbackEnabled(enabled: Boolean) = update(transform = { it.copy(categorizationFallbackEnabled = enabled) })
    fun setDedupeJudgeEnabled(enabled: Boolean) = update(transform = { it.copy(dedupeJudgeEnabled = enabled) })
    fun setProactiveBriefingsEnabled(enabled: Boolean) = update(
        transform = { it.copy(proactiveBriefingsEnabled = enabled) },
        syncProactiveBriefings = true
    )
    fun setReceiptQuickSaveEnabled(enabled: Boolean) = update(transform = { it.copy(receiptQuickSaveEnabled = enabled) })
    fun setReviewQuickApproveEnabled(enabled: Boolean) = update(transform = { it.copy(reviewQuickApproveEnabled = enabled) })
    fun setRedactBeforeCloud(enabled: Boolean) = update(transform = { it.copy(redactBeforeCloud = enabled) })
    fun setWifiOnlyForCloud(enabled: Boolean) = update(transform = { it.copy(wifiOnlyForCloud = enabled) })
    fun setStoreConversationHistory(enabled: Boolean) = update(transform = { it.copy(storeConversationHistory = enabled) })
    fun setPreferredMode(mode: AiMode) = update(transform = { it.copy(preferredMode = mode) })

    private fun update(
        transform: (AiSettings) -> AiSettings,
        syncProactiveBriefings: Boolean = false
    ) {
        viewModelScope.launch {
            val updated = transform(_uiState.value.settings)
            aiSettingsRepository.update(transform)
            if (syncProactiveBriefings) {
                syncProactiveBriefingWorkUseCase(updated)
            }
        }
    }

    private fun validateApiKey(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.length < 20) {
            return "API key looks too short"
        }
        if (!trimmed.startsWith("AIza")) {
            return "API key format looks invalid for Gemini"
        }
        return null
    }
}
