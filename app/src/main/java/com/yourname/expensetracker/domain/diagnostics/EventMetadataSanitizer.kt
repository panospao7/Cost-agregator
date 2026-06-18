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
 * - Exact safe-key allowlist checked first (no broad prefix bypass)
 * - Keys ending in "hash" or "idhash" are allowed (values are already hashed)
 * - Blocked by exact canonical key OR dangerous substring
 * - Recursive sanitization of nested maps/lists/JSON
 * - Value scanning for tokens, paths, IBANs, large blobs
 */
@Singleton
class EventMetadataSanitizer @Inject constructor() {

    companion object {
        const val MAX_STRING_LENGTH = 256
        private const val REDACTED = "[REDACTED]"

        /** Exact canonical keys that are always safe. No prefix matching — exact only. */
        private val SAFE_EXACT_KEYS = setOf(
            "expenseid", "receiptid", "entityid", "entitytype",
            "operationtype", "operationid", "operationrunid",
            "stage", "status", "count", "rowcount", "rows",
            "rowssucceeded", "rowsfailed", "rowsskipped",
            "duration", "elapsed", "elapsedms",
            "source", "sourcetype",
            "pipeline", "reason", "reasoncode",
            "sideeffect",
            // note: keys ending in 'hash' are NOT listed here — they belong in SAFE_HASH_KEYS only
            "matchedentityid", "duplicateentityid",
            "retryable", "causationid", "correlationid", "eventid",
            "isterminal", "delivered", "partial", "percent",
            "confidence", "parsersource",
            "transactionsfound", "reviewscreated", "duplicatesskipped",
            "pagecount", "itemcount", "currency", "classifier",
            "provider", "dedupewindowms", "hasattachments", "posttime",
            "errorcount", "warningcount"
        )

        /** DDL-016-09 / DDL-F876-15: exact known hash keys — no arbitrary endsWith("hash") allowed */
        private val SAFE_HASH_KEYS = setOf(
            "sourceidhash",
            "notificationkeyhash",
            "packagenamehash",
            "packagehash",      // DDL-F876-15: moved from SAFE_EXACT_KEYS; must validate hex
            "messageidhash",
            "providerhash",
            "providertransactionidhash",
            "externalhash",
            "payloadhash",
            "contentfingerprinthash",
            "filehash",
            "backuphash",
            "assetrelativepathhash"
        )

        /** Exact canonical keys that are always blocked. */
        private val BLOCKED_EXACT = setOf(
            "body", "rawbody", "rawtext", "rawocr", "rawocrtext",
            "prompt", "token", "accesstoken", "refreshtoken",
            "authorization", "password", "secret", "fullpath",
            "filepath", "iban", "accountnumber", "cardnumber",
            "cvv", "pin", "bankdescription", "emailbody",
            "emailsubject", "sender"
        )

        /** Substrings in canonical key that trigger redaction (after safe-key check). */
        private val BLOCKED_SUBSTRINGS = listOf(
            "raw", "ocr", "prompt", "token", "auth", "password",
            "secret", "path", "iban", "account", "card", "cvv",
            "pin", "body", "subject", "sender", "bearer"
        )

        private val BEARER_PATTERN = Regex("""(?i)bearer\s+\S{8,}""")
        private val JWT_PATTERN = Regex("""[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
        private val FILE_PATH_PATTERN = Regex("""(/[^\s]{10,}|[A-Za-z]:\\[^\s]{10,})""")
        private val IBAN_PATTERN = Regex("""[A-Z]{2}\d{2}[A-Z0-9]{4,30}""")
        private val LONG_DIGITS_PATTERN = Regex("""\d{12,}""")
        // DDL-A8-17: only allow hex strings in hash-key slots
        private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")
    }

    /** Canonical key: lowercase, strip non-alphanumeric. */
    fun canonicalizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** DDL-C67-11: check if a key is an approved hash key (for putHashed validation). */
    fun isApprovedHashKey(key: String): Boolean =
        canonicalizeKey(key) in SAFE_HASH_KEYS

    fun isDangerousKey(key: String): Boolean {
        val canonical = canonicalizeKey(key)

        // Exact safe keys are always allowed
        if (canonical in SAFE_EXACT_KEYS) return false

        // DDL-F876-15: any key ending in 'hash' or 'idhash' must be in SAFE_HASH_KEYS
        // If it's not an approved hash key, treat it as dangerous (unknown provenance)
        if (canonical.endsWith("hash")) {
            return canonical !in SAFE_HASH_KEYS
        }

        // DDL-016-09 / DDL-A8-17: only exact known hash keys are safe — value must also be validated
        if (canonical in SAFE_HASH_KEYS) return false  // value validated separately in sanitizeValue

        // Exact blocked keys
        if (canonical in BLOCKED_EXACT) return true

        // Dangerous substring check — no prefix bypass
        return BLOCKED_SUBSTRINGS.any { canonical.contains(it) }
    }

    fun sanitizeValue(key: String, value: Any?): Any? {
        if (isDangerousKey(key)) return REDACTED
        val canonical = canonicalizeKey(key)
        // DDL-A8-17: validate hash-key values — only allow hex strings, redact plain text
        if (canonical in SAFE_HASH_KEYS) {
            return if (value is String && HASH_VALUE_PATTERN.matches(value)) value else REDACTED
        }
        return when (value) {
            null -> null
            is Boolean, is Number -> value
            is Enum<*> -> value.name
            is String -> sanitizeStringValue(value)
            is Map<*, *> -> sanitizeMap(value.entries.associate { (k, v) -> k.toString() to v })
            is Iterable<*> -> sanitizeList(value.toList())
            is Array<*> -> sanitizeList(value.toList())
            // DDL-A8-18: unknown objects must go through full string sanitizer, not raw .take()
            else -> sanitizeStringValue(value.toString())
        }
    }

    fun sanitizeMap(raw: Map<String, Any?>): Map<String, Any?> =
        raw.entries.associate { (k, v) -> k to sanitizeValue(k, v) }

    /** DDL-016-10: fully recursive sanitization for any value type at any nesting depth. */
    private fun sanitizeAny(value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is Number -> value
        is Enum<*> -> value.name
        is String -> sanitizeStringValue(value)
        is JSONObject -> sanitizeJsonObject(value)
        is JSONArray -> sanitizeJsonArray(value)
        is Map<*, *> -> sanitizeMap(value.entries.associate { (k, v) -> k.toString() to v })
        is Iterable<*> -> value.map { sanitizeAny(it) }
        is Array<*> -> value.map { sanitizeAny(it) }
        else -> sanitizeStringValue(value.toString())
    }

    private fun sanitizeList(list: List<Any?>): List<Any?> = list.map { sanitizeAny(it) }

    internal fun sanitizeStringValue(value: String): String {
        if (value.length > MAX_STRING_LENGTH * 2) return REDACTED
        val v = value
            .replace(BEARER_PATTERN, REDACTED)
            .replace(JWT_PATTERN, REDACTED)
            .replace(FILE_PATH_PATTERN, "[PATH]")
            .replace(IBAN_PATTERN, REDACTED)
            .replace(LONG_DIGITS_PATTERN, REDACTED)
        return v.take(MAX_STRING_LENGTH)
    }

    fun sanitizeJsonString(json: String?): String? {
        if (json.isNullOrBlank() || json == "{}") return null
        return try {
            val obj = JSONObject(json)
            val sanitized = sanitizeJsonObject(obj)
            if (sanitized.length() == 0) null else sanitized.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeJsonObject(obj: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in obj.keys()) {
            if (isDangerousKey(key)) { result.put(key, REDACTED); continue }
            when (val v = obj.opt(key)) {
                is JSONObject -> result.put(key, sanitizeJsonObject(v))
                is JSONArray -> result.put(key, sanitizeJsonArray(v))
                // DDL-512-07: use sanitizeValue(key, v) so hash-key validation applies
                else -> result.put(key, sanitizeValue(key, v))
            }
        }
        return result
    }

    private fun sanitizeJsonArray(arr: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until arr.length()) {
            result.put(sanitizeAny(arr.opt(i)))
        }
        return result
    }

    /** Reuses full string sanitizer — same privacy policy as metadata values. */
    fun sanitizeExceptionMessage(message: String?): String? {
        if (message == null) return null
        return sanitizeStringValue(message)
    }
}
