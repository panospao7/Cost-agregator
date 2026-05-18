package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.domain.common.sha256Prefix
import org.json.JSONObject

/**
 * Privacy-safe key/value bag for event metadata.
 *
 * Construction is only possible via [builder] or [empty].
 * The builder is **non-throwing** by default: blocked keys are silently
 * redacted rather than throwing, so diagnostics never crash production flow.
 */
class SafeEventMetadata private constructor(
    private val values: Map<String, Any?>
) {

    fun toJson(): String = JSONObject(values).toString()

    fun isEmpty(): Boolean = values.isEmpty()

    /** Merge another SafeEventMetadata into this one, returning a new instance. Sanitization applied. */
    fun merge(other: SafeEventMetadata): SafeEventMetadata {
        if (other.isEmpty()) return this
        val merged = values.toMutableMap()
        other.values.forEach { (k, v) -> merged[k] = v }
        return SafeEventMetadata(merged)
    }

    companion object {
        private val sanitizer = EventMetadataSanitizer()

        fun empty(): SafeEventMetadata = SafeEventMetadata(emptyMap())

        fun builder(): Builder = Builder()
    }

    class Builder {
        private val map = mutableMapOf<String, Any?>()

        /**
         * Store a plain app-internal value.
         * Blocked keys are silently redacted; no exception is thrown.
         */
        fun put(key: String, value: Any?): Builder {
            map[key] = sanitizer.sanitizeValue(key, value)
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
