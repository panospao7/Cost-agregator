package com.yourname.expensetracker.domain.privacy

/**
 * PR6: The contract that every cloud provider MUST use when sending data to
 * a cloud AI endpoint.
 *
 * Rules:
 * - [rawTextIncluded] can be true ONLY if effective policy allows no redaction.
 * - [rawImageIncluded] can be true ONLY if [receiptImageUploadAllowed] and
 *   redaction is NOT required.
 * - If redaction is required, image upload is suppressed unless a dedicated
 *   image-redaction pipeline exists.
 *
 * Cloud providers MUST NOT construct HTTP request bodies from raw strings
 * directly. They MUST receive a PreparedCloudPayload from [CloudPayloadPolicy].
 */
data class PreparedCloudPayload(
    val purpose: CloudPayloadPurpose,
    /** The actual text to send — may be redacted. */
    val text: String,
    val redactionApplied: Boolean,
    val fieldsRedacted: Set<String>,
    /** SHA-256 hash of [text] for audit provenance. */
    val payloadHash: String,
    /** True only if raw (un-redacted) text is included. */
    val rawTextIncluded: Boolean,
    /** True only if a raw image is included. */
    val rawImageIncluded: Boolean,
    val imageBytes: ByteArray? = null,
    val imageMimeType: String? = null,
    /** Audit metadata — must not contain raw sensitive values. */
    val auditMetadata: SafePrivacyMetadata = SafePrivacyMetadata.empty()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedCloudPayload) return false
        return purpose == other.purpose &&
            text == other.text &&
            redactionApplied == other.redactionApplied &&
            fieldsRedacted == other.fieldsRedacted &&
            payloadHash == other.payloadHash &&
            rawTextIncluded == other.rawTextIncluded &&
            rawImageIncluded == other.rawImageIncluded &&
            (imageBytes?.contentEquals(other.imageBytes ?: byteArrayOf()) ?: (other.imageBytes == null)) &&
            imageMimeType == other.imageMimeType
    }

    override fun hashCode(): Int {
        var result = purpose.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + payloadHash.hashCode()
        return result
    }
}
