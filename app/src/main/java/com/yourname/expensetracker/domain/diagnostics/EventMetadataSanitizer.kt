package com.yourname.expensetracker.domain.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes all metadata privacy/safety logic for diagnostic events.
 *
 * Key policy:
 * - Canonical key = lowercase, non-alphanumeric stripped
 * - Blocked by exact canonical key OR dangerous substring
 * - Recursive sanitization of nested maps/lists
 * - Value scanning for tokens, paths, IBANs, large blobs
 */
@Singleton
class EventMetadataSanitizer @Inject constructor() {

    companion object {
        const val MAX_STRING_LENGTH = 256
        private const val REDACTED = "[REDACTED]"

        /** Exact canonical keys that are always blocked. */
        private val BLOCKED_EXACT = setOf(
            "body", "rawbody", "rawtext", "rawocr", "rawocrtext",
            "prompt", "token", "accesstoken", "refreshtoken",
            "authorization", "password", "secret", "fullpath",
            "filepath", "iban", "accountnumber", "cardnumber",
            "cvv", "pin", "bankdescription", "emailbody",
            "emailsubject", "sender"
        )

        /** Substrings in canonical key that trigger redaction. */
        private val BLOCKED_SUBSTRINGS = listOf(
            "raw", "ocr", "prompt", "token", "auth", "password",
            "secret", "path", "iban", "account", "card", "cvv",
            "pin", "body", "subject", "sender", "bearer"
        )

        /** Safe key prefixes that are never blocked even if they contain a substring match. */
        private val SAFE_PREFIXES = setOf(
            "expenseid", "receiptid", "operationtype", "operationid",
            "stage", "status", "count", "rows", "duration", "elapsed",
            "source", "pipeline", "reason", "sideeffect", "packagehash",
            "notificationkeyhash", "messageidhash", "providerhash",
            "externalhash", "matchedentityid", "duplicateentityid",
            "retryable", "entityid", "entitytype", "causationid",
            "correlationid", "delivered", "partial", "percent",
            "spent", "limit", "confidence", "parsersource",
            "transactionsfound", "reviewscreated", "duplicatesskipped",
            "pagecount", "itemcount", "currency", "classifier"
        )

        // Value patterns to redact
        private val BEARER_PATTERN = Regex("""(?i)bearer\s+\S{8,}""")
        private val JWT_PATTERN = Regex("""[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
        private val FILE_PATH_PATTERN = Regex("""(/[^\s]{10,}|[A-Za-z]:\\[^\s]{10,})""")
        private val IBAN_PATTERN = Regex("""[A-Z]{2}\d{2}[A-Z0-9]{4,30}""")
        private val LONG_DIGITS_PATTERN = Regex("""\d{12,}""")
    }

    /** Canonical key: lowercase, strip non-alphanumeric. */
    fun canonicalizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun isDangerousKey(key: String): Boolean {
        val canonical = canonicalizeKey(key)
        if (SAFE_PREFIXES.any { canonical.startsWith(it) }) return false
        if (canonical in BLOCKED_EXACT) return true
        return BLOCKED_SUBSTRINGS.any { canonical.contains(it) }
    }

    fun sanitizeValue(key: String, value: Any?): Any? {
        if (isDangerousKey(key)) return REDACTED
        return when (value) {
            null -> null
            is Boolean, is Number -> value
            is Enum<*> -> value.name
            is String -> sanitizeStringValue(value)
            is Map<*, *> -> sanitizeMap(value.entries.associate { (k, v) -> k.toString() to v })
            is Iterable<*> -> sanitizeList(value.toList())
            is Array<*> -> sanitizeList(value.toList())
            else -> value.toString().take(MAX_STRING_LENGTH)
        }
    }

    fun sanitizeMap(raw: Map<String, Any?>): Map<String, Any?> =
        raw.entries.associate { (k, v) -> k to sanitizeValue(k, v) }

    private fun sanitizeList(list: List<Any?>): List<Any?> =
        list.map { item ->
            when (item) {
                is Map<*, *> -> sanitizeMap(item.entries.associate { (k, v) -> k.toString() to v })
                is String -> sanitizeStringValue(item)
                else -> item
            }
        }

    private fun sanitizeStringValue(value: String): String {
        if (value.length > MAX_STRING_LENGTH * 2) return REDACTED // large blob
        val v = value
            .replace(BEARER_PATTERN, REDACTED)
            .replace(JWT_PATTERN, REDACTED)
            .replace(FILE_PATH_PATTERN, "[PATH]")
            .replace(IBAN_PATTERN, REDACTED)
            .replace(LONG_DIGITS_PATTERN, REDACTED)
        return v.take(MAX_STRING_LENGTH)
    }

    /**
     * Sanitizes a JSON string produced by [SafeEventMetadata.toJson].
     * Returns null if input is null or empty.
     */
    fun sanitizeJsonString(json: String?): String? {
        if (json.isNullOrBlank() || json == "{}") return null
        return try {
            val obj = JSONObject(json)
            val sanitized = sanitizeJsonObject(obj)
            if (sanitized.length() == 0) null else sanitized.toString()
        } catch (_: Exception) {
            null // malformed JSON — drop it
        }
    }

    private fun sanitizeJsonObject(obj: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in obj.keys()) {
            if (isDangerousKey(key)) {
                result.put(key, REDACTED)
                continue
            }
            when (val v = obj.get(key)) {
                is JSONObject -> result.put(key, sanitizeJsonObject(v))
                is JSONArray -> result.put(key, sanitizeJsonArray(v))
                is String -> result.put(key, sanitizeStringValue(v))
                else -> result.put(key, v)
            }
        }
        return result
    }

    private fun sanitizeJsonArray(arr: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until arr.length()) {
            when (val v = arr.get(i)) {
                is JSONObject -> result.put(sanitizeJsonObject(v))
                is String -> result.put(sanitizeStringValue(v))
                else -> result.put(v)
            }
        }
        return result
    }

    fun sanitizeExceptionMessage(message: String?): String? {
        if (message == null) return null
        return message
            .replace(FILE_PATH_PATTERN, "[PATH]")
            .replace(BEARER_PATTERN, REDACTED)
            .replace(JWT_PATTERN, REDACTED)
            .take(MAX_STRING_LENGTH)
    }
}
