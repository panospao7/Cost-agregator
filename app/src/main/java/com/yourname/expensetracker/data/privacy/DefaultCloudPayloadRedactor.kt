package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.RedactedField
import com.yourname.expensetracker.domain.privacy.RedactedPayload
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [CloudPiiSanitizer] behind the [CloudPayloadRedactor] interface.
 * (ARCH-04/P8-P1-1 Stage 1 implementation.)
 */
@Singleton
class DefaultCloudPayloadRedactor @Inject constructor() : CloudPayloadRedactor {

    // TODO (P8-P1-08): Vary redaction rules by purpose. Currently all purposes
    // use the same CloudPiiSanitizer.sanitizeText(). For RECEIPT_ASSIST, amounts
    // should be preserved; for QUERY_INTERPRETATION, merchant names may be kept.
    override fun redactText(text: String, purpose: CloudPayloadPurpose): RedactedPayload {
        val sanitized = CloudPiiSanitizer.sanitizeText(
            raw = text,
            maxChars = MAX_TEXT_CHARS,
            fallbackPrefix = "text"
        )
        return RedactedPayload(
            text = sanitized,
            redactionApplied = sanitized != text,
            fieldsRedacted = detectRedactedFields(text, sanitized),
            payloadHash = hashPayload(sanitized)
        )
    }

    override fun redactMerchant(merchant: String?): RedactedField {
        val sanitized = CloudPiiSanitizer.sanitizeMerchant(merchant, shouldRedact = true)
        val nonRedacted = merchant?.take(80) ?: "Unknown"
        return RedactedField(
            value = sanitized,
            wasRedacted = sanitized != nonRedacted
        )
    }

    private fun hashPayload(raw: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun detectRedactedFields(original: String, redacted: String): Set<String> {
        val fields = mutableSetOf<String>()
        if (redacted.contains("[REDACTED_EMAIL]")) fields.add("email")
        if (redacted.contains("[REDACTED_PHONE]")) fields.add("phone")
        if (redacted.contains("[REDACTED_IBAN]")) fields.add("iban")
        if (redacted.contains("[REDACTED_CARD]")) fields.add("card")
        if (redacted.contains("[REDACTED_NUMBER]")) fields.add("number")
        return fields
    }

    private companion object {
        /** Max characters for text redaction. */
        private const val MAX_TEXT_CHARS = 2000
    }
}
