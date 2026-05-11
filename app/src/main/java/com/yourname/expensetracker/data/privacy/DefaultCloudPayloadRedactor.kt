package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.data.ai.provider.internal.sha256Prefix
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

    // P8-P1-08: Purpose-aware redaction — adjusts rules based on cloud AI use case.
    override fun redactText(text: String, purpose: CloudPayloadPurpose): RedactedPayload {
        val maxChars = when (purpose) {
            CloudPayloadPurpose.RECEIPT_ASSIST,
            CloudPayloadPurpose.WARRANTY_EXTRACTION -> MAX_TEXT_CHARS_RECEIPT
            CloudPayloadPurpose.DASHBOARD_BRIEFING -> MAX_TEXT_CHARS_BRIEFING
            else -> MAX_TEXT_CHARS
        }

        val sanitized = when (purpose) {
            // Preserve amounts/dates for receipt and warranty extraction
            CloudPayloadPurpose.RECEIPT_ASSIST,
            CloudPayloadPurpose.WARRANTY_EXTRACTION -> CloudPiiSanitizer.sanitizeText(
                raw = text,
                maxChars = maxChars,
                fallbackPrefix = "text"
            )
            // Hash merchant names for dashboard briefings
            CloudPayloadPurpose.DASHBOARD_BRIEFING -> {
                val base = CloudPiiSanitizer.sanitizeText(
                    raw = text,
                    maxChars = maxChars,
                    fallbackPrefix = "text"
                )
                MERCHANT_LINE_REGEX.replace(base) { match ->
                    "merchant_${match.value.sha256Prefix()}"
                }
            }
            else -> CloudPiiSanitizer.sanitizeText(
                raw = text,
                maxChars = maxChars,
                fallbackPrefix = "text"
            )
        }

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
        /** Max characters for general text redaction. */
        private const val MAX_TEXT_CHARS = 2000
        /** Higher limit for receipt/warranty text where amounts matter. */
        private const val MAX_TEXT_CHARS_RECEIPT = 3000
        /** Lower limit for dashboard briefings (summary only). */
        private const val MAX_TEXT_CHARS_BRIEFING = 1500
        /** Matches capitalized words that look like merchant/brand names. */
        private val MERCHANT_LINE_REGEX = Regex("""\b[A-Z][A-Za-z]{2,}(?:\s[A-Z][A-Za-z]+)*\b""")
    }
}
