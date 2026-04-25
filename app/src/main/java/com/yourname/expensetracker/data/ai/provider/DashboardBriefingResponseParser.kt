package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.config.AppConfig
import org.json.JSONObject
import timber.log.Timber

internal object DashboardBriefingResponseParser {

    fun parseResponse(text: String): DashboardBriefing? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val root = JSONObject(jsonText)
            val title = root.optString("title").trim().take(60)
            val body = root.optString("text").trim().take(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS)
            if (title.isBlank() || body.isBlank()) return null

            DashboardBriefing(
                title = title,
                text = body,
                tone = root.optString("tone").trim().ifBlank { "neutral" },
                confidence = if (root.has("confidence") && !root.isNull("confidence")) {
                    StrictAiJsonParsing.run {
                        root.boundedConfidenceOrNull("confidence")
                    } ?: return null
                } else {
                    null
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "DashboardBriefingResponseParser: JSON parse failure")
            null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
