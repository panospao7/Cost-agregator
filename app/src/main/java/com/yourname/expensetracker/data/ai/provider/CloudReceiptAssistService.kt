package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
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
class CloudReceiptAssistService @Inject constructor() : ReceiptAssistService {

    private var apiKeyOverride: String? = null

    internal constructor(apiKeyOverride: String) : this() {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = apiKeyOverride ?: BuildConfig.GEMINI_API_KEY

    override suspend fun suggest(input: ReceiptAssistInput): ReceiptAssistSuggestion? {
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptAssistService: Gemini API key missing, skipping.")
            return null
        }

        val requestBody = buildRequestBody(input)
        val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL}:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w(
                        "CloudReceiptAssistService: HTTP ${response.code} ${response.body?.string()?.take(200)}"
                    )
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                parseResponse(body)
            }
        } catch (e: IOException) {
            Timber.w(e, "CloudReceiptAssistService: network failure")
            null
        } catch (e: Exception) {
            Timber.w(e, "CloudReceiptAssistService: parse failure")
            null
        }
    }

    private fun buildRequestBody(input: ReceiptAssistInput): String {
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
                    put("maxOutputTokens", AppConfig.Ai.RECEIPT_ASSIST_MAX_OUTPUT_TOKENS)
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

    private fun buildPrompt(input: ReceiptAssistInput): String {
        return """
            You are helping recover missing receipt fields from OCR text in a finance app.
            Use only the provided OCR text and parsed receipt values.
            Stay conservative.
            Do not invent values when uncertain.
            Return compact JSON only.

            JSON schema:
            {
              "merchant": { "value": "string", "confidence": 0.0, "rationale": "string" } | null,
              "total": { "value": 0.0, "confidence": 0.0, "rationale": "string" } | null,
              "date": { "value": 0, "confidence": 0.0, "rationale": "string" } | null,
              "taxAmount": { "value": 0.0, "confidence": 0.0, "rationale": "string" } | null,
              "notes": ["short note"]
            }

            Rules:
            - Prefer null over guessing.
            - Only provide a Unix epoch milliseconds date if the date is reasonably supported.
            - Keep notes short.

            Receipt facts:
            - currency: ${input.currency}
            - parsedMerchant: ${input.parsedMerchant ?: "none"}
            - parsedTotal: ${input.parsedTotal?.toString() ?: "none"}
            - parsedDate: ${input.parsedDate?.toString() ?: "none"}
            - parsedTaxAmount: ${input.parsedTaxAmount?.toString() ?: "none"}
            - lineItemsJson: ${input.lineItemsJson ?: "none"}
            - rawOcrText:
            ${input.rawOcrText}
        """.trimIndent()
    }

    private fun parseResponse(body: String): ReceiptAssistSuggestion? {
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

        return ReceiptAssistSuggestion(
            merchant = suggestion.optJSONObject("merchant")?.toSuggestedStringOrNull(),
            total = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull(),
            date = suggestion.optJSONObject("date")?.toSuggestedLongOrNull(),
            taxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull(),
            notes = suggestion.optJSONArray("notes").toStringList()
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONObject.toSuggestedStringOrNull(): SuggestedValue<String>? {
        val value = optString("value").trim()
        if (value.isBlank()) return null
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedDoubleOrNull(): SuggestedValue<Double>? {
        if (!has("value") || isNull("value")) return null
        val value = optDouble("value")
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.toSuggestedLongOrNull(): SuggestedValue<Long>? {
        if (!has("value") || isNull("value")) return null
        val value = optLong("value")
        return SuggestedValue(
            value = value,
            confidence = optDoubleOrNull("confidence")?.toFloat(),
            rationale = optString("rationale").trim().ifBlank { null }
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
