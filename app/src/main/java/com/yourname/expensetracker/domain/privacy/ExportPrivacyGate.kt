package com.yourname.expensetracker.domain.privacy

/**
 * PR8: Separates export privacy policy from backup policy.
 *
 * [encryptedBackupEnabled] being false MUST NOT allow raw export.
 * Each export type requires explicit policy.
 */
enum class ExportPrivacyPolicy {
    /** Export is disabled — no data may be exported. */
    DISABLED,
    /** Only encrypted export is allowed. */
    ENCRYPTED_ONLY,
    /** Redacted export is allowed (sensitive fields removed). */
    REDACTED_ALLOWED,
    /** Raw debug export — only allowed in debug builds with privacy consent. */
    RAW_DEBUG_ONLY
}

/**
 * Privacy gate for export operations.
 *
 * Key rules:
 * - [encryptedBackupEnabled] = false does NOT imply raw export is allowed.
 * - [EXPENSE_EXPORT_RAW] requires explicit [debugDataPersistenceEnabled].
 * - [DEBUG_RAW_EXPORT] requires debug build.
 * - [RAW_DATABASE_EXPORT] is always denied in release builds.
 */
class ExportPrivacyGate(
    private val settingsRepository: PrivacySettingsRepository,
    private val isDebugBuild: Boolean = false
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        val settings = settingsRepository.getSettings()

        val decision = when (capability) {
            PrivacyCapability.EXPENSE_EXPORT ->
                PrivacyDecision.Allowed

            PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED ->
                if (settings.encryptedBackupEnabled) PrivacyDecision.Allowed
                else PrivacyDecision.Denied("Encrypted export disabled by user setting")

            PrivacyCapability.EXPENSE_EXPORT_REDACTED ->
                PrivacyDecision.Allowed  // Redacted export is always safe

            PrivacyCapability.EXPENSE_EXPORT_RAW ->
                if (settings.debugDataPersistenceEnabled)
                    PrivacyDecision.Allowed
                else
                    PrivacyDecision.Denied("Raw export requires explicit debug data persistence consent")

            PrivacyCapability.DEBUG_RAW_EXPORT ->
                if (isDebugBuild && settings.debugDataPersistenceEnabled)
                    PrivacyDecision.Allowed
                else if (!isDebugBuild)
                    PrivacyDecision.Denied("Debug raw export is not allowed in release builds")
                else
                    PrivacyDecision.Denied("Debug raw export requires debug data persistence consent")

            PrivacyCapability.RAW_DATABASE_EXPORT ->
                if (isDebugBuild && settings.debugDataPersistenceEnabled)
                    PrivacyDecision.Allowed
                else
                    PrivacyDecision.Denied("Raw database export is release-disabled")

            // PR8: Disabling encrypted backup NEVER implies raw export is allowed
            PrivacyCapability.RAWBACKUP_EXPORT ->
                if (settings.encryptedBackupEnabled)
                    PrivacyDecision.Denied("Raw backup export is disabled because encrypted backup is enabled")
                else
                    PrivacyDecision.Denied("Raw backup export requires explicit debug consent")

            else -> PrivacyDecision.NotApplicable
        }

        // Per PrivacyGate contract (#2): concrete gates do NOT audit. Only
        // CompositePrivacyGate writes the final audit row, so auditing here
        // would produce duplicate audit entries.
        return decision
    }
}
