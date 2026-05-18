package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.domain.common.sha256Prefix
import org.json.JSONObject

/**
 * Privacy-safe key/value bag for event metadata.
 *
 * Construction is only possible via [builder] or [empty] to enforce the
 * allowlist/blocklist policy at build time.
 */
class SafeEventMetadata private constructor(
    private val values: Map<String, Any?>
) {

    fun toJson(): String = JSONObject(values).toString()

    fun isEmpty(): Boolean = values.isEmpty()

    companion object {
        private val BLOCKED_KEYS = setOf(
            "body", "rawbody", "rawtext", "rawocrtext", "prompt",
            "token", "accesstoken", "refreshtoken", "authorization",
            "password", "fullpath", "iban", "accountnumber"
        )

        private const val MAX_STRING_LENGTH = 256

        fun empty(): SafeEventMetadata = SafeEventMetadata(emptyMap())

        fun builder(): Builder = Builder()
    }

    class Builder {
        private val map = mutableMapOf<String, Any?>()

        /** Store a plain app-internal value (string truncated to 256 chars). */
        fun put(key: String, value: Any?): Builder {
            val normalizedKey = key.lowercase()
            require(normalizedKey !in BLOCKED_KEYS) {
                "Key '$key' is blocked from event metadata"
            }
            map[key] = when (value) {
                is String -> value.take(MAX_STRING_LENGTH)
                else -> value
            }
            return this
        }

        /** Store a SHA-256 hash prefix of an external identifier. */
        fun putHashed(key: String, value: String?): Builder {
            if (value == null) return this
            map[key] = value.sha256Prefix(16)
            return this
        }

        /** Explicitly mark a field as redacted (stores literal "[REDACTED]"). */
        fun putRedacted(key: String): Builder {
            map[key] = "[REDACTED]"
            return this
        }

        fun build(): SafeEventMetadata = SafeEventMetadata(map.toMap())
    }
}
