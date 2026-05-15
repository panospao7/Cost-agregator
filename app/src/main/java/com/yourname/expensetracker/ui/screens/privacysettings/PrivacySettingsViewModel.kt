package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject

data class PrivacySettingsUiState(
    val settings: PrivacySettings = PrivacySettings(),
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val blocked: List<PrivacyBlocked> = emptyList(),
    val errorMessage: String? = null,
    /** S3-010: pending risky toggle confirmation */
    val pendingRiskyConfirm: RiskyToggleConfirm? = null
)

enum class RiskyToggleConfirm { ENABLE_CLOUD_AI, DISABLE_REDACTION }

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    private val repository: PrivacySettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacySettingsUiState())
    val uiState: StateFlow<PrivacySettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { settings ->
                _uiState.value = _uiState.value.copy(
                    settings = settings,
                    blocked = computeBlocked(settings),
                    isLoading = false  // S3-017: clear loading after first real emission
                )
            }
        }
    }

    private fun computeBlocked(settings: PrivacySettings): List<PrivacyBlocked> = buildList {
        if (!settings.cloudAiEnabled) add(PrivacyBlocked.CloudAiDisabled())
        if (!settings.receiptImageCloudEnabled) add(PrivacyBlocked.ReceiptImageUploadDisabled())
        if (!settings.externalGeocodingEnabled) add(PrivacyBlocked.ExternalGeocodingDisabled())
        if (!settings.notificationCaptureEnabled) add(PrivacyBlocked.NotificationCaptureDisabled())
        // S3-001: Additional capabilities
        if (!settings.bankStatementAiEnabled) add(PrivacyBlocked.BankStatementAiDisabled())
        if (!settings.backgroundLocationBackfillEnabled) add(PrivacyBlocked.BackgroundLocationDisabled())
        if (!settings.deviceGpsLocationEnabled) add(PrivacyBlocked.DeviceGpsDisabled())
        if (!settings.encryptedBackupEnabled) add(PrivacyBlocked.EncryptedBackupDisabled())
        if (!settings.debugDataPersistenceEnabled) add(PrivacyBlocked.DebugDataPersistenceDisabled())
    }

    fun setNotificationCaptureEnabled(enabled: Boolean) = update { it.copy(notificationCaptureEnabled = enabled) }

    // S3-010: Risky toggles require confirmation before applying
    fun requestSetCloudAiEnabled(enabled: Boolean) {
        if (enabled && !_uiState.value.settings.cloudAiEnabled) {
            _uiState.value = _uiState.value.copy(pendingRiskyConfirm = RiskyToggleConfirm.ENABLE_CLOUD_AI)
        } else {
            update { it.copy(cloudAiEnabled = enabled) }
        }
    }

    fun requestSetRedactBeforeCloud(enabled: Boolean) {
        if (!enabled && _uiState.value.settings.redactBeforeCloud) {
            _uiState.value = _uiState.value.copy(pendingRiskyConfirm = RiskyToggleConfirm.DISABLE_REDACTION)
        } else {
            update { it.copy(redactBeforeCloud = enabled) }
        }
    }

    fun confirmRiskyToggle() {
        when (_uiState.value.pendingRiskyConfirm) {
            RiskyToggleConfirm.ENABLE_CLOUD_AI -> update { it.copy(cloudAiEnabled = true) }
            RiskyToggleConfirm.DISABLE_REDACTION -> update { it.copy(redactBeforeCloud = false) }
            null -> Unit
        }
        _uiState.value = _uiState.value.copy(pendingRiskyConfirm = null)
    }

    fun dismissRiskyConfirm() {
        _uiState.value = _uiState.value.copy(pendingRiskyConfirm = null)
    }

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

    // S3-009: Raw storage mode controls
    fun setRawNotificationStorageMode(mode: com.yourname.expensetracker.domain.privacy.RawStorageMode) = update { it.copy(rawNotificationStorageMode = mode) }
    fun setRawOcrStorageMode(mode: com.yourname.expensetracker.domain.privacy.RawStorageMode) = update { it.copy(rawOcrStorageMode = mode) }
    fun setEmailReceiptStorageMode(mode: com.yourname.expensetracker.domain.privacy.RawStorageMode) = update { it.copy(emailReceiptStorageMode = mode) }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private val updateMutex = Mutex()

    private fun update(transform: (PrivacySettings) -> PrivacySettings) {
        viewModelScope.launch {
            updateMutex.withLock {
                try {
                    _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
                    repository.updateSettings(transform)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update privacy setting")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to save privacy setting. Please retry."
                    )
                } finally {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                }
            }
        }
    }
}
