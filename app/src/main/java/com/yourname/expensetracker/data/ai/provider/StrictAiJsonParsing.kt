package com.yourname.expensetracker.data.ai.provider

import org.json.JSONArray
import org.json.JSONObject

internal object StrictAiJsonParsing {

    inline fun <reified T : Enum<T>> enumOrNull(raw: String?, ignoreCase: Boolean = false): T? {
        val normalized = raw?.trim().takeUnless { it.isNullOrBlank() || it.equals("null", ignoreCase = true) }
            ?: return null
        return enumValues<T>().firstOrNull { candidate ->
            candidate.name.equals(normalized, ignoreCase = ignoreCase)
        }
    }

    fun JSONObject.positiveIdOrNull(key: String): Long? {
        val parsed = longOrNull(key) ?: return null
        return parsed.takeIf { it > 0L }
    }

    fun JSONObject.nullableLongRejectingZeroOrNull(key: String): Long? {
        val parsed = longOrNull(key) ?: return null
        return parsed.takeIf { it > 0L }
    }

    fun JSONObject.finiteFloatOrNull(key: String): Float? {
        val value = numberAsDoubleOrNull(opt(key)) ?: return null
        return value.toFloat()
    }

    fun JSONObject.boundedConfidenceOrNull(key: String): Float? {
        val parsed = finiteFloatOrNull(key) ?: return null
        return parsed.takeIf { it in 0f..1f }
    }

    fun JSONArray?.positiveLongs(): List<Long> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val parsed = numberAsLongOrNull(opt(index))
                if (parsed != null && parsed > 0L) {
                    add(parsed)
                }
            }
        }
    }

    private fun JSONObject.longOrNull(key: String): Long? = numberAsLongOrNull(opt(key))

    private fun numberAsDoubleOrNull(raw: Any?): Double? {
        if (raw == null || raw === JSONObject.NULL) return null
        val parsed = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return parsed.takeIf { it.isFinite() }
    }

    private fun numberAsLongOrNull(raw: Any?): Long? {
        if (raw == null || raw === JSONObject.NULL) return null
        return when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            is Float, is Double -> {
                val doubleValue = (raw as Number).toDouble()
                if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0) {
                    null
                } else {
                    doubleValue.toLong()
                }
            }
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }
}
