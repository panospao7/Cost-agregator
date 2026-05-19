package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import com.yourname.expensetracker.domain.privacy.SafePrivacyMetadata
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [CloudPayloadPolicy] implementation.
 *
 * Redaction authority comes exclusively from [EffectiveCloudAiPolicyResolver].
 * No raw string is sent unless effective policy allows it.
 * Image upload is suppressed when redaction is required.
 */
@Singleton
class DefaultCloudPayloadPolicy @Inject constructor(
    private val policyResolver: EffectiveCloudAiPolicyResolver,
    private val redactor: CloudPayloadRedactor
) : CloudPayloadPolicy {

    override suspend fun prepareText(
        purpose: CloudPayloadPurpose,
        rawText: String,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        val policy = policyResolver.resolve()
        val redactRequired = policy.redactBeforeCloud

        return if (redactRequired) {
            val redacted = redactor.redactText(rawText, purpose)
            PreparedCloudPayload(
                purpose = purpose,
                text = redacted.text,
                redactionApplied = true,
                fieldsRedacted = redacted.fieldsRedacted,
                payloadHash = redacted.payloadHash,
                rawTextIncluded = false,
                rawImageIncluded = false,
                auditMetadata = SafePrivacyMetadata.builder()
                    .put("purpose", purpose.name)
                    .put("redacted", true)
                    .build()
            )
        } else {
            val hash = rawText.sha256Prefix(32)
            PreparedCloudPayload(
                purpose = purpose,
                text = rawText,
                redactionApplied = false,
                fieldsRedacted = emptySet(),
                payloadHash = hash,
                rawTextIncluded = true,
                rawImageIncluded = false,
                auditMetadata = SafePrivacyMetadata.builder()
                    .put("purpose", purpose.name)
                    .put("redacted", false)
                    .build()
            )
        }
    }

    override suspend fun prepareBankStatementValidation(
        rawText: String,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        // Bank statement always uses strict redaction regardless of general policy
        val redacted = redactor.redactText(rawText, CloudPayloadPurpose.BANK_STATEMENT_VALIDATION)
        return PreparedCloudPayload(
            purpose = CloudPayloadPurpose.BANK_STATEMENT_VALIDATION,
            text = redacted.text,
            redactionApplied = true,
            fieldsRedacted = redacted.fieldsRedacted,
            payloadHash = redacted.payloadHash,
            rawTextIncluded = false,
            rawImageIncluded = false,
            auditMetadata = SafePrivacyMetadata.builder()
                .put("purpose", CloudPayloadPurpose.BANK_STATEMENT_VALIDATION.name)
                .put("redacted", true)
                .build()
        )
    }
}
