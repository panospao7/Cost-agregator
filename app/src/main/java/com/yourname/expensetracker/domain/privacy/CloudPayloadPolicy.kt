package com.yourname.expensetracker.domain.privacy

/**
 * PR6: The policy layer every cloud provider MUST use to obtain a
 * [PreparedCloudPayload] before sending data to a cloud AI endpoint.
 *
 * No cloud provider may call AiSettings.redactBeforeCloud or
 * caller input.redactBeforeCloud for privacy decisions.
 * Redaction authority comes exclusively from this policy.
 *
 * All implementations must:
 * 1. Consult [EffectiveCloudAiPolicyResolver] for the effective redaction policy.
 * 2. Apply redaction via [CloudPayloadRedactor] when required.
 * 3. Suppress image upload when redaction is required.
 * 4. Log audit metadata via [PrivacyAuditLogger].
 */
interface CloudPayloadPolicy {
    /**
     * Prepare a generic text payload for a given [purpose].
     * Redaction is applied automatically based on the effective policy.
     */
    suspend fun prepareText(
        purpose: CloudPayloadPurpose,
        rawText: String,
        context: SafePrivacyMetadata = SafePrivacyMetadata.empty()
    ): PreparedCloudPayload

    /**
     * Prepare a bank statement validation payload.
     * Uses [CloudPayloadPurpose.BANK_STATEMENT_VALIDATION] with strict redaction.
     */
    suspend fun prepareBankStatementValidation(
        rawText: String,
        context: SafePrivacyMetadata = SafePrivacyMetadata.empty()
    ): PreparedCloudPayload
}
