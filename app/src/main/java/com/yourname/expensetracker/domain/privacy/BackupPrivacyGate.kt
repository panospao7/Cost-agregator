package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy gate that guards backup-related capabilities.
 *
 * Sole owner of [PrivacyCapability.ENCRYPTED_BACKUP]:
 * - allowed only when [PrivacySettings.encryptedBackupEnabled] is **true**.
 *
 * [PrivacyCapability.RAWBACKUP_EXPORT] is **not** handled here — it is owned
 * exclusively by [ExportPrivacyGate] (which denies it unless explicit debug
 * consent in debug builds). Returning [PrivacyDecision.NotApplicable] for it
 * avoids two gates issuing conflicting decisions for the same capability.
 *
 * Capabilities not handled by this gate return [PrivacyDecision.NotApplicable].
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
                // RAWBACKUP_EXPORT is owned solely by ExportPrivacyGate.
                PrivacyDecision.NotApplicable
            }
        }

        if (decision is PrivacyDecision.Denied) {
            Timber.d("Backup gate denied: ${decision.reason} (capability=$capability)")
        }
        return decision
    }
}
