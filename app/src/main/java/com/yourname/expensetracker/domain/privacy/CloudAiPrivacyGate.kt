package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy gate that guards cloud AI capabilities.
 *
 * Delegates to [EffectiveCloudAiPolicyResolver] which reconciles both
 * [PrivacySettings] and [AiSettings] into a single authoritative policy.
 * This ensures cloud AI is blocked when EITHER settings system disables it.
 */
@Singleton
class CloudAiPrivacyGate @Inject constructor(
    private val policyResolver: EffectiveCloudAiPolicyResolver,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        val policy = policyResolver.resolve()

        val decision = when (capability) {
            PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
            PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION,
            PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION,
            PrivacyCapability.CLOUD_AI_DAILY_BRIEFING,
            PrivacyCapability.CLOUD_AI_GENERAL -> {
                if (!policy.cloudAllowed) {
                    PrivacyDecision.Denied(policy.reason ?: "Cloud AI disabled")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            PrivacyCapability.AI_BANK_STATEMENT_PARSING -> {
                if (!policy.cloudAllowed) {
                    PrivacyDecision.Denied(policy.reason ?: "Cloud AI disabled")
                } else if (!policy.bankStatementCloudAllowed) {
                    PrivacyDecision.Denied("Bank statement AI is disabled")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> {
                if (!policy.cloudAllowed) {
                    PrivacyDecision.Denied(policy.reason ?: "Cloud AI disabled")
                } else if (!policy.receiptImageUploadAllowed) {
                    PrivacyDecision.Denied("Receipt image cloud upload is disabled")
                } else if (policy.redactBeforeCloud) {
                    PrivacyDecision.Denied("Redaction required — image upload suppressed")
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
