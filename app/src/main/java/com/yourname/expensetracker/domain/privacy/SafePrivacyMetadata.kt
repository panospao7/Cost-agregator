package com.yourname.expensetracker.domain.privacy

import org.json.JSONObject

/**
 * Privacy-safe key/value bag for cloud payload audit and persistence metadata.
 *
 * Safety guarantees (PR1 of 4b4b5f7 follow-up):
 * - Blocked key substrings → [REDACTED]
 * - Approved hash keys + plaintext value → [REDACTED] (put() is now hash-key aware)
 * - Unknown *Hash keys → [REDACTED]
 * - Sensitive value patterns → [REDACTED]
 * - putHash() validates approved key + hex format
 * - merge() re-sanitizes via put() so hash-key safety is preserved
 * - toJson() runs a final-pass sanitization
 */
class SafePrivacyMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String {
        // Final-pass: re-sanitize all values before serialization
        val safe = values.mapValues { (k, v) -> sanitizeByKey(k, v) }
        return JSONObject(safe as Map<*, *>).toString()
    }

    fun isEmpty(): Boolean = values.isEmpty()

    fun merge(other: SafePrivacyMetadata): SafePrivacyMetadata {
        if (other.isEmpty()) return this
        val builder = builder()
        values.forEach { (k, v) -> builder.put(k, v) }
        other.values.forEach { (k, v) -> builder.put(k, v) }
        return builder.build()
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

        internal val APPROVED_HASH_KEYS = setOf(
            "sourceidhash", "notificationkeyhash", "packagehash",
            "messageidhash", "providertransactionidhash", "accountidhash",
            "counterpartyhash", "contentfingerprintidhash", "providerorderidhash",
            "payloadhash", "externalhash", "backuphash", "assetrelativepathhash"
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

        internal fun canonicalizeKey(key: String): String =
            key.lowercase().replace(Regex("[^a-z0-9]"), "")

        internal fun isBlocked(key: String): Boolean {
            val lower = key.lowercase()
            return BLOCKED_KEY_SUBSTRINGS.any { lower.contains(it) }
        }

        internal fun isApprovedHashKey(key: String): Boolean =
            canonicalizeKey(key) in APPROVED_HASH_KEYS

        internal fun isHashLikeKey(key: String): Boolean {
            val canonical = canonicalizeKey(key)
            return canonical.endsWith("hash") || canonical.endsWith("idhash")
        }

        internal fun isHashLikeValue(value: Any?): Boolean =
            value is String && value.matches(Regex("[0-9a-fA-F]{8,128}"))

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

        /**
         * Key-aware sanitization: applies hash-key rules then value-level rules.
         */
        internal fun sanitizeByKey(key: String, value: Any?): Any? = when {
            isBlocked(key) -> "[REDACTED]"
            isApprovedHashKey(key) -> if (isHashLikeValue(value)) value else "[REDACTED]"
            isHashLikeKey(key) -> "[REDACTED]"  // unknown *Hash key — reject
            else -> sanitizeValue(value)
        }
    }

    class Builder {
        private val map = mutableMapOf<String, Any?>()

        /**
         * Store a value. Applies key-aware sanitization:
         * - Blocked keys → [REDACTED]
         * - Approved hash keys + plaintext → [REDACTED]
         * - Unknown *Hash keys → [REDACTED]
         * - Benign keys → value-level sanitized
         */
        fun put(key: String, value: Any?): Builder {
            map[key] = sanitizeByKey(key, value)
            return this
        }

        /**
         * Store a hash of a sensitive identifier.
         * Key must be in the approved hash key set; value must look like a hex hash.
         */
        fun putHash(key: String, hash: String?): Builder {
            if (hash == null) return this
            if (!isApprovedHashKey(key)) {
                map[key] = "[REDACTED]"
                return this
            }
            map[key] = if (isHashLikeValue(hash)) hash else "[REDACTED]"
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
