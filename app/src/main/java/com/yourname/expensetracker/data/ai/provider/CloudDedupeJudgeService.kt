package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.config.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class CloudDedupeJudgeService @Inject constructor() : DedupeJudgeService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.DEDUPE_JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.DEDUPE_JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = BuildConfig.GEMINI_API_KEY

    override suspend fun judge(input: DedupeJudgeInput): DedupeJudgeSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudDedupeJudgeService: Gemini API key missing, skipping.")
            return null
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.DEDUPE_JUDGE_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudDedupeJudgeService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudDedupeJudgeService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudDedupeJudgeService: parse failure")
            null
        }
    }

    private fun buildRequestBody(input: DedupeJudgeInput): String {
        val prompt = buildPrompt(input)
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", AppConfig.Ai.DEDUPE_JUDGE_MAX_OUTPUT_TOKENS)
                    put("responseMimeType", "application/json")
                    put(
                        "thinkingConfig",
                        JSONObject().apply {
                            put("thinkingBudget", 0)
                        }
                    )
                }
            )
        }.toString()
    }

    private fun buildPrompt(input: DedupeJudgeInput): String {
        val candidates = input.candidates.joinToString("\n") { candidate ->
            "- targetType=${candidate.targetType}, targetId=${candidate.targetId}, merchant=${candidate.merchant}, amount=${candidate.amount} ${candidate.currency}, date=${candidate.date}, source=${candidate.sourceLabel}, preview=${candidate.textPreview ?: "none"}"
        }

        return """
            You are helping judge whether a pending finance review is likely a duplicate of one bounded candidate set.
            Stay conservative.
            Never assume certainty from weak evidence.
            Use only the subject and candidates below.
            Return JSON only.

            JSON schema:
            {
              "verdict": "LIKELY_DUPLICATE|LIKELY_DISTINCT|UNCERTAIN",
              "matchedTargetType": "PENDING_REVIEW|EXPENSE|null",
              "matchedTargetId": 0,
              "confidence": 0.0,
              "rationale": "short explanation"
            }

            Subject:
            - targetType=${input.subject.targetType}
            - targetId=${input.subject.targetId}
            - merchant=${input.subject.merchant}
            - amount=${input.subject.amount} ${input.subject.currency}
            - date=${input.subject.date}
            - source=${input.subject.sourceLabel}
            - preview=${input.subject.textPreview ?: "none"}

            Candidates:
            $candidates
        """.trimIndent()
    }

    private fun parseResponse(body: String): DedupeJudgeSuggestion? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val text = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?: return null

        val jsonText = extractFirstJsonObject(text) ?: return null
        val suggestion = JSONObject(jsonText)

        return DedupeJudgeSuggestion(
            verdict = DuplicateVerdict.valueOf(suggestion.optString("verdict")),
            matchedTargetType = suggestion.optString("matchedTargetType").trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { AiTargetType.valueOf(it) },
            matchedTargetId = if (suggestion.has("matchedTargetId") && !suggestion.isNull("matchedTargetId")) suggestion.optLong("matchedTargetId") else null,
            confidence = if (suggestion.has("confidence") && !suggestion.isNull("confidence")) suggestion.optDouble("confidence").toFloat() else null,
            rationale = suggestion.optString("rationale").trim().ifBlank { null }
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
