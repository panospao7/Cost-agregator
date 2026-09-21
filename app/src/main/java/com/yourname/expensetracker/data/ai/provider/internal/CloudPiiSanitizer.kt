package com.yourname.expensetracker.data.ai.provider.internal

import com.yourname.expensetracker.domain.privacy.InstallationSecretHasher
import com.yourname.expensetracker.domain.privacy.versionedPseudonym
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RP-15 (15-C, D13 remediation): PII sanitizer for cloud payloads.
 *
 * Converted from a static object to an injected class so that merchant
 * identity pseudonyms and blank-text fallbacks are keyed by the SAME
 * Keystore-backed installation secret that
 * [com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor] uses.
 * The legacy implementation hashed these values with an unsalted public
 * SHA-256 (trivially reversible via dictionary attack); the replacement is a
 * versioned installation-secret HMAC with NO public-hash fallback — when the
 * installation secret is unavailable, an identity-free marker is emitted.
 *
 * Output formats:
 * - merchant pseudonym: `merchant_v<version>_<hmac>` (or `merchant_redacted`)
 * - blank-text fallback: `<prefix>_v<version>_<hmac>` (or `<prefix>_redacted`)
 * Legacy `merchant_<unsalted-sha>` artifacts are never regenerated or matched.
 */
@Singleton
class CloudPiiSanitizer @Inject constructor(
    private val installationSecretHasher: InstallationSecretHasher
) {
    private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
    private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
    private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
    private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")

    // P8-PR3 (NEW-P8-004): Additional PII patterns

    /** US Social Security Number (XXX-XX-XXXX) */
    private val SSN_REGEX = Regex("""\b(?!000|666|9\d{2})\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b""")

    /** UK National Insurance Number (AB 12 34 56 C) */
    private val NI_NUMBER_REGEX = Regex("""\b[A-Z]{2}\s?\d{2}\s?\d{2}\s?\d{2}\s?[A-Z]\b""")

    /** Canadian Social Insurance Number (XXX-XXX-XXX) */
    private val SIN_REGEX = Regex("""\b\d{3}-\d{3}-\d{3}\b""")

    /** Australian Tax File Number (XXX XXX XXX) */
    private val TFN_REGEX = Regex("""\b\d{3}\s?\d{3}\s?\d{3}\b""")

    /** Generic passport-like patterns (1-2 uppercase letters followed by 5-9 digits) */
    private val PASSPORT_REGEX = Regex("""\b[A-Z]{1,2}\d{5,9}\b""")

    fun sanitizeText(raw: String, maxChars: Int, fallbackPrefix: String): String {
        val trimmed = raw.trim().take(maxChars)
        val redacted = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(SSN_REGEX, "[REDACTED_SSN]")
            .replace(NI_NUMBER_REGEX, "[REDACTED_NI_NUMBER]")
            .replace(SIN_REGEX, "[REDACTED_SIN]")
            .replace(TFN_REGEX, "[REDACTED_TFN]")
            .replace(PASSPORT_REGEX, "[REDACTED_PASSPORT]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        return if (redacted.isBlank()) {
            "${fallbackPrefix}_${installationSecretHasher.versionedPseudonym(trimmed)}"
        } else {
            redacted
        }
    }

    fun sanitizeMerchant(raw: String?, shouldRedact: Boolean): String {
        val trimmed = raw?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown"
        if (!shouldRedact) return trimmed.take(80)
        return "merchant_${installationSecretHasher.versionedPseudonym(trimmed)}"
    }
}
