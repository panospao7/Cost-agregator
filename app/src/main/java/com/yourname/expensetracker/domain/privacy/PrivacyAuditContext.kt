package com.yourname.expensetracker.domain.privacy

/**
 * PR7: Typed audit context for privacy gate decisions and cloud AI calls.
 *
 * Replaces the raw [Map<String, String>] used in PrivacyGate.check() context.
 * All fields that could carry sensitive values are explicitly typed so the
 * sanitizer can validate them at construction time.
 *
 * Fields that may be raw-sensitive (prompt, rawText, etc.) are intentionally
 * absent — they must never appear in audit records.
 */
data class PrivacyAuditContext(
    val operation: String,
    val caller: String? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
    val provider: String? = null,
    val modelId: String? = null,
    val purpose: CloudPayloadPurpose? = null,
    /** SHA-256 hash of the payload — NEVER the raw payload. */
    val payloadHash: String? = null,
    val redactionApplied: Boolean? = null,
    val rawTextIncluded: Boolean? = null,
    val rawImageIncluded: Boolean? = null,
    val correlationId: String? = null,
    val metadata: SafePrivacyMetadata = SafePrivacyMetadata.empty()
) {
    /**
     * Convert to the legacy Map<String, String> for backward compatibility with
     * [PrivacyGate.check] and [PrivacyAuditLogger.logDecision].
     */
    fun toMap(): Map<String, String> = buildMap {
        put("operation", operation)
        caller?.let { put("caller", it) }
        entityType?.let { put("entityType", it) }
        entityId?.let { put("entityId", it.toString()) }
        provider?.let { put("provider", it) }
        modelId?.let { put("modelId", it) }
        purpose?.let { put("purpose", it.name) }
        payloadHash?.let { put("payloadHash", it) }
        redactionApplied?.let { put("redactionApplied", it.toString()) }
        rawTextIncluded?.let { put("rawTextIncluded", it.toString()) }
        rawImageIncluded?.let { put("rawImageIncluded", it.toString()) }
        correlationId?.let { put("correlationId", it) }
    }

    companion object {
        fun forCloudCall(
            provider: String,
            modelId: String,
            purpose: CloudPayloadPurpose,
            payload: PreparedCloudPayload,
            correlationId: String? = null
        ): PrivacyAuditContext = PrivacyAuditContext(
            operation = "cloud_ai_call",
            caller = provider,
            provider = provider,
            modelId = modelId,
            purpose = purpose,
            payloadHash = payload.payloadHash,
            redactionApplied = payload.redactionApplied,
            rawTextIncluded = payload.rawTextIncluded,
            rawImageIncluded = payload.rawImageIncluded,
            correlationId = correlationId
        )
    }
}
