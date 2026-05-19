package com.yourname.expensetracker.domain.privacy

import org.json.JSONObject

/**
 * Privacy-safe key/value bag for cloud payload audit and persistence metadata.
 *
 * Safety guarantees (PR2):
 * - Blocked key substrings → value replaced with "[REDACTED]"
 * - Sensitive value patterns (tokens, IBANs, card numbers, JWTs, long blobs) → "[REDACTED]"
 * - putHash only accepts approved hash keys and hex-like values
 * - merge() re-sanitizes so unsafe values cannot sneak in through composition
 */
class SafePrivacyMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String = JSONObject(values as Map<*, *>).toString()

    fun isEmpty(): Boolean = values.isEmpty()

    fun merge(other: SafePrivacyMetadata): SafePrivacyMetadata {
        if (other.isEmpty()) return this
        val merged = mutableMapOf<String, Any?>()
        (values + other.values).forEach { (k, v) ->
            merged[k] = if (isBlocked(k)) "[REDACTED]" else sanitizeValue(v)
        }
        return SafePrivacyMetadata(merged)
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

        private val APPROVED_HASH_KEYS = setOf(
            "sourceidhash", "notificationkeyhash", "messageidhash",
            "providertransactionidhash", "accountidhash", "counterpartyhash",
            "contentfingerprintidhash", "providerorderidhash", "payloadhash"
        )

        // Value patterns that indicate sensitive content regardless of key name
        private val SENSITIVE_VALUE_PATTERNS = listOf(
            Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),           // base64-like (tokens, keys)
            Regex("""[Bb]earer\s+\S+"""),                      // Bearer token
            Regex("""[A-Z]{2}\d{2}[A-Z0-9]{4,30}"""),         // IBAN-like
            Regex("""(?:\d[ -]?){13,19}"""),                   // card number
            Regex("""(?:[a-zA-Z]:\\|/home/|/Users/)\S+"""),    // file path
            Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")    // JWT header.payload
        )

        internal fun isBlocked(key: String): Boolean {
            val lower = key.lowercase()
            return BLOCKED_KEY_SUBSTRINGS.any { lower.contains(it) }
        }

        internal fun sanitizeValue(value: Any?): Any? = when (value) {
            null -> null
            is String -> sanitizeString(value)
            is Number, is Boolean -> value
            is Map<*, *> -> "[REDACTED_MAP]"
            is Collection<*> -> "[REDACTED_LIST]"
            else -> sanitizeString(value.toString())
        }

        private fun sanitizeString(s: String): Any? {
            if (s.length > 512) return "[REDACTED_BLOB]"
            if (SENSITIVE_VALUE_PATTERNS.any { it.containsMatchIn(s) }) return "[REDACTED]"
            return s
        }

        private fun isHashLike(value: String): Boolean =
            value.matches(Regex("[0-9a-fA-F]{8,64}"))
    }

    class Builder {
        private val map = mutableMapOf<String, Any?>()

        /** Store a value. Blocked keys and sensitive values are redacted. */
        fun put(key: String, value: Any?): Builder {
            map[key] = if (isBlocked(key)) "[REDACTED]" else sanitizeValue(value)
            return this
        }

        /**
         * Store a hash of a sensitive identifier.
         * Key must be in the approved hash key set; value must look like a hex hash.
         */
        fun putHash(key: String, hash: String?): Builder {
            if (hash == null) return this
            val canonicalKey = key.lowercase().replace("[^a-z0-9]".toRegex(), "")
            if (canonicalKey !in APPROVED_HASH_KEYS) {
                map[key] = "[REDACTED]"
                return this
            }
            map[key] = if (isHashLike(hash)) hash else "[REDACTED]"
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
