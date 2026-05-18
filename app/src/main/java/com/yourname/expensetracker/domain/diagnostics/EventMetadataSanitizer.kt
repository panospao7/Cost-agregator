package com.yourname.expensetracker.domain.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sanitizes free-form metadata maps before they are stored in event tables.
 *
 * Used by writers that receive metadata from callers who may not have used
 * [SafeEventMetadata.builder] directly (e.g. legacy code paths).
 */
@Singleton
class EventMetadataSanitizer @Inject constructor() {

    private val blockedKeys = setOf(
        "body", "rawbody", "rawtext", "rawocrtext", "prompt",
        "token", "accesstoken", "refreshtoken", "authorization",
        "password", "fullpath", "iban", "accountnumber"
    )

    companion object {
        private const val MAX_STRING_LENGTH = 256
    }

    /**
     * Drops blocked keys, truncates long strings, and returns a clean map.
     */
    fun sanitize(raw: Map<String, Any?>): Map<String, Any?> =
        raw.entries
            .filter { (k, _) -> k.lowercase() !in blockedKeys }
            .associate { (k, v) ->
                k to when (v) {
                    is String -> v.take(MAX_STRING_LENGTH)
                    else -> v
                }
            }

    /**
     * Sanitizes an exception message: strips anything that looks like a file path,
     * token, or raw content snippet.
     */
    fun sanitizeExceptionMessage(message: String?): String? {
        if (message == null) return null
        return message
            .replace(Regex("""(/[^\s]{10,}|[A-Za-z]:\\[^\s]{10,})"""), "[PATH]")
            .take(MAX_STRING_LENGTH)
    }
}
