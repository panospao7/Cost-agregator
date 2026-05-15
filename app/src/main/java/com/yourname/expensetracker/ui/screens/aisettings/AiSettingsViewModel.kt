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
import timber.log.Timber
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
    val isConnectionTestSuccess: Boolean? = null,
    /** S11-007: non-null when a settings write failed */
    val settingsWriteError: String? = null,
    /** S11-002: non-null when cloud AI is blocked by privacy/environment */
    val effectiveCloudBlocked: String? = null
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase,
    private val secureKeyStorage: SecureKeyStorage,
    private val privacyGate: PrivacyGate,
    /** S11-003: Injected tester — no OkHttp construction in ViewModel */
    private val connectionTester: com.yourname.expensetracker.domain.ai.service.CloudProviderConnectionTester
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSettingsUiState())
    val uiState: StateFlow<AiSettingsUiState> = _uiState.asStateFlow()

    /** S11-006: Cancel prior refresh job to prevent race */
    private var runtimeRefreshJob: kotlinx.coroutines.Job? = null
    private var runtimeRefreshSeq = 0L

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
        // S11-006: Cancel prior refresh and increment request ID
        val requestId = ++runtimeRefreshSeq
        runtimeRefreshJob?.cancel()
        runtimeRefreshJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRefreshingRuntime = true)
                val summary = getAiRuntimeStatusUseCase(
                    capabilities = listOf(
                        AiCapability.QUERY_INTERPRETATION,
                        AiCapability.DASHBOARD_BRIEFING,
                        AiCapability.REVIEW_EXPLANATION,
                        AiCapability.RECEIPT_EXTRACTION,
                        AiCapability.CATEGORIZATION_FALLBACK,
                        AiCapability.DEDUPE_JUDGE,
                        AiCapability.RECEIPT_ITEM_CATEGORIZATION
                    )
                )
                // S11-006: Discard stale result
                if (requestId != runtimeRefreshSeq) return@launch
                // S11-002: Compute effective cloud blocked reason
                val effectiveCloudBlocked = when {
                    !_uiState.value.settings.aiEnabled -> "AI is disabled"
                    !_uiState.value.settings.allowCloudAi -> "Cloud AI is disabled in settings"
                    !secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI) -> "No API key configured"
                    !summary.networkAvailable -> "No network connection"
                    _uiState.value.settings.wifiOnlyForCloud && !summary.wifiConnected -> "Wi-Fi required (Wi-Fi-only mode)"
                    privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL).blocksExecution() ->
                        "Blocked by Privacy Settings"
                    else -> null
                }
                _uiState.value = _uiState.value.copy(
                    runtimeSummary = summary,
                    hasStoredApiKey = secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI),
                    effectiveCloudBlocked = effectiveCloudBlocked
                )
                aiRuntimeDiagnostics.recordRuntimeRefresh(
                    message = "AI settings refresh: network=${summary.networkAvailable}, wifi=${summary.wifiConnected}, highest='${summary.highestPriorityMessage ?: "none"}'",
                    now = summary.lastRefreshedAt
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId != runtimeRefreshSeq) return@launch
                Timber.e(e, "Runtime refresh failed")
            } finally {
                if (requestId == runtimeRefreshSeq) {
                    _uiState.value = _uiState.value.copy(isRefreshingRuntime = false)
                }
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
        // S11-027: Idempotency guard — prevent double-tap
        if (_uiState.value.isTestingConnection) return
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

                val providerFailure = runtimeFailure ?: connectionTester.testGemini(keyToUse)

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
            try {
                val updated = transform(_uiState.value.settings)
                aiSettingsRepository.update(transform)
                if (syncProactiveBriefings) {
                    syncProactiveBriefingWorkUseCase(updated)
                }
            } catch (e: Exception) {
                Timber.e(e, "AI settings write failed")
                _uiState.value = _uiState.value.copy(settingsWriteError = "Settings could not be saved: ${e.message}")
            }
        }
    }

    fun clearSettingsWriteError() {
        _uiState.value = _uiState.value.copy(settingsWriteError = null)
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
