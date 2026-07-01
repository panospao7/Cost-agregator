package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.util.CancellationSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * The gate is warmed up on service start. If a notification arrives before
 * warm-up completes, [decide] performs self-healing one-shot loads with a
 * short timeout so valid notifications are not falsely dropped.
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

    /** Max time to wait for a self-healing one-shot load inside [decide]. */
    private val selfHealTimeoutMs = 300L

    private val _state = MutableStateFlow<GateState>(GateState.NotReady)
    val state: StateFlow<GateState> = _state.asStateFlow()

    sealed interface GateState {
        data object NotReady : GateState
        data object Ready : GateState
        data class Error(val message: String) : GateState
    }

    /**
     * One-shot load of privacy settings and blocked packages.
     * Launched asynchronously from service, but [decide] will self-heal
     * if called before this completes.
     */
    suspend fun warmUp() {
        try {
            val settings = privacySettingsRepository.getSettings()
            notificationCaptureEnabled = settings.notificationCaptureEnabled
            settingsLoaded = true
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w(e, "CaptureGate: failed to load privacy settings during warmUp")
        }

        try {
            blockedPackages = blockedPackageDao.getAllPackageNamesOnce().toSet()
            blockedPackagesLoaded = true
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w(e, "CaptureGate: failed to load blocked packages during warmUp")
        }

        _state.value = if (settingsLoaded && blockedPackagesLoaded) {
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
            // P1-PR4 (NEW-P1-017): Retry loop so observer restarts after transient failure
            while (true) {
            try {
                privacySettingsRepository.observeSettings().collect { settings ->
                    notificationCaptureEnabled = settings.notificationCaptureEnabled
                    settingsLoaded = true
                    if (_state.value !is GateState.Ready && blockedPackagesLoaded) {
                        _state.value = GateState.Ready
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "CaptureGate: settings observer failed, retrying in 5s")
                kotlinx.coroutines.delay(5_000L)
            }
            }
        }

        scope.launch {
            // P1-PR4 (NEW-P1-017): Retry loop so observer restarts after transient failure
            while (true) {
            try {
                blockedPackageDao.getAllPackageNamesFlow().collect { packages ->
                    blockedPackages = packages.toSet()
                    blockedPackagesLoaded = true
                    if (_state.value !is GateState.Ready && settingsLoaded) {
                        _state.value = GateState.Ready
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "CaptureGate: blocked packages observer failed, retrying in 5s")
                kotlinx.coroutines.delay(5_000L)
            }
            }
        }
    }

    /**
     * Returns true when both settings and blocked-packages observers have loaded.
     * Non-blocking; does not trigger self-healing.
     */
    fun isReady(): Boolean = settingsLoaded && blockedPackagesLoaded

    /**
     * Decide whether capture is allowed for the given notification.
     *
     * This MUST be called before accessing [android.app.Notification.extras]
     * or extracting any text.
     *
     * If the gate is not fully warmed up, this method performs self-healing
     * one-shot loads with a short timeout to avoid false startup drops.
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

        // 3. Self-healing: if either settings or blocked packages not loaded,
        //    try one-shot loads with a short timeout. This prevents false
        //    startup drops while keeping gate readiness bounded.
        val needSettings = !settingsLoaded
        val needBlocked = !blockedPackagesLoaded

        if (needSettings) {
            settingsLoaded = withTimeoutOrNull(selfHealTimeoutMs) {
                try {
                    val settings = privacySettingsRepository.getSettings()
                    notificationCaptureEnabled = settings.notificationCaptureEnabled
                    true
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    Timber.w(e, "CaptureGate: self-heal settings load failed")
                    false
                }
            } ?: false
        }

        if (needBlocked) {
            blockedPackagesLoaded = withTimeoutOrNull(selfHealTimeoutMs) {
                try {
                    blockedPackages = blockedPackageDao.getAllPackageNamesOnce().toSet()
                    true
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    Timber.w(e, "CaptureGate: self-heal blocked packages load failed")
                    false
                }
            } ?: false
        }

        // 3b. Gate not ready — BOTH must be loaded to proceed safely.
        // BUG-B fix: if only blocked packages loaded but settings are not,
        // we can't verify notificationCaptureEnabled or run the full privacy gate.
        // BUG-A fix: if only settings loaded but blocked packages are not,
        // we must check this specific package one-shot.
        if (!settingsLoaded) {
            return NotificationCaptureDecision.TemporarilyUnavailable(
                reason = NotificationCaptureBlockReason.GATE_NOT_READY,
                retryable = true
            )
        }

        // 4. Privacy setting disabled
        if (!notificationCaptureEnabled) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.PRIVACY_SETTING_DISABLED,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
        }

        // 5. Full privacy gate check
        val gateDecision = try {
            privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
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
        // BUG-A fix: if blocked packages list still not loaded after self-heal,
        // fall back to a one-shot isBlocked() query for this specific package.
        val isBlocked = if (blockedPackagesLoaded) {
            packageName in blockedPackages
        } else {
            withTimeoutOrNull(selfHealTimeoutMs) {
                try {
                    blockedPackageDao.isBlocked(packageName)
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    Timber.w(e, "CaptureGate: one-shot isBlocked query failed for %s", packageName)
                    // Fail closed: treat as blocked if we can't verify
                    true
                }
            } ?: true // timeout → fail closed (treat as blocked)
        }

        if (isBlocked) {
            return NotificationCaptureDecision.Blocked(
                reason = NotificationCaptureBlockReason.BLOCKED_PACKAGE,
                diagnosticReasonCode = DiagnosticReasonCode.BLOCKED_PACKAGE
            )
        }

        return NotificationCaptureDecision.Allowed
    }
}
