package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.ai.model.ReviewExplanation
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
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
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudReviewExplanationService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) : ReviewExplanationService {

    private var apiKeyOverride: String? = null

    // Secondary constructor for testing
    constructor(secureKeyStorage: SecureKeyStorage, apiKeyOverride: String) : this(secureKeyStorage) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.REVIEW_EXPLANATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.REVIEW_EXPLANATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    override suspend fun generate(input: ReviewExplanationInput): ReviewExplanation? {
        if (apiKey.isBlank()) {
            Timber.d("CloudReviewExplanationService: Gemini API key missing, skipping.")
            return null
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.REVIEW_EXPLANATION_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudReviewExplanationService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use null
                }

                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudReviewExplanationService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudReviewExplanationService: parse failure")
            null
        }
    }

    private fun buildRequestBody(input: ReviewExplanationInput): String {
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
                    put("temperature", 0.2)
                    put("maxOutputTokens", AppConfig.Ai.REVIEW_EXPLANATION_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: ReviewExplanationInput): String {
        return """
            You are helping explain why a pending expense review exists inside a finance app.
            Stay cautious, concise, and non-authoritative.
            Do not invent facts.
            Do not tell the user the transaction is certainly correct.
            Use the given signals only.
            Keep the output short and plain.
            Headline max 60 characters.
            Body max 180 characters and one sentence.
            Caution max 90 characters or null.
            Do not include numbered lists.
            Do not include markdown.

            Return strict JSON with this schema:
            {
              "headline": "short title",
              "body": "1-2 sentence explanation",
              "caution": "optional caution or null"
            }

            Review facts:
            - merchant: ${input.merchant}
            - amount: ${input.amount} ${input.currency}
            - type: ${input.suggestedType}
            - confidence: ${input.confidence}
            - matchType: ${input.matchType ?: "unknown"}
            - deterministicExplanation: ${input.explanation ?: "none"}
            - packageName: ${input.packageName}
            - notificationTitle: ${input.notificationTitle ?: "none"}
            - notificationText: ${input.notificationText ?: "none"}

            Write a user-facing explanation for why this landed in review and what signal looks uncertain.
            Return JSON only.
        """.trimIndent()
    }

    private fun parseResponse(body: String): ReviewExplanation? {
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

        val explanationJson = JSONObject(extractFirstJsonObject(text) ?: return null)
        val headline = explanationJson.optString("headline").trim()
            .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_HEADLINE_CHARS)
        val bodyText = explanationJson.optString("body").trim()
            .take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_BODY_CHARS)
        if (headline.isBlank() || bodyText.isBlank()) return null

        return ReviewExplanation(
            headline = headline,
            body = bodyText,
            caution = explanationJson.optString("caution").trim()
                .ifBlank { null }
                ?.take(AppConfig.Ai.MAX_REVIEW_EXPLANATION_CAUTION_CHARS)
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
