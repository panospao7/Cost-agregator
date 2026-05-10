package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy gate that guards cloud AI capabilities.
 *
 * Checks:
 * 1. [PrivacySettings.cloudAiEnabled] — master toggle for cloud AI features
 * 2. [PrivacySettings.receiptImageCloudEnabled] — receipt image upload to cloud
 * 3. [PrivacySettings.redactBeforeCloud] — redaction required before sending data
 *
 * Returns [PrivacyDecision.Denied] with a specific reason when the
 * corresponding setting disables the capability. Capabilities not handled
 * by this gate default to [PrivacyDecision.Allowed].
 *
 * TODO: Use [EffectiveCloudAiPolicyResolver] to resolve the effective cloud AI
 * policy from both [PrivacySettingsRepository] and [AiSettingsRepository]
 * instead of reading privacy settings directly. This would unify the privacy
 * and AI settings layers into a single policy result.
 */
@Singleton
class CloudAiPrivacyGate @Inject constructor(
    private val settingsRepository: PrivacySettingsRepository,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        val settings = settingsRepository.getSettings()

        val decision = when (capability) {
            PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
            PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
            PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
            PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
            PrivacyCapability.CLOUD_AI_GENERAL -> {
                if (!settings.cloudAiEnabled) {
                    PrivacyDecision.Denied("Cloud AI is disabled by user setting")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            PrivacyCapability.AI_BANK_STATEMENT_PARSING -> {
                if (!settings.cloudAiEnabled) {
                    PrivacyDecision.Denied("Cloud AI is disabled by user setting")
                } else if (!settings.bankStatementAiEnabled) {
                    PrivacyDecision.Denied("Bank statement AI is disabled by user setting")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> {
                if (!settings.cloudAiEnabled) {
                    PrivacyDecision.Denied("Cloud AI is disabled by user setting")
                } else if (!settings.receiptImageCloudEnabled) {
                    PrivacyDecision.Denied("Receipt image cloud upload is disabled by user setting")
                } else if (settings.redactBeforeCloud) {
                    // When redaction is required, image upload is suppressed
                    // because images cannot be meaningfully redacted.
                    PrivacyDecision.Denied("Redaction is required before cloud processing — image upload suppressed")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            else -> {
                PrivacyDecision.NotApplicable
            }
        }

        if (decision is PrivacyDecision.Denied) {
            Timber.d("Cloud AI gate denied: ${decision.reason} (capability=$capability)")
        }
        return decision
    }
}
