package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// TODO (P8-P1-12): Add a `deniedCapabilities: List<PrivacyCapability>` field to
// PrivacySettingsUiState so the UI can show which features are currently blocked
// by each toggle (e.g. "Disabling cloud AI blocks: receipt assist, categorization,
// dashboard briefing"). Compute from EffectiveCloudAiPolicyResolver + LocationPrivacyGate.
data class PrivacySettingsUiState(
    val settings: PrivacySettings = PrivacySettings(),
    val isSaving: Boolean = false
)

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val repository: PrivacySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacySettingsUiState())
    val uiState: StateFlow<PrivacySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

    fun setNotificationCaptureEnabled(enabled: Boolean) = update { it.copy(notificationCaptureEnabled = enabled) }
    fun setCloudAiEnabled(enabled: Boolean) = update { it.copy(cloudAiEnabled = enabled) }
    fun setRedactBeforeCloud(enabled: Boolean) = update { it.copy(redactBeforeCloud = enabled) }
    fun setReceiptImageCloudEnabled(enabled: Boolean) = update { it.copy(receiptImageCloudEnabled = enabled) }
    fun setBankStatementAiEnabled(enabled: Boolean) = update { it.copy(bankStatementAiEnabled = enabled) }
    fun setExternalGeocodingEnabled(enabled: Boolean) = update { it.copy(externalGeocodingEnabled = enabled) }
    fun setBackgroundLocationBackfillEnabled(enabled: Boolean) = update { it.copy(backgroundLocationBackfillEnabled = enabled) }
    fun setDeviceGpsLocationEnabled(enabled: Boolean) = update { it.copy(deviceGpsLocationEnabled = enabled) }
    fun setEncryptedBackupEnabled(enabled: Boolean) = update { it.copy(encryptedBackupEnabled = enabled) }
    fun setDebugDataPersistenceEnabled(enabled: Boolean) = update { it.copy(debugDataPersistenceEnabled = enabled) }

    fun setRawNotificationRetentionDays(days: Int) = update { it.copy(rawNotificationRetentionDays = days.coerceIn(0, 365)) }
    fun setRawOcrRetentionDays(days: Int) = update { it.copy(rawOcrRetentionDays = days.coerceIn(0, 365)) }

    private fun update(transform: (PrivacySettings) -> PrivacySettings) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSaving = true)
                repository.updateSettings(transform)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update privacy setting")
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }
}
