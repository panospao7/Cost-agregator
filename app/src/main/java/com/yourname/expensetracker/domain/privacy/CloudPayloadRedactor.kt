package com.yourname.expensetracker.domain.privacy

/**
 * Unified redaction contract for cloud AI payloads. (ARCH-04/P8-P1-1)
 *
 * Stage 1: Interface definition — additive, no consumers broken.
 * Stage 2: Wrap existing sanitizer implementations.
 * Stage 3: Migrate cloud providers one by one.
 */
interface CloudPayloadRedactor {
    /**
     * Redacts sensitive data from text before sending to cloud AI.
     * @param text The raw text to redact
     * @param purpose The cloud AI purpose (determines redaction rules)
     * @return Redacted payload with metadata
     */
    fun redactText(text: String, purpose: CloudPayloadPurpose): RedactedPayload

    /**
     * Redacts a merchant name for cloud AI use.
     */
    fun redactMerchant(merchant: String?): RedactedField
}

enum class CloudPayloadPurpose {
    RECEIPT_ASSIST,
    ITEM_CATEGORIZATION,
    QUERY_INTERPRETATION,
    REVIEW_EXPLANATION,
    DASHBOARD_BRIEFING,
    DEDUPE_JUDGE,
    WARRANTY_EXTRACTION
}

data class RedactedPayload(
    val text: String,
    val redactionApplied: Boolean,
    val fieldsRedacted: Set<String>,
    val payloadHash: String
)

data class RedactedField(
    val value: String?,
    val wasRedacted: Boolean
)
