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

    override suspend fun prepareReceiptAssist(
        rawPrompt: String,
        imagePath: String?,
        imageMimeType: String?,
        allowImage: Boolean,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        val policy = policyResolver.resolve()
        val redactRequired = policy.redactBeforeCloud

        val textPayload = if (redactRequired) {
            redactor.redactText(rawPrompt, CloudPayloadPurpose.RECEIPT_ASSIST)
        } else null

        val preparedText = textPayload?.text ?: rawPrompt
        val hash = preparedText.sha256Prefix(32)

        // Image is only included when: allowImage=true AND redaction is NOT required AND file exists AND MIME allowed
        val (imageBytes, resolvedMimeType) = if (allowImage && !redactRequired && imagePath != null && imageMimeType != null) {
            if (imageMimeType !in ALLOWED_RECEIPT_IMAGE_MIME_TYPES) {
                null to null  // unsupported MIME — suppress image
            } else {
                val file = java.io.File(imagePath)
                if (file.exists() && file.length() <= MAX_INLINE_IMAGE_BYTES) {
                    runCatching { file.readBytes() }.getOrNull() to imageMimeType
                } else null to null
            }
        } else null to null

        return PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = preparedText,
            redactionApplied = redactRequired,
            fieldsRedacted = textPayload?.fieldsRedacted ?: emptySet(),
            payloadHash = hash,
            rawTextIncluded = !redactRequired,
            rawImageIncluded = imageBytes != null,
            imageBytes = imageBytes,
            imageMimeType = resolvedMimeType,
            auditMetadata = SafePrivacyMetadata.builder()
                .put("purpose", CloudPayloadPurpose.RECEIPT_ASSIST.name)
                .put("redacted", redactRequired)
                .put("imageIncluded", imageBytes != null)
                .build()
        )
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

    private companion object {
        private const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
        private val ALLOWED_RECEIPT_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
