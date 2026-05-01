package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject

/**
 * Privacy gate that guards notification capture capabilities.
 *
 * Checks:
 * 1. Master toggle [PrivacySettings.notificationCaptureEnabled]
 * 2. Per-package allowlist (simplified: if master toggle is off, deny all)
 */
class NotificationPrivacyGate @Inject constructor(
    private val settingsRepository: PrivacySettingsRepository,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        return when (capability) {
            PrivacyCapability.NOTIFICATION_CAPTURE,
            PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> {
                val settings = settingsRepository.getSettings()
                if (!settings.notificationCaptureEnabled) {
                    val reason = "Notification capture is disabled by master toggle"
                    val decision = PrivacyDecision.Denied(reason)
                    auditLogger.logDecision(capability, decision, context)
                    Timber.d("$reason (capability=$capability)")
                    decision
                } else {
                    val decision = PrivacyDecision.Allowed
                    auditLogger.logDecision(capability, decision, context)
                    Timber.d("Notification capture allowed (capability=$capability)")
                    decision
                }
            }

            else -> {
                // Delegate to a default "allow" for capabilities this gate doesn't handle
                val decision = PrivacyDecision.Allowed
                auditLogger.logDecision(capability, decision, context)
                decision
            }
        }
    }
}
