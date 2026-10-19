package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureStatus
import com.yourname.expensetracker.domain.notification.capture.NotificationListenerAccessChecker
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * R17: read-only status of notification capture (consent + OS listener access).
 *
 * Never writes privacy settings and never bypasses NotificationCaptureGate/PrivacyGate.
 * A [PrivacySettingsLoadState.CorruptedFailClosed] state is treated as consent off, and
 * consent always wins over OS access. Until settings load, the status is CONSENT_OFF
 * (fail closed, so the Home prompt stays hidden).
 */
@HiltViewModel
class NotificationCaptureStatusViewModel @Inject constructor(
    repository: PrivacySettingsRepository,
    private val accessChecker: NotificationListenerAccessChecker
) : ViewModel() {

    private val accessGranted = MutableStateFlow(accessChecker.isListenerAccessGranted())

    val status: StateFlow<NotificationCaptureStatus> = combine(
        repository.observeLoadState().map { loadState ->
            when (loadState) {
                is PrivacySettingsLoadState.CorruptedFailClosed -> false
                is PrivacySettingsLoadState.Loaded -> loadState.settings.notificationCaptureEnabled
                is PrivacySettingsLoadState.FirstRunDefault -> loadState.settings.notificationCaptureEnabled
            }
        }.catch { emit(false) },
        accessGranted
    ) { consentOn, granted ->
        NotificationCaptureStatus.resolve(consentOn = consentOn, accessGranted = granted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationCaptureStatus.CONSENT_OFF)

    /** Re-checks the OS grant; call on lifecycle resume (user may have toggled it in Settings). */
    fun refresh() {
        accessGranted.value = accessChecker.isListenerAccessGranted()
    }
}
