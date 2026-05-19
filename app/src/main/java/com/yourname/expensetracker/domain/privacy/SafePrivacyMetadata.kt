package com.yourname.expensetracker.domain.privacy

import org.json.JSONObject

/**
 * Privacy-safe key/value bag for cloud payload audit and persistence metadata.
 *
 * This is the privacy-domain counterpart to [SafeEventMetadata] (diagnostics).
 * Keys that carry raw sensitive content are blocked at construction time.
 *
 * Blocked key substrings (case-insensitive):
 *   rawText, rawBody, body, subjectRaw, senderRaw, prompt, token, accessToken,
 *   refreshToken, authorization, password, iban, accountNumber, cardNumber,
 *   fullPath, ocrText, emailBody, bankDescription
 */
class SafePrivacyMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String = JSONObject(values as Map<*, *>).toString()

    fun isEmpty(): Boolean = values.isEmpty()

    fun merge(other: SafePrivacyMetadata): SafePrivacyMetadata {
        if (other.isEmpty()) return this
        return SafePrivacyMetadata((values + other.values))
    }

    companion object {
        fun empty(): SafePrivacyMetadata = SafePrivacyMetadata(emptyMap())

        fun builder(): Builder = Builder()

        private val BLOCKED_KEY_SUBSTRINGS = setOf(
            "rawtext", "rawbody", "body", "subjectraw", "senderraw",
            "prompt", "token", "accesstoken", "refreshtoken",
            "authorization", "password", "iban", "accountnumber",
            "cardnumber", "fullpath", "ocrtext", "emailbody", "bankdescription"
        )

        internal fun isBlocked(key: String): Boolean {
            val lower = key.lowercase()
            return BLOCKED_KEY_SUBSTRINGS.any { lower.contains(it) }
        }
    }

    class Builder {
        private val map = mutableMapOf<String, Any?>()

        /** Store a safe value. Blocked keys are silently redacted. */
        fun put(key: String, value: Any?): Builder {
            map[key] = if (isBlocked(key)) "[REDACTED]" else value
            return this
        }

        /** Store a hash of a sensitive value (key must end with "Hash"). */
        fun putHash(key: String, hash: String?): Builder {
            if (hash != null) map[key] = hash
            return this
        }

        /** Explicitly mark a field as redacted. */
        fun putRedacted(key: String): Builder {
            map[key] = "[REDACTED]"
            return this
        }

        fun build(): SafePrivacyMetadata = SafePrivacyMetadata(map.toMap())
    }
}
