package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified capture gate that must return [NotificationCaptureDecision.Allowed]
 * before ANY notification text/extras are extracted.
 *
 * Combines:
 *  - Restore/maintenance mode
 *  - Service shutdown state
 *  - Privacy settings (notificationCaptureEnabled)
 *  - Full [PrivacyGate.check(NOTIFICATION_CAPTURE)]
 *  - Blocked-package list
 *
 * The gate is warmed up synchronously on service start to avoid fail-closed
 * false drops before the first flow emission.
 */
@Singleton
class NotificationCaptureGate @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val privacyGate: PrivacyGate,
    private val blockedPackageDao: BlockedPackageDao,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {

    @Volatile
    private var notificationCaptureEnabled: Boolean = false

    @Volatile
    private var settingsLoaded: Boolean = false

    @Volatile
    private var blockedPackages: Set<String> = emptySet()

    @Volatile
    private var blockedPackagesLoaded: Boolean = false

    private val _state = MutableStateFlow<GateState>(GateState.NotReady)
    val state: StateFlow<GateState> = _state.asStateFlow()

    sealed interface GateState {
        data object NotReady : GateState
        data object Ready : GateState
        data class Error(val message: String) : GateState
    }

    /**
     * Synchronously load privacy settings and blocked packages once.
     * Must be called before any [decide] calls.
     */
    suspend fun warmUp() {
        try {
            val settings = privacySettingsRepository.getSettings()
            notificationCaptureEnabled = settings.notificationCaptureEnabled
            settingsLoaded = true
        } catch (e: Exception) {
            Timber.w(e, "CaptureGate: failed to load privacy settings during warmUp")
            // Fail closed — leave notificationCaptureEnabled=false, settingsLoaded=false
        }

        try {
            blockedPackages = blockedPackageDao.getAllPackageNamesOnce().toSet()
            blockedPackagesLoaded = true
        } catch (e: Exception) {
            Timber.w(e, "CaptureGate: failed to load blocked packages during warmUp")
            blockedPackagesLoaded = false
        }

        _state.value = if (settingsLoaded || blockedPackagesLoaded) {
            GateState.Ready
        } else {
            GateState.Error("Failed to load both privacy settings and blocked packages")
        }
    }

    /**
     * Observe live changes to settings and blocked packages after warm-up.
     */
    fun startObservers(scope: CoroutineScope) {
        scope.launch {
            try {
                privacySettingsRepository.observeSettings().collect { settings ->
                    notificationCaptureEnabled = settings.notificationCaptureEnabled
                    settingsLoaded = true
                    if (_state.value !is GateState.Ready) {
                        _state.value = GateState.Ready
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "CaptureGate: settings observer failed")
            }
        }

        scope.launch {
            try {
                blockedPackageDao.getAllPackageNamesFlow().collect { packages ->
                    blockedPackages = packages.toSet()
                    blockedPackagesLoaded = true
                    if (_state.value !is GateState.Ready) {
                        _state.value = GateState.Ready
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "CaptureGate: blocked packages observer failed")
            }
        }
    }

    /**
     * Decide whether capture is allowed for the given notification.
     *
     * This MUST be called before accessing [android.app.Notification.extras]
     * or extracting any text.
     */
    suspend fun decide(
        packageName: String,
        isShuttingDown: Boolean
    ): NotificationCaptureDecision {
        // 1. Restore / maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.RESTORE_MODE,
                diagnosticReasonCode = DiagnosticReasonCode.RESTORE_BLOCKED
            )
        }

        // 2. Service shutting down
        if (isShuttingDown) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.SERVICE_SHUTTING_DOWN,
                diagnosticReasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM
            )
        }

        // 3. Gate not ready
        if (!settingsLoaded && !blockedPackagesLoaded) {
            return NotificationCaptureDecision.TemporarilyUnavailable(
                reason = NotificationCaptureBlockReason.GATE_NOT_READY,
                retryable = true
            )
        }

        // 4. Privacy setting disabled
        if (settingsLoaded && !notificationCaptureEnabled) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_SETTING_DISABLED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        }

        // 5. Full privacy gate check
        val gateDecision = try {
            privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
        } catch (e: Exception) {
            Timber.w(e, "CaptureGate: privacy gate check threw, failing closed")
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.GATE_ERROR,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        }

        when (gateDecision) {
            is PrivacyDecision.Allowed -> { /* continue to blocked-package check */ }
            is PrivacyDecision.Denied -> return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_GATE_DENIED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
            is PrivacyDecision.FailClosed -> return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_GATE_FAIL_CLOSED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
            is PrivacyDecision.NotApplicable -> return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_GATE_NOT_APPLICABLE,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        }

        // 6. Blocked package check
        if (blockedPackagesLoaded && packageName in blockedPackages) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.BLOCKED_PACKAGE,
                diagnosticReasonCode = DiagnosticReasonCode.BLOCKED_PACKAGE
            )
        }

        return NotificationCaptureDecision.Allowed
    }
}
