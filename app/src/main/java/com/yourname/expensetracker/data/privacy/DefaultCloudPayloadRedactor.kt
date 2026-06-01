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

    /**
     * P8-PR3 (NEW-P8-008): Also detects *truncated* redaction markers.
     * If the redacted text is truncated (e.g. "[REDACTED_EMAI" instead of
     * "[REDACTED_EMAIL]"), the field is still reported as redacted so downstream
     * consumers know that redaction was at least partially applied.
     *
     * P8-PR3 (NEW-P8-004): Also detects the new PII markers (SSN, NI_NUMBER,
     * SIN, TFN, PASSPORT) added to [CloudPiiSanitizer].
     */
    private fun detectRedactedFields(original: String, redacted: String): Set<String> {
        val fields = mutableSetOf<String>()
        // Full markers (complete, non-truncated)
        if (redacted.contains("[REDACTED_EMAIL]")) fields.add("email")
        if (redacted.contains("[REDACTED_PHONE]")) fields.add("phone")
        if (redacted.contains("[REDACTED_IBAN]")) fields.add("iban")
        if (redacted.contains("[REDACTED_CARD]")) fields.add("card")
        if (redacted.contains("[REDACTED_NUMBER]")) fields.add("number")
        // P8-PR3 (NEW-P8-004): New PII markers from CloudPiiSanitizer
        if (redacted.contains("[REDACTED_SSN]")) fields.add("ssn")
        if (redacted.contains("[REDACTED_NI_NUMBER]")) fields.add("ni_number")
        if (redacted.contains("[REDACTED_SIN]")) fields.add("sin")
        if (redacted.contains("[REDACTED_TFN]")) fields.add("tfn")
        if (redacted.contains("[REDACTED_PASSPORT]")) fields.add("passport")
        // P8-PR3 (NEW-P8-008): Truncated markers — the text may have been cut
        // at the max-chars boundary, leaving an incomplete "[REDACTED_..." token.
        if (!fields.contains("email") && truncationMatches(redacted, "[REDACTED_EMAIL", "[REDACTED_EMAI", "[REDACTED_EM", "[REDACTED_E")) fields.add("email_truncated")
        if (!fields.contains("phone") && truncationMatches(redacted, "[REDACTED_PHONE", "[REDACTED_PHON", "[REDACTED_PHO")) fields.add("phone_truncated")
        if (!fields.contains("iban") && truncationMatches(redacted, "[REDACTED_IBAN", "[REDACTED_IBA", "[REDACTED_IB")) fields.add("iban_truncated")
        if (!fields.contains("card") && truncationMatches(redacted, "[REDACTED_CARD", "[REDACTED_CAR", "[REDACTED_CA")) fields.add("card_truncated")
        if (!fields.contains("number") && truncationMatches(redacted, "[REDACTED_NUMBER", "[REDACTED_NUMB", "[REDACTED_NUM")) fields.add("number_truncated")
        if (!fields.contains("ssn") && truncationMatches(redacted, "[REDACTED_SSN", "[REDACTED_SS")) fields.add("ssn_truncated")
        if (!fields.contains("ni_number") && truncationMatches(redacted, "[REDACTED_NI_NUMBER", "[REDACTED_NI_NUM")) fields.add("ni_number_truncated")
        if (!fields.contains("sin") && truncationMatches(redacted, "[REDACTED_SIN", "[REDACTED_SI")) fields.add("sin_truncated")
        if (!fields.contains("tfn") && truncationMatches(redacted, "[REDACTED_TFN", "[REDACTED_TF")) fields.add("tfn_truncated")
        if (!fields.contains("passport") && truncationMatches(redacted, "[REDACTED_PASSPORT", "[REDACTED_PASSPO")) fields.add("passport_truncated")
        return fields
    }

    /**
     * P8-PR3 (NEW-P8-008): Returns true if [redacted] contains any of the
     * given [prefixes], indicating a truncated redaction marker.
     */
    private fun truncationMatches(redacted: String, vararg prefixes: String): Boolean {
        return prefixes.any { prefix -> redacted.contains(prefix) }
    }

    private companion object {
        /** Max characters for general text redaction. */
        private const val MAX_TEXT_CHARS = 2000
        /** Higher limit for receipt/warranty text where amounts matter. */
        private const val MAX_TEXT_CHARS_RECEIPT = 3000
        /** Lower limit for dashboard briefings (summary only). */
        private const val MAX_TEXT_CHARS_BRIEFING = 1500
        /**
         * P8-PR3 (NEW-P8-003): Tightened merchant-line regex.
         *
         * Matches sequences that look like merchant/brand names while avoiding false
         * matches on generic capitalized phrases (e.g. "The Quick Brown Fox").
         *
         * Rules:
         * - Single-word all-caps entries of 4+ chars (e.g. "AMAZON", "TESCO", "IKEA").
         * - Or at least 2 consecutive capitalized words (e.g. "STARBUCKS COFFEE").
         * - Each word is 3+ characters (to skip articles/prepositions like "The", "My").
         * - Words may contain apostrophes, hyphens, or ampersands (e.g. "MCDONALD'S",
         *   "WELLS-FARGO", "AT&T").
         * - The sequence must NOT start with common non-merchant words such as
         *   "The", "This", "That", "My", "Our", "Your", "His", "Her", "Its",
         *   "A", "An", "One", "Two", "Some", "Any", "Each", "Every", "All".
         */
        private val MERCHANT_LINE_REGEX = Regex(
            """\b(?!(?:The|This|That|My|Our|Your|His|Her|Its|A|An|One|Two|Some|Any|Each|Every|All)\b)(?:[A-Z][A-Za-z'&-]{3,}(?:\s[A-Z][A-Za-z'&-]+){1,}|[A-Z][A-Z]{1,}[A-Za-z'&-]*)\b"""
        )
    }
}
