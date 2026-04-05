package com.yourname.expensetracker.data.ai.provider.internal

import java.security.MessageDigest

object CloudPiiSanitizer {
    private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
    private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
    private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
    private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")

    fun sanitizeText(raw: String, maxChars: Int, fallbackPrefix: String): String {
        val trimmed = raw.trim().take(maxChars)
        val redacted = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        return if (redacted.isBlank()) {
            "${fallbackPrefix}_${trimmed.sha256Prefix()}"
        } else {
            redacted
        }
    }

    fun sanitizeMerchant(raw: String?, shouldRedact: Boolean): String {
        val trimmed = raw?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown"
        if (!shouldRedact) return trimmed.take(80)
        return "merchant_${trimmed.sha256Prefix()}"
    }
}

fun String.sha256Prefix(length: Int = 12): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
}
