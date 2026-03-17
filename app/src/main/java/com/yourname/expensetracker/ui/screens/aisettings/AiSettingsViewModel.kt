package com.yourname.expensetracker.ui.screens.aisettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
    val settings: AiSettings = AiSettings(),
    val runtimeSummary: AiRuntimeStatusSummary = AiRuntimeStatusSummary(emptyList(), null),
    val isRefreshingRuntime: Boolean = false
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase
) : ViewModel() {

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
                _uiState.value = _uiState.value.copy(settings = settings)
                refreshRuntimeStatus()
            }
        }
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
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
                isRefreshingRuntime = false
            )
            aiRuntimeDiagnostics.recordRuntimeRefresh(
                message = "AI settings refresh: network=${summary.networkAvailable}, wifi=${summary.wifiConnected}, highest='${summary.highestPriorityMessage ?: "none"}'",
                now = summary.lastRefreshedAt
            )
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
}
