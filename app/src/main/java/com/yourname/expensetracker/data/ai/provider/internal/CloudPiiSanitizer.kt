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
    private val PHONE_CANDIDATE_REGEX = Regex("""(?<![\p{L}\p{N}_])\+?\d[\d \t().,-]{6,}\d(?![\p{L}\p{N}_%])""")
    private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    private val PHONE_LABEL_REGEX = Regex(
        """(?:^|[^\p{L}\p{N}_])(?:phone|telephone|tel|mobile|cell|fax|call|support)[ \t:#=(]*$""",
        RegexOption.IGNORE_CASE
    )
    private val IDENTIFIER_LABEL_REGEX = Regex(
        """(?:^|[^\p{L}\p{N}_])(?:invoice|order|receipt|reference|ref|id)[ \t]*#[ \t]*$""",
        RegexOption.IGNORE_CASE
    )
    // Exempt recognizable decimal amounts, not every short numeric candidate.
    // Explicit phone labels and international '+' prefixes take precedence.
    private val DECIMAL_AMOUNT_REGEX = Regex(
        """(?:(?:\d+|\d{1,3}(?:[ \t,]\d{3})+)\.\d{2}|(?:\d+|\d{1,3}(?:[ \t.]\d{3})+),\d{2})"""
    )
    private val DATE_SHAPE_REGEX = Regex(
        """(?:\d{4}-\d{2}-\d{2}|\d{1,2}([.-])\d{1,2}\1\d{4})"""
    )

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
        val contactText = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
        val redacted = contactText
            .replace(PHONE_CANDIDATE_REGEX) { match ->
                val prefix = contactText.substring(
                    contactText.lastIndexOf('\n', match.range.first - 1) + 1,
                    match.range.first
                )
                if (isLikelyPhoneNumber(match.value, prefix)) "[REDACTED_PHONE]" else match.value
            }
            .replace(CARD_REGEX, "[REDACTED_CARD]")
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

    private fun isLikelyPhoneNumber(candidate: String, prefix: String): Boolean {
        val digits = candidate.filter(Char::isDigit)
        if (candidate.trimStart().startsWith("+")) return true
        if (PHONE_LABEL_REGEX.containsMatchIn(prefix)) return true
        if (IDENTIFIER_LABEL_REGEX.containsMatchIn(prefix)) return false
        if (DECIMAL_AMOUNT_REGEX.matches(candidate) || DATE_SHAPE_REGEX.matches(candidate)) return false
        // Leave structured identifiers to their dedicated redactors below.
        if (SSN_REGEX.matches(candidate) || SIN_REGEX.matches(candidate) || TFN_REGEX.matches(candidate)) {
            return false
        }

        val groups = candidate.split(Regex("""[\s().,-]+""")).filter { it.isNotEmpty() }
        if (groups.size >= 2 && groups.any { it.length != 4 }) return true

        // Do not impose a digit-count floor or ceiling: local numbers and adjacent
        // phone-like fields must not escape redaction. Valid payment-card shapes
        // retain their dedicated marker rather than being relabelled as phones.
        return digits.length !in 13..19 || !CARD_REGEX.matches(candidate) || !passesLuhn(digits)
    }

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        val parity = digits.length % 2
        for (index in digits.indices) {
            var value = digits[index].digitToInt()
            if (index % 2 == parity) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return sum % 10 == 0
    }
}
