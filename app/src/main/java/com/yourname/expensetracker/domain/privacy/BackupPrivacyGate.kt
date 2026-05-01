package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy gate that guards backup-related capabilities.
 *
 * Checks:
 * 1. [PrivacySettings.encryptedBackupEnabled] — if enabled, plaintext raw
 *    backup export is denied in favour of encrypted backup.
 * 2. [PrivacyCapability.RAWBACKUP_EXPORT] — allowed only when
 *    [encryptedBackupEnabled] is **false**.
 * 3. [PrivacyCapability.ENCRYPTED_BACKUP] — allowed only when
 *    [encryptedBackupEnabled] is **true**.
 *
 * Capabilities not handled by this gate default to [PrivacyDecision.Allowed].
 */
@Singleton
class BackupPrivacyGate @Inject constructor(
    private val settingsRepository: PrivacySettingsRepository,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        val settings = settingsRepository.getSettings()

        val decision = when (capability) {
            PrivacyCapability.RAWBACKUP_EXPORT -> {
                if (settings.encryptedBackupEnabled) {
                    PrivacyDecision.Denied(
                        "Raw (plaintext) backup export is disabled because encrypted backup is enabled in privacy settings"
                    )
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.ENCRYPTED_BACKUP -> {
                if (!settings.encryptedBackupEnabled) {
                    PrivacyDecision.Denied(
                        "Encrypted backup is disabled by user setting"
                    )
                } else {
                    PrivacyDecision.Allowed
                }
            }

            else -> {
                PrivacyDecision.Allowed
            }
        }

        auditLogger.logDecision(capability, decision, context)
        if (decision is PrivacyDecision.Denied) {
            Timber.d("Backup gate denied: ${decision.reason} (capability=$capability)")
        }
        return decision
    }
}
