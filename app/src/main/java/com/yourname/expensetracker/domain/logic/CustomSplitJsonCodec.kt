package com.yourname.expensetracker.domain.logic

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.math.BigDecimal

object CustomSplitJsonCodec {

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Double>>() {}.type

    fun toCanonicalJson(splits: Map<Long, Double>): String {
        require(splits.isNotEmpty()) { "Custom split payload is missing" }

        return splits.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (memberId, value) ->
            require(value.isFinite()) { "Custom split values must be finite numbers" }
            "\"$memberId\":${BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()}"
        }
    }

    fun parseCanonicalJsonOrNull(payload: String?): Map<Long, Double>? {
        val normalizedPayload = payload?.trim() ?: return null
        if (!normalizedPayload.startsWith("{") || !normalizedPayload.endsWith("}")) {
            return null
        }

        return try {
            val parsed: Map<String, Double> = gson.fromJson(normalizedPayload, mapType) ?: return null
            buildMap {
                for ((memberId, value) in parsed) {
                    val parsedMemberId = memberId.toLongOrNull() ?: return null
                    if (!value.isFinite()) return null
                    put(parsedMemberId, value)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isCanonicalJsonPayload(payload: String?): Boolean = parseCanonicalJsonOrNull(payload) != null
}
