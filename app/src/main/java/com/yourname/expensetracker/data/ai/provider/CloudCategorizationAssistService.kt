package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
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
class CloudCategorizationAssistService @Inject constructor() : CategorizationAssistService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.CATEGORIZATION_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.CATEGORIZATION_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = BuildConfig.GEMINI_API_KEY

    override suspend fun suggest(input: CategorizationAssistInput): CategoryAssistSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudCategorizationAssistService: Gemini API key missing, skipping.")
            return null
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.CATEGORIZATION_ASSIST_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudCategorizationAssistService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudCategorizationAssistService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudCategorizationAssistService: parse failure")
            null
        }
    }

    private fun buildRequestBody(input: CategorizationAssistInput): String {
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
                    put("maxOutputTokens", AppConfig.Ai.CATEGORIZATION_ASSIST_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: CategorizationAssistInput): String {
        val categories = input.candidateCategories.joinToString("\n") { "- ${it.id}: ${it.name}" }
        return """
            You are helping categorize a pending finance review.
            Choose only from the provided category list.
            Never invent a new category.
            Prefer the best supported category.
            Return JSON only.

            JSON schema:
            {
              "categoryId": 0,
              "categoryName": "string",
              "confidence": 0.0,
              "rationale": "short explanation",
              "alternativeCategoryIds": [0]
            }

            Review facts:
            - merchant: ${input.merchant}
            - amount: ${input.amount} ${input.currency}
            - transactionType: ${input.transactionType}
            - date: ${input.date?.toString() ?: "none"}
            - currentCategoryId: ${input.currentCategoryId?.toString() ?: "none"}
            - deterministicMatchType: ${input.deterministicMatchType ?: "none"}
            - deterministicExplanation: ${input.deterministicExplanation ?: "none"}
            - supportingText: ${input.supportingText ?: "none"}

            Allowed categories:
            $categories
        """.trimIndent()
    }

    private fun parseResponse(body: String): CategoryAssistSuggestion? {
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
        if (!suggestion.has("categoryId") || suggestion.isNull("categoryId")) return null
        if (!suggestion.has("categoryName") || suggestion.optString("categoryName").isBlank()) return null

        return CategoryAssistSuggestion(
            categoryId = suggestion.optLong("categoryId"),
            categoryName = suggestion.optString("categoryName").trim(),
            confidence = if (suggestion.has("confidence") && !suggestion.isNull("confidence")) suggestion.optDouble("confidence").toFloat() else null,
            rationale = suggestion.optString("rationale").trim().ifBlank { null },
            alternativeCategoryIds = suggestion.optJSONArray("alternativeCategoryIds").toLongList()
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                add(optLong(index))
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
